package com.lsfg.android.session

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.lsfg.android.SHOW_IMAGE_QUALITY
import com.lsfg.android.prefs.DrawerEdge
import com.lsfg.android.prefs.GpuPostProcessingMethod
import com.lsfg.android.prefs.GpuPostProcessingStage
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.CpuPostProcessingPreset
import com.lsfg.android.prefs.NpuPostProcessingPreset
import com.lsfg.android.prefs.OverlayMode
import com.lsfg.android.prefs.PacingDefaults
import com.lsfg.android.prefs.PacingPreset
import com.lsfg.android.prefs.VsyncRefreshOverride
import java.io.File

/**
 * Right-edge settings drawer for the in-game overlay.
 *
 * Collapsed: a thin touchable edge strip + a vertical "handle" pill visible at mid-height.
 *            The handle pulses subtly so the user can find it.
 * Drag:      swiping leftward from the strip moves the panel with the finger 1:1. Releasing
 *            past the halfway point snaps open (with a slight overshoot); otherwise snaps back.
 * Expanded:  scrim darkens the game area; tapping outside the panel closes it.
 *
 * Always hosted as TYPE_APPLICATION_OVERLAY (or legacy TYPE_SYSTEM_ALERT). The drawer is
 * always touchable (it has its own UI) so the Android 12+ 0.8-alpha clamp does not apply;
 * scrim and panel fade are driven via View.setAlpha, which is unaffected by the window
 * clamp.
 */
