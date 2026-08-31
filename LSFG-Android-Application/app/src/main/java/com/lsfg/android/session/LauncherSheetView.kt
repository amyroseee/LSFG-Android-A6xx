package com.lsfg.android.session

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.lsfg.android.R

/**
 * Builds the panel View shown by [LauncherDotOverlay] when the user drags the
 * yellow handle open. This is a pure View factory — no Window is created here;
 * [LauncherDotOverlay] hosts the panel inside its own Window so the drag-to-open
 * gesture works identically to [SettingsDrawerOverlay].
 *
 * The yellow/orange palette mirrors the orange handle so the user immediately
 * associates this sheet with the Automatic Overlay flow (vs. the blue in-game
 * settings drawer).
 */
object LauncherSheetView {

    private const val COLOR_PRIMARY = 0xFFFFC857.toInt()       // warm yellow
    private const val COLOR_ACCENT_DEEP = 0xFFFF8A2B.toInt()   // deep orange
    private const val COLOR_PANEL_BG = 0xF20F1622.toInt()
    private const val COLOR_PANEL_STROKE = 0x55FFC857.toInt()
    private const val COLOR_TEXT_BODY = 0xCCFFFFFF.toInt()
    private const val COLOR_TEXT_MUTED = 0xFFE6E8EE.toInt()

    fun build(
        ctx: Context,
        targetPackage: String,
        onActivate: () -> Unit,
        onDisableForApp: () -> Unit,
        onClose: () -> Unit,
    ): View {
        val density = ctx.resources.displayMetrics.density
        val pm = ctx.packageManager
        val targetLabel = runCatching {
            val ai = pm.getApplicationInfo(targetPackage, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrDefault(targetPackage)
        val targetIcon = runCatching { pm.getApplicationIcon(targetPackage) }.getOrNull()

        // Outer container holds the rounded panel plus a small floating margin so the
        // panel does not touch the screen edge (gives it a card/sheet feel — matches
        // SettingsDrawerOverlay behaviour).
        val container = FrameLayout(ctx).apply {
            val padPx = (12 * density).toInt()
            setPadding(0, padPx, padPx, padPx)
            isClickable = true // absorb taps so they don't bubble to the scrim
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * density
                setColor(COLOR_PANEL_BG)
                setStroke((1 * density).toInt(), COLOR_PANEL_STROKE)
            }
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val padPx = (20 * density).toInt()
            setPadding(padPx, padPx, padPx, padPx)
        }

        // Header row: app icon + label.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconSize = (44 * density).toInt()
        val iconView = ImageView(ctx).apply {
            if (targetIcon != null) setImageDrawable(targetIcon)
            else setImageResource(R.drawable.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        }
        header.addView(iconView)
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = (12 * density).toInt()
            layoutParams = lp
        }
        val eyebrow = TextView(ctx).apply {
            text = ctx.getString(R.string.auto_overlay_sheet_target).uppercase()
            setTextColor(COLOR_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.08f
        }
        val title = TextView(ctx).apply {
            text = targetLabel
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        titleCol.addView(eyebrow)
        titleCol.addView(title)
        header.addView(titleCol)
        panel.addView(header)

        // Intro text.
        val intro = TextView(ctx).apply {
            text = ctx.getString(R.string.auto_overlay_sheet_intro)
            setTextColor(COLOR_TEXT_BODY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (14 * density).toInt()
            layoutParams = lp
        }
        panel.addView(intro)

        // Primary action — orange gradient, mirrors the warm palette of the handle.
        val activateBg = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(COLOR_PRIMARY, COLOR_ACCENT_DEEP),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
        }
        val activate = Button(ctx).apply {
            text = ctx.getString(R.string.auto_overlay_sheet_activate)
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isAllCaps = false
            background = activateBg
            stateListAnimator = null
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (52 * density).toInt(),
            )
            lp.topMargin = (18 * density).toInt()
            layoutParams = lp
            setOnClickListener { onActivate() }
        }
        panel.addView(activate)

        // Secondary action: disable for this app.
        val disableBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f * density
            setColor(0x00000000)
            setStroke((1 * density).toInt(), COLOR_PANEL_STROKE)
        }
        val disable = Button(ctx).apply {
            text = ctx.getString(R.string.auto_overlay_sheet_disable)
            setTextColor(COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isAllCaps = false
            background = disableBg
            stateListAnimator = null
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt(),
            )
            lp.topMargin = (10 * density).toInt()
            layoutParams = lp
            setOnClickListener { onDisableForApp() }
        }
        panel.addView(disable)

        // Close.
        val close = TextView(ctx).apply {
            text = ctx.getString(R.string.auto_overlay_sheet_close)
            setTextColor(0xFFC09A4D.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            isClickable = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * density).toInt(),
            )
            lp.topMargin = (8 * density).toInt()
            layoutParams = lp
            setOnClickListener { onClose() }
        }
        panel.addView(close)

        scroll.addView(panel)
        container.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        return container
    }
}
