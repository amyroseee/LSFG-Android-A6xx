package com.lsfg.android.session

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * Wraps [MediaProjection] + [VirtualDisplay] and directs the captured content onto a Surface
 * the caller provides, at a caller-specified size so overlay and capture stay pixel-aligned.
 *
 * The Surface lifecycle is fragile — a valid Surface can disappear at any time (orientation
 * change, SurfaceFlinger reshuffling, user entering another immersive app). [setSurface]
 * and [clearSurface] track that lifecycle without tearing down the VirtualDisplay itself,
 * which is important: re-creating the VirtualDisplay would require a second MediaProjection
 * consent on some OEMs.
 */
class CaptureEngine(
    private val ctx: Context,
    private val mediaProjection: MediaProjection,
) {

    private var virtualDisplay: VirtualDisplay? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var currentSurface: Surface? = null

    @Volatile
    private var currentW: Int = 0

    @Volatile
    private var currentH: Int = 0

    /**
     * When true, skip the (future) LSFG processing step and forward raw capture frames
     * straight to the overlay Surface. Read by the processing pipeline once it's wired
     * up; today the capture path is already pass-through so this is a no-op at the GPU
     * level but still useful as the single source of truth for the drawer's bypass
     * toggle.
     */
    @Volatile
    var frameGenBypass: Boolean = false

    // --- FPS counter ---
    //
    // Android 14 forbids a second createVirtualDisplay on the same MediaProjection
    // (SecurityException "Don't take multiple captures..."), so the old probe display
    // is gone. In LSFG mode we count frames directly from the main ImageReader
    // listener in setLsfgMode. In mirror mode the VirtualDisplay writes straight
    // to the overlay Surface and we have no frame-level hook — the counter simply
    // stays idle and reports only the generated count from framegen (0 when
    // framegen is disabled too).

    fun interface FpsListener {
        fun onFpsUpdate(capturedFps: Float, postedFps: Float)
    }

    /**
     * Emitted ~5 times per second while the graph is enabled. Values are the
     * instantaneous fps over the last sample window (200 ms by default), split
     * into real captured frames and LSFG-generated frames.
     */
    fun interface FrameGraphListener {
        fun onFrameGraphSample(realFps: Float, generatedFps: Float)
    }

    @Volatile
    private var fpsListener: FpsListener? = null
    @Volatile
    private var graphListener: FrameGraphListener? = null
    private var fpsThread: HandlerThread? = null
    private var fpsHandler: Handler? = null
    private var fpsPoller: Runnable? = null
    private var graphPoller: Runnable? = null

    @Volatile
    private var fpsFrameCount: Long = 0
    private var fpsWindowStartMs: Long = 0L
    private var lastGeneratedCount: Long = 0L

    // Parallel counters for the graph poller — kept separate so the 1 Hz FPS
    // window and the 5 Hz graph window don't steal each other's deltas.
    @Volatile
    private var graphCapturedCount: Long = 0L
    private var graphWindowStartMs: Long = 0L
    private var graphLastGeneratedCount: Long = 0L

    fun setFpsListener(l: FpsListener?) {
        fpsListener = l
    }

    fun setFrameGraphListener(l: FrameGraphListener?) {
        graphListener = l
    }

    private fun ensureFpsThread(): Handler {
        val existing = fpsHandler
        if (existing != null) return existing
        val t = HandlerThread("lsfg-fps").also { it.start() }
        val h = Handler(t.looper)
        fpsThread = t
        fpsHandler = h
        return h
    }

    private fun maybeQuitFpsThread() {
        if (fpsPoller == null && graphPoller == null) {
            fpsThread?.quitSafely()
            fpsThread = null
            fpsHandler = null
        }
    }

    // FPS / graph polling used to count ImageReader callbacks and framegen-
    // generated frames separately. That was wrong on two counts:
    //   (1) ImageReader fires at the display refresh rate (120 Hz on most
    //       flagships), not at the target app's render rate — so "real fps"
    //       would show 120 even for a 60-fps game;
    //   (2) The worker drops many captures (queueDepth=2), so the ImageReader
    //       count wasn't even the "posted to overlay" count — summing it
    //       with genFps double-counted.
    // Now we ask the native side for two ground-truth counters:
    //   uniqueCaptures  — frames with distinct pixel content (target render rate)
    //   postedFrames    — frames actually posted to the overlay (CPU or WSI path)
    @Volatile
    private var lastUniqueCaptureCount: Long = 0L
    @Volatile
    private var graphLastUniqueCaptureCount: Long = 0L
    @Volatile
    private var lastPostedCount: Long = 0L
    @Volatile
    private var graphLastPostedCount: Long = 0L
    // EMA state for the 5 Hz frame graph. Raw per-window counts jitter because
    // the 200 ms window catches an integer number of worker cycles (typically
    // 5-7 of ~33 ms), and the boundary varies — so 20 % raw jitter even when
    // the underlying rate is steady. α=0.35 → ~3-sample lag ≈ 600 ms settling,
    // slow enough to kill aliasing, fast enough to follow real rate changes.
    @Volatile
    private var graphRealEma: Float = 0f
    @Volatile
    private var graphGenEma: Float = 0f

    @Synchronized
    fun startFpsCounter() {
        if (fpsPoller != null) return
        val h = ensureFpsThread()

        fpsWindowStartMs = SystemClock.elapsedRealtime()
        lastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        lastPostedCount = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - fpsWindowStartMs).coerceAtLeast(1L)
                fpsWindowStartMs = now

                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - lastUniqueCaptureCount).coerceAtLeast(0L)
                lastUniqueCaptureCount = uniqNow
                val realFps = uniqDelta * 1000f / elapsed

                val postedNow = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
                val postedDelta = (postedNow - lastPostedCount).coerceAtLeast(0L)
                lastPostedCount = postedNow
                val totalFps = postedDelta * 1000f / elapsed

                fpsListener?.onFpsUpdate(realFps, totalFps)
                fpsHandler?.postDelayed(this, 1000L)
            }
        }
        fpsPoller = poll
        h.postDelayed(poll, 1000L)
        LsfgLog.i(TAG, "FPS counter started (native counters)")
    }

    @Synchronized
    fun stopFpsCounter() {
        fpsPoller?.let { fpsHandler?.removeCallbacks(it) }
        fpsPoller = null
        fpsFrameCount = 0
        maybeQuitFpsThread()
    }

    @Synchronized
    fun startFrameGraph() {
        if (graphPoller != null) return
        val h = ensureFpsThread()

        graphWindowStartMs = SystemClock.elapsedRealtime()
        graphLastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        graphLastGeneratedCount = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
        graphRealEma = 0f
        graphGenEma = 0f
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - graphWindowStartMs).coerceAtLeast(1L)
                graphWindowStartMs = now

                // Real line: target-app render rate, measured as the rate of
                // capture frames whose pixel content changed vs. the prior
                // frame. This is the ground truth for "how fast is the game
                // actually producing new content".
                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - graphLastUniqueCaptureCount).coerceAtLeast(0L)
                graphLastUniqueCaptureCount = uniqNow
                val realFpsRaw = uniqDelta * 1000f / elapsed

                // Generated line: LSFG-interpolated output frames. Incremented
                // in the native worker by `g.outputs.size()` per cycle — a
                // clean count of synthetic frames independent of capture rate.
                // Using this directly (instead of postedDelta - realPosted,
                // which double-counted when uniqDelta > cycle rate) fixes the
                // "30 fps of generated" mismatch seen with 60 fps games.
                val genNow = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
                val genDelta = (genNow - graphLastGeneratedCount).coerceAtLeast(0L)
                graphLastGeneratedCount = genNow
                val genFpsRaw = genDelta * 1000f / elapsed

                // EMA smoothing — flattens the aliasing jitter that comes from
                // a 200 ms window catching 5-7 worker cycles depending on
                // phase. Seeds with the first real value so the curve doesn't
                // start from zero.
                val alpha = 0.35f
                graphRealEma = if (graphRealEma <= 0.01f) realFpsRaw
                               else alpha * realFpsRaw + (1f - alpha) * graphRealEma
                graphGenEma  = if (graphGenEma  <= 0.01f) genFpsRaw
                               else alpha * genFpsRaw  + (1f - alpha) * graphGenEma

                graphListener?.onFrameGraphSample(graphRealEma, graphGenEma)
                fpsHandler?.postDelayed(this, 200L)
            }
        }
        graphPoller = poll
        h.postDelayed(poll, 200L)
        LsfgLog.i(TAG, "Frame graph started (5 Hz, EMA-smoothed native counters)")
    }

    @Synchronized
    fun stopFrameGraph() {
        graphPoller?.let { fpsHandler?.removeCallbacks(it) }
        graphPoller = null
        graphCapturedCount = 0
        maybeQuitFpsThread()
    }

    /**
     * Called by the LSFG-mode ImageReader listener whenever a fresh capture frame
     * arrives. Cheap atomic increment — no-op if the counter isn't running.
     */
    internal fun onCaptureFrameArrived() {
        fpsFrameCount++
        graphCapturedCount++
    }

    // --- LSFG mode ---
    //
    // In mirror mode (setSurface) the VirtualDisplay points straight at the overlay
    // SurfaceView and there is no frame generation. In LSFG mode the VirtualDisplay
    // points at an ImageReader instead, and each frame's HardwareBuffer is forwarded
    // to the native render loop via NativeBridge.pushFrame(). The native side blits
    // generated frames to the overlay (which it learned about via setOutputSurface).
    private var lsfgReader: ImageReader? = null
    private var lsfgThread: HandlerThread? = null
    private var lsfgHandler: Handler? = null
    private var lsfgFrameLogCount: Int = 0

    @Volatile
    private var lsfgNativeInputEnabled: Boolean = true

    fun setLsfgNativeInputEnabled(enabled: Boolean) {
        lsfgNativeInputEnabled = enabled
    }

    @Synchronized
    fun setLsfgMode(width: Int, height: Int) {
        // Android 14+ treats each MediaProjection token as single-use for
        // createVirtualDisplay() on many OEM builds. Keep the existing display
        // alive and retarget it from mirror output to the ImageReader instead
        // of releasing it and creating a second display.
        currentSurface = null

        stopLsfgMode()

        val t = HandlerThread("lsfg-capture-lsfg").also { it.start() }
        val h = Handler(t.looper)
        lsfgThread = t
        lsfgHandler = h
        lsfgFrameLogCount = 0

        val reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888,
            /* maxImages */
            // Keep a slightly deeper queue than the mirror path so we can
            // preserve consecutive captures for framegen instead of constantly
            // collapsing to the latest frame during fast camera motion.
            5,
            android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        reader.setOnImageAvailableListener({ r ->
            // In LSFG mode we want temporal continuity more than minimum latency:
            // dropping to "latest" makes consecutive inputs farther apart in time,
            // which is exactly what produces torso/character warping on fast pans.
            val img = runCatching { r.acquireNextImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                onCaptureFrameArrived()
                val hb = img.hardwareBuffer
                if (hb != null) {
                    if (lsfgFrameLogCount < 8) {
                        lsfgFrameLogCount++
                        LsfgLog.i(
                            TAG,
                            "LSFG frame #$lsfgFrameLogCount ts=${img.timestamp} hb=${hb.width}x${hb.height}",
                        )
                    }
                    if (lsfgNativeInputEnabled) {
                        runCatching { NativeBridge.pushFrame(hb, img.timestamp) }
                            .onFailure { LsfgLog.w(TAG, "pushFrame failed", it) }
                    }
                    hb.close()
                }
            } finally {
                img.close()
            }
        }, h)
        lsfgReader = reader

        currentW = width
        currentH = height

        val dpi = ctx.resources.displayMetrics.densityDpi
        val vd = virtualDisplay
        if (vd == null) {
            val flags = 0
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "lsfg-vd",
                width, height, dpi,
                flags,
                reader.surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() { LsfgLog.i(TAG, "LSFG VD paused") }
                    override fun onResumed() { LsfgLog.i(TAG, "LSFG VD resumed") }
                    override fun onStopped() { LsfgLog.i(TAG, "LSFG VD stopped") }
                },
                h,
            )
            LsfgLog.i(TAG, "LSFG mode active ${width}x${height} @${dpi}dpi flags=0x${flags.toString(16)}")
        } else {
            runCatching { vd.resize(width, height, dpi) }
                .onFailure { LsfgLog.w(TAG, "LSFG resize failed", it) }
            runCatching { vd.surface = reader.surface }
                .onFailure { LsfgLog.w(TAG, "LSFG retarget failed", it) }
            LsfgLog.i(TAG, "LSFG mode retargeted ${width}x${height} @${dpi}dpi")
        }
    }

    @Synchronized
    private fun stopLsfgMode() {
        lsfgNativeInputEnabled = false
        runCatching { virtualDisplay?.surface = null }
            .onFailure { LsfgLog.w(TAG, "detaching LSFG surface failed", it) }
        runCatching { lsfgReader?.close() }
        lsfgReader = null
        lsfgThread?.quitSafely()
        lsfgThread = null
        lsfgHandler = null
    }

    /**
     * Points the capture at a freshly-valid Surface. Safe to call multiple times: the first
     * call lazily creates the VirtualDisplay; later calls swap the destination and resize.
     */
    @Synchronized
    fun setSurface(outputSurface: Surface, width: Int, height: Int) {
        LsfgLog.i(TAG, "setSurface enter ${width}x${height} valid=${outputSurface.isValid} hasVD=${virtualDisplay != null}")
        if (!outputSurface.isValid) {
            LsfgLog.w(TAG, "setSurface called with invalid Surface; ignoring")
            return
        }
        lsfgNativeInputEnabled = false
        currentSurface = outputSurface
        currentW = width
        currentH = height

        val vd = virtualDisplay
        if (vd == null) {
            val t = HandlerThread("lsfg-capture").also { it.start() }
            val h = Handler(t.looper)
            thread = t
            handler = h

            val dpi = ctx.resources.displayMetrics.densityDpi
            // IMPORTANT: flags=0 is what scrcpy and most screen-share apps use.
            // MediaProjection already delivers the physical display frames by
            // default. Adding AUTO_MIRROR or PRESENTATION creates a secondary
            // logical display and on PowerVR this feeds the overlay back into
            // itself (first frame freezes, captures itself, loop closes).
            val flags = 0
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "lsfg-vd",
                width, height, dpi,
                flags,
                outputSurface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() { LsfgLog.i(TAG, "VirtualDisplay paused") }
                    override fun onResumed() { LsfgLog.i(TAG, "VirtualDisplay resumed") }
                    override fun onStopped() { LsfgLog.i(TAG, "VirtualDisplay stopped") }
                },
                h,
            )
            LsfgLog.i(TAG, "VirtualDisplay created ${width}x${height} @${dpi}dpi flags=0x${flags.toString(16)}")
        } else {
            val dpi = ctx.resources.displayMetrics.densityDpi
            // Resize BEFORE swapping the surface: some drivers stop posting frames to
            // the new surface until its dimensions match the VirtualDisplay.
            runCatching { vd.resize(width, height, dpi) }
                .onFailure { LsfgLog.w(TAG, "resize failed", it) }
            runCatching { vd.surface = outputSurface }
                .onFailure { LsfgLog.w(TAG, "setting surface failed", it) }
            LsfgLog.i(TAG, "VirtualDisplay retargeted ${width}x${height}")
        }
    }

    /**
     * Detaches the current Surface without destroying the VirtualDisplay. The display keeps
     * running (paused) until [setSurface] attaches a new one.
     */
    @Synchronized
    fun clearSurface() {
        val vd = virtualDisplay ?: return
        currentSurface = null
        runCatching { vd.surface = null }
            .onFailure { LsfgLog.w(TAG, "clearing surface failed", it) }
        LsfgLog.i(TAG, "Surface detached from VirtualDisplay")
    }

    @Synchronized
    fun stop() {
        stopFpsCounter()
        stopLsfgMode()
        virtualDisplay?.release()
        virtualDisplay = null
        currentSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /**
     * Halts input pumping into the native pipeline without destroying the
     * MediaProjection token. Used by the service before re-creating the LSFG
     * context: we want the C++ worker to drain its queue before destroyContext
     * so a slow vkDeviceWaitIdle doesn't run while a new pushFrame is racing
     * the teardown. setLsfgMode() is called afterwards to re-create the
     * ImageReader against the new context's input AHBs.
     */
    @Synchronized
    fun pauseLsfgInput() {
        stopLsfgMode()
        currentSurface = null
    }

    companion object {
        private const val TAG = "CaptureEngine"
    }
}
