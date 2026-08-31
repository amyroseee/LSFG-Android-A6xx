package com.lsfg.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lsfg.android.R
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.session.LsfgForegroundService

/**
 * Translucent activity used by the Automatic Overlay flow to obtain a
 * MediaProjection consent token from a non-foreground context.
 *
 * The accessibility service that detects foreground apps cannot launch
 * `createScreenCaptureIntent()` directly — Android requires an Activity context
 * for `startActivityForResult`. Hosting the prompt here also satisfies the
 * Android 12+ requirement that a `mediaProjection` foreground service be started
 * from an Activity-visible context.
 */
class ProjectionRequestActivity : Activity() {

    companion object {
        private const val TAG = "ProjReqActivity"
        private const val EXTRA_TARGET_PACKAGE = "target_package"
        private const val REQ_PROJECTION = 0x4C53

        fun buildIntent(ctx: Context, targetPackage: String): Intent =
            Intent(ctx, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
    }

    private var targetPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        if (targetPackage == null) {
            Log.w(TAG, "No target package provided — finishing")
            finish()
            return
        }
        val prefs = LsfgPreferences(this).load()
        if (prefs.captureSource == CaptureSource.SHIZUKU) {
            val intent = LsfgForegroundService.buildShizukuStartIntent(
                ctx = this,
                targetPackage = targetPackage,
                fpsCounter = prefs.fpsCounterEnabled,
            )
            ContextCompat.startForegroundService(this, intent)
            finish()
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQ_PROJECTION)
    }

    @Deprecated("Activity result for legacy startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) {
            finish()
            return
        }
        val pkg = targetPackage
        if (resultCode != RESULT_OK || data == null || pkg == null) {
            Toast.makeText(this, R.string.perm_capture_denied, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val prefs = LsfgPreferences(this).load()
        val intent = LsfgForegroundService.buildStartIntent(
            ctx = this,
            resultCode = resultCode,
            resultData = data,
            targetPackage = pkg,
            fpsCounter = prefs.fpsCounterEnabled,
            captureSource = prefs.captureSource,
        )
        ContextCompat.startForegroundService(this, intent)
        finish()
    }
}
