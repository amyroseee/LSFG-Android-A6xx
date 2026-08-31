package com.lsfg.android.prefs

import android.content.Context
import android.content.SharedPreferences

data class LsfgConfig(
    val dllUri: String?,
    val dllDisplayName: String?,
    val shadersReady: Boolean,
    val lsfgEnabled: Boolean,
    val multiplier: Int,
    val flowScale: Float,
    val performanceMode: Boolean,
    val hdrMode: Boolean,
    val antiArtifacts: Boolean,
    /**
     * When true, the render loop loads the precompiled SPIR-V FP16 shader
     * variants from Lossless.dll (resource IDs 304..351) instead of
     * translating the DXBC FP32 set (255..302) via dxvk. Requires
     * `VK_KHR_shader_float16_int8` + `shaderFloat16` on the GPU. The UI
     * gates this via [NativeBridge.isFramegenFp16Supported] so unsupported
     * hardware never sees the toggle.
     */
    val framegenFp16: Boolean,
    val targetPackage: String?,
    val captureSource: CaptureSource,
    val legalAccepted: Boolean,
    val fpsCounterEnabled: Boolean,
    val frameGraphEnabled: Boolean,
    val drawerEdge: DrawerEdge,
    val overlayMode: OverlayMode,
    val npuPostProcessingEnabled: Boolean,
    val npuPostProcessingPreset: NpuPostProcessingPreset,
    val npuUpscaleFactor: Int,
    val npuAmount: Float,
    val npuRadius: Float,
    val npuThreshold: Float,
    val npuFp16: Boolean,
    val cpuPostProcessingEnabled: Boolean,
    val cpuPostProcessingPreset: CpuPostProcessingPreset,
    val cpuStrength: Float,
    val cpuSaturation: Float,
    val cpuVibrance: Float,
    val cpuVignette: Float,
    val gpuPostProcessingEnabled: Boolean,
    val gpuPostProcessingStage: GpuPostProcessingStage,
    val gpuPostProcessingMethod: GpuPostProcessingMethod,
    val gpuUpscaleFactor: Float,
    val gpuSharpness: Float,
    val gpuStrength: Float,
    val pacingPreset: PacingPreset,
    val vsyncAlignmentEnabled: Boolean,
    val vsyncRefreshOverride: VsyncRefreshOverride,
    val targetFpsCap: Int,
    val emaAlpha: Float,
    val outlierRatio: Float,
    val vsyncSlackMs: Float,
    val queueDepth: Int,
    val autoEnabledApps: Set<String>,
    val trustedOverlay: Boolean,
)

enum class DrawerEdge(val prefValue: String) {
    LEFT("left"),
    RIGHT("right"),
    TOP("top"),
    BOTTOM("bottom");

    companion object {
        fun fromPref(value: String?): DrawerEdge =
            values().firstOrNull { it.prefValue == value } ?: RIGHT
    }
}

/**
 * In-game settings overlay entry affordance.
 *
 *  - [ICON_BUTTON] (default): a small draggable circular icon floats on top of the
 *    target app. Tapping it opens the same settings panel from the chosen edge.
 *  - [DRAWER]: legacy edge-swipe handle. Reliable on most devices but a few OEM
 *    skins clip TYPE_APPLICATION_OVERLAY edges so the user can end up unable to
 *    drag the drawer back closed — see the warning shown in the UI.
 */
enum class OverlayMode(val prefValue: String) {
    ICON_BUTTON("icon_button"),
    DRAWER("drawer");

    companion object {
        fun fromPref(value: String?): OverlayMode =
            values().firstOrNull { it.prefValue == value } ?: ICON_BUTTON
    }
}

enum class CaptureSource(val prefValue: String) {
    MEDIA_PROJECTION("media_projection"),
    SHIZUKU("shizuku"),
    ROOT("root");

    companion object {
        fun fromPref(value: String?): CaptureSource =
            values().firstOrNull { it.prefValue == value } ?: MEDIA_PROJECTION
    }
}

/**
 * NPU enhance presets. Every entry maps to a handcrafted NNAPI graph built
 * inside nnapi_postprocess.cpp — OFF produces no graph, any other value
 * requires a dedicated NNAPI accelerator.
 */
