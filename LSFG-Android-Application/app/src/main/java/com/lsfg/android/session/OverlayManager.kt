package com.lsfg.android.session

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Region
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceControl
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.VsyncRefreshOverride

/**
 * Full-screen overlay that hosts a [TextureView] for the mirrored / LSFG-processed
 * frames.
 *
 * **Why TextureView instead of SurfaceView**: a SurfaceView creates a child BLAST
 * SurfaceControl that lives outside the parent window's surface hierarchy. On strict
 * AOSP builds (e.g. Rockchip Orange Pi 5 Ultra, Android 13) InputDispatcher's
 * BLOCK_UNTRUSTED_TOUCHES filter evaluates that BLAST surface independently, sees
 * an opaque (alpha=1.0) untrusted overlay sitting over the target app, and drops
 * every tap with `Dropping untrusted touch event due to /<uid>` — even when the
 * parent window has an empty touchable region and is itself trusted. The
 * "trusted" bit does NOT propagate to the child SurfaceControl, and the only API
 * to mark it trusted (`SurfaceControl.Transaction.setTrustedOverlay`) is hidden /
 * blocklisted on user builds.
 *
 * TextureView draws into the parent View's hardware layer instead of creating a
 * separate SurfaceControl, so InputDispatcher only sees one window — the parent
 * one, whose touchable region we already publish as empty. The cost is one extra
 * GPU copy per frame relative to SurfaceView; acceptable here because the
 * native render loop already CPU-blits each generated frame to ANativeWindow.
 *
 * Surface lifecycle is event-driven: consumers get [onSurfaceReady] every time a
 * new Surface becomes valid (initial show and every recreation after orientation
 * / immersive-mode changes) and [onSurfaceLost] every time the Surface is torn
 * down. We pin the producer-side buffer to the physical screen size so the
 * Surface dimensions match the VirtualDisplay dimensions exactly — no scaling,
 * no letter-boxing, no off-screen positioning.
 */
class OverlayManager(private val ctx: Context) {

    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var producerSurface: Surface? = null
    private var hostWindowManager: WindowManager? = null
    private var fpsView: TextView? = null
    private var graphView: FrameGraphView? = null
    private var benchmarkPanel: LinearLayout? = null
    private var benchmarkLabel: TextView? = null
    private var benchmarkBar: ProgressBar? = null
    private var insetsListener: Any? = null
    private var internalInsetsListener: Any? = null
    private var frameLoopCallback: Choreographer.FrameCallback? = null
    private var surfaceTextureUpdateCount = 0

    @Volatile
    private var surfaceReadyListener: ((Surface, Int, Int) -> Unit)? = null

    @Volatile
    private var surfaceLostListener: (() -> Unit)? = null
    private var requestedRefreshRateHz: Float = 0f
    private var overlayWidth: Int = 0
    private var overlayHeight: Int = 0

    /** Callback invoked every time the overlay Surface becomes valid for writing. */
    fun onSurfaceReady(cb: (Surface, Int, Int) -> Unit) {
        surfaceReadyListener = cb
    }

    /** Callback invoked every time the Surface is torn down. */
    fun onSurfaceLost(cb: () -> Unit) {
        surfaceLostListener = cb
    }

