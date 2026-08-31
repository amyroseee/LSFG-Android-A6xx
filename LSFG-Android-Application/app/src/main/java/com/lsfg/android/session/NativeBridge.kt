package com.lsfg.android.session

import android.hardware.HardwareBuffer
import android.view.Surface

/**
 * JNI entry points into liblsfg-android.so.
 * Implementations live in app/src/main/cpp/lsfg_jni.cpp.
 *
 * Every method returns a simple result so the Kotlin side can surface errors in the UI
 * without throwing across JNI.
 */
object NativeBridge {

    init {
        System.loadLibrary("lsfg-android")
    }

    /** Returns the liblsfg-android.so build version string. Used as a cheap sanity check. */
    external fun nativeVersion(): String

    /**
     * Installs native signal handlers (SIGSEGV/SIGABRT/SIGBUS/SIGFPE/SIGILL) that write a
     * crash report to [crashPath] and mirror every native LOG* call into [logPath].
     * Idempotent — subsequent calls are no-ops. Call once, early, from Application.onCreate.
     */
    external fun initCrashReporter(crashPath: String, logPath: String)

    /**
     * Extracts DXBC resources from the user-provided Lossless.dll and translates them to SPIR-V,
     * writing the result into [cacheDir]. Idempotent — a cached SPIR-V set is reused on subsequent
     * calls as long as [dllSha256] matches.
     *
     * @return 0 on success, non-zero error code otherwise.
     */
    external fun extractShaders(
        dllPath: String,
        dllSha256: String,
        cacheDir: String,
    ): Int

    /**
     * Creates a headless Vulkan instance + device and runs `vkCreateShaderModule` across every
     * cached SPIR-V blob. Useful to catch driver-side rejection of the DXBC→SPIR-V output
     * before we try to build the full pipeline.
     *
     * @return 0 on success; -10 no Vulkan loader, -11 cache missing, -12 shader rejected.
     */
    external fun probeShaders(cacheDir: String): Int

    /**
     * Initialises the Vulkan session, allocates ping-pong AHardwareBuffer-backed input images
     * plus [multiplier]-many output images, and creates a framegen LSFG_3_1 context.
     *
     * @return 0 on success; negative codes from android_vk_session.hpp / lsfg_render_loop.hpp
     *   describe specific failures (no Vulkan, missing extensions, AHB allocation failure, etc.).
     *   On a -41/-42 the Kotlin side should fall back to mirror mode and surface a notice.
     */
    external fun initContext(
        cacheDir: String,
        width: Int,
        height: Int,
        multiplier: Int,
        flowScale: Float,
        performance: Boolean,
        hdr: Boolean,
        antiArtifacts: Boolean,
        framegenFp16: Boolean,
        npuPostProcessing: Boolean,
        npuPreset: Int,
        npuUpscaleFactor: Int,
        npuAmount: Float,
        npuRadius: Float,
        npuThreshold: Float,
        npuFp16: Boolean,
        cpuPostProcessing: Boolean,
        cpuPreset: Int,
        cpuStrength: Float,
        cpuSaturation: Float,
        cpuVibrance: Float,
        cpuVignette: Float,
        gpuPostProcessing: Boolean,
        gpuStage: Int,
        gpuMethod: Int,
        gpuUpscaleFactor: Float,
        gpuSharpness: Float,
        gpuStrength: Float,
        targetFpsCap: Int,
        emaAlpha: Float,
        outlierRatio: Float,
        vsyncSlackMs: Float,
        queueDepth: Int,
    ): Int

    /** True only when NNAPI reports a dedicated accelerator device, not CPU/GPU fallback. */
    external fun isNpuAvailable(): Boolean

    /**
     * Reports whether the FP16 frame-generation shader path is usable on this
     * device. Two prerequisites must both hold:
     *  - The Vulkan driver advertises VK_KHR_shader_float16_int8 with
     *    `shaderFloat16=VK_TRUE`.
     *  - The DLL extraction step has populated `<cacheDir>/fp16/` with the
     *    49 SPIR-V FP16 shader variants from Lossless.dll resource IDs 304..351.
     *
     * The UI uses this to grey out the "FP16 frame-gen shaders" toggle on
     * unsupported hardware or before the user has picked a DLL. Cheap to
     * call (single VkInstance create + feature query, no device created).
     */
    external fun isFramegenFp16Supported(cacheDir: String): Boolean

    /** Human-readable NNAPI accelerator list for diagnostics and settings copy. */
    external fun getNpuSummary(): String

