package com.lsfg.android.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.lsfg.android.R
import com.lsfg.android.benchmark.BenchmarkController
import com.lsfg.android.benchmark.BenchmarkLogWriter
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.PacingDefaults
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs for the lifetime of an LSFG session. Owns the MediaProjection token, the
 * CaptureEngine, and the OverlayManager.
 *
 * Startup sequence (important for stable overlay behaviour):
 *   1. Post foreground notification (required by FGS + mediaProjection rules).
 *   2. Acquire MediaProjection from the consent intent.
 *   3. Show overlay (adds window via WindowManager — synchronous).
 *   4. **Wait for surfaceCreated before starting capture.** On-create is async;
 *      launching the target app before the Surface is ready was causing capture
 *      to race with the target's first paint, leaving the overlay empty.
 *   5. Launch the target app + re-assert overlay z-order.
 */
class LsfgForegroundService : Service() {

    private var projection: MediaProjection? = null
    private var capture: CaptureEngine? = null
    private var shizukuCapture: ShizukuCaptureEngine? = null
    private var rootCapture: RootCaptureEngine? = null
    private var overlay: OverlayManager? = null
    private var drawer: SettingsDrawerOverlay? = null
    private var targetPkgPending: String? = null
    private var initialCaptureStarted: Boolean = false
    private var lsfgContextActive: Boolean = false
    private var lastSurface: Surface? = null
    private var lastSurfaceW: Int = 0
    private var lastSurfaceH: Int = 0
    @Volatile
    private var activeRenderW: Int = 0
    @Volatile
    private var activeRenderH: Int = 0
    @Volatile
    private var reinitInFlight: Boolean = false
    // Signalled when the in-flight reinit thread finishes. Lets onDestroy() wait
    // for completion without busy-polling the Main thread (the previous code
    // burned ~75 cycles of Thread.sleep(20) before timeout, blocking the looper
    // and starving system input — causing visible freeze on swipe-out).
    @Volatile
    private var reinitDoneLatch: CountDownLatch? = null
    // When the user changes a parameter while a previous reinit is still in
    // flight, we can't start a second one concurrently (it would race on the
    // native context). Instead we mark a pending request and the in-flight
    // reinit re-runs itself once it finishes, picking up the freshest prefs.
    // Without this, mid-reinit changes were silently dropped — that's why
    // toggling "Bypass" appeared to "make settings apply": users were
    // accidentally triggering a second reinit by changing something else.
    @Volatile
    private var reinitRequested: Boolean = false
    @Volatile
    private var pendingReinitW: Int = 0
    @Volatile
    private var pendingReinitH: Int = 0
    // Set in onDestroy. While true, new reinit requests are dropped on the
    // floor — we're tearing down the service and any allocation we'd do here
    // would just have to be undone (and would race the shutdown).
    @Volatile
    private var shuttingDown: Boolean = false
    @Volatile
    private var pendingFpsCounter: Boolean = false
    @Volatile
    private var benchmarkRequested: Boolean = false
    @Volatile
    private var benchmarkStarted: Boolean = false
    /** Polls [BenchmarkController.currentState] to feed the in-overlay
     *  progress panel. Started in [maybeStartBenchmark]; cancelled when
     *  the controller reaches Completed/Failed or the service tears down. */
    private var benchmarkProgressPoller: Runnable? = null
    private var pendingPrivilegedVideoStart: ShizukuVideoStart? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    // Display rotation listener — Service.onConfigurationChanged only fires
    // for configChanges declared in the manifest, but services can't declare
    // them. DisplayManager.DisplayListener fires on every rotation regardless.
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        registerDisplayListener()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Belt-and-braces: in addition to DisplayListener we also handle the
        // platform configuration callback so very quick rotations don't slip
        // through (some OEMs deliver one but not the other).
        propagateDisplayChange()
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                propagateDisplayChange()
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        runCatching { dm.registerDisplayListener(listener, mainHandler) }
            .onSuccess { displayListener = listener }
            .onFailure { LsfgLog.w(TAG, "registerDisplayListener failed", it) }
    }

    private fun unregisterDisplayListener() {
        val l = displayListener ?: return
        displayListener = null
        val dm = getSystemService(DisplayManager::class.java) ?: return
        runCatching { dm.unregisterDisplayListener(l) }
    }

    private fun propagateDisplayChange() {
        // Always run on the main thread — WindowManager rejects updateViewLayout
        // calls from binder threads on some OEMs, and OverlayManager /
        // SettingsDrawerOverlay both touch the WM internally.
        mainHandler.post {
            runCatching { overlay?.onDisplayConfigurationChanged() }
                .onFailure { LsfgLog.w(TAG, "overlay.onDisplayConfigurationChanged failed", it) }
            runCatching { drawer?.onDisplayConfigurationChanged() }
                .onFailure { LsfgLog.w(TAG, "drawer.onDisplayConfigurationChanged failed", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LsfgLog.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                LsfgLog.i(TAG, "ACTION_STOP received — stopSelf()")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LsfgLog.i(TAG, "onDestroy — tearing down service (caller triggered stopSelf or system killed us)")
        super.onDestroy()
        unregisterDisplayListener()
        // Block any further reinit requests and wait for one already in flight
        // to finish. Without this, a parameter change happening concurrently
        // with stopSelf() races destroyContext() on the C++ side: the reinit
        // worker is in the middle of initRenderLoop(), allocating AHB images
        // and starting the worker thread, while onDestroy calls
        // shutdownRenderLoop() which joins that same worker and frees the
        // images — SIGSEGV on the next access. This was the "stop overlay
        // crashes when settings stop applying" symptom.
        shuttingDown = true
        stopBenchmarkProgressPoller()
        // Wait on the latch instead of polling — frees the Main thread looper
        // to deliver pending input events instead of sleeping in 20 ms ticks.
        val latch = reinitDoneLatch
        if (latch != null && reinitInFlight) {
            val finished = latch.await(1500, TimeUnit.MILLISECONDS)
            if (!finished) {
                LsfgLog.w(TAG, "onDestroy: reinit still in flight after 1.5s — proceeding anyway")
            }
        }
        capture?.stop()
        capture = null
        shizukuCapture?.stop()
        shizukuCapture = null
        rootCapture?.stop()
        rootCapture = null
        runCatching { NativeBridge.setShizukuTimingEnabled(false) }
            .onFailure { LsfgLog.w(TAG, "setShizukuTimingEnabled(false) failed during teardown", it) }
        if (lsfgContextActive) {
            runCatching { NativeBridge.setOutputSurface(null, 0, 0) }
            runCatching { NativeBridge.destroyContext() }
            lsfgContextActive = false
        }
        drawer?.hide()
        drawer = null
        overlay?.hide()
        overlay = null
        projection?.stop()
        projection = null
        // The session is gone — the launcher dot may want to re-appear if the
        // target app is still in the foreground.
        AutoOverlayController.onSessionStopped(applicationContext)
    }

    private fun handleStart(intent: Intent) {
        val captureSource = CaptureSource.fromPref(intent.getStringExtra(EXTRA_CAPTURE_SOURCE))
        val isPrivilegedCapture = captureSource == CaptureSource.SHIZUKU || captureSource == CaptureSource.ROOT
        runCatching { NativeBridge.setShizukuTimingEnabled(isPrivilegedCapture) }
            .onFailure { LsfgLog.w(TAG, "setShizukuTimingEnabled($isPrivilegedCapture) failed", it) }
        val usesMediaProjectionVideo = captureSource == CaptureSource.MEDIA_PROJECTION
        // Pick the FGS type that matches what we'll actually do this session.
        // - MediaProjection capture → FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        //   requires a live projection token (handled below) and the matching
        //   uses-permission in the manifest.
        // - Shizuku capture → FOREGROUND_SERVICE_TYPE_SPECIAL_USE. Without
        //   this, Android 14+/15+ rejects startForeground with
        //   "Starting FGS with type mediaProjection ... requires CAPTURE_VIDEO_OUTPUT
        //   or android:project_media" because the manifest declares
        //   foregroundServiceType="mediaProjection|specialUse" and the system
        //   defaults to the first declared type when none is passed.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (usesMediaProjectionVideo) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
        } else {
            0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(NOTIF_ID, buildNotification(), type)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        }
        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val initialFpsCounter = intent.getBooleanExtra(EXTRA_FPS_COUNTER, false)
        benchmarkRequested = intent.getBooleanExtra(EXTRA_BENCHMARK_REQUESTED, false)
        benchmarkStarted = false
        if (usesMediaProjectionVideo && (data == null || resultCode == 0)) {
            LsfgLog.e(TAG, "Missing MediaProjection result intent; stopping")
            stopSelf()
            return
        }

        val proj = if (usesMediaProjectionVideo) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mpm.getMediaProjection(resultCode, data!!).also {
                projection = it
                // Android 14 (API 34) requires a non-null Handler for registerCallback on
                // some OEMs (MediaTek/PowerVR devices observed revoking the projection
                // token instantly when callback handler is null). Register BEFORE any
                // VirtualDisplay is created so we also hear about system-initiated stops.
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        LsfgLog.i(TAG, "MediaProjection.onStop")
                        stopSelf()
                    }
                }, mainHandler)
            }
        } else {
            null
        }

        val ov = OverlayManager(this)
        overlay = ov
        targetPkgPending = targetPkg
        initialCaptureStarted = false

        // Tell the Automatic Overlay controller that the full session has started:
        // it hides the floating dot until we tear down again.
        AutoOverlayController.onSessionStarted(targetPkg)

        val cap = if (proj != null) CaptureEngine(this, proj) else null
        capture = cap
        val shizukuCap = if (captureSource == CaptureSource.SHIZUKU) {
            ShizukuCaptureEngine(this).also { engine ->
                engine.setErrorListener { msg ->
                    LsfgLog.w(TAG, msg)
                    ov.updateStatus(msg)
                }
            }
        } else null
        shizukuCapture = shizukuCap
        val rootCap = if (captureSource == CaptureSource.ROOT) {
            RootCaptureEngine(this).also { engine ->
                engine.setErrorListener { msg ->
                    LsfgLog.w(TAG, msg)
                    ov.updateStatus(msg)
                }
            }
        } else null
        rootCapture = rootCap
        cap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        cap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        shizukuCap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        shizukuCap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        rootCap?.setFpsListener { captured, posted -> ov.updateFps(captured, posted) }
        rootCap?.setFrameGraphListener { realFps, genFps ->
            ov.pushFrameGraphSample(realFps, genFps)
        }
        // Apply persisted LSFG on/off preference before the first frame is captured. The drawer
        // toggle persists `lsfgEnabled`; we mirror that into the bypass path (lsfgEnabled=false ⇒
        // bypass=true ⇒ raw passthrough).
        val persistedLsfgEnabled = com.lsfg.android.prefs.LsfgPreferences(this).load().lsfgEnabled
        if (!persistedLsfgEnabled) {
            cap?.frameGenBypass = true
            runCatching { NativeBridge.setBypass(true) }
                .onFailure { LsfgLog.w(TAG, "initial setBypass failed", it) }
            ov.updateStatus("LSFG: bypass (raw capture)")
        }
        // NOTE: deferring the initial FPS-counter wiring until AFTER ov.show() —
        // setFpsVisible() is a no-op when ov.fpsView hasn't been created yet,
        // and the view is only created inside show().

        ov.onSurfaceReady { surface, w, h ->
            // Android delivers surfaceCreated immediately followed by surfaceChanged
            // on the same Surface instance. Retargeting the VirtualDisplay onto the
            // identical Surface a second time stops frame delivery on PowerVR drivers
            // (the overlay freezes on the first frame). Coalesce duplicate ready
            // events when nothing actually changed.
            val sameAsLast = surface === lastSurface && w == lastSurfaceW && h == lastSurfaceH
            LsfgLog.i(TAG, "onSurfaceReady ${w}x${h} initial=$initialCaptureStarted sameAsLast=$sameAsLast")
            if (sameAsLast && initialCaptureStarted) {
                LsfgLog.i(TAG, "onSurfaceReady coalesced — identical Surface, skipping retarget")
                return@onSurfaceReady
            }
            lastSurface = surface
            lastSurfaceW = w
            lastSurfaceH = h
            // Always tell native about the latest output surface — even if the LSFG
            // context isn't (yet) active, this lets a future re-init pick it up.
            runCatching { NativeBridge.setOutputSurface(surface, w, h) }
                .onFailure { LsfgLog.w(TAG, "setOutputSurface failed", it) }

            if (!initialCaptureStarted) {
                initialCaptureStarted = true
                // CRITICAL ORDER (Android 14+ MediaTek/PowerVR):
                // getMediaProjection() opens a short window (~200 ms on some OEMs)
                // during which we MUST call createVirtualDisplay, or the system
                // revokes the projection with MediaProjection.onStop. We used to
                // run Vulkan init (100-200 ms) before the first createVirtualDisplay
                // and occasionally blew past that deadline. In MediaProjection
                // mode start capture first on the LSFG ImageReader so the token
                // is consumed immediately without ever mirroring the visible
                // overlay surface back into MediaProjection. On Orange Pi /
                // RK3588 Android builds, that mirror bootstrap can make the
                // framegen path capture its own overlay frames.
                if (cap != null) {
                    LsfgLog.i(TAG, "Starting ImageReader capture first to consume MediaProjection token")
                    cap.setLsfgNativeInputEnabled(false)
                    cap.setLsfgMode(w, h)
                }
                ov.updateStatus("LSFG: starting ${w}×${h}…")

                val cfg = LsfgPreferences(this).load()
                val cacheDir = File(filesDir, "spirv").absolutePath
                val pacing = PacingDefaults.forPreset(
                    cfg.pacingPreset,
                    PacingDefaults.Params(cfg.emaAlpha, cfg.outlierRatio, cfg.vsyncSlackMs, cfg.queueDepth),
                )
                val rc = runCatching {
                    NativeBridge.initContext(
                        cacheDir = cacheDir,
                        width = w,
                        height = h,
                        multiplier = cfg.multiplier,
                        flowScale = cfg.flowScale,
                        performance = cfg.performanceMode,
                        hdr = cfg.hdrMode,
                        antiArtifacts = cfg.antiArtifacts,
                        framegenFp16 = cfg.framegenFp16,
                        npuPostProcessing = cfg.npuPostProcessingEnabled,
                        npuPreset = cfg.npuPostProcessingPreset.nativeValue,
                        npuUpscaleFactor = cfg.npuUpscaleFactor,
                        npuAmount = cfg.npuAmount,
                        npuRadius = cfg.npuRadius,
                        npuThreshold = cfg.npuThreshold,
                        npuFp16 = cfg.npuFp16,
                        cpuPostProcessing = cfg.cpuPostProcessingEnabled,
                        cpuPreset = cfg.cpuPostProcessingPreset.nativeValue,
                        cpuStrength = cfg.cpuStrength,
                        cpuSaturation = cfg.cpuSaturation,
                        cpuVibrance = cfg.cpuVibrance,
                        cpuVignette = cfg.cpuVignette,
                        gpuPostProcessing = cfg.gpuPostProcessingEnabled,
                        gpuStage = cfg.gpuPostProcessingStage.nativeValue,
                        gpuMethod = cfg.gpuPostProcessingMethod.nativeValue,
                        gpuUpscaleFactor = cfg.gpuUpscaleFactor,
                        gpuSharpness = cfg.gpuSharpness,
                        gpuStrength = cfg.gpuStrength,
                        targetFpsCap = cfg.targetFpsCap,
                        emaAlpha = pacing.emaAlpha,
                        outlierRatio = pacing.outlierRatio,
                        vsyncSlackMs = pacing.vsyncSlackMs,
                        queueDepth = pacing.queueDepth,
                    )
                }.getOrElse { e ->
                    LsfgLog.w(TAG, "initContext threw", e)
                    -1
                }
                when {
                    rc == 0 -> {
                        // Framegen active: the ImageReader path is already running;
                        // now allow frames into the native render loop.
                        lsfgContextActive = true
                        activeRenderW = w
                        activeRenderH = h
                        cap?.setLsfgNativeInputEnabled(true)
                        if (isPrivilegedCapture) {
                            pendingPrivilegedVideoStart = ShizukuVideoStart(w, h, cfg)
                        }
                        ov.updateStatus("LSFG: frame-gen active ${w}×${h} ×${cfg.multiplier}")
                        // If the user launched this session via the Benchmark
                        // screen, hand control to BenchmarkController now that
                        // framegen has reached steady state. The controller runs
                        // on its own thread; we resume normal service handling.
                        maybeStartBenchmark()
                    }
                    rc > 0 -> {
                        LsfgLog.w(TAG, "initContext rc=$rc — framegen disabled, staying in mirror mode")
                        lsfgContextActive = true
                        if (isPrivilegedCapture) {
                            activeRenderW = 0
                            activeRenderH = 0
                            pendingPrivilegedVideoStart = ShizukuVideoStart(w, h, cfg)
                            val label = if (captureSource == CaptureSource.ROOT) "Root" else "Shizuku"
                            ov.updateStatus("LSFG: $label mirror ${w}×${h} (frame-gen init failed; see diagnostics)")
                        } else if (cap != null) {
                            cap.setSurface(surface, w, h)
                            activeRenderW = 0
                            activeRenderH = 0
                            // Retarget the bootstrap ImageReader capture to mirror
                            // mode because framegen is unavailable.
                            ov.updateStatus("LSFG: mirror ${w}×${h} (frame-gen init failed; see diagnostics)")
                        } else {
                            activeRenderW = 0
                            activeRenderH = 0
                            ov.updateStatus("LSFG: frame-gen unavailable (init rc=$rc)")
                        }
                    }
                    else -> {
                        LsfgLog.w(TAG, "initContext failed rc=$rc — staying in mirror mode")
                        activeRenderW = 0
                        activeRenderH = 0
                        if (isPrivilegedCapture && rc > 0) {
                            pendingPrivilegedVideoStart = ShizukuVideoStart(w, h, cfg)
                            val label = if (captureSource == CaptureSource.ROOT) "Root" else "Shizuku"
                            ov.updateStatus("LSFG: $label mirror active ${w}×${h} (init rc=$rc)")
                        } else if (cap != null) {
                            ov.updateStatus("LSFG: mirror active ${w}×${h} (init rc=$rc)")
                        } else {
                            ov.updateStatus("LSFG: init failed (rc=$rc)")
                        }
                    }
                }
                // Launch the target only after the first valid Surface exists so the
                // VirtualDisplay has somewhere to draw from frame #1.
                val pkg = targetPkgPending
                LsfgLog.i(TAG, "About to launch target: pkg=$pkg")
                if (pkg != null) {
                    targetPkgPending = null
                    launchTarget(pkg)
                    pendingPrivilegedVideoStart?.let { start ->
                        pendingPrivilegedVideoStart = null
                        mainHandler.postDelayed({
                            startShizukuVideo(shizukuCap, pkg, start.width, start.height, start.cfg)
                            startRootVideo(rootCap, pkg, start.width, start.height, start.cfg)
                        }, 500L)
                    }
                    // Re-assert the overlay on top a few times to win a potential race
                    // with the target activity's first-paint z-order assignment.
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 150)
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 600)
                    mainHandler.postDelayed({ overlay?.bringToFront() }, 1500)
                } else {
                    LsfgLog.w(TAG, "No target package set — overlay will show but no app is launched")
                }
                // FPS counter no longer creates a VirtualDisplay; it piggybacks on
                // the main LSFG-mode ImageReader. Safe to start immediately.
                if (pendingFpsCounter) {
                    pendingFpsCounter = false
                    runCatching {
                        when {
                            captureSource == CaptureSource.SHIZUKU -> shizukuCap?.startFpsCounter()
                            captureSource == CaptureSource.ROOT -> rootCap?.startFpsCounter()
                            else -> cap?.startFpsCounter()
                        }
                    }.onFailure { LsfgLog.w(TAG, "startFpsCounter failed", it) }
                }
            } else if (!lsfgContextActive || activeRenderW == 0) {
                // Mirror mode (framegen disabled or context not yet active): retarget
                // the existing VirtualDisplay onto the new Surface. activeRenderW==0
                // is our "running in mirror" sentinel — don't try to reinit the
                // render loop, just keep the capture alive.
                cap?.setSurface(surface, w, h)
            } else if (w != activeRenderW || h != activeRenderH) {
                LsfgLog.i(TAG, "Surface geometry changed ${activeRenderW}x${activeRenderH} -> ${w}x${h}; reinitializing LSFG context")
                reinitLsfgContext(w, h)
            }
        }
        ov.onSurfaceLost {
            LsfgLog.i(TAG, "onSurfaceLost — detaching output until a new Surface arrives")
            lastSurface = null
            runCatching { NativeBridge.setOutputSurface(null, 0, 0) }
            if (!lsfgContextActive) {
                capture?.clearSurface()
            }
        }
        ov.show()

        // Now that ov.show() has actually created the FPS TextView, we can safely
        // make the UI visible. The actual counter (second VirtualDisplay) is
        // started AFTER the main capture is running — on Android 14 MediaTek/
        // PowerVR the system revokes MediaProjection if a second VirtualDisplay
        // is created while the first token is still unconsumed.
        //
        // When a benchmark session is starting we force the FPS counter ON
        // regardless of the user's last toggle state — the run is meaningless
        // without the live real/total readout, and the in-overlay benchmark
        // progress panel (shown below) is also revealed.
        val effectiveFpsCounter = initialFpsCounter || benchmarkRequested
        if (effectiveFpsCounter) {
            ov.setFpsVisible(true)
        }
        pendingFpsCounter = effectiveFpsCounter
        if (benchmarkRequested) {
            ov.setBenchmarkProgressVisible(true)
            ov.updateBenchmarkProgress("Benchmark — preparing", 0L, 1L)
        }

        // Frame pacing graph is purely a diagnostic overlay — no intent extra needed,
        // read the persisted pref directly so it restores across service restarts.
        val initialFrameGraph = LsfgPreferences(this).load().frameGraphEnabled
        if (initialFrameGraph) {
            ov.setFrameGraphVisible(true)
            when {
                captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFrameGraph()
                captureSource == CaptureSource.ROOT -> rootCapture?.startFrameGraph()
                else -> capture?.startFrameGraph()
            }
        }

        // The main overlay and drawer both stay in TYPE_APPLICATION_OVERLAY so
        // the drawer/icon remains visible above the full-screen output surface.
        val overlayMode = LsfgPreferences(this).load().overlayMode
        val dr = SettingsDrawerOverlay(this, overlayMode)
        dr.setBypassListener { bypass ->
            LsfgLog.i(TAG, "frameGenBypass=$bypass")
            capture?.frameGenBypass = bypass
            runCatching { NativeBridge.setBypass(bypass) }
                .onFailure { LsfgLog.w(TAG, "setBypass failed", it) }
            ov.updateStatus(if (bypass) "LSFG: bypass (raw capture)" else "LSFG: frame-gen active")
        }
        dr.setStopOverlayListener {
            LsfgLog.i(TAG, "Stop overlay requested from drawer")
            stopSelf()
        }
        dr.setFpsCounterListener { enabled ->
            LsfgLog.i(TAG, "fpsCounter=$enabled")
            if (enabled) {
                when {
                    captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFpsCounter()
                    captureSource == CaptureSource.ROOT -> rootCapture?.startFpsCounter()
                    else -> capture?.startFpsCounter()
                }
                overlay?.setFpsVisible(true)
            } else {
                capture?.stopFpsCounter()
                shizukuCapture?.stopFpsCounter()
                rootCapture?.stopFpsCounter()
                overlay?.setFpsVisible(false)
            }
        }
        dr.setFrameGraphListener { enabled ->
            LsfgLog.i(TAG, "frameGraph=$enabled")
            if (enabled) {
                when {
                    captureSource == CaptureSource.SHIZUKU -> shizukuCapture?.startFrameGraph()
                    captureSource == CaptureSource.ROOT -> rootCapture?.startFrameGraph()
                    else -> capture?.startFrameGraph()
                }
                overlay?.setFrameGraphVisible(true)
            } else {
                capture?.stopFrameGraph()
                shizukuCapture?.stopFrameGraph()
                rootCapture?.stopFrameGraph()
                overlay?.setFrameGraphVisible(false)
            }
        }
        dr.setInitialFpsCounterState(effectiveFpsCounter)
        dr.setInitialFrameGraphState(initialFrameGraph)
        dr.setLiveParamsListener {
            reinitLsfgContext()
        }
        dr.show()
        drawer = dr
    }

    /**
     * Tear down and re-create the native LSFG context so a parameter change from
     * the live drawer (multiplier, flow scale, performance/HDR switch) actually
     * takes effect. The shaders and pipeline state are baked at initContext time;
     * there's no in-place update path.
     *
     * Runs on a worker thread because destroyContext blocks on vkDeviceWaitIdle
     * and initContext can take 100-300ms while it recompiles the shader chain.
     */
    private fun reinitLsfgContext(width: Int = lastSurfaceW, height: Int = lastSurfaceH) {
        if (shuttingDown) {
            LsfgLog.i(TAG, "reinitLsfgContext skipped — shutting down")
            return
        }
        val cap = capture
        val ov = overlay ?: return
        if (width == 0 || height == 0) {
            LsfgLog.w(TAG, "reinitLsfgContext skipped — no surface yet")
            return
        }
        // Coalesce concurrent requests: if a reinit is already running, just
        // mark that another one is wanted. The running worker will pick up the
        // newest prefs in a follow-up pass before clearing the in-flight flag.
        if (reinitInFlight) {
            pendingReinitW = width
            pendingReinitH = height
            LsfgLog.i(TAG, "reinitLsfgContext queued ${width}x${height} while another pass is running")
            reinitRequested = true
            return
        }
        reinitInFlight = true
        reinitRequested = false
        pendingReinitW = width
        pendingReinitH = height
        val doneLatch = CountDownLatch(1)
        reinitDoneLatch = doneLatch
        Thread {
            try {
            val cacheDir = File(filesDir, "spirv").absolutePath
            // Drain any pending requests that arrived while we were running.
            // Each pass re-reads prefs so the final native state matches the
            // most recent UI value, even if the user spammed slider releases.
            var pass = 0
            do {
                reinitRequested = false
                pass++
                val targetW = pendingReinitW.takeIf { it > 0 } ?: width
                val targetH = pendingReinitH.takeIf { it > 0 } ?: height
                pendingReinitW = 0
                pendingReinitH = 0
                val cfg = LsfgPreferences(this).load()
                LsfgLog.i(TAG, "Re-init LSFG context pass=$pass ${targetW}x${targetH} multiplier=${cfg.multiplier} flowScale=${cfg.flowScale} perf=${cfg.performanceMode} hdr=${cfg.hdrMode}")

                if (lsfgContextActive) {
                    // Stop pushing new captures BEFORE we tear down the native
                    // context. shutdownRenderLoop() joins the C++ worker which
                    // can sit inside vkDeviceWaitIdle for tens of ms; if a new
                    // pushFrame arrives concurrently it can leave the framegen
                    // device with in-flight commands and the next waitIdle
                    // hangs forever (multi-second). Symptom from logs: re-init
                    // started but "Render loop shut down" never came.
                    runCatching { cap?.pauseLsfgInput() }
                        .onFailure { LsfgLog.w(TAG, "pauseLsfgInput failed", it) }
                    runCatching { shizukuCapture?.pauseCapture() }
                        .onFailure { LsfgLog.w(TAG, "pause Shizuku capture failed", it) }
                    runCatching { rootCapture?.pauseCapture() }
                        .onFailure { LsfgLog.w(TAG, "pause Root capture failed", it) }
                    runCatching { NativeBridge.destroyContext() }
                    lsfgContextActive = false
                }
                val pacing = PacingDefaults.forPreset(
                    cfg.pacingPreset,
                    PacingDefaults.Params(cfg.emaAlpha, cfg.outlierRatio, cfg.vsyncSlackMs, cfg.queueDepth),
                )
                val rc = runCatching {
                    NativeBridge.initContext(
                        cacheDir = cacheDir,
                        width = targetW,
                        height = targetH,
                        multiplier = cfg.multiplier,
                        flowScale = cfg.flowScale,
                        performance = cfg.performanceMode,
                        hdr = cfg.hdrMode,
                        antiArtifacts = cfg.antiArtifacts,
                        framegenFp16 = cfg.framegenFp16,
                        npuPostProcessing = cfg.npuPostProcessingEnabled,
                        npuPreset = cfg.npuPostProcessingPreset.nativeValue,
                        npuUpscaleFactor = cfg.npuUpscaleFactor,
                        npuAmount = cfg.npuAmount,
                        npuRadius = cfg.npuRadius,
                        npuThreshold = cfg.npuThreshold,
                        npuFp16 = cfg.npuFp16,
                        cpuPostProcessing = cfg.cpuPostProcessingEnabled,
                        cpuPreset = cfg.cpuPostProcessingPreset.nativeValue,
                        cpuStrength = cfg.cpuStrength,
                        cpuSaturation = cfg.cpuSaturation,
                        cpuVibrance = cfg.cpuVibrance,
                        cpuVignette = cfg.cpuVignette,
                        gpuPostProcessing = cfg.gpuPostProcessingEnabled,
                        gpuStage = cfg.gpuPostProcessingStage.nativeValue,
                        gpuMethod = cfg.gpuPostProcessingMethod.nativeValue,
                        gpuUpscaleFactor = cfg.gpuUpscaleFactor,
                        gpuSharpness = cfg.gpuSharpness,
                        gpuStrength = cfg.gpuStrength,
                        targetFpsCap = cfg.targetFpsCap,
                        emaAlpha = pacing.emaAlpha,
                        outlierRatio = pacing.outlierRatio,
                        vsyncSlackMs = pacing.vsyncSlackMs,
                        queueDepth = pacing.queueDepth,
                    )
                }.getOrElse { -1 }
                if (rc == 0 || rc > 0) {
                    lsfgContextActive = true
                    // CRITICAL: destroyContext() above released the native ANativeWindow
                    // handle, so initContext() came up with no output surface attached.
                    // Without this re-attach, blitOutputToWindow() short-circuits and
                    // the overlay freezes on whatever was last posted.
                    val surface = lastSurface
                    if (surface != null) {
                        runCatching { NativeBridge.setOutputSurface(surface, targetW, targetH) }
                            .onFailure { LsfgLog.w(TAG, "setOutputSurface (re-init) failed", it) }
                    } else {
                        LsfgLog.w(TAG, "reinit: no cached surface to re-attach")
                    }
                    if (rc == 0) {
                        activeRenderW = targetW
                        activeRenderH = targetH
                        cap?.setLsfgMode(targetW, targetH)
                        cap?.setLsfgNativeInputEnabled(true)
                        val reinitTarget = targetPkgPending ?: LsfgPreferences(this).load().targetPackage
                        startShizukuVideo(shizukuCapture, reinitTarget, targetW, targetH, cfg)
                        startRootVideo(rootCapture, reinitTarget, targetW, targetH, cfg)
                        mainHandler.post {
                            ov.updateStatus("LSFG: ${lastSurfaceW}×${lastSurfaceH} ×${cfg.multiplier} flow=${"%.2f".format(cfg.flowScale)}")
                        }
                    } else {
                        LsfgLog.w(TAG, "reinit rc=$rc — framegen disabled, staying in mirror mode")
                        activeRenderW = 0
                        activeRenderH = 0
                        if (surface != null) cap?.setSurface(surface, targetW, targetH)
                        val reinitTarget = targetPkgPending ?: LsfgPreferences(this).load().targetPackage
                        startShizukuVideo(shizukuCapture, reinitTarget, targetW, targetH, cfg)
                        startRootVideo(rootCapture, reinitTarget, targetW, targetH, cfg)
                        mainHandler.post {
                            if ((shizukuCapture != null || rootCapture != null) && cap != null) {
                                ov.updateStatus("LSFG: privileged capture unavailable for mirror fallback (frame-gen unavailable)")
                            } else if (cap != null) {
                                ov.updateStatus("LSFG: mirror ${width}×${height} (GPU lacks required Vulkan ext)")
                            } else {
                                ov.updateStatus("LSFG: frame-gen unavailable (init rc=$rc)")
                            }
                        }
                    }
                } else {
                    LsfgLog.w(TAG, "reinit failed rc=$rc")
                    activeRenderW = 0
                    activeRenderH = 0
                }
            } while (reinitRequested)
            } finally {
                reinitInFlight = false
                doneLatch.countDown()
            }
        }.start()
    }

    /**
     * Kicks the [BenchmarkController] off if this session was started with
     * EXTRA_BENCHMARK_REQUESTED. Idempotent — flips [benchmarkStarted] so
     * subsequent reinit completions don't re-trigger the controller.
     */
    private fun maybeStartBenchmark() {
        if (!benchmarkRequested || benchmarkStarted) return
        if (shuttingDown) return
        benchmarkStarted = true
        LsfgLog.i(TAG, "starting BenchmarkController")
        startBenchmarkProgressPoller()
        BenchmarkController.start(applicationContext, object : BenchmarkController.Hooks {
            override fun requestReinit() {
                // Mirror the live-drawer code path so the controller doesn't
                // need to know about reinit internals.
                mainHandler.post { reinitLsfgContext() }
            }
            override fun activeRenderWidth(): Int = activeRenderW
            override fun activeRenderHeight(): Int = activeRenderH
            override fun targetPackage(): String? =
                LsfgPreferences(this@LsfgForegroundService).load().targetPackage
            override fun vsyncPeriodNs(): Long {
                val hz = runCatching {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.refreshRate
                }.getOrNull() ?: return 0L
                if (hz <= 0f) return 0L
                return (1_000_000_000.0 / hz).toLong()
            }
            override fun onCompleted(state: BenchmarkController.State.Completed) {
                LsfgLog.i(TAG, "benchmark completed — report=${state.reportFile.absolutePath}")
                // Stash the report file for the UI to pick up.
                lastBenchmarkReport = state.reportFile
                mainHandler.post {
                    stopBenchmarkProgressPoller()
                    overlay?.setBenchmarkProgressVisible(false)
                    runCatching {
                        val intent = BenchmarkLogWriter.buildShareIntent(
                            this@LsfgForegroundService, state.reportFile,
                        )
                        val chooser = Intent.createChooser(
                            intent, "Share LSFG benchmark report",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(chooser)
                    }.onFailure {
                        LsfgLog.w(TAG, "failed to launch share chooser", it)
                    }
                    // The session is now done — tear it down.
                    stopSelf()
                }
            }
            override fun onFailed(state: BenchmarkController.State.Failed) {
                LsfgLog.w(TAG, "benchmark failed: ${state.message}")
                mainHandler.post {
                    stopBenchmarkProgressPoller()
                    overlay?.setBenchmarkProgressVisible(false)
                    stopSelf()
                }
            }
        })
    }

    /**
     * Drives the in-overlay benchmark progress panel by polling the
     * [BenchmarkController] state at 200 ms (the same cadence the Compose
     * BenchmarkScreen uses, so the two stay visually in sync). The poller
     * computes the elapsed/total fraction inside the current Running phase
     * (warmup or sampling) and pushes it to [OverlayManager.updateBenchmarkProgress].
     *
     * Each phase resets the bar to 0 and refills as the phase advances; the
     * label shows "Run x/y · <precision> ×<mult> — warming up | sampling" so
     * the user can see how much of the run is left without leaving the game.
     */
    private fun startBenchmarkProgressPoller() {
        if (benchmarkProgressPoller != null) return
        val poll = object : Runnable {
            override fun run() {
                val ov = overlay
                val st = BenchmarkController.currentState()
                if (ov == null) {
                    // Overlay torn down — exit the loop; teardown path will
                    // null the field below the next time we look.
                    benchmarkProgressPoller = null
                    return
                }
                when (st) {
                    is BenchmarkController.State.Running -> {
                        val elapsed = (System.currentTimeMillis() - st.phaseStartedAtMs)
                            .coerceAtLeast(0L)
                        val phase = when (st.phase) {
                            BenchmarkController.Phase.WARMUP -> "warming up"
                            BenchmarkController.Phase.SAMPLING -> "sampling"
                        }
                        val label = "Run ${st.runIndex + 1}/${st.totalRuns} · " +
                            "${st.precision.label} ×${st.multiplier} — $phase"
                        ov.updateBenchmarkProgress(label, elapsed, st.phaseDurationMs)
                        mainHandler.postDelayed(this, 200L)
                    }
                    is BenchmarkController.State.Completed,
                    is BenchmarkController.State.Failed -> {
                        // The controller's onCompleted/onFailed callbacks
                        // already handle hiding the panel and stopping the
                        // service — just exit the loop.
                        benchmarkProgressPoller = null
                    }
                    BenchmarkController.State.Idle -> {
                        // Controller hasn't published Running yet — keep
                        // the "preparing" label visible and check again.
                        mainHandler.postDelayed(this, 200L)
                    }
                }
            }
        }
        benchmarkProgressPoller = poll
        mainHandler.post(poll)
    }

    private fun stopBenchmarkProgressPoller() {
        val p = benchmarkProgressPoller
        benchmarkProgressPoller = null
        if (p != null) mainHandler.removeCallbacks(p)
    }

    private fun launchTarget(pkg: String) {
        LsfgLog.i(TAG, "launchTarget($pkg)")
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            LsfgLog.e(TAG, "No launch intent for $pkg")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
            .onSuccess { LsfgLog.i(TAG, "startActivity($pkg) returned") }
            .onFailure { LsfgLog.e(TAG, "Launching $pkg failed", it) }
    }

    private fun startShizukuMetrics(
        engine: ShizukuCaptureEngine?,
        targetPackage: String?,
        width: Int,
        height: Int,
        cfg: com.lsfg.android.prefs.LsfgConfig,
    ) {
        if (engine == null || targetPackage == null) return
        engine.startMetricsOnly(targetPackage, width, height, maxCaptureFps(cfg))
    }

    private fun startShizukuVideo(
        engine: ShizukuCaptureEngine?,
        targetPackage: String?,
        width: Int,
        height: Int,
        cfg: com.lsfg.android.prefs.LsfgConfig,
    ) {
        if (engine == null || targetPackage == null) return
        engine.startCapture(targetPackage, width, height, maxCaptureFps(cfg))
    }

    private fun startRootVideo(
        engine: RootCaptureEngine?,
        targetPackage: String?,
        width: Int,
        height: Int,
        cfg: com.lsfg.android.prefs.LsfgConfig,
    ) {
        if (engine == null || targetPackage == null) return
        engine.startCapture(targetPackage, width, height, maxCaptureFps(cfg))
    }

    private fun maxCaptureFps(cfg: com.lsfg.android.prefs.LsfgConfig): Int {
        val cap = cfg.targetFpsCap.takeIf { it > 0 }
        val refresh = cfg.vsyncRefreshOverride.hz.takeIf { it > 0 }
            ?: requestedOverlayRefreshHz()
        return (cap ?: refresh ?: 60).coerceIn(15, 120)
    }

    private fun requestedOverlayRefreshHz(): Int? {
        val surfaceW = lastSurfaceW
        if (surfaceW == 0) return null
        return runCatching {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.refreshRate.toInt()
        }.getOrNull()
    }

    private data class ShizukuVideoStart(
        val width: Int,
        val height: Int,
        val cfg: com.lsfg.android.prefs.LsfgConfig,
    )

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_session),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LsfgForegroundService::class.java).setAction(ACTION_STOP)
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_session_title))
            .setContentText(getString(R.string.notif_session_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(stopPending)
            .build()
    }

    companion object {
        private const val TAG = "LsfgFGS"
        private const val CHANNEL_ID = "lsfg_session"
        private const val NOTIF_ID = 1001

        /** Last benchmark report file produced by a benchmark session. The
         *  Benchmark screen polls this so the user can re-share the file
         *  after dismissing the system chooser. Set by the BenchmarkController
         *  hook callback; cleared lazily by the UI when acknowledged. */
        @Volatile
        var lastBenchmarkReport: File? = null

        const val ACTION_START = "com.lsfg.android.action.START_SESSION"
        const val ACTION_STOP = "com.lsfg.android.action.STOP_SESSION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_FPS_COUNTER = "fps_counter"
        const val EXTRA_CAPTURE_SOURCE = "capture_source"
        // When true, after framegen reaches steady state the service hands
        // control to BenchmarkController which runs three back-to-back passes
        // (multiplier x2/x3/x4) and produces a shareable report. The session
        // is stopped automatically when the report is ready.
        const val EXTRA_BENCHMARK_REQUESTED = "benchmark_requested"

        fun buildStartIntent(
            ctx: Context,
            resultCode: Int,
            resultData: Intent,
            targetPackage: String?,
            fpsCounter: Boolean,
            captureSource: CaptureSource = CaptureSource.MEDIA_PROJECTION,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, resultData)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, captureSource.prefValue)

        fun buildShizukuStartIntent(
            ctx: Context,
            targetPackage: String?,
            fpsCounter: Boolean,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.SHIZUKU.prefValue)

        fun buildRootStartIntent(
            ctx: Context,
            targetPackage: String?,
            fpsCounter: Boolean,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, fpsCounter)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.ROOT.prefValue)

        fun stop(ctx: Context) {
            ctx.startService(
                Intent(ctx, LsfgForegroundService::class.java).setAction(ACTION_STOP)
            )
        }

        /** MediaProjection variant that flips on EXTRA_BENCHMARK_REQUESTED. The
         *  caller still supplies a fresh consent token (resultCode/resultData)
         *  exactly like the normal path. */
        fun buildBenchmarkStartIntent(
            ctx: Context,
            resultCode: Int,
            resultData: Intent,
            targetPackage: String?,
            captureSource: CaptureSource = CaptureSource.MEDIA_PROJECTION,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_RESULT_CODE, resultCode)
            .putExtra(EXTRA_RESULT_DATA, resultData)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, false)
            .putExtra(EXTRA_CAPTURE_SOURCE, captureSource.prefValue)
            .putExtra(EXTRA_BENCHMARK_REQUESTED, true)

        /** Shizuku-capture variant of the benchmark intent. No MediaProjection
         *  consent token is required — the privileged side channel handles
         *  capture without the system recording dialog. */
        fun buildShizukuBenchmarkStartIntent(
            ctx: Context,
            targetPackage: String?,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, false)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.SHIZUKU.prefValue)
            .putExtra(EXTRA_BENCHMARK_REQUESTED, true)

        /** Root-capture variant. Same shape as the Shizuku variant — no
         *  consent intent, capture happens through the rooted privileged
         *  pipeline. */
        fun buildRootBenchmarkStartIntent(
            ctx: Context,
            targetPackage: String?,
        ): Intent = Intent(ctx, LsfgForegroundService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            .putExtra(EXTRA_FPS_COUNTER, false)
            .putExtra(EXTRA_CAPTURE_SOURCE, CaptureSource.ROOT.prefValue)
            .putExtra(EXTRA_BENCHMARK_REQUESTED, true)
    }
}