class SettingsDrawerOverlay(
    private val ctx: Context,
    private val entryMode: OverlayMode = OverlayMode.ICON_BUTTON,
) {

    fun interface BypassToggleListener {
        fun onBypassChanged(bypass: Boolean)
    }

    fun interface StopOverlayListener {
        fun onStopOverlay()
    }

    fun interface FpsCounterListener {
        fun onFpsCounterChanged(enabled: Boolean)
    }

    fun interface FrameGraphListener {
        fun onFrameGraphChanged(enabled: Boolean)
    }

    fun interface LiveParamsListener {
        fun onParamsChanged()
    }

    private var hostWindowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var handleView: HandleView? = null
    private var scrim: View? = null
    private var panelContainer: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var bypassListener: BypassToggleListener? = null
    private var stopListener: StopOverlayListener? = null
    private var fpsCounterListener: FpsCounterListener? = null
    private var frameGraphListener: FrameGraphListener? = null
    private var liveParamsListener: LiveParamsListener? = null
    private var initialFpsCounter: Boolean = false
    private var initialFrameGraph: Boolean = false

    /** 0 = collapsed, 1 = fully expanded. During drag this tracks the finger. */
    private var progress: Float = 0f
    private var expanded: Boolean = false
    private var dragActive: Boolean = false
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var dragStartProgress: Float = 0f
    private var settleAnimator: ValueAnimator? = null
    private var handlePulseAnimator: ValueAnimator? = null

    private var edgeStripWidthPx = 0
    private var handleWidthPx = 0
    private var handleHeightPx = 0
    private var panelWidthPx = 0
    private var panelHeightPx = 0
    private var panelMarginPx = 0
    private var screenW = 0
    private var screenH = 0
    private var drawerEdge: DrawerEdge = DrawerEdge.RIGHT

    // ICON_BUTTON mode state.
    private var iconButton: View? = null
    private var iconSizePx: Int = 0
    /** Last position of the icon (TOP|START gravity). Persisted across open/close. */
    private var iconX: Int = 0
    private var iconY: Int = 0
    private var iconDragStartRawX: Float = 0f
    private var iconDragStartRawY: Float = 0f
    private var iconDragStartX: Int = 0
    private var iconDragStartY: Int = 0
    private var iconDragMoved: Boolean = false
    // Coalesce per-event updateViewLayout into one Binder RPC per vsync. Without
    // this, touch can fire 240+ Hz on flagships and each updateViewLayout is a
    // round-trip to WindowManagerService.
    private var iconDragFramePending: Boolean = false
    private val iconDragFrameCallback = Choreographer.FrameCallback {
        iconDragFramePending = false
        val lp = params
        val r = root
        val wm = hostWindowManager
        if (lp != null && r != null && wm != null && r.isAttachedToWindow &&
            lp.width != WindowManager.LayoutParams.MATCH_PARENT) {
            lp.x = iconX
            lp.y = iconY
            runCatching { wm.updateViewLayout(r, lp) }
                .onFailure { Log.w(TAG, "icon drag updateViewLayout failed", it) }
        }
    }

    fun setBypassListener(l: BypassToggleListener) { bypassListener = l }
    fun setStopOverlayListener(l: StopOverlayListener) { stopListener = l }
    fun setFpsCounterListener(l: FpsCounterListener) { fpsCounterListener = l }
    fun setFrameGraphListener(l: FrameGraphListener) { frameGraphListener = l }
    fun setLiveParamsListener(l: LiveParamsListener) { liveParamsListener = l }
    fun setInitialFpsCounterState(enabled: Boolean) { initialFpsCounter = enabled }
    fun setInitialFrameGraphState(enabled: Boolean) { initialFrameGraph = enabled }

    fun show() {
        if (root != null) return

        // Match OverlayManager's host choice — they MUST live in the same
        // layer family or the drawer disappears behind the capture overlay.
        // See OverlayManager.show() for the trusted-overlay rationale.
        val prefs = LsfgPreferences(ctx).load()
        val a11y = LsfgAccessibilityService.instance
        val useTrusted = prefs.trustedOverlay && a11y != null
        val hostCtx: Context = if (useTrusted) a11y!! else ctx
        val wm = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hostWindowManager = wm

        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        edgeStripWidthPx = dp(16)
        handleWidthPx = dp(5)
        handleHeightPx = dp(68)
        iconSizePx = dp(48)
        panelWidthPx = minOf(dp(340), (screenW * 0.85f).toInt())
        panelHeightPx = minOf(dp(340), (screenH * 0.85f).toInt())
        panelMarginPx = dp(12)
        drawerEdge = prefs.drawerEdge
        // Initial icon position: stick it to the right edge, vertically centred.
        iconX = screenW - iconSizePx - dp(8)
        iconY = (screenH - iconSizePx) / 2

        val layoutType = when {
            useTrusted -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        // Start narrow so only the entry affordance captures touches. When the user opens
        // the panel we expand to MATCH_PARENT so scrim + panel can be laid out across the
        // whole screen.
        val lp = WindowManager.LayoutParams(
            collapsedWindowWidth(),
            collapsedWindowHeight(),
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = collapsedWindowGravity()
            x = if (entryMode == OverlayMode.ICON_BUTTON) iconX else 0
            y = if (entryMode == OverlayMode.ICON_BUTTON) iconY else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        params = lp

        val rootLayout = FrameLayout(ctx)
        root = rootLayout

        // Scrim — darkens game area when drawer is open. Alpha is bound to progress so it
        // fades in as the user drags / after the icon is tapped.
        val scrimView = View(ctx).apply {
            setBackgroundColor(0xFF000000.toInt()) // solid black, we drive alpha separately
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { animateTo(0f) }
        }
        scrim = scrimView
        rootLayout.addView(
            scrimView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        val panelView = buildPanel()
        panelContainer = panelView
        panelView.visibility = View.GONE
        applyPanelProgress(panelView, 0f)
        rootLayout.addView(
            panelView,
            panelLayoutParams(),
        )

        if (entryMode == OverlayMode.DRAWER) {
            val handle = HandleView(ctx).apply {
                isClickable = false
                isFocusable = false
            }
            handleView = handle
            rootLayout.addView(
                handle,
                handleLayoutParams(),
            )
            attachEdgeSwipeBehavior(rootLayout)
            startHandlePulse()
        } else {
            val icon = buildIconButton()
            iconButton = icon
            rootLayout.addView(icon, iconButtonLayoutParams())
            attachIconButtonBehavior(icon)
        }

        runCatching { wm.addView(rootLayout, lp) }
            .onFailure { Log.w(TAG, "addView failed", it) }
    }

    fun hide() {
        val r = root ?: return
        val wm = hostWindowManager
        settleAnimator?.cancel()
        settleAnimator = null
        handlePulseAnimator?.cancel()
        handlePulseAnimator = null
        if (wm != null) {
            runCatching { wm.removeView(r) }
                .onFailure { Log.w(TAG, "removeView failed", it) }
        }
        root = null
        handleView = null
        iconButton = null
        scrim = null
        panelContainer = null
        params = null
        hostWindowManager = null
        expanded = false
        progress = 0f
    }

    // --- panel ---------------------------------------------------------------------------

    private fun buildPanel(): View {
        val prefs = LsfgPreferences(ctx)
        val initial = prefs.load()

        // Outer container holds the rounded panel plus a small floating margin on the right
        // so the panel does not touch the screen edge (gives it a card/sheet feel).
        val container = FrameLayout(ctx).apply {
            setPadding(0, panelMarginPx, panelMarginPx, panelMarginPx)
            isClickable = true // absorb taps so they don't bubble to scrim
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = buildPanelBackground()
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // Header
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(brandMark())
        header.addView(
            TextView(ctx).apply {
                text = "LSFG"
                setTextColor(COLOR_ON_SURFACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply { leftMargin = dp(10) }
                layoutParams = lp
            },
        )
        val closeBtn = ImageView(ctx).apply {
            setImageDrawable(crossDrawable())
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { animateTo(0f) }
        }
        header.addView(closeBtn)
        panel.addView(header)

        panel.addView(sectionSpacer(12))

        // ---- Frame Generation (expanded by default so drawer shows content on first open) ----
        val frameGenSection = collapsibleSection(panel, "FRAME GENERATION", initiallyExpanded = true)

        frameGenSection.addView(switchRow(
            label = "LSFG Frame Gen",
            initial = initial.lsfgEnabled,
        ) {
            Log.i(TAG, "live: lsfgEnabled=$it")
            prefs.setLsfgEnabled(it)
            // Invert for the bypass plumbing: on = frame gen active, off = bypass raw capture.
            bypassListener?.onBypassChanged(!it)
        })

        frameGenSection.addView(switchRow(
            label = "Performance mode",
            initial = initial.performanceMode,
        ) {
            Log.i(TAG, "live: performance=$it")
            prefs.setPerformance(it)
            liveParamsListener?.onParamsChanged()
        })
        frameGenSection.addView(switchRow(
            label = "HDR mode",
            initial = initial.hdrMode,
        ) {
            Log.i(TAG, "live: hdr=$it")
            prefs.setHdr(it)
            liveParamsListener?.onParamsChanged()
        })
        frameGenSection.addView(switchRow(
            label = "Anti-artifacts",
            initial = initial.antiArtifacts,
        ) {
            Log.i(TAG, "live: antiArtifacts=$it")
            prefs.setAntiArtifacts(it)
            NativeBridge.setAntiArtifacts(it)
        })

        // FP16 frame-gen shaders — only show when the GPU supports shaderFloat16
        // and the FP16 SPIR-V cache has been populated (same gate the in-app
        // Params screen uses). Toggling requires a context re-init because
        // shader modules are bound at LSFG_3_X::initialize time, hence the
        // liveParamsListener call.
        val fp16CacheDir = File(ctx.filesDir, "spirv").absolutePath
        val fp16Available = runCatching {
            NativeBridge.isFramegenFp16Supported(fp16CacheDir)
        }.getOrDefault(false)
        if (fp16Available) {
            frameGenSection.addView(switchRow(
                label = "FP16 frame-gen shaders",
                initial = initial.framegenFp16,
            ) {
                Log.i(TAG, "live: framegenFp16=$it")
                prefs.setFramegenFp16(it)
                liveParamsListener?.onParamsChanged()
            })
        }

        frameGenSection.addView(sectionSpacer(8))

        val multiplierValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${initial.multiplier}×"
        }
        frameGenSection.addView(
            sliderRow(
                labelText = "Frame multiplier",
                valueView = multiplierValue,
            ),
        )
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 6
            progress = (initial.multiplier - 2).coerceIn(0, 6)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            var dragging = false
            var pendingMultiplier = initial.multiplier
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val m = (p + 2).coerceIn(2, 8)
                    multiplierValue.text = "${m}×"
                    if (fromUser) {
                        pendingMultiplier = m
                        // Tap (not drag) fires only onProgressChanged — write prefs +
                        // trigger reinit immediately. During drag we coalesce both the
                        // pref write and the reinit to onStopTrackingTouch to avoid
                        // 60×/sec SharedPreferences writes (lock + async fsync).
                        if (!dragging) {
                            prefs.setMultiplier(m)
                            Log.i(TAG, "live: multiplier tap → $m")
                            liveParamsListener?.onParamsChanged()
                        }
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    prefs.setMultiplier(pendingMultiplier)
                    Log.i(TAG, "live: multiplier release → $pendingMultiplier")
                    liveParamsListener?.onParamsChanged()
                }
            })
        })

        frameGenSection.addView(sectionSpacer(10))

        val flowValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "%.2f".format(initial.flowScale)
        }
        frameGenSection.addView(
            sliderRow(
                labelText = "Flow scale",
                valueView = flowValue,
            ),
        )
        frameGenSection.addView(SeekBar(ctx).apply {
            max = 15
            progress = ((initial.flowScale - 0.25f) / 0.05f).toInt().coerceIn(0, 15)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            var dragging = false
            var pendingFlowScale = initial.flowScale
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val f = (0.25f + p * 0.05f).coerceIn(0.25f, 1.0f)
                    flowValue.text = "%.2f".format(f)
                    if (fromUser) {
                        pendingFlowScale = f
                        if (!dragging) {
                            prefs.setFlowScale(f)
                            Log.i(TAG, "live: flowScale tap → $f")
                            liveParamsListener?.onParamsChanged()
                        }
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    prefs.setFlowScale(pendingFlowScale)
                    Log.i(TAG, "live: flowScale release → $pendingFlowScale")
                    liveParamsListener?.onParamsChanged()
                }
            })
        })

        panel.addView(divider())

        // ---- Pacing (separate section — was previously nested inside frame gen) ---------
        val pacingSection = collapsibleSection(panel, "PACING")
        buildPacingControls(pacingSection, prefs, initial)

        panel.addView(divider())

        if (SHOW_IMAGE_QUALITY) {
            buildGpuImageQualitySection(panel, prefs, initial)
            panel.addView(divider())
            buildNpuImageQualitySection(panel, prefs, initial)
            panel.addView(divider())
            buildCpuImageQualitySection(panel, prefs, initial)
            panel.addView(divider())
        }

        // ---- HUD & Overlay (FPS, frame graph, drawer edge) -----------------------------
        val hudSection = collapsibleSection(panel, "HUD & OVERLAY")
        hudSection.addView(switchRow(
            label = "FPS counter",
            initial = initialFpsCounter,
        ) {
            prefs.setFpsCounterEnabled(it)
            fpsCounterListener?.onFpsCounterChanged(it)
        })
        hudSection.addView(switchRow(
            label = "Frame pacing graph",
            initial = initialFrameGraph,
        ) {
            prefs.setFrameGraphEnabled(it)
            frameGraphListener?.onFrameGraphChanged(it)
        })
        hudSection.addView(miniHeader("Drawer handle"))
        hudSection.addView(drawerEdgeChipRow(initial.drawerEdge) {
            prefs.setDrawerEdge(it)
        })

        panel.addView(sectionSpacer(20))

        val stopBtn = Button(ctx).apply {
            text = "End session"
            setTextColor(COLOR_STOP_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_STOP_BG)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), COLOR_STOP_STROKE)
            }
            setPadding(0, dp(14), 0, dp(14))
            stateListAnimator = null
            setOnClickListener { stopListener?.onStopOverlay() }
        }
        val stopLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }
        panel.addView(stopBtn, stopLp)

        scroll.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        container.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        return container
    }

    // --- drawer state / animation -------------------------------------------------------

    private fun attachEdgeSwipeBehavior(strip: View) {
        val slop = dp(8)
        strip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // A bare touch on the strip is not enough to expand the window — we wait
                    // until ACTION_MOVE crosses the slop threshold. Keeping the window at
                    // `edgeStripWidthPx` while only a tap is in flight avoids forcing the
                    // compositor to blend a full-screen overlay on top of the running game.
                    dragStartX = ev.rawX
                    dragStartY = ev.rawY
                    dragStartProgress = progress
                    dragActive = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaPx = dragDeltaTowardCenter(ev)
                    if (!dragActive && kotlin.math.abs(deltaPx) > slop) {
                        dragActive = true
                        settleAnimator?.cancel()
                        // Only now that the user is really dragging do we expand the window
                        // so the panel can be laid out across the screen.
                        if (!expanded) expandWindow()
                    }
                    if (dragActive) {
                        val delta = deltaPx / panelTravelPx().toFloat()
                        setProgress((dragStartProgress + delta).coerceIn(0f, 1f))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragActive) {
                        val target = if (progress > 0.45f) 1f else 0f
                        animateTo(target)
                    } else if (!expanded && progress == 0f) {
                        // Touch without drag on the collapsed strip — keep window narrow,
                        // no state change.
                        collapseWindowIfNeeded()
                    }
                    dragActive = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setProgress(p: Float) {
        val wasZero = progress == 0f
        progress = p
        val panelView = panelContainer ?: return
        panelView.visibility = View.VISIBLE
        applyPanelProgress(panelView, p)
        val scrimView = scrim
        if (scrimView != null) {
            scrimView.visibility = if (p > 0f) View.VISIBLE else View.GONE
            scrimView.alpha = p * 0.45f
        }
        val entryAlpha = (1f - p).coerceIn(0f, 1f)
        handleView?.alpha = entryAlpha
        iconButton?.alpha = entryAlpha
        expanded = p >= 0.99f
        // Stop the pulse as soon as the drawer starts opening — no point burning invalidate
        // cycles on a handle the user has already grabbed. Resume when fully collapsed.
        if (entryMode == OverlayMode.DRAWER) {
            if (p > 0f && wasZero) {
                stopHandlePulse()
            } else if (p == 0f && !wasZero) {
                startHandlePulse()
            }
        }
    }

    private fun animateTo(target: Float) {
        settleAnimator?.cancel()
        val from = progress
        val duration = if (target > from) 280L else 220L
        val interpolator = if (target > from) {
            OvershootInterpolator(1.05f)
        } else {
            AccelerateInterpolator(1.1f)
        }
        val anim = ValueAnimator.ofFloat(from, target).apply {
            this.duration = duration
            this.interpolator = interpolator
            addUpdateListener { setProgress(it.animatedValue as Float) }
        }
        settleAnimator = anim
        anim.start()
        // Cleanup after close: shrink the window back to the edge strip so touches on the
        // game area pass through again.
        if (target == 0f) {
            anim.doOnEnd {
                if (progress == 0f) collapseWindowIfNeeded()
            }
        }
    }

    private fun expandWindow() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return
        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT &&
            lp.height == WindowManager.LayoutParams.MATCH_PARENT) return
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        // In ICON_BUTTON mode the collapsed window was offset to the icon's position;
        // when expanded to fullscreen we need to reset x/y so the scrim and panel
        // cover the entire display rather than starting at the icon.
        if (entryMode == OverlayMode.ICON_BUTTON) {
            lp.x = 0
            lp.y = 0
        }
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "expandWindow failed", it) }
    }

    private fun collapseWindowIfNeeded() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return
        val collapsedW = collapsedWindowWidth()
        val collapsedH = collapsedWindowHeight()
        val needsResize = lp.width != collapsedW || lp.height != collapsedH
        val needsReposition = entryMode == OverlayMode.ICON_BUTTON && (lp.x != iconX || lp.y != iconY)
        if (!needsResize && !needsReposition) return
        lp.width = collapsedW
        lp.height = collapsedH
        if (entryMode == OverlayMode.ICON_BUTTON) {
            lp.x = iconX
            lp.y = iconY
        }
        scrim?.visibility = View.GONE
        panelContainer?.visibility = View.GONE
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "collapseWindowIfNeeded failed", it) }
    }

    /**
     * Re-reads display metrics, recomputes panel/icon dimensions and re-applies
     * layout params after a screen rotation or display configuration change.
     *
     * Without this, after rotating the device:
     *   - in ICON_BUTTON mode the icon ends up off-screen (the cached
     *     `iconX/iconY` reference the pre-rotation extents);
     *   - in DRAWER mode the panel is sized to the wrong axis, so it appears
     *     empty / clipped — that is the "non si vede più il drawer" symptom.
     *
     * Posted to the view's main-thread looper because WindowManager rejects
     * updateViewLayout calls from a binder thread on some OEMs.
     */
    fun onDisplayConfigurationChanged() {
        val r = root ?: return
        r.post {
            relayoutForCurrentDisplay()
        }
    }

    private fun relayoutForCurrentDisplay() {
        val wm = hostWindowManager ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return

        // Stop any in-flight slide animation — the dimensions it was animating
        // toward are about to be invalidated.
        settleAnimator?.cancel()
        settleAnimator = null

        val dm = ctx.resources.displayMetrics
        val newW = dm.widthPixels
        val newH = dm.heightPixels
        if (newW <= 0 || newH <= 0) return
        val sizeChanged = newW != screenW || newH != screenH
        screenW = newW
        screenH = newH

        // Recompute size-dependent dimensions. dp(...) is density-relative so
        // it's stable across rotations, but the screen-percentage clamps on
        // panel size are not.
        panelWidthPx = minOf(dp(340), (screenW * 0.85f).toInt())
        panelHeightPx = minOf(dp(340), (screenH * 0.85f).toInt())

        // ICON_BUTTON: clamp the icon back inside the new screen bounds so it
        // stays visible after a rotation that shrank the relevant axis.
        if (entryMode == OverlayMode.ICON_BUTTON && sizeChanged) {
            iconX = iconX.coerceIn(0, (screenW - iconSizePx).coerceAtLeast(0))
            iconY = iconY.coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
        }

        // Rebuild panel layout params (size + gravity) on the new orientation.
        panelContainer?.let { pv ->
            pv.layoutParams = panelLayoutParams()
            applyPanelProgress(pv, progress)
        }
        // Same for the drawer handle pill — its preferred size depends on
        // whether the active edge is vertical or horizontal.
        handleView?.layoutParams = handleLayoutParams()

        // Reset the WindowManager params to match the current state (collapsed
        // vs expanded, icon vs drawer).
        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT &&
            lp.height == WindowManager.LayoutParams.MATCH_PARENT) {
            // Expanded: nothing to resize, the panel itself was rebuilt above.
        } else {
            lp.width = collapsedWindowWidth()
            lp.height = collapsedWindowHeight()
            lp.gravity = collapsedWindowGravity()
            if (entryMode == OverlayMode.ICON_BUTTON) {
                lp.x = iconX
                lp.y = iconY
            } else {
                lp.x = 0
                lp.y = 0
            }
        }
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "relayoutForCurrentDisplay updateViewLayout failed", it) }
        Log.i(TAG, "Drawer relayout for ${newW}x${newH}")
    }

    private fun startHandlePulse() {
        handlePulseAnimator?.cancel()
        // The pulse is decorative. It only exists to make the edge handle findable while
        // collapsed, so we keep it running with a slow duration and stop it entirely once
        // the user starts interacting with the drawer — during a session the compositor
        // would otherwise re-blend an overlay layer on every pulse frame even though
        // nothing else on screen has changed.
        val anim = ValueAnimator.ofFloat(0.72f, 1f).apply {
            duration = 3200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val v = it.animatedValue as Float
                handleView?.setGlow(v)
            }
        }
        handlePulseAnimator = anim
        anim.start()
    }

    private fun stopHandlePulse() {
        handlePulseAnimator?.cancel()
        handlePulseAnimator = null
        handleView?.setGlow(1f)
    }

    // --- small helpers & drawables ------------------------------------------------------

    private fun brandMark(): View {
        val size = dp(26)
        val radius = dp(7).toFloat()
        // Wrap the bitmap icon in a RoundedBitmapDrawable-equivalent (manual clip via
        // ShapeAppearance) by drawing it into a GradientDrawable with a bitmap shader.
        val mark = ImageView(ctx).apply {
            setImageResource(com.lsfg.android.R.drawable.lsfg_app_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        return mark
    }

    private fun crossDrawable(): android.graphics.drawable.Drawable {
        // Simple X drawn via a custom drawable (no vector asset needed).
        return object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_ON_SURFACE
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val inset = dp(5).toFloat()
                canvas.drawLine(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, paint)
                canvas.drawLine(b.right - inset, b.top + inset, b.left + inset, b.bottom - inset, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun buildPanelBackground(): android.graphics.drawable.Drawable {
        val shadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x66000000.toInt())
            cornerRadius = dp(24).toFloat()
        }
        val body = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(COLOR_PANEL_BG)
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), COLOR_PANEL_STROKE)
        }
        val layers = LayerDrawable(arrayOf(shadow, body))
        // Offset the body slightly upward so the shadow peeks at the bottom edge for a
        // floating/elevated look without any hardware shadow config.
        layers.setLayerInset(0, 0, 0, 0, 0)
        layers.setLayerInset(1, 0, 0, 0, dp(2))
        return layers
    }

    private fun buildSeekTrack(): android.graphics.drawable.Drawable {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(COLOR_TRACK_BG)
            cornerRadius = dp(3).toFloat()
        }
        val bgInset = android.graphics.drawable.InsetDrawable(bg, 0, dp(10), 0, dp(10))
        val progress = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(COLOR_ACCENT_DEEP, COLOR_PRIMARY),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3).toFloat()
        }
        val progressScale = android.graphics.drawable.ScaleDrawable(
            progress, Gravity.START or Gravity.CENTER_VERTICAL, 1f, -1f,
        )
        progressScale.level = 0
        val progressInset = android.graphics.drawable.InsetDrawable(progressScale, 0, dp(10), 0, dp(10))
        val layers = LayerDrawable(arrayOf(bgInset, progressInset))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        return layers
    }

    private fun buildSeekThumb(): android.graphics.drawable.Drawable {
        val thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_PRIMARY)
            setStroke(dp(2), 0xFFFFFFFF.toInt())
            setSize(dp(20), dp(20))
        }
        return thumb
    }

    /**
     * Builds a collapsible section with a tappable header (brand-primary eyebrow label + chevron)
     * and a body [LinearLayout] that callers populate. Returns the body so the rest of the panel
     * construction can `body.addView(...)` the section's children.
     *
     * The whole wrapper (header + body) is the view that gets added to the scrolling panel, so the
     * section dividers/spacers between sections remain the caller's responsibility — same visual
     * language as before, just with toggle affordances.
     */
    private fun buildPacingControls(
        parent: LinearLayout,
        prefs: LsfgPreferences,
        initial: com.lsfg.android.prefs.LsfgConfig,
    ) {
        // Section title is provided by the parent collapsibleSection — don't emit one here.

        // Forward-declare widgets so the preset chip row can mutate them.
        val emaValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        }
        val outlierValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        }
        val slackValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        }
        val queueValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        }

        val emaBar = SeekBar(ctx)
        val outlierBar = SeekBar(ctx)
        val slackBar = SeekBar(ctx)
        val queueBar = SeekBar(ctx)

        // Guard flag: true while we programmatically update sliders via preset so
        // their onProgressChanged doesn't flip the preset back to CUSTOM.
        var applyingPreset = false

        var currentPreset = initial.pacingPreset

        // Pushes the current tunables + target fps cap + queue depth down to
        // the native render loop without re-creating the context.
        fun pushPacing() {
            val cfg = prefs.load()
            val p = PacingDefaults.forPreset(
                cfg.pacingPreset,
                PacingDefaults.Params(cfg.emaAlpha, cfg.outlierRatio, cfg.vsyncSlackMs, cfg.queueDepth),
            )
            runCatching {
                NativeBridge.setPacingParams(
                    cfg.targetFpsCap,
                    p.emaAlpha,
                    p.outlierRatio,
                    p.vsyncSlackMs,
                    p.queueDepth,
                )
            }
        }

        // Preset chip row with in-place visual updates (chips keep their views
        // and only toggle colors, so the click never fights a view re-parent).
        val presetItems = listOf(
            PacingPreset.SMOOTH to "Smooth",
            PacingPreset.BALANCED to "Balanced",
            PacingPreset.LOW_LATENCY to "Low-latency",
            PacingPreset.CUSTOM to "Custom",
        )
        val presetBtns = mutableListOf<Button>()
        fun paintPresetChips(selected: PacingPreset) {
            presetBtns.forEachIndexed { i, btn ->
                val isSel = presetItems[i].first == selected
                btn.setTextColor(if (isSel) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                (btn.background as? GradientDrawable)?.setColor(
                    if (isSel) COLOR_PRIMARY else COLOR_CHIP_BG,
                )
            }
        }
        val presetRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        presetItems.forEach { (preset, label) ->
            val btn = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            presetBtns.add(btn)
            presetRow.addView(
                btn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                },
            )
        }
        parent.addView(presetRow)

        fun applyPreset(preset: PacingPreset) {
            currentPreset = preset
            prefs.setPacingPreset(preset)
            paintPresetChips(preset)
            if (preset != PacingPreset.CUSTOM) {
                applyingPreset = true
                val p = PacingDefaults.forPreset(preset, PacingDefaults.Params(0f, 0f, 0f, 0))
                prefs.setEmaAlpha(p.emaAlpha)
                prefs.setOutlierRatio(p.outlierRatio)
                prefs.setVsyncSlackMs(p.vsyncSlackMs)
                prefs.setQueueDepth(p.queueDepth)
                emaBar.progress = (((p.emaAlpha - 0.05f) / 0.01f).toInt()).coerceIn(0, 45)
                outlierBar.progress = (((p.outlierRatio - 2.0f) / 0.5f).toInt()).coerceIn(0, 12)
                slackBar.progress = (((p.vsyncSlackMs - 1.0f) / 0.5f).toInt()).coerceIn(0, 8)
                queueBar.progress = (p.queueDepth - 2).coerceIn(0, 4)
                emaValue.text = "%.2f".format(p.emaAlpha)
                outlierValue.text = "%.1f×".format(p.outlierRatio)
                slackValue.text = "%.1f ms".format(p.vsyncSlackMs)
                queueValue.text = "${p.queueDepth}"
                applyingPreset = false
            }
            pushPacing()
        }
        presetBtns.forEachIndexed { i, btn ->
            btn.setOnClickListener { applyPreset(presetItems[i].first) }
        }
        paintPresetChips(currentPreset)

        // VSYNC alignment switch + refresh override chip row.
        parent.addView(switchRow(
            label = "VSYNC-aligned pacing",
            initial = initial.vsyncAlignmentEnabled,
        ) { on ->
            prefs.setVsyncAlignmentEnabled(on)
            // Hot-apply: 0 period disables alignment in the native pacer.
            if (!on) {
                runCatching { NativeBridge.setVsyncPeriodNs(0L) }
            } else {
                val override = prefs.load().vsyncRefreshOverride
                if (override != VsyncRefreshOverride.AUTO && override.hz > 0) {
                    val periodNs = (1_000_000_000.0 / override.hz).toLong()
                    runCatching { NativeBridge.setVsyncPeriodNs(periodNs) }
                }
                // For ON+AUTO we don't know the display rate from here; the next
                // overlay re-show will re-read prefs and set the correct period.
            }
        })

        val refreshItems = listOf(
            VsyncRefreshOverride.AUTO to "Auto",
            VsyncRefreshOverride.HZ_60 to "60",
            VsyncRefreshOverride.HZ_90 to "90",
            VsyncRefreshOverride.HZ_120 to "120",
            VsyncRefreshOverride.HZ_144 to "144",
        )
        val refreshBtns = mutableListOf<Button>()
        fun paintRefreshChips(selected: VsyncRefreshOverride) {
            refreshBtns.forEachIndexed { i, btn ->
                val isSel = refreshItems[i].first == selected
                btn.setTextColor(if (isSel) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                (btn.background as? GradientDrawable)?.setColor(
                    if (isSel) COLOR_PRIMARY else COLOR_CHIP_BG,
                )
            }
        }
        val refreshRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        refreshItems.forEach { (option, label) ->
            val btn = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    prefs.setVsyncRefreshOverride(option)
                    paintRefreshChips(option)
                    if (prefs.load().vsyncAlignmentEnabled && option.hz > 0) {
                        val periodNs = (1_000_000_000.0 / option.hz).toLong()
                        runCatching { NativeBridge.setVsyncPeriodNs(periodNs) }
                    }
                }
            }
            refreshBtns.add(btn)
            refreshRow.addView(
                btn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                },
            )
        }
        parent.addView(refreshRow)
        paintRefreshChips(initial.vsyncRefreshOverride)

        // Target FPS cap. Snap values: 0 = unlimited, then 30/45/60/90/120/144.
        val fpsCapSteps = listOf(0, 30, 45, 60, 90, 120, 144)
        val fpsCapValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = if (initial.targetFpsCap <= 0) "Unlimited" else "${initial.targetFpsCap} fps"
        }
        parent.addView(sliderRow("Target FPS cap", fpsCapValue))
        parent.addView(SeekBar(ctx).apply {
            max = fpsCapSteps.size - 1
            progress = fpsCapSteps.indexOf(initial.targetFpsCap).coerceAtLeast(0)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            var dragging = false
            var pendingFpsCap = initial.targetFpsCap
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = fpsCapSteps[p.coerceIn(0, fpsCapSteps.size - 1)]
                    fpsCapValue.text = if (v <= 0) "Unlimited" else "${v} fps"
                    if (fromUser) {
                        pendingFpsCap = v
                        // pushPacing() is in-process and cheap — keep it live so the
                        // pacing loop reflects the slider during drag. Defer the
                        // SharedPreferences write to onStopTrackingTouch.
                        if (!dragging) {
                            prefs.setTargetFpsCap(v)
                        }
                        pushPacing()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    prefs.setTargetFpsCap(pendingFpsCap)
                }
            })
        })

        // ---- Advanced collapsible ----------------------------------------------------
        val advanced = collapsibleSection(parent, "ADVANCED", initiallyExpanded = false)
        advanced.addView(TextView(ctx).apply {
            text = "Manual changes switch preset to Custom. Misconfiguring these may reduce smoothness."
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(8))
        })

        // Slider helper local to this builder so each slider can force preset→CUSTOM.
        // onCommit writes a SharedPreferences entry; we coalesce it to the slider
        // release event during drag (avoids ~60 prefs writes/sec). The live native
        // push (pushPacing) stays per-tick — it is in-process and cheap.
        fun attachAdvancedSlider(
            bar: SeekBar,
            valueView: TextView,
            format: (Float) -> String,
            min: Float,
            step: Float,
            steps: Int,
            initialValue: Float,
            onCommit: (Float) -> Unit,
        ) {
            bar.max = steps
            bar.progress = (((initialValue - min) / step).toInt()).coerceIn(0, steps)
            bar.progressDrawable = buildSeekTrack()
            bar.thumb = buildSeekThumb()
            bar.splitTrack = false
            valueView.text = format(initialValue)
            var dragging = false
            var pendingValue = initialValue
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + p * step
                    valueView.text = format(v)
                    if (fromUser && !applyingPreset) {
                        pendingValue = v
                        if (!dragging) {
                            // Tap (not drag) — commit prefs immediately.
                            onCommit(v)
                        }
                        if (currentPreset != PacingPreset.CUSTOM) {
                            currentPreset = PacingPreset.CUSTOM
                            prefs.setPacingPreset(PacingPreset.CUSTOM)
                            paintPresetChips(PacingPreset.CUSTOM)
                        }
                        pushPacing()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    if (!applyingPreset) {
                        onCommit(pendingValue)
                    }
                }
            })
        }

        advanced.addView(sliderRow("EMA alpha", emaValue))
        attachAdvancedSlider(
            emaBar, emaValue, { "%.2f".format(it) },
            min = 0.05f, step = 0.01f, steps = 45,
            initialValue = initial.emaAlpha,
        ) { prefs.setEmaAlpha(it) }
        advanced.addView(emaBar)
        advanced.addView(sectionSpacer(8))

        advanced.addView(sliderRow("Outlier ratio", outlierValue))
        attachAdvancedSlider(
            outlierBar, outlierValue, { "%.1f×".format(it) },
            min = 2.0f, step = 0.5f, steps = 12,
            initialValue = initial.outlierRatio,
        ) { prefs.setOutlierRatio(it) }
        advanced.addView(outlierBar)
        advanced.addView(sectionSpacer(8))

        advanced.addView(sliderRow("VSYNC slack", slackValue))
        attachAdvancedSlider(
            slackBar, slackValue, { "%.1f ms".format(it) },
            min = 1.0f, step = 0.5f, steps = 8,
            initialValue = initial.vsyncSlackMs,
        ) { prefs.setVsyncSlackMs(it) }
        advanced.addView(slackBar)
        advanced.addView(sectionSpacer(8))

        advanced.addView(sliderRow("Queue depth", queueValue))
        attachAdvancedSlider(
            queueBar, queueValue, { "${it.toInt()}" },
            min = 2.0f, step = 1.0f, steps = 4,
            initialValue = initial.queueDepth.toFloat(),
        ) { prefs.setQueueDepth(it.toInt()) }
        advanced.addView(queueBar)
        advanced.addView(sectionSpacer(12))

        // Reset button.
        advanced.addView(Button(ctx).apply {
            text = "Reset to defaults"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(COLOR_PANEL_BG)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_PRIMARY)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                prefs.setTargetFpsCap(PacingDefaults.TARGET_FPS_CAP)
                // applyPreset handles prefs + slider refresh + paint + pushPacing.
                applyPreset(PacingPreset.BALANCED)
            }
        })
    }

    private fun buildGpuImageQualitySection(
        panel: LinearLayout,
        prefs: LsfgPreferences,
        initial: com.lsfg.android.prefs.LsfgConfig,
    ) {
        val gpuSection = collapsibleSection(panel, "IMAGE QUALITY — GPU")
        gpuSection.addView(switchRow(
            label = "GPU processing",
            initial = initial.gpuPostProcessingEnabled,
        ) {
            Log.i(TAG, "live: gpuPost=$it")
            prefs.setGpuPostProcessingEnabled(it)
            liveParamsListener?.onParamsChanged()
        })
        gpuSection.addView(gpuStageChipRow(initial.gpuPostProcessingStage) {
            prefs.setGpuPostProcessingStage(it)
            liveParamsListener?.onParamsChanged()
        })

        gpuSection.addView(miniHeader("Scaling"))
        gpuSection.addView(gpuMethodChipRow(
            category = GpuMethodCategory.SCALING,
            initial = initial.gpuPostProcessingMethod,
        ) {
            prefs.setGpuPostProcessingMethod(it)
            liveParamsListener?.onParamsChanged()
        })

        val gpuScaleValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "%.2fx".format(initial.gpuUpscaleFactor)
        }
        gpuSection.addView(sliderRow("GPU scale", gpuScaleValue))
        gpuSection.addView(SeekBar(ctx).apply {
            max = 4
            progress = ((initial.gpuUpscaleFactor - 1.0f) / 0.25f).toInt().coerceIn(0, 4)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            var dragging = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val scale = (1.0f + p * 0.25f).coerceIn(1.0f, 2.0f)
                    gpuScaleValue.text = "%.2fx".format(scale)
                    if (fromUser) {
                        prefs.setGpuUpscaleFactor(scale)
                        if (!dragging) liveParamsListener?.onParamsChanged()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    liveParamsListener?.onParamsChanged()
                }
            })
        })

        gpuSection.addView(miniHeader("Enhancement"))
        gpuSection.addView(gpuMethodChipRow(
            category = GpuMethodCategory.ENHANCEMENT,
            initial = initial.gpuPostProcessingMethod,
        ) {
            prefs.setGpuPostProcessingMethod(it)
            liveParamsListener?.onParamsChanged()
        })

        gpuSection.addView(miniHeader("Sharpen & color"))
        gpuSection.addView(gpuMethodChipRow(
            category = GpuMethodCategory.SHARPEN_COLOR,
            initial = initial.gpuPostProcessingMethod,
        ) {
            prefs.setGpuPostProcessingMethod(it)
            liveParamsListener?.onParamsChanged()
        })

        val gpuAdvanced = collapsibleSection(gpuSection, "ADVANCED", initiallyExpanded = false)

        val gpuSharpnessValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initial.gpuSharpness * 100f).toInt()}%"
        }
        gpuAdvanced.addView(sliderRow("GPU sharpness", gpuSharpnessValue))
        gpuAdvanced.addView(SeekBar(ctx).apply {
            max = 10
            progress = (initial.gpuSharpness * 10f).toInt().coerceIn(0, 10)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            var dragging = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val sharpness = (p / 10f).coerceIn(0f, 1f)
                    gpuSharpnessValue.text = "${(sharpness * 100f).toInt()}%"
                    if (fromUser) {
                        prefs.setGpuSharpness(sharpness)
                        if (!dragging) liveParamsListener?.onParamsChanged()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    liveParamsListener?.onParamsChanged()
                }
            })
        })

        val gpuStrengthValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initial.gpuStrength * 100f).toInt()}%"
        }
        gpuAdvanced.addView(sliderRow("GPU strength", gpuStrengthValue))
        gpuAdvanced.addView(SeekBar(ctx).apply {
            max = 10
            progress = (initial.gpuStrength * 10f).toInt().coerceIn(0, 10)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            var dragging = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val strength = (p / 10f).coerceIn(0f, 1f)
                    gpuStrengthValue.text = "${(strength * 100f).toInt()}%"
                    if (fromUser) {
                        prefs.setGpuStrength(strength)
                        if (!dragging) liveParamsListener?.onParamsChanged()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    liveParamsListener?.onParamsChanged()
                }
            })
        })
    }

    private fun buildNpuImageQualitySection(
        panel: LinearLayout,
        prefs: LsfgPreferences,
        initial: com.lsfg.android.prefs.LsfgConfig,
    ) {
        val npuSection = collapsibleSection(panel, "IMAGE QUALITY — NPU")
        val npuAvailable = runCatching { NativeBridge.isNpuAvailable() }.getOrDefault(false)
        npuSection.addView(switchRow(
            label = if (npuAvailable) "NPU enhancement" else "NPU unavailable",
            initial = initial.npuPostProcessingEnabled && npuAvailable,
        ) {
            Log.i(TAG, "live: npuPost=$it available=$npuAvailable")
            prefs.setNpuPostProcessingEnabled(it && npuAvailable)
            liveParamsListener?.onParamsChanged()
        })

        val npuAmountValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initial.npuAmount * 100f).toInt()}%"
        }
        npuSection.addView(sliderRow("NPU amount", npuAmountValue))
        npuSection.addView(SeekBar(ctx).apply {
            max = 10
            progress = (initial.npuAmount * 10f).toInt().coerceIn(0, 10)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            var dragging = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val amount = (p / 10f).coerceIn(0f, 1f)
                    npuAmountValue.text = "${(amount * 100f).toInt()}%"
                    if (fromUser) {
                        prefs.setNpuAmount(amount)
                        if (!dragging) liveParamsListener?.onParamsChanged()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    liveParamsListener?.onParamsChanged()
                }
            })
        })
        npuSection.addView(npuPresetChipRow(initial.npuPostProcessingPreset) {
            prefs.setNpuPostProcessingPreset(it)
            liveParamsListener?.onParamsChanged()
        })
    }

    private fun buildCpuImageQualitySection(
        panel: LinearLayout,
        prefs: LsfgPreferences,
        initial: com.lsfg.android.prefs.LsfgConfig,
    ) {
        val cpuSection = collapsibleSection(panel, "IMAGE QUALITY — CPU")
        cpuSection.addView(switchRow(
            label = "CPU post-process",
            initial = initial.cpuPostProcessingEnabled,
        ) {
            prefs.setCpuPostProcessingEnabled(it)
            liveParamsListener?.onParamsChanged()
        })
        val cpuStrengthValue = TextView(ctx).apply {
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            text = "${(initial.cpuStrength * 100f).toInt()}%"
        }
        cpuSection.addView(sliderRow("CPU strength", cpuStrengthValue))
        cpuSection.addView(SeekBar(ctx).apply {
            max = 10
            progress = (initial.cpuStrength * 10f).toInt().coerceIn(0, 10)
            progressDrawable = buildSeekTrack()
            thumb = buildSeekThumb()
            splitTrack = false
            var dragging = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    val strength = (p / 10f).coerceIn(0f, 1f)
                    cpuStrengthValue.text = "${(strength * 100f).toInt()}%"
                    if (fromUser) {
                        prefs.setCpuStrength(strength)
                        if (!dragging) liveParamsListener?.onParamsChanged()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    liveParamsListener?.onParamsChanged()
                }
            })
        })
        cpuSection.addView(cpuPresetChipRow(initial.cpuPostProcessingPreset) {
            prefs.setCpuPostProcessingPreset(it)
            liveParamsListener?.onParamsChanged()
        })
    }

    private fun collapsibleSection(
        parent: LinearLayout,
        title: String,
        initiallyExpanded: Boolean = false,
    ): LinearLayout {
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val chevron = ImageView(ctx).apply {
            setImageDrawable(chevronDrawable())
            val sz = dp(14)
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            rotation = if (initiallyExpanded) 180f else 0f
        }

        val label = TextView(ctx).apply {
            text = title
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.15f
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            isClickable = true
            isFocusable = true
            // Subtle ripple on tap to hint at interactivity; stays on-brand because the body will
            // just fade-slide via visibility toggle.
            val ta = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
            addView(label)
            addView(chevron)
        }

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }

        header.setOnClickListener {
            val nowVisible = body.visibility != View.VISIBLE
            body.visibility = if (nowVisible) View.VISIBLE else View.GONE
            chevron.animate()
                .rotation(if (nowVisible) 180f else 0f)
                .setDuration(180)
                .start()
        }

        wrapper.addView(header)
        wrapper.addView(body)
        parent.addView(wrapper)
        return body
    }

    private fun chevronDrawable(): android.graphics.drawable.Drawable {
        return object : android.graphics.drawable.Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_PRIMARY
                strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                style = Paint.Style.STROKE
            }
            override fun draw(canvas: Canvas) {
                val b = bounds
                val inset = dp(3).toFloat()
                val midX = (b.left + b.right) / 2f
                canvas.drawLine(b.left + inset, b.top + inset + dp(1), midX, b.bottom - inset - dp(1), paint)
                canvas.drawLine(b.right - inset, b.top + inset + dp(1), midX, b.bottom - inset - dp(1), paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Suppress("DEPRECATION")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun divider() = View(ctx).apply {
        setBackgroundColor(COLOR_DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1),
        )
    }

    private fun sectionSpacer(height: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(height),
        )
    }

    /**
     * Small uppercase caption used inside a section to group related chip rows
     * (e.g. "Scaling", "Enhancement", "Sharpen & color" inside the GPU section).
     */
    private fun miniHeader(text: String) = TextView(ctx).apply {
        this.text = text.uppercase()
        setTextColor(COLOR_ON_SURFACE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        alpha = 0.7f
        letterSpacing = 0.12f
        typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun drawerEdgeChipRow(
        initial: DrawerEdge,
        onSelected: (DrawerEdge) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val items = listOf(
            DrawerEdge.LEFT to "Left",
            DrawerEdge.RIGHT to "Right",
            DrawerEdge.TOP to "Top",
            DrawerEdge.BOTTOM to "Bottom",
        )
        val buttons = mutableListOf<Button>()
        fun paint(selected: DrawerEdge) {
            buttons.forEachIndexed { i, btn ->
                val isSel = items[i].first == selected
                btn.setTextColor(if (isSel) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                (btn.background as? GradientDrawable)?.setColor(
                    if (isSel) COLOR_PRIMARY else COLOR_CHIP_BG,
                )
            }
        }
        items.forEach { (edge, label) ->
            val btn = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                stateListAnimator = null
                setOnClickListener {
                    onSelected(edge)
                    paint(edge)
                }
            }
            val lp = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply {
                leftMargin = if (buttons.isEmpty()) 0 else dp(6)
            }
            row.addView(btn, lp)
            buttons += btn
        }
        paint(initial)
        return row
    }

    private fun sliderRow(labelText: String, valueView: TextView): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        row.addView(
            TextView(ctx).apply {
                text = labelText
                setTextColor(COLOR_ON_SURFACE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        val chip = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(COLOR_CHIP_BG)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(10), dp(3), dp(10), dp(3))
            addView(valueView)
        }
        row.addView(chip)
        return row
    }

    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val lbl = TextView(ctx).apply {
            setTextColor(COLOR_ON_SURFACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(ctx).apply {
            isChecked = initial
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> onChange(checked) }
            // Tint switch to match brand primary when checked.
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            val trackColors = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(
                    COLOR_PRIMARY,
                    COLOR_TRACK_BG,
                ),
            )
            trackTintList = trackColors
        }
        row.addView(lbl)
        row.addView(sw)
        return row
    }

    private fun npuPresetChipRow(
        initial: NpuPostProcessingPreset,
        onChange: (NpuPostProcessingPreset) -> Unit,
    ): View {
        val presets = listOf(
            NpuPostProcessingPreset.OFF to "Off",
            NpuPostProcessingPreset.SHARPEN to "Sharpen",
            NpuPostProcessingPreset.DETAIL_BOOST to "Detail",
            NpuPostProcessingPreset.CHROMA_CLEAN to "Clean",
            NpuPostProcessingPreset.GAME_CRISP to "Game",
        )
        return chipRowFor(initial, presets, onChange)
    }

    private fun cpuPresetChipRow(
        initial: CpuPostProcessingPreset,
        onChange: (CpuPostProcessingPreset) -> Unit,
    ): View {
        val presets = listOf(
            CpuPostProcessingPreset.OFF to "Off",
            CpuPostProcessingPreset.ENHANCE_LUT to "Enhance",
            CpuPostProcessingPreset.WARM to "Warm",
            CpuPostProcessingPreset.COOL to "Cool",
            CpuPostProcessingPreset.VIGNETTE to "Vignette",
            CpuPostProcessingPreset.GAMER_SHARP to "Sharp",
            CpuPostProcessingPreset.CINEMATIC to "Cinematic",
        )
        return chipRowFor(initial, presets, onChange)
    }

    // Compact wrap-layout that stays readable with 4-7 chips. Chips wrap into
    // a second line when the panel is narrow, which is the common case for
    // the in-session drawer on phones.
    private fun <T> chipRowFor(
        initial: T,
        presets: List<Pair<T, String>>,
        onChange: (T) -> Unit,
    ): View {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        val perRow = if (presets.size <= 4) presets.size else (presets.size + 1) / 2
        var i = 0
        while (i < presets.size) {
            val end = minOf(i + perRow, presets.size)
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            for (j in i until end) {
                val (preset, label) = presets[j]
                val btn = Button(ctx).apply {
                    text = label
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(if (preset == initial) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(if (preset == initial) COLOR_PRIMARY else COLOR_CHIP_BG)
                        cornerRadius = dp(10).toFloat()
                    }
                    setOnClickListener { onChange(preset) }
                }
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(3)
                        rightMargin = dp(3)
                    },
                )
            }
            outer.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            i = end
        }
        return outer
    }

    private fun gpuStageChipRow(
        initial: GpuPostProcessingStage,
        onChange: (GpuPostProcessingStage) -> Unit,
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        val stages = listOf(
            GpuPostProcessingStage.BEFORE_LSFG to "Real first",
            GpuPostProcessingStage.AFTER_LSFG to "Final frames",
        )
        stages.forEach { (stage, label) ->
            val btn = Button(ctx).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (stage == initial) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(if (stage == initial) COLOR_PRIMARY else COLOR_CHIP_BG)
                    cornerRadius = dp(10).toFloat()
                }
                setOnClickListener { onChange(stage) }
            }
            row.addView(
                btn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                },
            )
        }
        return row
    }

    private enum class GpuMethodCategory { SCALING, ENHANCEMENT, SHARPEN_COLOR }

    private fun gpuMethodChipRow(
        category: GpuMethodCategory,
        initial: GpuPostProcessingMethod,
        onChange: (GpuPostProcessingMethod) -> Unit,
    ): View {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        val methods = when (category) {
            GpuMethodCategory.SCALING -> listOf(
                GpuPostProcessingMethod.FSR1_EASU_RCAS to "FSR1",
                GpuPostProcessingMethod.NVIDIA_NIS to "NIS",
                GpuPostProcessingMethod.LANCZOS to "Lanczos",
                GpuPostProcessingMethod.BICUBIC to "Bicubic",
                GpuPostProcessingMethod.BILINEAR to "Bilinear",
                GpuPostProcessingMethod.CATMULL_ROM to "Catmull",
                GpuPostProcessingMethod.MITCHELL_NETRAVALI to "Mitchell",
            )
            GpuMethodCategory.ENHANCEMENT -> listOf(
                GpuPostProcessingMethod.ANIME4K_ULTRAFAST to "Anime4K Fast",
                GpuPostProcessingMethod.ANIME4K_RESTORE to "Anime4K Restore",
                GpuPostProcessingMethod.XBRZ to "xBRZ",
                GpuPostProcessingMethod.EDGE_DIRECTED to "Edge",
            )
            GpuMethodCategory.SHARPEN_COLOR -> listOf(
                GpuPostProcessingMethod.AMD_CAS to "CAS",
                GpuPostProcessingMethod.UNSHARP_MASK to "Unsharp",
                GpuPostProcessingMethod.LUMA_SHARPEN to "Luma",
                GpuPostProcessingMethod.CONTRAST_ADAPTIVE to "Contrast",
                GpuPostProcessingMethod.DEBAND to "Deband",
            )
        }
        methods.chunked(2).forEach { rowMethods ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowMethods.forEach { (method, label) ->
                val btn = Button(ctx).apply {
                    text = label
                    isAllCaps = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(if (method == initial) COLOR_PANEL_BG else COLOR_ON_SURFACE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(if (method == initial) COLOR_PRIMARY else COLOR_CHIP_BG)
                        cornerRadius = dp(10).toFloat()
                    }
                    setOnClickListener { onChange(method) }
                }
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        leftMargin = dp(3)
                        rightMargin = dp(3)
                        topMargin = dp(3)
                        bottomMargin = dp(3)
                    },
                )
            }
            container.addView(row)
        }
        return container
    }

    private fun dp(v: Int): Int {
        return (v * ctx.resources.displayMetrics.density).toInt()
    }

    private fun isVerticalEdge(): Boolean =
        drawerEdge == DrawerEdge.LEFT || drawerEdge == DrawerEdge.RIGHT

    private fun collapsedWindowWidth(): Int = when (entryMode) {
        OverlayMode.ICON_BUTTON -> iconSizePx
        OverlayMode.DRAWER ->
            if (isVerticalEdge()) edgeStripWidthPx else WindowManager.LayoutParams.MATCH_PARENT
    }

    private fun collapsedWindowHeight(): Int = when (entryMode) {
        OverlayMode.ICON_BUTTON -> iconSizePx
        OverlayMode.DRAWER ->
            if (isVerticalEdge()) WindowManager.LayoutParams.MATCH_PARENT else edgeStripWidthPx
    }

    private fun collapsedWindowGravity(): Int = when (entryMode) {
        OverlayMode.ICON_BUTTON -> Gravity.TOP or Gravity.START
        OverlayMode.DRAWER -> when (drawerEdge) {
            DrawerEdge.LEFT -> Gravity.TOP or Gravity.START
            DrawerEdge.RIGHT -> Gravity.TOP or Gravity.END
            DrawerEdge.TOP -> Gravity.TOP or Gravity.START
            DrawerEdge.BOTTOM -> Gravity.BOTTOM or Gravity.START
        }
    }

    private fun panelTravelPx(): Int =
        if (isVerticalEdge()) panelWidthPx.coerceAtLeast(1) else panelHeightPx.coerceAtLeast(1)

    private fun panelLayoutParams(): FrameLayout.LayoutParams =
        if (isVerticalEdge()) {
            FrameLayout.LayoutParams(
                panelWidthPx,
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (drawerEdge == DrawerEdge.LEFT) Gravity.START else Gravity.END,
            )
        } else {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                panelHeightPx,
                if (drawerEdge == DrawerEdge.TOP) Gravity.TOP else Gravity.BOTTOM,
            )
        }

    private fun handleLayoutParams(): FrameLayout.LayoutParams =
        if (isVerticalEdge()) {
            FrameLayout.LayoutParams(
                handleWidthPx + dp(8),
                handleHeightPx,
                Gravity.CENTER_VERTICAL or
                    (if (drawerEdge == DrawerEdge.LEFT) Gravity.START else Gravity.END),
            )
        } else {
            FrameLayout.LayoutParams(
                handleHeightPx,
                handleWidthPx + dp(8),
                Gravity.CENTER_HORIZONTAL or
                    (if (drawerEdge == DrawerEdge.TOP) Gravity.TOP else Gravity.BOTTOM),
            )
        }

    // --- Icon button entry mode --------------------------------------------------------

    /**
     * Floating circular icon (LSFG app icon on a translucent disc) used as the entry
     * affordance for the in-game settings panel when [entryMode] == ICON_BUTTON.
     */
    private fun buildIconButton(): View {
        val container = FrameLayout(ctx).apply {
            // The window is sized exactly to the icon, so the button just fills it.
            isClickable = true
            isFocusable = false
        }
        // Show the app launcher icon as-is (square with rounded corners),
        // matching the icon the user sees on the home screen. No disc /
        // background — the drawable already includes the rounded mask.
        val iconView = ImageView(ctx).apply {
            setImageResource(com.lsfg.android.R.drawable.lsfg_app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(
            iconView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        return container
    }

    private fun iconButtonLayoutParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.TOP or Gravity.START)

    /**
     * Touch-handling for the floating icon: drag to reposition (the WindowManager
     * lp.x/lp.y get updated live), tap (no significant movement) to open the panel.
     * After release we snap horizontally to whichever screen edge the icon is closer
     * to so it doesn't end up floating mid-screen, and we update [drawerEdge] so the
     * panel slides in from that same side.
     */
    private fun attachIconButtonBehavior(icon: View) {
        val touchSlop = dp(8)
        icon.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    iconDragStartRawX = ev.rawX
                    iconDragStartRawY = ev.rawY
                    iconDragStartX = iconX
                    iconDragStartY = iconY
                    iconDragMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - iconDragStartRawX
                    val dy = ev.rawY - iconDragStartRawY
                    if (!iconDragMoved && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        iconDragMoved = true
                    }
                    if (iconDragMoved) {
                        iconX = (iconDragStartX + dx.toInt()).coerceIn(0, (screenW - iconSizePx).coerceAtLeast(0))
                        iconY = (iconDragStartY + dy.toInt()).coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
                        if (!iconDragFramePending) {
                            iconDragFramePending = true
                            Choreographer.getInstance().postFrameCallback(iconDragFrameCallback)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (iconDragMoved) {
                        // Cancel any in-flight drag-frame callback so it doesn't
                        // race with the snap-to-edge updateViewLayout below.
                        if (iconDragFramePending) {
                            Choreographer.getInstance().removeFrameCallback(iconDragFrameCallback)
                            iconDragFramePending = false
                        }
                        // Snap to nearest horizontal edge and infer the slide-in side
                        // for the panel from the icon's current position.
                        val centerX = iconX + iconSizePx / 2
                        val centerY = iconY + iconSizePx / 2
                        val nearLeft = centerX < screenW / 2
                        iconX = if (nearLeft) dp(8) else (screenW - iconSizePx - dp(8)).coerceAtLeast(0)
                        iconY = iconY.coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
                        // Pick the closer edge (left/right vs top/bottom) for panel slide direction.
                        val distLeft = centerX
                        val distRight = screenW - centerX
                        val distTop = centerY
                        val distBottom = screenH - centerY
                        drawerEdge = listOf(
                            DrawerEdge.LEFT to distLeft,
                            DrawerEdge.RIGHT to distRight,
                            DrawerEdge.TOP to distTop,
                            DrawerEdge.BOTTOM to distBottom,
                        ).minByOrNull { it.second }?.first ?: DrawerEdge.RIGHT
                        // Re-build the panel layout params so the panel slides in from
                        // the new edge on next open.
                        panelContainer?.let { pv ->
                            pv.layoutParams = panelLayoutParams()
                            applyPanelProgress(pv, progress)
                        }
                        val lp = params
                        val r = root
                        val wm = hostWindowManager
                        if (lp != null && r != null && wm != null && r.isAttachedToWindow &&
                            lp.width != WindowManager.LayoutParams.MATCH_PARENT) {
                            lp.x = iconX
                            lp.y = iconY
                            runCatching { wm.updateViewLayout(r, lp) }
                                .onFailure { Log.w(TAG, "icon snap updateViewLayout failed", it) }
                        }
                    } else {
                        // Tap — open the panel.
                        if (!expanded) {
                            expandWindow()
                            animateTo(1f)
                        }
                    }
                    iconDragMoved = false
                    true
                }
                else -> false
            }
        }
    }

    private fun dragDeltaTowardCenter(ev: MotionEvent): Float = when (drawerEdge) {
        DrawerEdge.LEFT -> ev.rawX - dragStartX
        DrawerEdge.RIGHT -> dragStartX - ev.rawX
        DrawerEdge.TOP -> ev.rawY - dragStartY
        DrawerEdge.BOTTOM -> dragStartY - ev.rawY
    }

    private fun applyPanelProgress(panelView: View, p: Float) {
        panelView.translationX = 0f
        panelView.translationY = 0f
        when (drawerEdge) {
            DrawerEdge.LEFT -> panelView.translationX = -(1f - p) * panelWidthPx
            DrawerEdge.RIGHT -> panelView.translationX = (1f - p) * panelWidthPx
            DrawerEdge.TOP -> panelView.translationY = -(1f - p) * panelHeightPx
            DrawerEdge.BOTTOM -> panelView.translationY = (1f - p) * panelHeightPx
        }
    }

    // --- handle view -------------------------------------------------------------------

    private inner class HandleView(ctx: Context) : View(ctx) {
        private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var glow: Float = 1f
        private var shader: LinearGradient? = null

        fun setGlow(v: Float) {
            if (glow == v) return
            glow = v
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Build the gradient shader once per size change instead of every draw.
            if (isVerticalEdge()) {
                val pillWidth = handleWidthPx.toFloat()
                val pillHeight = handleHeightPx.toFloat()
                val right = if (drawerEdge == DrawerEdge.LEFT) {
                    dp(4).toFloat() + pillWidth
                } else {
                    w.toFloat() - dp(4)
                }
                val left = right - pillWidth
                val top = (h.toFloat() - pillHeight) / 2f
                val bottom = top + pillHeight
                shader = LinearGradient(
                    left, top, left, bottom,
                    COLOR_PRIMARY, COLOR_ACCENT_DEEP,
                    Shader.TileMode.CLAMP,
                )
            } else {
                val pillWidth = handleHeightPx.toFloat()
                val pillHeight = handleWidthPx.toFloat()
                val left = (w.toFloat() - pillWidth) / 2f
                val right = left + pillWidth
                val top = if (drawerEdge == DrawerEdge.TOP) {
                    dp(4).toFloat()
                } else {
                    h.toFloat() - dp(4) - pillHeight
                }
                val bottom = top + pillHeight
                shader = LinearGradient(
                    left, top, right, top,
                    COLOR_PRIMARY, COLOR_ACCENT_DEEP,
                    Shader.TileMode.CLAMP,
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            pillPaint.shader = shader
            pillPaint.alpha = (255 * glow).toInt().coerceIn(120, 255)
            val w = width.toFloat()
            val h = height.toFloat()
            val pillWidth = if (isVerticalEdge()) handleWidthPx.toFloat() else handleHeightPx.toFloat()
            val pillHeight = if (isVerticalEdge()) handleHeightPx.toFloat() else handleWidthPx.toFloat()
            val left = when {
                isVerticalEdge() && drawerEdge == DrawerEdge.LEFT -> dp(4).toFloat()
                isVerticalEdge() -> w - dp(4) - pillWidth
                else -> (w - pillWidth) / 2f
            }
            val top = when {
                !isVerticalEdge() && drawerEdge == DrawerEdge.TOP -> dp(4).toFloat()
                !isVerticalEdge() -> h - dp(4) - pillHeight
                else -> (h - pillHeight) / 2f
            }
            val right = left + pillWidth
            val bottom = top + pillHeight
            val radius = minOf(pillWidth, pillHeight) / 2f
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, pillPaint)
        }
    }

    // Kotlin-idiomatic wrapper for Animator.addListener { onEnd }.
    private inline fun android.animation.Animator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    companion object {
        private const val TAG = "SettingsDrawer"

        // Brand palette (matches ui/theme/Color.kt dark scheme)
        private const val COLOR_PRIMARY = 0xFF7FE3FF.toInt()
        private const val COLOR_ACCENT_DEEP = 0xFF4AA8CC.toInt()
        private const val COLOR_ON_SURFACE = 0xFFE2E8EC.toInt()
        private const val COLOR_PANEL_BG = 0xF0141B20.toInt()
        private const val COLOR_PANEL_STROKE = 0x33FFFFFF.toInt()
        private const val COLOR_DIVIDER = 0x1AFFFFFF
        private const val COLOR_TRACK_BG = 0xFF232D34.toInt()
        private const val COLOR_CHIP_BG = 0x337FE3FF
        private const val COLOR_STOP_BG = 0xFF2A1519.toInt()
        private const val COLOR_STOP_STROKE = 0x66FF8FA3.toInt()
        private const val COLOR_STOP_TEXT = 0xFFFF8FA3.toInt()
    }
}
