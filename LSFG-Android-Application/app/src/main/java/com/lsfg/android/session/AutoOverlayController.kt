package com.lsfg.android.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.ui.ProjectionRequestActivity

/**
 * Process-wide coordinator for the "Automatic Overlay" feature.
 *
 * Watches foreground app changes (fed by [LsfgAccessibilityService]) and toggles
 * the orange edge-handle [LauncherDotOverlay] when one of the user-selected
 * target apps comes to the front. The handle is purely a UI affordance — it
 * never starts capture on its own; the user drags it open, sees the panel built
 * by [LauncherSheetView], and from there explicitly activates the full LSFG
 * capture session.
 *
 * Coordinates with [LsfgForegroundService]: when a session is active the handle
 * is hidden (the in-game settings drawer takes over); when the session stops,
 * the handle reappears if the target app is still in foreground.
 */
object AutoOverlayController {

    private const val TAG = "AutoOverlayCtrl"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var enabledApps: Set<String> = emptySet()

    @Volatile
    private var lastForegroundPkg: String? = null

    @Volatile
    private var sessionActive: Boolean = false

    @Volatile
    private var dot: LauncherDotOverlay? = null

    @Synchronized
    fun init(ctx: Context) {
        enabledApps = LsfgPreferences(ctx).getAutoEnabledApps()
        Log.i(TAG, "init — ${enabledApps.size} app(s) enabled")
    }

    @Synchronized
    fun onAutoEnabledAppsChanged(ctx: Context, newSet: Set<String>) {
        enabledApps = newSet
        val pkg = lastForegroundPkg
        if (pkg != null) {
            evaluate(ctx, pkg)
        } else if (newSet.isEmpty()) {
            hideDot()
        }
    }

    /**
     * Called by the options screen after the user flips the overlay entry mode
     * between drawer / icon button. If the launcher dot is currently visible,
     * tear it down and re-show so it picks up the new entry-affordance.
     * Re-reading the pref on every show() also means a hidden dot will pick up
     * the change next time it appears.
     */
    @Synchronized
    fun onOverlayModeChanged(ctx: Context) {
        val pkg = lastForegroundPkg ?: return
        if (dot == null) return
        Log.i(TAG, "overlay mode changed — recreating launcher dot")
        val appCtx = ctx.applicationContext
        mainHandler.post {
            dot?.hide()
            dot = null
            evaluate(appCtx, pkg)
        }
    }

    fun onForegroundPackage(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName) return
        lastForegroundPkg = pkg
        evaluate(ctx, pkg)
    }

    fun onSessionStarted(pkg: String?) {
        sessionActive = true
        Log.i(TAG, "session started for $pkg — hiding handle")
        hideDot()
    }

    fun onSessionStopped(ctx: Context) {
        sessionActive = false
        Log.i(TAG, "session stopped — re-evaluating handle for $lastForegroundPkg")
        val pkg = lastForegroundPkg
        if (pkg != null) {
            evaluate(ctx, pkg)
        }
    }

    private fun onActivateRequested(ctx: Context) {
        val target = lastForegroundPkg ?: return
        hideDot()
        val intent = ProjectionRequestActivity.buildIntent(ctx, target)
        ctx.startActivity(intent)
    }

    private fun onDisableForApp(ctx: Context) {
        val pkg = lastForegroundPkg ?: return
        val updated = enabledApps - pkg
        enabledApps = updated
        LsfgPreferences(ctx).setAutoEnabledApps(updated)
        hideDot()
    }

    private fun evaluate(ctx: Context, pkg: String) {
        if (sessionActive) {
            hideDot()
            return
        }
        if (pkg in enabledApps) {
            showDot(ctx)
        } else {
            hideDot()
        }
    }

    private fun showDot(ctx: Context) {
        val appCtx = ctx.applicationContext
        mainHandler.post {
            if (dot != null) return@post
            // Mirror the in-game settings drawer's entry mode so the user sees
            // the same affordance everywhere. Reading the pref each time the
            // dot appears means switching the option in the app picks up
            // automatically the next time the launcher is shown.
            val mode = LsfgPreferences(appCtx).load().overlayMode
            val d = LauncherDotOverlay(
                ctx = appCtx,
                targetPackageProvider = { lastForegroundPkg },
                onActivate = { onActivateRequested(appCtx) },
                onDisableForApp = { onDisableForApp(appCtx) },
                entryMode = mode,
            )
            d.show()
            dot = d
        }
    }

    private fun hideDot() {
        mainHandler.post {
            dot?.hide()
            dot = null
        }
    }
}