enum class NpuPostProcessingPreset(val prefValue: String, val nativeValue: Int) {
    OFF("off", 0),
    SHARPEN("sharpen", 1),
    DETAIL_BOOST("detail_boost", 2),
    CHROMA_CLEAN("chroma_clean", 3),
    GAME_CRISP("game_crisp", 4);

    companion object {
        fun fromPref(value: String?): NpuPostProcessingPreset =
            values().firstOrNull { it.prefValue == value } ?: OFF
    }
}

/**
 * CPU post-process presets. Pure CPU pixel work that runs after the NPU
 * stage on the blit thread. Keep it cheap: these kernels fit in a couple
 * of milliseconds for 1080p.
 */
enum class CpuPostProcessingPreset(val prefValue: String, val nativeValue: Int) {
    OFF("off", 0),
    ENHANCE_LUT("enhance_lut", 1),
    WARM("warm", 2),
    COOL("cool", 3),
    VIGNETTE("vignette", 4),
    GAMER_SHARP("gamer_sharp", 5),
    CINEMATIC("cinematic", 6);

    companion object {
        fun fromPref(value: String?): CpuPostProcessingPreset =
            values().firstOrNull { it.prefValue == value } ?: OFF
    }
}

enum class GpuPostProcessingStage(val prefValue: String, val nativeValue: Int) {
    BEFORE_LSFG("before_lsfg", 0),
    AFTER_LSFG("after_lsfg", 1);

    companion object {
        fun fromPref(value: String?): GpuPostProcessingStage =
            values().firstOrNull { it.prefValue == value } ?: AFTER_LSFG
    }
}

enum class PacingPreset(val prefValue: String) {
    SMOOTH("smooth"),
    BALANCED("balanced"),
    LOW_LATENCY("low_latency"),
    CUSTOM("custom");

    companion object {
        fun fromPref(value: String?): PacingPreset =
            values().firstOrNull { it.prefValue == value } ?: BALANCED
    }
}

enum class VsyncRefreshOverride(val prefValue: String, val hz: Int) {
    AUTO("auto", 0),
    HZ_60("60", 60),
    HZ_90("90", 90),
    HZ_120("120", 120),
    HZ_144("144", 144);

    companion object {
        fun fromPref(value: String?): VsyncRefreshOverride =
            values().firstOrNull { it.prefValue == value } ?: AUTO
    }
}

enum class GpuPostProcessingMethod(val prefValue: String, val nativeValue: Int) {
    FSR1_EASU_RCAS("fsr1_easu_rcas", 0),
    AMD_CAS("amd_cas", 1),
    NVIDIA_NIS("nvidia_nis", 2),
    LANCZOS("lanczos", 3),
    BICUBIC("bicubic", 4),
    BILINEAR("bilinear", 5),
    CATMULL_ROM("catmull_rom", 6),
    MITCHELL_NETRAVALI("mitchell_netravali", 7),
    ANIME4K_ULTRAFAST("anime4k_ultrafast", 8),
    ANIME4K_RESTORE("anime4k_restore", 9),
    XBRZ("xbrz", 10),
    EDGE_DIRECTED("edge_directed", 11),
    UNSHARP_MASK("unsharp_mask", 12),
    LUMA_SHARPEN("luma_sharpen", 13),
    CONTRAST_ADAPTIVE("contrast_adaptive", 14),
    DEBAND("deband", 15);

    companion object {
        fun fromPref(value: String?): GpuPostProcessingMethod =
            values().firstOrNull { it.prefValue == value } ?: FSR1_EASU_RCAS
    }
}

object PacingDefaults {
    const val EMA_ALPHA: Float = 0.125f
    const val OUTLIER_RATIO: Float = 4.0f
    const val VSYNC_SLACK_MS: Float = 2.0f
    const val QUEUE_DEPTH: Int = 4
    const val TARGET_FPS_CAP: Int = 0
    const val VSYNC_ALIGNMENT: Boolean = true

    data class Params(val emaAlpha: Float, val outlierRatio: Float, val vsyncSlackMs: Float, val queueDepth: Int)

