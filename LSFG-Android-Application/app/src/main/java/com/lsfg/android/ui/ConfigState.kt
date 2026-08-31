package com.lsfg.android.ui

import com.lsfg.android.prefs.LsfgConfig
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.CpuPostProcessingPreset
import com.lsfg.android.prefs.DrawerEdge
import com.lsfg.android.prefs.GpuPostProcessingMethod
import com.lsfg.android.prefs.GpuPostProcessingStage
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.NpuPostProcessingPreset
import com.lsfg.android.prefs.OverlayMode
import com.lsfg.android.prefs.PacingDefaults
import com.lsfg.android.prefs.PacingPreset
import com.lsfg.android.prefs.VsyncRefreshOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Produces a StateFlow bound to [LsfgPreferences] content. Each screen that mutates prefs
 * should call [refresh] after commit so the home screen reflects updates.
 *
 * This is intentionally simple (manual refresh) to avoid pulling in DataStore for Phase 1.
 */
private val shared: MutableStateFlow<LsfgConfig> = MutableStateFlow(
    LsfgConfig(
        dllUri = null,
        dllDisplayName = null,
        shadersReady = false,
        lsfgEnabled = true,
        multiplier = 2,
        flowScale = 1.0f,
        performanceMode = true,
        hdrMode = false,
        antiArtifacts = false,
        framegenFp16 = false,
        targetPackage = null,
        captureSource = CaptureSource.MEDIA_PROJECTION,
        legalAccepted = false,
        fpsCounterEnabled = false,
        frameGraphEnabled = false,
        drawerEdge = DrawerEdge.RIGHT,
        overlayMode = OverlayMode.ICON_BUTTON,
        npuPostProcessingEnabled = false,
        npuPostProcessingPreset = NpuPostProcessingPreset.OFF,
        npuUpscaleFactor = 1,
        npuAmount = 0.5f,
        npuRadius = 1.0f,
        npuThreshold = 0.0f,
        npuFp16 = true,
        cpuPostProcessingEnabled = false,
        cpuPostProcessingPreset = CpuPostProcessingPreset.OFF,
        cpuStrength = 0.5f,
        cpuSaturation = 0.5f,
        cpuVibrance = 0.0f,
        cpuVignette = 0.0f,
        gpuPostProcessingEnabled = false,
        gpuPostProcessingStage = GpuPostProcessingStage.AFTER_LSFG,
        gpuPostProcessingMethod = GpuPostProcessingMethod.FSR1_EASU_RCAS,
        gpuUpscaleFactor = 1.0f,
        gpuSharpness = 0.5f,
        gpuStrength = 0.5f,
        pacingPreset = PacingPreset.BALANCED,
        vsyncAlignmentEnabled = PacingDefaults.VSYNC_ALIGNMENT,
        vsyncRefreshOverride = VsyncRefreshOverride.AUTO,
        targetFpsCap = PacingDefaults.TARGET_FPS_CAP,
        emaAlpha = PacingDefaults.EMA_ALPHA,
        outlierRatio = PacingDefaults.OUTLIER_RATIO,
        vsyncSlackMs = PacingDefaults.VSYNC_SLACK_MS,
        queueDepth = PacingDefaults.QUEUE_DEPTH,
        autoEnabledApps = emptySet(),
        trustedOverlay = false,
    )
)

fun produceConfigState(prefs: LsfgPreferences): StateFlow<LsfgConfig> {
    shared.value = prefs.load()
    return shared
}

fun refreshConfigState(prefs: LsfgPreferences) {
    shared.value = prefs.load()
}