    fun show() {
        if (root != null) return

        // Two host modes, controlled by the user's `trustedOverlay` preference:
        //
        //  - TYPE_APPLICATION_OVERLAY (default): the overlay sits below the
        //    system bars (status bar, navigation bar, notification shade) so
        //    the user can still pull down notifications and tap nav buttons.
        //    Works on most devices because BLOCK_UNTRUSTED_TOUCHES is
        //    permissive on Pixel/Samsung/Xiaomi/etc.
        //
        //  - TYPE_ACCESSIBILITY_OVERLAY (opt-in via preference, requires the
        //    LsfgAccessibilityService to be bound): the overlay becomes a
        //    trusted overlay so InputDispatcher's BLOCK_UNTRUSTED_TOUCHES
        //    filter does not drop tap pass-through on strict AOSP builds (e.g.
        //    Rockchip Orange Pi 5 Ultra, Android 13). Trade-off: a11y overlays
        //    are forced into a system layer family ABOVE the status / nav
        //    bars, so the user loses access to those bars while the session is
        //    running. Combined with TextureView (which avoids the child BLAST
        //    SurfaceControl that would re-introduce an untrusted occluder),
        //    this is the only path that makes pass-through actually work on
        //    those devices.
        val prefs = LsfgPreferences(ctx).load()
        val a11y = LsfgAccessibilityService.instance
        val useTrusted = prefs.trustedOverlay && a11y != null
        val hostCtx: Context = if (useTrusted) a11y!! else ctx
        val wm = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hostWindowManager = wm

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        @Suppress("DEPRECATION")
        requestedRefreshRateHz = wm.defaultDisplay.supportedModes
            .maxOfOrNull { it.refreshRate }
            ?: wm.defaultDisplay.refreshRate
        // Let the native pacer align its sleeps to this display's vsync.
        // Without it two consecutive unlockAndPost calls (gen + real, or two
        // generated) can land in the same SurfaceFlinger slot and one gets
        // held an extra vsync — visible as periodic stutter. The user can
        // disable this or force a specific refresh rate via settings.
        val effectiveHz = if (!prefs.vsyncAlignmentEnabled) {
            0f
        } else if (prefs.vsyncRefreshOverride != VsyncRefreshOverride.AUTO) {
            prefs.vsyncRefreshOverride.hz.toFloat()
        } else {
            requestedRefreshRateHz
        }
        if (effectiveHz > 0f) {
            val periodNs = (1_000_000_000.0 / effectiveHz).toLong()
            runCatching { NativeBridge.setVsyncPeriodNs(periodNs) }
        } else {
            runCatching { NativeBridge.setVsyncPeriodNs(0L) }
        }
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        overlayWidth = screenW
        overlayHeight = screenH
        Log.i(TAG, "Showing overlay at ${screenW}x${screenH} targetRefresh=${requestedRefreshRateHz}Hz")

        val layoutType = when {
            useTrusted -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        Log.i(TAG, "Overlay host=${if (useTrusted) "a11y/TRUSTED" else "app/UNTRUSTED"}")

        // No FLAG_NOT_TOUCHABLE: that flag, combined with TYPE_APPLICATION_OVERLAY, is
        // what triggers the Android 12+ 0.8-alpha clamp. Pass-through is handled by an
        // empty touchable region (installed right after addView).
        // FLAG_SECURE is intentionally absent: on MediaTek/OEM ROMs it composites the
        // window as opaque black on VirtualDisplays (MediaProjection), making every
        // captured frame black. Exclusion from capture is handled by installSkipScreenshot
        // via the eSkipScreenshot SurfaceFlinger layer flag instead.
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val params = WindowManager.LayoutParams(
            screenW,
            screenH,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            @Suppress("DEPRECATION")
            systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            // PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_CAPTURE (0x00080000, API 31+): instructs
            // WMS to tell SurfaceFlinger to skip this window in VirtualDisplay/screenshot
            // composition at window-creation time — no SurfaceControl timing race.
            runCatching {
                val f = javaClass.getDeclaredField("privateFlags")
                f.isAccessible = true
                f.setInt(this, f.getInt(this) or 0x00080000)
                Log.i("lsfg-vk-loop", "privateFlags EXCLUDE_FROM_SCREEN_CAPTURE applied")
            }.onFailure {
                Log.d("lsfg-vk-loop", "privateFlags field unavailable: ${it.message}")
            }
        }

        // FrameLayout background stays transparent — TextureView composites into
        // its parent's hardware layer, but we still want no opaque fill behind it
        // before the first frame arrives so the loading status text remains
        // visible against the underlying app instead of a black slab.
        val layout = FrameLayout(ctx)
        val tex = TextureView(ctx)
        // Must be false: isOpaque=true signals the hardware composer that the
        // layer is always opaque, causing HWC to skip compositing underlying
        // layers (the game) for VirtualDisplay/MediaProjection output.  With
        // opaque=true, even an alpha=0 cleared surface appears as opaque black
        // in the capture feed, seeding the dark feedback loop.  false lets the
        // compositor see through transparent pixels to the game content.
        tex.isOpaque = false
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                // Re-apply skip-screenshot now that the window's SurfaceControl is
                // guaranteed to have a valid native handle. The call in show() fires
                // immediately after wm.addView(), where isAttachedToWindow is already
                // true (ViewRootImpl.setView dispatches attachment synchronously) but
                // the SurfaceControl native handle is only assigned later during
                // relayoutWindow() — which completes before the first onSurfaceTextureAvailable.
                installSkipScreenshot(layout)

                // Pin the producer-side buffer size to the physical screen so the
                // VirtualDisplay's frames don't get scaled by the consumer.
                st.setDefaultBufferSize(screenW, screenH)
                val s = Surface(st)
                producerSurface = s
                syncOverlayGeometry()
                requestMaxRefreshRate(s)
                Log.i(TAG, "TextureView surface available ${width}x${height} valid=${s.isValid} hwAccel=${tex.isHardwareAccelerated}")
                if (s.isValid) {
                    surfaceReadyListener?.invoke(s, overlayWidth, overlayHeight)
                }
                // Drive TextureView redraws explicitly via Choreographer. On some
                // devices (MediaTek/Mali) the TextureView-internal onFrameAvailable
                // → scheduleTraversals path silently stops after the first buffer,
                // leaving the overlay frozen on frame 1. Calling invalidate() on
                // every VSYNC ensures updateTexImage() is called whenever a new
                // buffer is available, regardless of the internal mechanism.
                surfaceTextureUpdateCount = 0
                startFrameLoop(tex)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(screenW, screenH)
                val s = producerSurface
                syncOverlayGeometry()
                if (s != null) {
                    requestMaxRefreshRate(s)
                    Log.i(TAG, "TextureView size changed ${width}x${height}")
                    if (s.isValid) {
                        surfaceReadyListener?.invoke(s, overlayWidth, overlayHeight)
                    }
                }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                Log.i(TAG, "TextureView surface destroyed")
                stopFrameLoop()
                surfaceLostListener?.invoke()
                runCatching { producerSurface?.release() }
                producerSurface = null
                // Returning true tells TextureView it can release the SurfaceTexture
                // immediately; we have no off-thread producer holding a reference
                // beyond the native render loop, which has already been notified
                // via surfaceLostListener and detaches before this returns.
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                val n = ++surfaceTextureUpdateCount
                if (n <= 30 || n % 60 == 0) {
                    Log.d(TAG, "onSurfaceTextureUpdated #$n")
                }
            }
        }
        val fps = TextView(ctx).apply {
            text = ""
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
            visibility = View.GONE
        }
        layout.addView(
            tex,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        layout.addView(
            fps,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ).apply {
                topMargin = 24
                leftMargin = 24
            },
        )

