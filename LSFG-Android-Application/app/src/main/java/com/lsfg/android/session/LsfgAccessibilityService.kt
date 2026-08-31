package com.lsfg.android.session

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Required declaration so the user can enable the "LSFG Touch Passthrough" service
 * from Settings → Accessibility. The gesture-forwarding implementation is still
 * stubbed; the service additionally drives the Automatic Overlay feature by
 * forwarding TYPE_WINDOW_STATE_CHANGED events to [AutoOverlayController].
 */
class LsfgAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Filter out system UI / IMEs that would otherwise flap the controller.
        if (pkg.startsWith("com.android.systemui")) return
        if (pkg == packageName) return
        AutoOverlayController.onForegroundPackage(this, pkg)
    }

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: LsfgAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AutoOverlayController.init(applicationContext)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
