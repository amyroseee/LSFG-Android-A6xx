package com.lsfg.android.benchmark

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.lsfg.android.BuildConfig
import com.lsfg.android.session.NativeBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Formats a benchmark report as plain text and writes it to the app cache
 * directory. The output is human-readable and intended to be diffed by eye
 * across builds (baseline.txt vs post-sprintN.txt).
 *
 * Companion object methods build the [Intent.ACTION_SEND] Intent for the
 * standard Android share-sheet, attaching the file via FileProvider.
 */
object BenchmarkLogWriter {

    private const val FILE_PREFIX = "benchmark_"
    private const val FILE_SUFFIX = ".txt"

    /**
     * Builds the report text. The format is intentionally simple and stable —
     * downstream tooling (a future tools/benchmark_diff.py) parses it line-wise.
     */
    fun format(
        ctx: Context,
        results: List<BenchmarkRunResult>,
        startedAtMs: Long,
        endedAtMs: Long,
        targetPackage: String?,
        renderWidth: Int,
        renderHeight: Int,
    ): String {
        val sb = StringBuilder(8192)
        sb.appendLine("LSFG-Android Benchmark Report")
        sb.appendLine("=============================")
        sb.appendLine()

        // --- Header --------------------------------------------------------
        sb.appendLine("[device]")
        sb.appendLine("model            = ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("device           = ${Build.DEVICE}")
        sb.appendLine("android          = ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        sb.appendLine("abi              = ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("display_refresh  = ${displayRefreshHz(ctx)} Hz")
        sb.appendLine("display_modes    = ${displaySupportedModes(ctx)}")
        sb.appendLine()

        sb.appendLine("[app]")
        sb.appendLine("version          = ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        sb.appendLine("native           = ${runCatching { NativeBridge.nativeVersion() }.getOrDefault("?")}")
        sb.appendLine("npu_available    = ${runCatching { NativeBridge.isNpuAvailable() }.getOrDefault(false)}")
        sb.appendLine("npu_summary      = ${runCatching { NativeBridge.getNpuSummary() }.getOrDefault("?")}")
        sb.appendLine()

        sb.appendLine("[session]")
        sb.appendLine("target_package   = ${targetPackage ?: "(none)"}")
        sb.appendLine("render_size      = ${renderWidth}x${renderHeight}")
        sb.appendLine("started_at       = ${formatTimestamp(startedAtMs)}")
        sb.appendLine("ended_at         = ${formatTimestamp(endedAtMs)}")
        sb.appendLine("total_duration_s = ${"%.1f".format(Locale.US, (endedAtMs - startedAtMs) / 1000.0)}")
        sb.appendLine()

        sb.appendLine("[benchmark_preset]")
        sb.appendLine("flow_scale       = ${BenchmarkConfig.FLOW_SCALE}")
        sb.appendLine("performance_mode = ${BenchmarkConfig.PERFORMANCE_MODE}")
        // Show whether this run captured both precisions; the controller may
        // have skipped FP16 if shaderFloat16 isn't supported on this device.
        val precisions = results.map { if (it.framegenFp16) "FP16" else "FP32" }.distinct()
        sb.appendLine("precisions       = ${precisions.joinToString(",")}")
        sb.appendLine("run_duration_s   = ${BenchmarkConfig.RUN_DURATION_SEC}")
        sb.appendLine("warmup_ms        = ${BenchmarkConfig.WARMUP_MS}")
        sb.appendLine("sample_hz        = ${1000L / BenchmarkConfig.SAMPLE_INTERVAL_MS}")
        sb.appendLine("post_processing  = OFF (npu/cpu/gpu all disabled)")
        sb.appendLine()

        // --- Per-run results ----------------------------------------------
        for (r in results) {
            val precisionTag = if (r.framegenFp16) "fp16" else "fp32"
            sb.appendLine("[run_${precisionTag}_x${r.multiplier}]")
            sb.appendLine("multiplier       = ${r.multiplier}")
            sb.appendLine("precision        = ${if (r.framegenFp16) "FP16" else "FP32"}")
            sb.appendLine("duration_ms      = ${r.runDurationMs}")
            sb.appendLine("real_fps         = ${"%.2f".format(Locale.US, r.realFps)}")
            sb.appendLine("generated_fps    = ${"%.2f".format(Locale.US, r.generatedFps)}")
            sb.appendLine("posted_fps       = ${"%.2f".format(Locale.US, r.postedFps)}")
            sb.appendLine("unique_captures  = ${r.totalUniqueCaptures}")
            sb.appendLine("generated_frames = ${r.totalGeneratedFrames}")
            sb.appendLine("posted_frames    = ${r.totalPostedFrames}")
            // Frame loss ratio: (real_fps * multiplier) is the theoretical
            // posted-fps target if every captured frame produced multiplier
            // outputs. Posted/target < 1.0 means we dropped frames.
            val target = r.realFps * r.multiplier
            val ratio = if (target > 0) r.postedFps / target else 0.0
            sb.appendLine("posted_vs_target = ${"%.3f".format(Locale.US, ratio)} (1.000 = perfect)")
            sb.appendLine("stalls           = ${r.stallCount} (intervals > 2x vsync)")
            r.vsyncAlignmentPercent?.let {
                sb.appendLine("vsync_alignment  = ${"%.1f".format(Locale.US, it)}%")
            }
            r.pacingMs?.let { p ->
                sb.appendLine("pacing_samples   = ${p.sampleCount}")
                sb.appendLine("pacing_min_ms    = ${"%.2f".format(Locale.US, p.minMs)}")
                sb.appendLine("pacing_p50_ms    = ${"%.2f".format(Locale.US, p.p50Ms)}")
                sb.appendLine("pacing_p90_ms    = ${"%.2f".format(Locale.US, p.p90Ms)}")
                sb.appendLine("pacing_p99_ms    = ${"%.2f".format(Locale.US, p.p99Ms)}")
                sb.appendLine("pacing_max_ms    = ${"%.2f".format(Locale.US, p.maxMs)}")
                sb.appendLine("pacing_mean_ms   = ${"%.2f".format(Locale.US, p.meanMs)}")
                sb.appendLine("pacing_stddev_ms = ${"%.2f".format(Locale.US, p.stddevMs)}")
                sb.appendLine("pacing_jitter    = ${"%.3f".format(Locale.US, p.jitterRatio)}")
            }
            r.profile?.let { p ->
                sb.appendLine("profile_samples  = ${p.samples}")
                sb.appendLine("profile_copy_ms  = ${"%.3f".format(Locale.US, p.copyMs)}")
                sb.appendLine("profile_pres_ms  = ${"%.3f".format(Locale.US, p.presentMs)}")
                sb.appendLine("profile_wait_ms  = ${"%.3f".format(Locale.US, p.waitIdleMs)}")
                sb.appendLine("profile_blit_ms  = ${"%.3f".format(Locale.US, p.blitMs)}")
                sb.appendLine("profile_total_ms = ${"%.3f".format(Locale.US, p.totalMs)}")
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    /**
     * Writes [text] to a timestamped file in the app cache directory and
     * returns the file. The cache root is exposed via FileProvider in
     * file_provider_paths.xml.
     */
    fun write(ctx: Context, text: String): File {
        val dir = File(ctx.cacheDir, "benchmark").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        file.writeText(text)
        return file
    }

    /**
     * Builds an ACTION_SEND chooser-friendly Intent for [file]. The caller
     * wraps it with Intent.createChooser before startActivity.
     */
    fun buildShareIntent(ctx: Context, file: File): Intent {
        val authority = "${ctx.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(ctx, authority, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LSFG-Android benchmark — ${file.nameWithoutExtension}")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return fmt.format(Date(ms))
    }

    private fun displayRefreshHz(ctx: Context): String {
        return runCatching {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            "%.1f".format(Locale.US, wm.defaultDisplay.refreshRate)
        }.getOrDefault("?")
    }

    private fun displaySupportedModes(ctx: Context): String {
        return runCatching {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.supportedModes.joinToString(",") {
                "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(Locale.US, it.refreshRate)}"
            }
        }.getOrDefault("?")
    }
}