        val graph = FrameGraphView(ctx).apply {
            visibility = View.GONE
        }
        val graphWidthPx = (metrics.density * 220f).toInt()
        val graphHeightPx = (metrics.density * 80f).toInt()
        layout.addView(
            graph,
            FrameLayout.LayoutParams(
                graphWidthPx,
                graphHeightPx,
                Gravity.TOP or Gravity.START,
            ).apply {
                // Position just below the FPS text so both stay in the top-left cluster.
                topMargin = 112
                leftMargin = 24
            },
        )

        // Benchmark progress panel — pinned to the top-center of the overlay,
        // hidden until BenchmarkController publishes a Running state. Hosts
        // a one-line label ("Run x/y · FP16 ×3 — sampling 28s left") and a
        // horizontal ProgressBar that fills as the current phase advances and
        // is reset at every phase transition.
        val benchPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            val padH = (metrics.density * 12f).toInt()
            val padV = (metrics.density * 8f).toInt()
            setPadding(padH, padV, padH, padV)
            visibility = View.GONE
        }
        val benchLabel = TextView(ctx).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val benchBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            isIndeterminate = false
        }
        benchPanel.addView(
            benchLabel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        benchPanel.addView(
            benchBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (metrics.density * 6f).toInt(),
            ).apply {
                topMargin = (metrics.density * 4f).toInt()
            },
        )
        val benchWidthPx = (metrics.density * 320f).toInt()
        layout.addView(
            benchPanel,
            FrameLayout.LayoutParams(
                benchWidthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply {
                topMargin = 24
            },
        )

        wm.addView(layout, params)

        // Mark the overlay's SurfaceControl with skipScreenshot so SurfaceFlinger
        // excludes it from virtual-display composition (MediaProjection) while still
        // rendering it normally on the physical display.  This prevents the feedback
        // loop where MediaProjection captures our overlay output instead of the live
        // game when the overlay is opaque.
        installSkipScreenshot(layout)

        // Two complementary pass-through mechanisms — we install both because each
        // is needed on a different subset of devices:
        //   1) AttachedSurfaceControl.setTouchableRegion(empty) — the modern API
        //      (public on API 33+, reflective on 29–32). Most reliable on Pixel /
        //      AOSP-like ROMs.
        //   2) ViewTreeObserver.OnComputeInternalInsetsListener with
        //      TOUCHABLE_INSETS_REGION — the legacy SystemUI pattern that some
        //      OEMs (Samsung One UI, Xiaomi HyperOS, OPPO ColorOS) honour even
        //      when (1) is silently rejected.
        // We do NOT use FLAG_NOT_TOUCHABLE, which would re-enable the Android 12+
        // 0.8-alpha clamp.
        insetsListener = installEmptyTouchableRegion(layout)
        internalInsetsListener = installEmptyInternalInsets(layout)
        root = layout
        fpsView = fps
        graphView = graph
        benchmarkPanel = benchPanel
        benchmarkLabel = benchLabel
        benchmarkBar = benchBar
        textureView = tex
    }

    /**
     * Re-asserts the overlay as the topmost window. Call this after the target app is
     * launched so the new foreground activity doesn't leave a stale z-order with our
     * overlay stuck behind it.
     */
    fun bringToFront() {
        val r = root ?: return
        val wm = hostWindowManager ?: return
        if (!r.isAttachedToWindow) return
        val lp = r.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "bringToFront updateViewLayout failed", it) }
    }

    /**
     * Re-reads display metrics and resizes the overlay window to match a new
     * orientation / display configuration. Idempotent; safe to call multiple
     * times for a single rotation. The actual `wm.updateViewLayout` is posted
     * to the root view's main-thread looper to avoid racing with WindowManager
     * while it is still distributing the configuration change.
     */
    fun onDisplayConfigurationChanged() {
        val r = root ?: return
        r.post { syncOverlayGeometry() }
    }

    fun updateStatus(line: String) {
        // Status text was removed from the overlay; keep the API as a no-op so
        // existing call sites in the foreground service continue to compile.
    }

    fun setFpsVisible(visible: Boolean) {
        fpsView?.post { fpsView?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    fun updateFps(capturedFps: Float, postedFps: Float) {
        val v = fpsView ?: return
        val queueMs = runCatching { NativeBridge.getAverageQueueMs() }.getOrDefault(0.0)
        val latencyMs = runCatching { NativeBridge.getAverageLatencyMs() }.getOrDefault(0.0)
        val text = "real ${"%.1f".format(capturedFps)} fps · total ${"%.1f".format(postedFps)} fps (latency: ${"%.1f".format(latencyMs)} ms · queue: ${"%.1f".format(queueMs)} ms)"
        v.post { v.text = text }
    }

    fun setFrameGraphVisible(visible: Boolean) {
        val g = graphView ?: return
        g.post {
            g.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) g.reset()
        }
    }

    fun pushFrameGraphSample(realFps: Float, generatedFps: Float) {
        val g = graphView ?: return
        g.post { g.pushSample(realFps, generatedFps, realFps + generatedFps) }
    }

    /**
     * Show the benchmark progress panel (idempotent). The panel is hidden by
     * default and only revealed while a benchmark is running.
     */
    fun setBenchmarkProgressVisible(visible: Boolean) {
        val p = benchmarkPanel ?: return
        p.post {
            p.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) {
                benchmarkBar?.progress = 0
                benchmarkLabel?.text = ""
            }
        }
    }

    /**
     * Update the benchmark progress panel.
     *
     * @param label one-line status, e.g. "Run 2/6 · FP32 ×3 — sampling"
     * @param elapsedMs elapsed time inside the current phase
     * @param totalMs total length of the current phase
     */
    fun updateBenchmarkProgress(label: String, elapsedMs: Long, totalMs: Long) {
        val l = benchmarkLabel ?: return
        val b = benchmarkBar ?: return
        val clampedTotal = totalMs.coerceAtLeast(1L)
        val ratio = (elapsedMs.toFloat() / clampedTotal).coerceIn(0f, 1f)
        val remainingSec = ((clampedTotal - elapsedMs).coerceAtLeast(0L) + 999L) / 1000L
        val text = "$label  ·  ${remainingSec}s left"
        l.post {
            l.text = text
            b.progress = (ratio * 1000f).toInt()
        }
    }

    fun hide() {
        val r = root ?: return
        insetsListener?.let { removeEmptyTouchableRegion(r, it) }
        insetsListener = null
        internalInsetsListener?.let { removeEmptyInternalInsets(r, it) }
        internalInsetsListener = null
        val wm = hostWindowManager
        if (wm != null) {
            runCatching { wm.removeView(r) }
        }
        runCatching { NativeBridge.setVsyncPeriodNs(0L) }
        runCatching { producerSurface?.release() }
        producerSurface = null
        root = null
        textureView = null
        fpsView = null
        graphView = null
        benchmarkPanel = null
        benchmarkLabel = null
        benchmarkBar = null
        hostWindowManager = null
    }

    /**
     * Publishes an empty touchable region on the overlay's root surface so
     * InputDispatcher skips the window and events fall through to the game below.
     *
     * Primary path (API 33+): public `View.getRootSurfaceControl().setTouchableRegion()`.
     * Legacy path (API 29-32): `getRootSurfaceControl()` is `@hide` but callable via
     *   reflection (not in the blocklist). `AttachedSurfaceControl.setTouchableRegion`
     *   has been present since API 29.
     * The call must happen after the view is attached to a window, so we hook it
     * into `onAttachedToWindow` / `OnAttachStateChangeListener`.
     *
     * Returns the attach listener (so we can detach it in hide()) or null if the
     * reflective lookup failed on a very old device.
     */
    private fun installEmptyTouchableRegion(host: View): Any? {
        val applyEmptyRegion: () -> Unit = {
            runCatching {
                val rootSc = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> host.rootSurfaceControl
                    else -> host.javaClass
                        .getMethod("getRootSurfaceControl")
                        .invoke(host)
                }
                if (rootSc == null) {
                    Log.w(TAG, "rootSurfaceControl is null; window may not be attached yet")
                } else {
                    val setTouchableRegion = rootSc.javaClass
                        .getMethod("setTouchableRegion", Region::class.java)
                    setTouchableRegion.invoke(rootSc, Region())
                    Log.i(TAG, "Empty touchable region applied (api=${Build.VERSION.SDK_INT})")
                }
            }.onFailure { Log.w(TAG, "setTouchableRegion(empty) failed", it) }
        }

        if (host.isAttachedToWindow) {
            applyEmptyRegion()
            return Unit
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                applyEmptyRegion()
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        host.addOnAttachStateChangeListener(attachListener)
        return attachListener
    }

    private fun removeEmptyTouchableRegion(host: View, listener: Any) {
        if (listener is View.OnAttachStateChangeListener) {
            runCatching { host.removeOnAttachStateChangeListener(listener) }
                .onFailure { Log.w(TAG, "removeOnAttachStateChangeListener failed", it) }
        }
    }

    /**
     * Belt-and-braces touch pass-through using the SystemUI-canonical
     * `ViewTreeObserver.OnComputeInternalInsetsListener` + `TOUCHABLE_INSETS_REGION`.
     * Both the listener interface and `InternalInsetsInfo` are `@hide` in the public
     * SDK (stable since API 1), so the implementation is built reflectively. On
     * devices where this succeeds, InputDispatcher excludes our window from the
     * touchable region and events fall through to the app underneath even if
     * setTouchableRegion(empty) was silently rejected.
     *
     * Returns the proxy listener (so it can be detached in [hide]) or null on failure.
     */
    private fun installEmptyInternalInsets(host: View): Any? {
        return runCatching {
            val internalInsetsInfoCls = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val touchableInsetsRegion = internalInsetsInfoCls
                .getField("TOUCHABLE_INSETS_REGION")
                .getInt(null)
            val setTouchableInsets = internalInsetsInfoCls
                .getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            val touchableRegionField = internalInsetsInfoCls.getField("touchableRegion")

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerCls.classLoader,
                arrayOf(listenerCls),
            ) { _, method, args ->
                if (method.name == "onComputeInternalInsets" && args != null && args.isNotEmpty()) {
                    val info = args[0]
                    runCatching {
                        setTouchableInsets.invoke(info, touchableInsetsRegion)
                        (touchableRegionField.get(info) as? Region)?.setEmpty()
                    }
                }
                null
            }
            val addMethod = host.viewTreeObserver.javaClass
                .getMethod("addOnComputeInternalInsetsListener", listenerCls)
            addMethod.invoke(host.viewTreeObserver, proxy)
            Log.i(TAG, "OnComputeInternalInsetsListener pass-through installed")
            proxy
        }.onFailure { Log.w(TAG, "installEmptyInternalInsets failed", it) }.getOrNull()
    }

    private fun removeEmptyInternalInsets(host: View, listener: Any) {
        runCatching {
            val listenerCls = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val removeMethod = host.viewTreeObserver.javaClass
                .getMethod("removeOnComputeInternalInsetsListener", listenerCls)
            removeMethod.invoke(host.viewTreeObserver, listener)
        }.onFailure { Log.w(TAG, "removeOnComputeInternalInsetsListener failed", it) }
    }

    private fun requestMaxRefreshRate(surface: Surface) {
        if (!surface.isValid || requestedRefreshRateHz <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        runCatching {
            surface.setFrameRate(
                requestedRefreshRateHz,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS,
            )
        }.onFailure { Log.w(TAG, "setFrameRate(${requestedRefreshRateHz}Hz) failed", it) }
    }

    private fun syncOverlayGeometry() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) {
            // The window has already been torn down (or hasn't finished attaching).
            // updateViewLayout in either state throws on some OEMs.
            return
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val newW = metrics.widthPixels
        val newH = metrics.heightPixels
        if (newW == overlayWidth && newH == overlayHeight) return

        overlayWidth = newW
        overlayHeight = newH

        val lp = r.layoutParams as? WindowManager.LayoutParams
        if (lp != null) {
            lp.width = newW
            lp.height = newH
            runCatching { wm.updateViewLayout(r, lp) }
                .onFailure { Log.w(TAG, "updateViewLayout(${newW}x${newH}) failed", it) }
        }
        runCatching { textureView?.surfaceTexture?.setDefaultBufferSize(newW, newH) }
            .onFailure { Log.w(TAG, "setDefaultBufferSize(${newW}x${newH}) failed", it) }
        Log.i(TAG, "Overlay geometry synced to ${newW}x${newH}")
    }

    /**
     * Sets the overlay window's `eSkipScreenshot` SurfaceFlinger flag so SurfaceFlinger
     * excludes it from VirtualDisplay composition (MediaProjection capture) while rendering
     * it normally on the physical display.
     *
     * Reflection path:
     *   View.getViewRootImpl() (@hide, API 11+) → ViewRootImpl
     *   ViewRootImpl.getSurfaceControl() (@hide, API 29+) → window root SurfaceControl
     *   SurfaceControl.Transaction.setSkipScreenshot(@hide, API 12+)
     *
     * Note: rootSurfaceControl/getRootSurfaceControl() returns AttachedSurfaceControl, not
     * ViewRootImpl, so getSurfaceControl() would throw NoSuchMethodException on that path.
     */
    private fun installSkipScreenshot(host: View) {
        val apply: () -> Unit = {
            runCatching {
                // getViewRootImpl() is @hide but stable since API 11 and returns ViewRootImpl
                // directly — the object that owns the window's root SurfaceControl.
                val viewRootImpl = host.javaClass.getMethod("getViewRootImpl").invoke(host)
                    ?: error("getViewRootImpl() returned null (view not attached?)")

                // Get the window's SurfaceControl.
                // Primary path:   ViewRootImpl.getSurfaceControl() — @hide, API 29+.
                // Fallback path:  direct ViewRootImpl.mSurfaceControl field — some OEM
                //   ROMs (MediaTek Android 13 confirmed) strip the accessor method while
                //   leaving the underlying field intact in the class hierarchy.
                val sc: SurfaceControl = run {
                    runCatching {
                        viewRootImpl.javaClass.getMethod("getSurfaceControl")
                            .invoke(viewRootImpl) as? SurfaceControl
                    }.getOrNull()?.let { return@run it }

                    var cls: Class<*>? = viewRootImpl.javaClass
                    while (cls != null) {
                        runCatching {
                            cls!!.getDeclaredField("mSurfaceControl")
                                .also { it.isAccessible = true }
                                .get(viewRootImpl) as? SurfaceControl
                        }.getOrNull()?.let {
                            Log.d("lsfg-vk-loop", "installSkipScreenshot: SurfaceControl via ${cls!!.simpleName}.mSurfaceControl")
                            return@run it
                        }
                        cls = cls.superclass
                    }

                    // Third fallback: enumerate all fields by type — catches OEM renames.
                    val scFieldsFound = mutableListOf<String>()
                    var typeCls: Class<*>? = viewRootImpl.javaClass
                    while (typeCls != null) {
                        for (field in typeCls!!.declaredFields) {
                            if (SurfaceControl::class.java.isAssignableFrom(field.type)) {
                                val fieldId = "${typeCls.simpleName}.${field.name}"
                                scFieldsFound.add(fieldId)
                                runCatching {
                                    field.isAccessible = true
                                    field.get(viewRootImpl) as? SurfaceControl
                                }.getOrNull()?.let { found ->
                                    Log.i("lsfg-vk-loop", "installSkipScreenshot: SurfaceControl via type-search: $fieldId")
                                    return@run found
                                }
                            }
                        }
                        typeCls = typeCls.superclass
                    }
                    Log.i("lsfg-vk-loop", "installSkipScreenshot: type-search SC-typed fields: $scFieldsFound")

                    // Fourth fallback: Android 12+ BLAST pipeline — ViewRootImpl stores a
                    // BLASTBufferQueue which owns the window's SurfaceControl internally.
                    // Some OEM ROMs strip all direct SC fields from ViewRootImpl but leave
                    // the BLASTBufferQueue field intact.
                    val bbqClass = runCatching {
                        Class.forName("android.graphics.BLASTBufferQueue")
                    }.getOrNull()
                    if (bbqClass != null) {
                        var bbqSearchCls: Class<*>? = viewRootImpl.javaClass
                        outer@ while (bbqSearchCls != null) {
                            for (field in bbqSearchCls!!.declaredFields) {
                                if (bbqClass.isAssignableFrom(field.type)) {
                                    runCatching {
                                        field.isAccessible = true
                                        val bbq = field.get(viewRootImpl) ?: return@runCatching
                                        for (methodName in listOf(
                                            "getSyncedSurfaceControl",
                                            "getSurfaceControl",
                                        )) {
                                            runCatching {
                                                bbq.javaClass.getMethod(methodName)
                                                    .invoke(bbq) as? SurfaceControl
                                            }.getOrNull()?.let { found ->
                                                Log.i("lsfg-vk-loop",
                                                    "installSkipScreenshot: SurfaceControl via " +
                                                    "BLASTBufferQueue.${field.name}.$methodName()")
                                                return@run found
                                            }
                                        }
                                    }
                                }
                            }
                            bbqSearchCls = bbqSearchCls.superclass
                        }
                    }

                    // All paths exhausted — dump surface/blast/buffer related field names from
                    // the ViewRootImpl hierarchy so we can see what the OEM actually has.
                    val relatedFields = mutableListOf<String>()
                    var dumpCls: Class<*>? = viewRootImpl.javaClass
                    while (dumpCls != null) {
                        for (field in dumpCls!!.declaredFields) {
                            val n = field.name.lowercase()
                            if (n.contains("surface") || n.contains("blast") ||
                                n.contains("buffer") || n.contains("mbbq") ||
                                n == "msc" || n == "mwrl") {
                                relatedFields.add(
                                    "${dumpCls.simpleName}.${field.name}:${field.type.simpleName}"
                                )
                            }
                        }
                        dumpCls = dumpCls.superclass
                    }
                    Log.i("lsfg-vk-loop", "installSkipScreenshot: related fields dump: $relatedFields")
                    error("getSurfaceControl() missing and no SurfaceControl field found in class hierarchy")
                }

                // Verify the native handle is non-zero — it is 0 if relayoutWindow()
                // hasn't run yet. The onSurfaceTextureAvailable retry guarantees it is set.
                val nativeHandle = runCatching {
                    sc.javaClass.getDeclaredField("mNativeObject")
                        .also { it.isAccessible = true }.getLong(sc)
                }.getOrDefault(-1L)
                Log.i("lsfg-vk-loop", "installSkipScreenshot: SurfaceControl nativeHandle=0x${nativeHandle.toString(16)}")
                if (nativeHandle == 0L) {
                    error("SurfaceControl native handle is 0 — relayoutWindow not yet run; onSurfaceTextureAvailable will retry")
                }

                // setSkipScreenshot (@hide) — layer skipped during VirtualDisplay composition
                // (MediaProjection) but rendered normally on physical display.
                val txn = SurfaceControl.Transaction()
                val method = txn.javaClass.getMethod(
                    "setSkipScreenshot",
                    SurfaceControl::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                method.invoke(txn, sc, true)
                txn.apply()
                Log.i("lsfg-vk-loop", "installSkipScreenshot: OK — overlay excluded from MediaProjection")
            }.onFailure {
                Log.w("lsfg-vk-loop", "installSkipScreenshot FAILED (${it.javaClass.simpleName}): ${it.message}" +
                           " — feedback loop may occur")
            }
        }

        if (host.isAttachedToWindow) {
            apply()
        } else {
            host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { apply() }
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
    }

    private fun startFrameLoop(tex: TextureView) {
        stopFrameLoop()
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (frameLoopCallback === this) {
                    tex.invalidate()
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
        frameLoopCallback = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun stopFrameLoop() {
        val cb = frameLoopCallback
        frameLoopCallback = null
        if (cb != null) {
            Choreographer.getInstance().removeFrameCallback(cb)
        }
    }

    companion object {
        private const val TAG = "OverlayManager"
    }
}