    /**
     * Attaches the overlay [surface] as the destination for blit of generated frames.
     * Pass null to detach. Safe to call before initContext (it just stashes the window).
     */
    external fun setOutputSurface(surface: Surface?, w: Int, h: Int)

    /**
     * Hands a fresh capture frame (as a [HardwareBuffer]) to the native render loop.
     * The native side acquires its own reference, so the caller may close the source
     * Image/HardwareBuffer immediately after this returns.
     */
    external fun pushFrame(hardwareBuffer: HardwareBuffer, timestampNs: Long)

    /**
     * Atomic counter of frames produced by framegen since the last initContext.
     * Used by CaptureEngine's FPS poller to compute the "total fps" delta.
     */
    external fun getGeneratedFrameCount(): Long

    /**
     * Total frames actually posted to the overlay surface since the last
     * initContext (both real captures AND LSFG-generated frames, across CPU
     * blit and WSI present paths). This is the ground-truth count for
     * "total fps" in the HUD — replaces the old `capturedFps + genFps` sum
     * which conflated capture rate with post rate.
     */
    external fun getPostedFrameCount(): Long

    /**
     * Number of capture frames whose pixel content differs from the previous
     * capture. MediaProjection delivers at the display refresh rate, which is
     * usually higher than the target app's render rate — consecutive captures
     * are often pixel-identical duplicates of the same game frame. This
     * counter approximates the target app's TRUE render rate (what the HUD
     * should show as "real fps"). Computed via an 8×8 luma hash in pushFrame.
     */
    external fun getUniqueCaptureCount(): Long

    /**
     * Average native queue residency time in milliseconds for the last completed profiling window.
     */
    external fun getAverageQueueMs(): Double

    /**
     * Average end-to-end latency in milliseconds (capture-to-display) for the last completed profiling window.
     */
    external fun getAverageLatencyMs(): Double

    /**
     * Fills [outIntervalsNs] with the nanosecond intervals between consecutive
     * overlay posts, newest-first. Returns the number of intervals actually
     * written (may be fewer than the array length if the session is young).
     * Used by the HUD frame-pacing graph to show real jitter instead of rolling
     * counts.
     */
    external fun getRecentPostIntervalsNs(outIntervalsNs: LongArray): Int

    /**
     * Snapshot of the most recently completed profiling window from the native
     * worker thread. `out` must have length >= 6; on success it's populated with
     * `[copyNs, presentNs, waitIdleNs, blitNs, totalNs, samples]` (segment SUMS
     * over the window — divide by samples for per-frame averages) and the call
     * returns 6. Returns 0 when no window has closed yet (samples == 0). Used
     * by the benchmark mode to surface frame-time profiling without scraping
     * logcat.
     */
    external fun getProfileWindowNs(out: LongArray): Int

    /**
     * Toggle frame-generation bypass. When true, the native render loop blits
     * the latest captured frame straight to the overlay surface and skips
     * framegen entirely — useful for A/B comparisons against the generated output.
     * Persists across re-inits triggered by other parameter changes.
     */
    external fun setBypass(bypass: Boolean)

    /** Enables the native high-motion artifact suppression guard. */
    external fun setAntiArtifacts(enabled: Boolean)

    /**
     * Reports the overlay display's vsync period to the native pacing loop
     * (nanoseconds; e.g. 16_666_666 for 60 Hz). When non-zero the pacer aligns
     * consecutive unlockAndPost calls to distinct vsync slots, which removes
     * the periodic stutter visible when framegen multiplier > 1 causes two
     * posts to land in the same SurfaceFlinger flip. Pass 0 to disable.
     */
    external fun setVsyncPeriodNs(periodNs: Long)

    /**
     * Hot-apply pacing parameters to the running render loop without tearing
     * down the Vulkan context. `targetFpsCap` = 0 means no cap. Safe to call
     * from any thread; all values are stored atomically and picked up on the
     * next pacing iteration.
     */
    external fun setPacingParams(
        targetFpsCap: Int,
        emaAlpha: Float,
        outlierRatio: Float,
        vsyncSlackMs: Float,
        queueDepth: Int,
    )

    /** Enables the Shizuku timing side channel for pacing decisions. */
    external fun setShizukuTimingEnabled(enabled: Boolean)

    /**
     * Reports one timing sample from the Shizuku metrics side channel.
     * Used only to influence pacing / frame skipping, never as the visible
     * video source.
     */
    external fun reportShizukuTiming(
        timestampNs: Long,
        frameTimeNs: Long,
        pacingJitterNs: Long,
    )

    /** Tears down the Vulkan context. Safe to call multiple times. */
    external fun destroyContext()
}
