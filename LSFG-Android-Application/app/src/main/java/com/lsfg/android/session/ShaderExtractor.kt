package com.lsfg.android.session

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

sealed class ExtractResult {
    data object Success : ExtractResult()
    data class Failure(val code: Int, val message: String) : ExtractResult()
}

/**
 * Copies the user-picked Lossless.dll out of the ContentProvider URI into the app's private
 * filesDir, then invokes the native extractor to produce per-resource SPIR-V blobs.
 *
 * The DLL itself is kept on disk only long enough to be parsed. After extraction we delete it,
 * because we don't want a redistributable copy hanging around and because SharedPreferences
 * remembers only the user's URI choice, not the bytes.
 */
object ShaderExtractor {

    private const val TAG = "ShaderExtractor"

    fun extract(ctx: Context, dllUri: Uri): ExtractResult {
        val dllDir = File(ctx.filesDir, "dll").apply { mkdirs() }
        val cacheDir = File(ctx.filesDir, "spirv").apply { mkdirs() }
        val dllFile = File(dllDir, "Lossless.dll")

        try {
            ctx.contentResolver.openInputStream(dllUri)?.use { input ->
                dllFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return ExtractResult.Failure(-1, "Couldn't open Lossless.dll (revoked SAF permission?)")
        } catch (t: Throwable) {
            Log.e(TAG, "Copy failed", t)
            return ExtractResult.Failure(-1, "Couldn't copy Lossless.dll: ${t.message}")
        }

        val sha = sha256(dllFile)
        Log.i(TAG, "Lossless.dll sha256=$sha size=${dllFile.length()}")

        val code = try {
            NativeBridge.extractShaders(dllFile.absolutePath, sha, cacheDir.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "Native extractShaders threw", t)
            return ExtractResult.Failure(-1, "Native crash: ${t.message}")
        } finally {
            // We always delete the DLL copy — we never want to keep it around, licensed or not.
            dllFile.delete()
        }

        if (code != 0) {
            return ExtractResult.Failure(code, describe(code))
        }

        // Second line of defense: have the device driver load every SPIR-V we just wrote.
        // Drivers reject invalid SPIR-V at module-creation time, which is much cheaper to
        // trigger here than deep inside the pipeline build later on.
        val probe = try {
            NativeBridge.probeShaders(cacheDir.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "probeShaders threw", t)
            return ExtractResult.Failure(-1, "Native crash during shader probe: ${t.message}")
        }
        return if (probe == 0) {
            ExtractResult.Success
        } else {
            ExtractResult.Failure(probe, describe(probe))
        }
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "ok"
        -1 -> "The selected file couldn't be parsed as a Windows DLL. Make sure you picked the real Lossless.dll."
        -2 -> "Lossless.dll is missing shaders this app needs. Update Lossless Scaling and try again."
        -3 -> "One of the shaders failed DXBC→SPIR-V translation. Your Lossless Scaling version may not be supported yet."
        -4 -> "Couldn't write translated shaders to the app's storage."
        -10 -> "No Vulkan loader on this device — LSFG needs Vulkan 1.1+."
        -11 -> "Extracted SPIR-V cache is missing files. Try re-extracting."
        -12 -> "This device's Vulkan driver rejected one of the shaders. Your GPU may not be compatible."
        else -> "Unknown error ($code)"
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