    fun forPreset(preset: PacingPreset, custom: Params): Params = when (preset) {
        PacingPreset.SMOOTH -> Params(0.08f, 6.0f, 3.0f, 6)
        PacingPreset.BALANCED -> Params(EMA_ALPHA, OUTLIER_RATIO, VSYNC_SLACK_MS, QUEUE_DEPTH)
        PacingPreset.LOW_LATENCY -> Params(0.2f, 3.0f, 1.5f, 2)
        PacingPreset.CUSTOM -> custom
    }
}

class LsfgPreferences(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): LsfgConfig = LsfgConfig(
        dllUri = prefs.getString(KEY_DLL_URI, null),
        dllDisplayName = prefs.getString(KEY_DLL_NAME, null),
        shadersReady = prefs.getBoolean(KEY_SHADERS_READY, false),
        lsfgEnabled = prefs.getBoolean(KEY_LSFG_ENABLED, true),
        multiplier = prefs.getInt(KEY_MULTIPLIER, 2).coerceIn(2, 8),
        flowScale = prefs.getFloat(KEY_FLOW_SCALE, 1.0f).coerceIn(0.25f, 1.0f),
        performanceMode = prefs.getBoolean(KEY_PERF, true),
        hdrMode = prefs.getBoolean(KEY_HDR, false),
        antiArtifacts = prefs.getBoolean(KEY_ANTI_ARTIFACTS, false),
        framegenFp16 = prefs.getBoolean(KEY_FRAMEGEN_FP16, false),
        targetPackage = prefs.getString(KEY_TARGET, null),
        captureSource = CaptureSource.fromPref(prefs.getString(KEY_CAPTURE_SOURCE, null)),
        legalAccepted = prefs.getBoolean(KEY_LEGAL, false),
        fpsCounterEnabled = prefs.getBoolean(KEY_FPS_COUNTER, false),
        frameGraphEnabled = prefs.getBoolean(KEY_FRAME_GRAPH, false),
        drawerEdge = DrawerEdge.fromPref(prefs.getString(KEY_DRAWER_EDGE, null)),
        overlayMode = OverlayMode.fromPref(prefs.getString(KEY_OVERLAY_MODE, null)),
        npuPostProcessingEnabled = prefs.getBoolean(KEY_NPU_POST, false),
        npuPostProcessingPreset = NpuPostProcessingPreset.fromPref(prefs.getString(KEY_NPU_PRESET, null)),
        npuUpscaleFactor = prefs.getInt(KEY_NPU_UPSCALE, 1).coerceIn(1, 2),
        npuAmount = prefs.getFloat(KEY_NPU_AMOUNT, 0.5f).coerceIn(0f, 1f),
        npuRadius = prefs.getFloat(KEY_NPU_RADIUS, 1.0f).coerceIn(0.5f, 2.0f),
        npuThreshold = prefs.getFloat(KEY_NPU_THRESHOLD, 0.0f).coerceIn(0f, 1f),
        npuFp16 = prefs.getBoolean(KEY_NPU_FP16, true),
        cpuPostProcessingEnabled = prefs.getBoolean(KEY_CPU_POST, false),
        cpuPostProcessingPreset = CpuPostProcessingPreset.fromPref(prefs.getString(KEY_CPU_PRESET, null)),
        cpuStrength = prefs.getFloat(KEY_CPU_STRENGTH, 0.5f).coerceIn(0f, 1f),
        cpuSaturation = prefs.getFloat(KEY_CPU_SATURATION, 0.5f).coerceIn(0f, 1f),
        cpuVibrance = prefs.getFloat(KEY_CPU_VIBRANCE, 0.0f).coerceIn(0f, 1f),
        cpuVignette = prefs.getFloat(KEY_CPU_VIGNETTE, 0.0f).coerceIn(0f, 1f),
        gpuPostProcessingEnabled = prefs.getBoolean(KEY_GPU_POST, false),
        gpuPostProcessingStage = GpuPostProcessingStage.fromPref(prefs.getString(KEY_GPU_STAGE, null)),
        gpuPostProcessingMethod = GpuPostProcessingMethod.fromPref(prefs.getString(KEY_GPU_METHOD, null)),
        gpuUpscaleFactor = prefs.getFloat(KEY_GPU_UPSCALE, 1.0f).coerceIn(1.0f, 2.0f),
        gpuSharpness = prefs.getFloat(KEY_GPU_SHARPNESS, 0.5f).coerceIn(0f, 1f),
        gpuStrength = prefs.getFloat(KEY_GPU_STRENGTH, 0.5f).coerceIn(0f, 1f),
        pacingPreset = PacingPreset.fromPref(prefs.getString(KEY_PACING_PRESET, null)),
        vsyncAlignmentEnabled = prefs.getBoolean(KEY_VSYNC_ALIGN, PacingDefaults.VSYNC_ALIGNMENT),
        vsyncRefreshOverride = VsyncRefreshOverride.fromPref(prefs.getString(KEY_VSYNC_OVERRIDE, null)),
        targetFpsCap = prefs.getInt(KEY_TARGET_FPS_CAP, PacingDefaults.TARGET_FPS_CAP).coerceIn(0, 240),
        emaAlpha = prefs.getFloat(KEY_EMA_ALPHA, PacingDefaults.EMA_ALPHA).coerceIn(0.05f, 0.5f),
        outlierRatio = prefs.getFloat(KEY_OUTLIER_RATIO, PacingDefaults.OUTLIER_RATIO).coerceIn(2.0f, 8.0f),
        vsyncSlackMs = prefs.getFloat(KEY_VSYNC_SLACK_MS, PacingDefaults.VSYNC_SLACK_MS).coerceIn(1.0f, 5.0f),
        queueDepth = prefs.getInt(KEY_QUEUE_DEPTH, PacingDefaults.QUEUE_DEPTH).coerceIn(2, 6),
        autoEnabledApps = decodeAutoEnabledApps(prefs.getString(KEY_AUTO_ENABLED_APPS, null)),
        trustedOverlay = prefs.getBoolean(KEY_TRUSTED_OVERLAY, false),
    )

    fun getAutoEnabledApps(): Set<String> =
        decodeAutoEnabledApps(prefs.getString(KEY_AUTO_ENABLED_APPS, null))

    fun setAutoEnabledApps(value: Set<String>) {
        prefs.edit()
            .putString(KEY_AUTO_ENABLED_APPS, value.joinToString("\n"))
            .apply()
    }

    fun setDll(uri: String, displayName: String) = prefs.edit()
        .putString(KEY_DLL_URI, uri)
        .putString(KEY_DLL_NAME, displayName)
        .putBoolean(KEY_SHADERS_READY, false)
        .apply()

    fun setShadersReady(ready: Boolean) = prefs.edit()
        .putBoolean(KEY_SHADERS_READY, ready)
        .apply()

    fun setLsfgEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_LSFG_ENABLED, value).apply()
    fun setMultiplier(value: Int) = prefs.edit().putInt(KEY_MULTIPLIER, value).apply()
    fun setFlowScale(value: Float) = prefs.edit().putFloat(KEY_FLOW_SCALE, value).apply()
    fun setPerformance(value: Boolean) = prefs.edit().putBoolean(KEY_PERF, value).apply()
    fun setHdr(value: Boolean) = prefs.edit().putBoolean(KEY_HDR, value).apply()
    fun setAntiArtifacts(value: Boolean) = prefs.edit().putBoolean(KEY_ANTI_ARTIFACTS, value).apply()
    fun setFramegenFp16(value: Boolean) = prefs.edit().putBoolean(KEY_FRAMEGEN_FP16, value).apply()
    fun setTargetPackage(pkg: String?) = prefs.edit().putString(KEY_TARGET, pkg).apply()
    fun setCaptureSource(value: CaptureSource) = prefs.edit()
        .putString(KEY_CAPTURE_SOURCE, value.prefValue)
        .apply()
    fun setLegalAccepted(value: Boolean) = prefs.edit().putBoolean(KEY_LEGAL, value).apply()
    fun isTutorialPromptShown(): Boolean = prefs.getBoolean(KEY_TUTORIAL_PROMPT_SHOWN, false)
    fun setTutorialPromptShown(value: Boolean) =
        prefs.edit().putBoolean(KEY_TUTORIAL_PROMPT_SHOWN, value).apply()
    fun setFpsCounterEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_FPS_COUNTER, value).apply()
    fun setFrameGraphEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_FRAME_GRAPH, value).apply()
    fun setDrawerEdge(value: DrawerEdge) = prefs.edit()
        .putString(KEY_DRAWER_EDGE, value.prefValue)
        .apply()
    fun setOverlayMode(value: OverlayMode) = prefs.edit()
        .putString(KEY_OVERLAY_MODE, value.prefValue)
        .apply()
    fun setNpuPostProcessingEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_NPU_POST, value).apply()
    fun setNpuPostProcessingPreset(value: NpuPostProcessingPreset) = prefs.edit()
        .putString(KEY_NPU_PRESET, value.prefValue)
        .apply()
    fun setNpuUpscaleFactor(value: Int) = prefs.edit().putInt(KEY_NPU_UPSCALE, value.coerceIn(1, 2)).apply()
    fun setNpuAmount(value: Float) = prefs.edit().putFloat(KEY_NPU_AMOUNT, value.coerceIn(0f, 1f)).apply()
    fun setNpuRadius(value: Float) = prefs.edit().putFloat(KEY_NPU_RADIUS, value.coerceIn(0.5f, 2.0f)).apply()
    fun setNpuThreshold(value: Float) = prefs.edit().putFloat(KEY_NPU_THRESHOLD, value.coerceIn(0f, 1f)).apply()
    fun setNpuFp16(value: Boolean) = prefs.edit().putBoolean(KEY_NPU_FP16, value).apply()
    fun setCpuPostProcessingEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_CPU_POST, value).apply()
    fun setCpuPostProcessingPreset(value: CpuPostProcessingPreset) = prefs.edit()
        .putString(KEY_CPU_PRESET, value.prefValue)
        .apply()
    fun setCpuStrength(value: Float) = prefs.edit().putFloat(KEY_CPU_STRENGTH, value.coerceIn(0f, 1f)).apply()
    fun setCpuSaturation(value: Float) = prefs.edit().putFloat(KEY_CPU_SATURATION, value.coerceIn(0f, 1f)).apply()
    fun setCpuVibrance(value: Float) = prefs.edit().putFloat(KEY_CPU_VIBRANCE, value.coerceIn(0f, 1f)).apply()
    fun setCpuVignette(value: Float) = prefs.edit().putFloat(KEY_CPU_VIGNETTE, value.coerceIn(0f, 1f)).apply()
    fun setGpuPostProcessingEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_GPU_POST, value).apply()
    fun setGpuPostProcessingStage(value: GpuPostProcessingStage) = prefs.edit()
        .putString(KEY_GPU_STAGE, value.prefValue)
        .apply()
    fun setGpuPostProcessingMethod(value: GpuPostProcessingMethod) = prefs.edit()
        .putString(KEY_GPU_METHOD, value.prefValue)
        .apply()
    fun setGpuUpscaleFactor(value: Float) = prefs.edit()
        .putFloat(KEY_GPU_UPSCALE, value.coerceIn(1.0f, 2.0f))
        .apply()
    fun setGpuSharpness(value: Float) = prefs.edit()
        .putFloat(KEY_GPU_SHARPNESS, value.coerceIn(0f, 1f))
        .apply()
    fun setGpuStrength(value: Float) = prefs.edit()
        .putFloat(KEY_GPU_STRENGTH, value.coerceIn(0f, 1f))
        .apply()

    fun setPacingPreset(value: PacingPreset) = prefs.edit()
        .putString(KEY_PACING_PRESET, value.prefValue)
        .apply()
    fun setVsyncAlignmentEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_VSYNC_ALIGN, value).apply()
    fun setVsyncRefreshOverride(value: VsyncRefreshOverride) = prefs.edit()
        .putString(KEY_VSYNC_OVERRIDE, value.prefValue)
        .apply()
    fun setTargetFpsCap(value: Int) = prefs.edit().putInt(KEY_TARGET_FPS_CAP, value.coerceIn(0, 240)).apply()
    fun setEmaAlpha(value: Float) = prefs.edit().putFloat(KEY_EMA_ALPHA, value.coerceIn(0.05f, 0.5f)).apply()
    fun setOutlierRatio(value: Float) = prefs.edit().putFloat(KEY_OUTLIER_RATIO, value.coerceIn(2.0f, 8.0f)).apply()
    fun setVsyncSlackMs(value: Float) = prefs.edit().putFloat(KEY_VSYNC_SLACK_MS, value.coerceIn(1.0f, 5.0f)).apply()
    fun setQueueDepth(value: Int) = prefs.edit().putInt(KEY_QUEUE_DEPTH, value.coerceIn(2, 6)).apply()

    fun setTrustedOverlay(value: Boolean) = prefs.edit().putBoolean(KEY_TRUSTED_OVERLAY, value).apply()

    companion object {
        private const val FILE = "lsfg_prefs"
        private const val KEY_DLL_URI = "dll_uri"
        private const val KEY_DLL_NAME = "dll_name"
        private const val KEY_SHADERS_READY = "shaders_ready"
        private const val KEY_LSFG_ENABLED = "lsfg_enabled"
        private const val KEY_MULTIPLIER = "multiplier"
        private const val KEY_FLOW_SCALE = "flow_scale"
        private const val KEY_PERF = "performance"
        private const val KEY_HDR = "hdr"
        private const val KEY_ANTI_ARTIFACTS = "anti_artifacts"
        private const val KEY_FRAMEGEN_FP16 = "framegen_fp16"
        private const val KEY_TARGET = "target_pkg"
        private const val KEY_CAPTURE_SOURCE = "capture_source"
        private const val KEY_LEGAL = "legal_accepted"
        private const val KEY_TUTORIAL_PROMPT_SHOWN = "tutorial_prompt_shown"
        private const val KEY_FPS_COUNTER = "fps_counter"
        private const val KEY_FRAME_GRAPH = "frame_graph"
        private const val KEY_DRAWER_EDGE = "drawer_edge"
        private const val KEY_OVERLAY_MODE = "overlay_mode"
        private const val KEY_NPU_POST = "npu_post_processing"
        private const val KEY_NPU_PRESET = "npu_post_processing_preset"
        private const val KEY_NPU_UPSCALE = "npu_upscale_factor"
        private const val KEY_NPU_AMOUNT = "npu_amount"
        private const val KEY_NPU_RADIUS = "npu_radius"
        private const val KEY_NPU_THRESHOLD = "npu_threshold"
        private const val KEY_NPU_FP16 = "npu_fp16"
        private const val KEY_CPU_POST = "cpu_post_processing"
        private const val KEY_CPU_PRESET = "cpu_post_processing_preset"
        private const val KEY_CPU_STRENGTH = "cpu_strength"
        private const val KEY_CPU_SATURATION = "cpu_saturation"
        private const val KEY_CPU_VIBRANCE = "cpu_vibrance"
        private const val KEY_CPU_VIGNETTE = "cpu_vignette"
        private const val KEY_GPU_POST = "gpu_post_processing"
        private const val KEY_GPU_STAGE = "gpu_post_processing_stage"
        private const val KEY_GPU_METHOD = "gpu_post_processing_method"
        private const val KEY_GPU_UPSCALE = "gpu_upscale_factor"
        private const val KEY_GPU_SHARPNESS = "gpu_sharpness"
        private const val KEY_GPU_STRENGTH = "gpu_strength"
        private const val KEY_PACING_PRESET = "pacing_preset"
        private const val KEY_VSYNC_ALIGN = "vsync_alignment"
        private const val KEY_VSYNC_OVERRIDE = "vsync_refresh_override"
        private const val KEY_TARGET_FPS_CAP = "target_fps_cap"
        private const val KEY_EMA_ALPHA = "pacing_ema_alpha"
        private const val KEY_OUTLIER_RATIO = "pacing_outlier_ratio"
        private const val KEY_VSYNC_SLACK_MS = "pacing_vsync_slack_ms"
        private const val KEY_QUEUE_DEPTH = "pacing_queue_depth"
        private const val KEY_AUTO_ENABLED_APPS = "auto_enabled_apps"
        private const val KEY_TRUSTED_OVERLAY = "trusted_overlay"

        private fun decodeAutoEnabledApps(raw: String?): Set<String> {
            if (raw.isNullOrEmpty()) return emptySet()
            return raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
        }
    }
}
