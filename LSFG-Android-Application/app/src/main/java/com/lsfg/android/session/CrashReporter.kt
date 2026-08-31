package com.lsfg.android.session

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based crash capture + report export. We can't use logcat in the wild
 * (no adb access from remote testers), so:
 *   - Java/Kotlin uncaught exceptions → write a text report to filesDir/last_crash.txt
 *     plus a line into the native ring log.
 *   - Native fatal signals → handled by crash_reporter.cpp, same file.
 *   - Users get a dialog on next launch that builds an ACTION_SEND intent with
 *     the crash file + rolling native log attached.
 */
object CrashReporter {

    private const val TAG = "LsfgCrash"
    const val CRASH_FILE = "last_crash.txt"
    // File the dialog has already been shown for. Kept on disk so the share
    // button can still attach the most recent crash even after the user has
    // dismissed the one-shot dialog, but it never re-triggers the dialog.
    const val CRASH_FILE_SEEN = "last_crash_seen.txt"
    const val LOG_FILE = "lsfg.log"

    /** Absolute path used by both sides to locate the crash file. */
    fun crashFile(ctx: Context): File = File(ctx.filesDir, CRASH_FILE)
    fun seenCrashFile(ctx: Context): File = File(ctx.filesDir, CRASH_FILE_SEEN)
    fun logFile(ctx: Context): File = File(ctx.filesDir, LOG_FILE)

    /**
     * Install both halves. Safe to call multiple times; the native side is
     * idempotent and we only set the Java handler once.
     */
    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        val crashPath = crashFile(appCtx).absolutePath
        val logPath = logFile(appCtx).absolutePath

        runCatching { NativeBridge.initCrashReporter(crashPath, logPath) }
            .onFailure { Log.w(TAG, "initCrashReporter failed", it) }

        if (installed) return
        installed = true

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeJavaCrash(appCtx, thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "writeJavaCrash failed", t)
            }
            prev?.uncaughtException(thread, throwable)
                ?: kotlin.system.exitProcess(10)
        }
    }

    private var installed = false

    private fun writeJavaCrash(ctx: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("=== LSFG java crash ===")
            pw.println("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            pw.println("thread: ${thread.name}")
            appendDeviceInfo(pw)
            pw.println()
            pw.println("--- stack ---")
            throwable.printStackTrace(pw)
            pw.println("=== end ===")
        }
        crashFile(ctx).writeText(sw.toString())
    }

    private fun appendDeviceInfo(pw: PrintWriter) {
        pw.println("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        pw.println("product: ${Build.PRODUCT}")
        pw.println("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        pw.println("abi: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        pw.println("hardware: ${Build.HARDWARE} board=${Build.BOARD}")
        pw.println("soc: ${safeSoc()}")
    }

    private fun safeSoc(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        } else {
            "n/a"
        }
    } catch (_: Throwable) {
        "n/a"
    }

    /** True if a crash report is waiting to be seen. */
    fun hasPendingCrash(ctx: Context): Boolean {
        val f = crashFile(ctx)
        return f.exists() && f.length() > 0
    }

    /**
     * Mark the pending crash as seen by moving it aside. After this call
     * [hasPendingCrash] returns false, but the file is still attached by
     * [buildShareIntent] so the user can share it from the diagnostics chip.
     * Called as soon as the one-shot dialog is shown — this prevents the
     * dialog from re-appearing on every launch when the user dismisses by
     * swiping the app away rather than tapping a button.
     */
    fun markPendingCrashSeen(ctx: Context) {
        val src = crashFile(ctx)
        if (!src.exists()) return
        val dst = seenCrashFile(ctx)
        // Best-effort rename. If the rename fails (e.g. dst already exists on
        // some FS implementations) fall back to copy + delete; if both fail,
        // delete the source so we still don't loop the dialog.
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            try {
                dst.writeBytes(src.readBytes())
            } catch (t: Throwable) {
                Log.w(TAG, "markPendingCrashSeen copy failed", t)
            }
            src.delete()
        }
    }

    /** Delete both the pending and seen crash files; the rolling log is kept. */
    fun clearPendingCrash(ctx: Context) {
        crashFile(ctx).delete()
        seenCrashFile(ctx).delete()
    }

    /** Read the crash file, up to [maxBytes] from the end. */
    fun readCrashSummary(ctx: Context, maxBytes: Int = 64 * 1024): String {
        val f = if (crashFile(ctx).exists()) crashFile(ctx) else seenCrashFile(ctx)
        if (!f.exists()) return ""
        val bytes = f.readBytes()
        val start = (bytes.size - maxBytes).coerceAtLeast(0)
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /**
     * Build a share intent bundling both last_crash.txt and lsfg.log.
     * Works via FileProvider so the receiving app can read them.
     */
    fun buildShareIntent(ctx: Context): Intent? {
        // Prefer the fresh crash file if present; otherwise fall back to the
        // already-seen one so the share chip still works after the dialog has
        // been shown.
        val crash = crashFile(ctx).takeIf { it.exists() && it.length() > 0 }
            ?: seenCrashFile(ctx).takeIf { it.exists() && it.length() > 0 }
        val files = listOfNotNull(
            crash,
            logFile(ctx).takeIf { it.exists() && it.length() > 0 },
        )
        if (files.isEmpty()) return null

        val authority = "${ctx.packageName}.fileprovider"
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, authority, it) })

        val subject = "LSFG-Android crash — ${Build.MANUFACTURER} ${Build.MODEL}"
        val body = buildString {
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine()
            appendLine("Attached: last_crash.txt + lsfg.log")
        }

        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
