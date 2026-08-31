package com.lsfg.android.session

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.lsfg.android.prefs.DrawerEdge
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.OverlayMode

/**
 * "Automatic Overlay" entry point. Behaviour and visuals mirror the in-game
 * [SettingsDrawerOverlay] one-to-one — same DRAWER (edge handle) and
 * ICON_BUTTON (floating draggable icon) entry modes, drag-to-open with snap,
 * dim scrim, sliding panel — with two differences:
 *
 *  - palette is yellow/orange so users can distinguish the two overlays;
 *  - the panel content is the [LauncherSheetView] (Activate / Disable for app /
 *    Close) instead of the full settings drawer.
 *
 * The entry mode (drawer vs icon) is selected by the user from the in-app
 * options screen and is shared with [SettingsDrawerOverlay] via the
 * [OverlayMode] preference, so toggling it in one place flips both.
 *
 * Hosting the panel inside the same Window as the entry affordance is what
 * makes the drag gesture work at all — touches that cross the slop threshold
 * continue to flow to the panel as it expands. A separate Window for the
 * sheet cannot receive the drag because the gesture starts in a different
 * window's input pipeline.
 */
class LauncherDotOverlay(
    private val ctx: Context,
    private val targetPackageProvider: () -> String?,
    private val onActivate: () -> Unit,
    private val onDisableForApp: () -> Unit,
    private val entryMode: OverlayMode = OverlayMode.ICON_BUTTON,
) {

    companion object {
        private const val TAG = "LauncherHandle"
        // Yellow/orange palette — mirrors the blue palette in SettingsDrawerOverlay.
        private const val COLOR_PRIMARY = 0xFFFFC857.toInt()
        private const val COLOR_ACCENT_DEEP = 0xFFFF8A2B.toInt()
    }

    private var hostWm: WindowManager? = null
    private var root: FrameLayout? = null
    private var handleView: HandleView? = null
    private var scrim: View? = null
    private var panelContainer: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var screenW: Int = 0
    private var screenH: Int = 0
    private var edgeStripWidthPx: Int = 0
    private var handleWidthPx: Int = 0
    private var handleHeightPx: Int = 0
    private var panelWidthPx: Int = 0
    private var panelHeightPx: Int = 0
    private var drawerEdge: DrawerEdge = DrawerEdge.RIGHT

    // The launcher handle lives outside any Service / Activity, so we listen
    // for display rotations directly via DisplayManager. Without this the
    // collapsed strip / icon ends up off-screen the first time the user
    // rotates the phone while the handle is showing — same root cause as
    // the in-game drawer crash, but no Service.onConfigurationChanged path
    // can reach us here because AutoOverlayController owns the lifecycle.
    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayListener: DisplayManager.DisplayListener? = null
    private var hostDisplayId: Int = Display.DEFAULT_DISPLAY

    /** 0 = collapsed, 1 = fully expanded. */
    private var progress: Float = 0f
    private var expanded: Boolean = false
    private var dragActive: Boolean = false
    private var dragStartX: Float = 0f
    private var dragStartY: Float = 0f
    private var dragStartProgress: Float = 0f
    private var settleAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    // ICON_BUTTON mode state — mirrors SettingsDrawerOverlay.
    private var iconButton: View? = null
    private var iconSizePx: Int = 0
    private var iconX: Int = 0
    private var iconY: Int = 0
    private var iconDragStartRawX: Float = 0f
    private var iconDragStartRawY: Float = 0f
    private var iconDragStartX: Int = 0
    private var iconDragStartY: Int = 0
    private var iconDragMoved: Boolean = false
    // Coalesce icon-drag updateViewLayout calls to one per vsync. Touch events
    // can fire at 240+ Hz on flagships and each updateViewLayout is a Binder RPC
    // to WindowManagerService, so without this the drag floods the system
    // window thread.
    private var iconDragFramePending: Boolean = false
    private val iconDragFrameCallback = Choreographer.FrameCallback {
        iconDragFramePending = false
        val lp = params
        val r = root
        val wm = hostWm
        if (lp != null && r != null && wm != null && r.isAttachedToWindow &&
            lp.width != WindowManager.LayoutParams.MATCH_PARENT) {
            lp.x = iconX
            lp.y = iconY
            runCatching { wm.updateViewLayout(r, lp) }
                .onFailure { Log.w(TAG, "icon drag updateViewLayout failed", it) }
        }
    }

    fun show() {
        if (root != null) return

        val a11y = LsfgAccessibilityService.instance
        val hostCtx: Context = a11y ?: ctx
        val wm = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        hostWm = wm

        val dm = ctx.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        edgeStripWidthPx = dp(16)
        handleWidthPx = dp(5)
        handleHeightPx = dp(68)
        iconSizePx = dp(48)
        panelWidthPx = minOf(dp(340), (screenW * 0.85f).toInt())
        panelHeightPx = minOf(dp(340), (screenH * 0.85f).toInt())
        drawerEdge = LsfgPreferences(ctx).load().drawerEdge
        // Icon defaults: stuck to the right edge, vertically centred.
        iconX = (screenW - iconSizePx - dp(8)).coerceAtLeast(0)
        iconY = ((screenH - iconSizePx) / 2).coerceAtLeast(0)

        val layoutType = when {
            a11y != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        // Start narrow so only the entry affordance captures touches. When the user
        // opens the panel we expand to MATCH_PARENT so the scrim + panel can be
        // laid out across the whole screen.
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

        // Scrim — dims the game while the drawer is open.
        val scrimView = View(ctx).apply {
            setBackgroundColor(0xFF000000.toInt())
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

        val panelView = buildPanelHost()
        panelContainer = panelView
        panelView.visibility = View.GONE
        applyPanelProgress(panelView, 0f)
        rootLayout.addView(panelView, panelLayoutParams())

        if (entryMode == OverlayMode.DRAWER) {
            val handle = HandleView(ctx).apply {
                isClickable = false
                isFocusable = false
            }
            handleView = handle
            rootLayout.addView(handle, handleLayoutParams())
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
        registerDisplayListener()
        Log.i(TAG, "Launcher entry shown (mode=$entryMode)")
    }

    fun hide() {
        val r = root ?: return
        val wm = hostWm
        unregisterDisplayListener()
        settleAnimator?.cancel()
        settleAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
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
        hostWm = null
        expanded = false
        progress = 0f
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != hostDisplayId) return
                mainHandler.post { relayoutForCurrentDisplay() }
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        runCatching { dm.registerDisplayListener(listener, mainHandler) }
            .onSuccess { displayListener = listener }
            .onFailure { Log.w(TAG, "registerDisplayListener failed", it) }
    }

    private fun unregisterDisplayListener() {
        val l = displayListener ?: return
        displayListener = null
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        runCatching { dm.unregisterDisplayListener(l) }
    }

    private fun relayoutForCurrentDisplay() {
        val wm = hostWm ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return

        settleAnimator?.cancel()
        settleAnimator = null

        val dm = ctx.resources.displayMetrics
        val newW = dm.widthPixels
        val newH = dm.heightPixels
        if (newW <= 0 || newH <= 0) return
        if (newW == screenW && newH == screenH) return
        screenW = newW
        screenH = newH

        panelWidthPx = minOf(dp(340), (screenW * 0.85f).toInt())
        panelHeightPx = minOf(dp(340), (screenH * 0.85f).toInt())

        // Reload preferred edge in case the user toggled it via the in-game
        // drawer while the handle was hidden, then rebuild children that are
        // edge-dependent.
        drawerEdge = LsfgPreferences(ctx).load().drawerEdge
        panelContainer?.let { pv ->
            pv.layoutParams = panelLayoutParams()
            applyPanelProgress(pv, progress)
        }
        handleView?.layoutParams = handleLayoutParams()

        // Clamp icon back inside new screen bounds so it stays visible after a
        // rotation that shrank the relevant axis.
        if (entryMode == OverlayMode.ICON_BUTTON) {
            iconX = iconX.coerceIn(0, (screenW - iconSizePx).coerceAtLeast(0))
            iconY = iconY.coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
        }

        if (lp.width != WindowManager.LayoutParams.MATCH_PARENT ||
            lp.height != WindowManager.LayoutParams.MATCH_PARENT) {
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
        Log.i(TAG, "Launcher relayout for ${newW}x${newH} mode=$entryMode")
    }

    /** Programmatically close the drawer (used after Activate / Disable / Close). */
    fun collapse() {
        animateTo(0f)
    }

    private fun buildPanelHost(): View {
        val pkg = targetPackageProvider() ?: ""
        return LauncherSheetView.build(
            ctx = ctx,
            targetPackage = pkg,
            onActivate = onActivate,
            onDisableForApp = onDisableForApp,
            onClose = { animateTo(0f) },
        )
    }

    // --- drag / snap behaviour --------------------------------------------------

    private fun attachEdgeSwipeBehavior(strip: View) {
        val slop = dp(8)
        strip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                        // A bare tap on the strip pulls the drawer open — this matches
                        // user expectation when the strip is so narrow that intentional
                        // dragging from the edge is finicky on tall phones.
                        expandWindow()
                        animateTo(1f)
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
        if (target == 0f) {
            anim.doOnEnd {
                if (progress == 0f) collapseWindowIfNeeded()
            }
        }
    }

    private fun expandWindow() {
        val wm = hostWm ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return
        if (lp.width == WindowManager.LayoutParams.MATCH_PARENT &&
            lp.height == WindowManager.LayoutParams.MATCH_PARENT) return
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        // ICON_BUTTON: collapsed window was offset to the icon's position; when
        // expanded to fullscreen reset x/y so scrim+panel cover the entire display.
        if (entryMode == OverlayMode.ICON_BUTTON) {
            lp.x = 0
            lp.y = 0
        }
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "expandWindow failed", it) }
    }

    private fun collapseWindowIfNeeded() {
        val wm = hostWm ?: return
        val r = root ?: return
        if (!r.isAttachedToWindow) return
        val lp = params ?: return
        val targetW = collapsedWindowWidth()
        val targetH = collapsedWindowHeight()
        val needsResize = lp.width != targetW || lp.height != targetH
        val needsReposition = entryMode == OverlayMode.ICON_BUTTON && (lp.x != iconX || lp.y != iconY)
        if (!needsResize && !needsReposition) return
        lp.width = targetW
        lp.height = targetH
        if (entryMode == OverlayMode.ICON_BUTTON) {
            lp.x = iconX
            lp.y = iconY
        }
        scrim?.visibility = View.GONE
        panelContainer?.visibility = View.GONE
        runCatching { wm.updateViewLayout(r, lp) }
            .onFailure { Log.w(TAG, "collapseWindowIfNeeded failed", it) }
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

    private fun dragDeltaTowardCenter(ev: MotionEvent): Float = when (drawerEdge) {
        DrawerEdge.LEFT -> ev.rawX - dragStartX
        DrawerEdge.RIGHT -> dragStartX - ev.rawX
        DrawerEdge.TOP -> ev.rawY - dragStartY
        DrawerEdge.BOTTOM -> dragStartY - ev.rawY
    }

    private fun startHandlePulse() {
        pulseAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(0.72f, 1f).apply {
            duration = 3200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val v = it.animatedValue as Float
                handleView?.setGlow(v)
            }
        }
        pulseAnimator = anim
        anim.start()
    }

    private fun stopHandlePulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        handleView?.setGlow(1f)
    }

    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private inline fun android.animation.Animator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    /**
     * Vertical rounded pill flush to the right edge with a top→bottom yellow→orange
     * gradient. Identical geometry to [SettingsDrawerOverlay.HandleView]; only the
     * colors differ.
     */
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

    // --- Icon button entry mode --------------------------------------------------------

    /**
     * Floating circular icon (LSFG app icon on a translucent disc with a brand-
     * coloured stroke) used as the entry affordance for the Automatic Overlay
     * launcher panel when [entryMode] == ICON_BUTTON. Mirrors the in-game
     * [SettingsDrawerOverlay] icon visual but uses the launcher's yellow/orange
     * accent so users can tell the two overlays apart.
     */
    private fun buildIconButton(): View {
        val container = FrameLayout(ctx).apply {
            isClickable = true
            isFocusable = false
        }
        // Show the app launcher icon as-is (square with rounded corners) so
        // the floating button matches the icon on the home screen exactly.
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
     * Drag the floating icon to reposition; tap (no significant movement) to open
     * the panel. After release we snap horizontally to the nearer screen edge so
     * the icon doesn't end up floating mid-screen, and we update [drawerEdge] so
     * the panel slides in from the same side on next open.
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
                        // Cancel any in-flight Choreographer drag callback so it
                        // doesn't race with the snap-to-edge updateViewLayout below.
                        if (iconDragFramePending) {
                            Choreographer.getInstance().removeFrameCallback(iconDragFrameCallback)
                            iconDragFramePending = false
                        }
                        val centerX = iconX + iconSizePx / 2
                        val centerY = iconY + iconSizePx / 2
                        val nearLeft = centerX < screenW / 2
                        iconX = if (nearLeft) dp(8) else (screenW - iconSizePx - dp(8)).coerceAtLeast(0)
                        iconY = iconY.coerceIn(0, (screenH - iconSizePx).coerceAtLeast(0))
                        // Pick the closer edge for panel slide direction.
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
                        panelContainer?.let { pv ->
                            pv.layoutParams = panelLayoutParams()
                            applyPanelProgress(pv, progress)
                        }
                        val lp = params
                        val r = root
                        val wm = hostWm
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
}
