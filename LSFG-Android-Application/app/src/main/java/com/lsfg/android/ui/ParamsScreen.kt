package com.lsfg.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.CpuPostProcessingPreset
import com.lsfg.android.prefs.DrawerEdge
import com.lsfg.android.prefs.GpuPostProcessingMethod
import com.lsfg.android.prefs.GpuPostProcessingStage
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.NpuPostProcessingPreset
import com.lsfg.android.prefs.OverlayMode
import com.lsfg.android.session.AutoOverlayController
import com.lsfg.android.session.NativeBridge
import com.lsfg.android.ui.components.IconBadge
import com.lsfg.android.ui.components.LsfgCard
import com.lsfg.android.ui.components.LsfgTopBar
import com.lsfg.android.ui.components.SectionHeader
import com.lsfg.android.ui.components.ToggleRow
import com.lsfg.android.ui.components.ValueSlider
import com.lsfg.android.ui.theme.LsfgPrimary

// ----------------------------------------------------------------------------------------
// Frame generation & pacing — consolidated screen
// ----------------------------------------------------------------------------------------

@Composable
fun ParamsFrameGenPacingScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()
    // The FP16 toggle is only meaningful when (a) the GPU advertises shaderFloat16
    // and (b) the FP16 SPIR-V cache from Lossless.dll has been populated. The
    // native probe ANDs both. Cached for the screen's lifetime — recomputed on
    // re-entry, which covers the post-DLL-pick / post-extract case naturally.
    val fp16Available = remember {
        val cacheDir = java.io.File(ctx.filesDir, "spirv").absolutePath
        runCatching { NativeBridge.isFramegenFp16Supported(cacheDir) }.getOrDefault(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_framegen_pacing),
            onBack = { nav.popBackStack() },
        )

        // ---- Frame generation -------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_frame_generation), title = null)
            Spacer(Modifier.height(4.dp))

            ToggleRow(
                icon = Icons.Filled.FlashOn,
                title = "LSFG Frame Gen",
                description = "Master toggle for frame generation. Off = raw capture passthrough.",
                checked = state.lsfgEnabled,
                onCheckedChange = {
                    prefs.setLsfgEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            ValueSlider(
                title = stringResource(R.string.param_multiplier),
                valueDisplay = "${state.multiplier}×",
                description = stringResource(R.string.param_multiplier_desc),
                value = state.multiplier.toFloat(),
                range = 2f..8f,
                steps = 5,
                leadingIcon = Icons.Filled.Timeline,
                onValueChange = {
                    prefs.setMultiplier(it.toInt().coerceIn(2, 8))
                    refreshConfigState(prefs)
                },
            )

            ValueSlider(
                title = stringResource(R.string.param_flow_scale),
                valueDisplay = "%.2f".format(state.flowScale),
                description = stringResource(R.string.param_flow_scale_desc),
                value = state.flowScale,
                range = 0.25f..1.0f,
                steps = 0,
                leadingIcon = Icons.AutoMirrored.Filled.ShowChart,
                onValueChange = {
                    prefs.setFlowScale(it)
                    refreshConfigState(prefs)
                },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.Speed,
                title = stringResource(R.string.param_performance_mode),
                description = stringResource(R.string.param_performance_mode_desc),
                checked = state.performanceMode,
                onCheckedChange = {
                    prefs.setPerformance(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.HdrOn,
                title = stringResource(R.string.param_hdr),
                description = stringResource(R.string.param_hdr_desc),
                checked = state.hdrMode,
                onCheckedChange = {
                    prefs.setHdr(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.Filled.AutoFixHigh,
                title = stringResource(R.string.param_anti_artifacts),
                description = stringResource(R.string.param_anti_artifacts_desc),
                checked = state.antiArtifacts,
                onCheckedChange = {
                    prefs.setAntiArtifacts(it)
                    refreshConfigState(prefs)
                },
            )
            if (fp16Available) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow(
                    icon = Icons.Filled.Memory,
                    title = stringResource(R.string.param_framegen_fp16),
                    description = stringResource(R.string.param_framegen_fp16_desc),
                    checked = state.framegenFp16,
                    onCheckedChange = {
                        prefs.setFramegenFp16(it)
                        refreshConfigState(prefs)
                    },
                )
            }
        }

        // ---- Pacing -----------------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_pacing), title = null)
            Spacer(Modifier.height(4.dp))

            Text(
                text = "Pacing controls timing of generated frames — preset, VSYNC alignment and FPS cap. Full pacing tuning is only available in the in-game overlay (advanced EMA/slack/queue sliders).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Current preset: ${state.pacingPreset.name.lowercase().replaceFirstChar { it.uppercase() }}" +
                    " · VSYNC-aligned: ${if (state.vsyncAlignmentEnabled) "on" else "off"}" +
                    " · FPS cap: ${if (state.targetFpsCap > 0) "${state.targetFpsCap}" else "none"}",
                style = MaterialTheme.typography.bodySmall,
                color = LsfgPrimary,
            )
        }

        TailNote()
    }
}

// ----------------------------------------------------------------------------------------
// Image Quality — consolidated GPU + NPU + CPU screen
// ----------------------------------------------------------------------------------------

@Composable
fun ParamsImageQualityScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()
    val npuAvailable = remember { runCatching { NativeBridge.isNpuAvailable() }.getOrDefault(false) }
    val npuSummary = remember { runCatching { NativeBridge.getNpuSummary() }.getOrDefault("NNAPI unavailable") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_image_quality),
            onBack = { nav.popBackStack() },
        )

        // ---- GPU --------------------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_gpu), title = null)
            Spacer(Modifier.height(4.dp))

            ToggleRow(
                icon = Icons.Filled.AutoFixHigh,
                title = stringResource(R.string.param_gpu_post),
                description = stringResource(R.string.param_gpu_post_desc),
                checked = state.gpuPostProcessingEnabled,
                onCheckedChange = {
                    prefs.setGpuPostProcessingEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            GpuStageSelector(
                selected = state.gpuPostProcessingStage,
                onSelected = {
                    prefs.setGpuPostProcessingStage(it)
                    refreshConfigState(prefs)
                },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            SubSectionHeader(text = stringResource(R.string.section_gpu_scaling))
            GpuMethodSelector(
                category = GpuMethodCategory.SCALING,
                selected = state.gpuPostProcessingMethod,
                onSelected = {
                    prefs.setGpuPostProcessingMethod(it)
                    refreshConfigState(prefs)
                },
            )
            Spacer(Modifier.height(8.dp))
            ValueSlider(
                title = stringResource(R.string.param_gpu_upscale),
                valueDisplay = "%.2fx".format(state.gpuUpscaleFactor),
                description = stringResource(R.string.param_gpu_upscale_desc),
                value = state.gpuUpscaleFactor,
                range = 1.0f..2.0f,
                steps = 3,
                leadingIcon = Icons.Filled.OpenInFull,
                onValueChange = {
                    prefs.setGpuUpscaleFactor(it)
                    refreshConfigState(prefs)
                },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            SubSectionHeader(text = stringResource(R.string.section_gpu_enhancement))
            GpuMethodSelector(
                category = GpuMethodCategory.ENHANCEMENT,
                selected = state.gpuPostProcessingMethod,
                onSelected = {
                    prefs.setGpuPostProcessingMethod(it)
                    refreshConfigState(prefs)
                },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            SubSectionHeader(text = stringResource(R.string.section_gpu_sharpen_color))
            GpuMethodSelector(
                category = GpuMethodCategory.SHARPEN_COLOR,
                selected = state.gpuPostProcessingMethod,
                onSelected = {
                    prefs.setGpuPostProcessingMethod(it)
                    refreshConfigState(prefs)
                },
            )

            AdvancedSection(label = stringResource(R.string.section_advanced)) {
                ValueSlider(
                    title = stringResource(R.string.param_gpu_sharpness),
                    valueDisplay = "${(state.gpuSharpness * 100f).toInt()}%",
                    description = stringResource(R.string.param_gpu_sharpness_desc),
                    value = state.gpuSharpness,
                    range = 0f..1f,
                    steps = 9,
                    leadingIcon = Icons.Filled.AutoFixHigh,
                    onValueChange = {
                        prefs.setGpuSharpness(it)
                        refreshConfigState(prefs)
                    },
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                ValueSlider(
                    title = stringResource(R.string.param_gpu_strength),
                    valueDisplay = "${(state.gpuStrength * 100f).toInt()}%",
                    description = stringResource(R.string.param_gpu_strength_desc),
                    value = state.gpuStrength,
                    range = 0f..1f,
                    steps = 9,
                    leadingIcon = Icons.Filled.HdrOn,
                    onValueChange = {
                        prefs.setGpuStrength(it)
                        refreshConfigState(prefs)
                    },
                )
            }
        }

        // ---- NPU --------------------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_npu), title = null)
            Spacer(Modifier.height(4.dp))

            ToggleRow(
                icon = Icons.Filled.Memory,
                title = stringResource(R.string.param_npu_post),
                description = if (npuAvailable) {
                    stringResource(R.string.param_npu_post_desc_available, npuSummary)
                } else {
                    stringResource(R.string.param_npu_post_desc_unavailable, npuSummary)
                },
                checked = state.npuPostProcessingEnabled && npuAvailable,
                onCheckedChange = {
                    prefs.setNpuPostProcessingEnabled(it && npuAvailable)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            NpuPresetSelector(
                selected = state.npuPostProcessingPreset,
                onSelected = {
                    prefs.setNpuPostProcessingPreset(it)
                    refreshConfigState(prefs)
                },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            ValueSlider(
                title = stringResource(R.string.param_npu_amount),
                valueDisplay = "${(state.npuAmount * 100f).toInt()}%",
                description = stringResource(R.string.param_npu_amount_desc),
                value = state.npuAmount,
                range = 0f..1f,
                steps = 9,
                leadingIcon = Icons.Filled.AutoFixHigh,
                onValueChange = {
                    prefs.setNpuAmount(it)
                    refreshConfigState(prefs)
                },
            )

            AdvancedSection(label = stringResource(R.string.section_advanced)) {
                ValueSlider(
                    title = stringResource(R.string.param_npu_radius),
                    valueDisplay = "%.2f".format(state.npuRadius),
                    description = stringResource(R.string.param_npu_radius_desc),
                    value = state.npuRadius,
                    range = 0.5f..2f,
                    steps = 14,
                    leadingIcon = Icons.Filled.AutoFixHigh,
                    onValueChange = {
                        prefs.setNpuRadius(it)
                        refreshConfigState(prefs)
                    },
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                ValueSlider(
                    title = stringResource(R.string.param_npu_upscale),
                    valueDisplay = "${state.npuUpscaleFactor}x",
                    description = stringResource(R.string.param_npu_upscale_desc),
                    value = state.npuUpscaleFactor.toFloat(),
                    range = 1f..2f,
                    steps = 0,
                    leadingIcon = Icons.Filled.OpenInFull,
                    onValueChange = {
                        prefs.setNpuUpscaleFactor(it.toInt().coerceIn(1, 2))
                        refreshConfigState(prefs)
                    },
                )
            }
        }

        // ---- CPU --------------------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_cpu), title = null)
            Spacer(Modifier.height(4.dp))

            ToggleRow(
                icon = Icons.Filled.AutoFixHigh,
                title = stringResource(R.string.param_cpu_post),
                description = stringResource(R.string.param_cpu_post_desc),
                checked = state.cpuPostProcessingEnabled,
                onCheckedChange = {
                    prefs.setCpuPostProcessingEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            CpuPresetSelector(
                selected = state.cpuPostProcessingPreset,
                onSelected = {
                    prefs.setCpuPostProcessingPreset(it)
                    refreshConfigState(prefs)
                },
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))

            ValueSlider(
                title = stringResource(R.string.param_cpu_strength),
                valueDisplay = "${(state.cpuStrength * 100f).toInt()}%",
                description = stringResource(R.string.param_cpu_strength_desc),
                value = state.cpuStrength,
                range = 0f..1f,
                steps = 9,
                leadingIcon = Icons.Filled.AutoFixHigh,
                onValueChange = {
                    prefs.setCpuStrength(it)
                    refreshConfigState(prefs)
                },
            )

            AdvancedSection(label = stringResource(R.string.section_advanced)) {
                ValueSlider(
                    title = stringResource(R.string.param_cpu_saturation),
                    valueDisplay = "${(state.cpuSaturation * 100f).toInt()}%",
                    description = stringResource(R.string.param_cpu_saturation_desc),
                    value = state.cpuSaturation,
                    range = 0f..1f,
                    steps = 9,
                    leadingIcon = Icons.Filled.HdrOn,
                    onValueChange = {
                        prefs.setCpuSaturation(it)
                        refreshConfigState(prefs)
                    },
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                ValueSlider(
                    title = stringResource(R.string.param_cpu_vibrance),
                    valueDisplay = "${(state.cpuVibrance * 100f).toInt()}%",
                    description = stringResource(R.string.param_cpu_vibrance_desc),
                    value = state.cpuVibrance,
                    range = 0f..1f,
                    steps = 9,
                    leadingIcon = Icons.Filled.HdrOn,
                    onValueChange = {
                        prefs.setCpuVibrance(it)
                        refreshConfigState(prefs)
                    },
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))
                ValueSlider(
                    title = stringResource(R.string.param_cpu_vignette),
                    valueDisplay = "${(state.cpuVignette * 100f).toInt()}%",
                    description = stringResource(R.string.param_cpu_vignette_desc),
                    value = state.cpuVignette,
                    range = 0f..1f,
                    steps = 9,
                    leadingIcon = Icons.Filled.HdrOn,
                    onValueChange = {
                        prefs.setCpuVignette(it)
                        refreshConfigState(prefs)
                    },
                )
            }
        }

        TailNote()
    }
}

// ----------------------------------------------------------------------------------------
// Overlay & Display — new screen consolidating overlay handle, HUD, capture mode
// ----------------------------------------------------------------------------------------

@Composable
fun OverlayDisplayScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.nav_overlay_display),
            onBack = { nav.popBackStack() },
        )

        // ---- Overlay entry mode (icon button vs drawer) -----------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_overlay_mode), title = null)
            Spacer(Modifier.height(4.dp))
            OverlayModeSelector(
                selected = state.overlayMode,
                onSelected = {
                    prefs.setOverlayMode(it)
                    refreshConfigState(prefs)
                    // Live-update the Automatic Overlay launcher: if the dot is
                    // currently shown for a target app it is recreated with the
                    // new affordance, otherwise the change is picked up the next
                    // time it appears.
                    AutoOverlayController.onOverlayModeChanged(ctx)
                },
            )
        }

        // ---- Overlay handle ---------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_overlay_handle), title = null)
            Spacer(Modifier.height(4.dp))
            DrawerEdgeSelector(
                selected = state.drawerEdge,
                onSelected = {
                    prefs.setDrawerEdge(it)
                    refreshConfigState(prefs)
                },
            )
        }

        // ---- HUD --------------------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_hud), title = null)
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                icon = Icons.Filled.FlashOn,
                title = stringResource(R.string.param_fps_counter),
                description = stringResource(R.string.param_fps_counter_desc),
                checked = state.fpsCounterEnabled,
                onCheckedChange = {
                    prefs.setFpsCounterEnabled(it)
                    refreshConfigState(prefs)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow(
                icon = Icons.AutoMirrored.Filled.ShowChart,
                title = stringResource(R.string.param_frame_graph),
                description = stringResource(R.string.param_frame_graph_desc),
                checked = state.frameGraphEnabled,
                onCheckedChange = {
                    prefs.setFrameGraphEnabled(it)
                    refreshConfigState(prefs)
                },
            )
        }

        // ---- Trusted overlay (accessibility) ---------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_trusted_overlay), title = null)
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                icon = Icons.Filled.TouchApp,
                title = stringResource(R.string.param_trusted_overlay),
                description = stringResource(R.string.param_trusted_overlay_desc),
                checked = state.trustedOverlay,
                onCheckedChange = {
                    prefs.setTrustedOverlay(it)
                    refreshConfigState(prefs)
                },
            )
        }

        // ---- Capture mode ----------------------------------------------------------
        LsfgCard {
            SectionHeader(eyebrow = stringResource(R.string.section_capture_mode), title = null)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.capture_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.captureSource == CaptureSource.MEDIA_PROJECTION,
                    onClick = {
                        prefs.setCaptureSource(CaptureSource.MEDIA_PROJECTION)
                        refreshConfigState(prefs)
                    },
                    label = { Text(stringResource(R.string.capture_mode_mediaprojection)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.captureSource == CaptureSource.SHIZUKU,
                    onClick = {
                        prefs.setCaptureSource(CaptureSource.SHIZUKU)
                        refreshConfigState(prefs)
                    },
                    label = { Text(stringResource(R.string.capture_mode_shizuku)) },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = state.captureSource == CaptureSource.ROOT,
                    onClick = {
                        prefs.setCaptureSource(CaptureSource.ROOT)
                        refreshConfigState(prefs)
                    },
                    label = { Text(stringResource(R.string.capture_mode_root)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        TailNote()
    }
}

@Composable
private fun TailNote() {
    Text(
        text = "Changes apply on the next session start. During an active session, open the in-game drawer to tweak values live.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ----------------------------------------------------------------------------------------
// Helpers — Advanced expander + sub-section header
// ----------------------------------------------------------------------------------------

@Composable
private fun AdvancedSection(
    label: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = LsfgPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = LsfgPrimary,
            modifier = Modifier
                .size(18.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(160)) + expandVertically(tween(180)),
        exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
    ) {
        Column { content() }
    }
}

@Composable
private fun SubSectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

// ----------------------------------------------------------------------------------------
// Selectors shared across screens
// ----------------------------------------------------------------------------------------

@Composable
private fun GpuStageSelector(
    selected: GpuPostProcessingStage,
    onSelected: (GpuPostProcessingStage) -> Unit,
) {
    val options = listOf(
        GpuPostProcessingStage.BEFORE_LSFG to stringResource(R.string.gpu_stage_before_lsfg),
        GpuPostProcessingStage.AFTER_LSFG to stringResource(R.string.gpu_stage_after_lsfg),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.param_gpu_stage),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (stage, label) ->
                FilterChip(
                    selected = selected == stage,
                    onClick = { onSelected(stage) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private enum class GpuMethodCategory { SCALING, ENHANCEMENT, SHARPEN_COLOR }

@Composable
private fun GpuMethodSelector(
    category: GpuMethodCategory,
    selected: GpuPostProcessingMethod,
    onSelected: (GpuPostProcessingMethod) -> Unit,
) {
    val options = when (category) {
        GpuMethodCategory.SCALING -> listOf(
            GpuPostProcessingMethod.FSR1_EASU_RCAS to stringResource(R.string.gpu_method_fsr1),
            GpuPostProcessingMethod.NVIDIA_NIS to stringResource(R.string.gpu_method_nis),
            GpuPostProcessingMethod.LANCZOS to stringResource(R.string.gpu_method_lanczos),
            GpuPostProcessingMethod.BICUBIC to stringResource(R.string.gpu_method_bicubic),
            GpuPostProcessingMethod.BILINEAR to stringResource(R.string.gpu_method_bilinear),
            GpuPostProcessingMethod.CATMULL_ROM to stringResource(R.string.gpu_method_catmull),
            GpuPostProcessingMethod.MITCHELL_NETRAVALI to stringResource(R.string.gpu_method_mitchell),
        )
        GpuMethodCategory.ENHANCEMENT -> listOf(
            GpuPostProcessingMethod.ANIME4K_ULTRAFAST to stringResource(R.string.gpu_method_anime4k_fast),
            GpuPostProcessingMethod.ANIME4K_RESTORE to stringResource(R.string.gpu_method_anime4k_restore),
            GpuPostProcessingMethod.XBRZ to stringResource(R.string.gpu_method_xbrz),
            GpuPostProcessingMethod.EDGE_DIRECTED to stringResource(R.string.gpu_method_edge_directed),
        )
        GpuMethodCategory.SHARPEN_COLOR -> listOf(
            GpuPostProcessingMethod.AMD_CAS to stringResource(R.string.gpu_method_cas),
            GpuPostProcessingMethod.UNSHARP_MASK to stringResource(R.string.gpu_method_unsharp),
            GpuPostProcessingMethod.LUMA_SHARPEN to stringResource(R.string.gpu_method_luma),
            GpuPostProcessingMethod.CONTRAST_ADAPTIVE to stringResource(R.string.gpu_method_contrast),
            GpuPostProcessingMethod.DEBAND to stringResource(R.string.gpu_method_deband),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        options.chunked(2).forEachIndexed { index, rowOptions ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { (method, label) ->
                    FilterChip(
                        selected = selected == method,
                        onClick = { onSelected(method) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowOptions.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NpuPresetSelector(
    selected: NpuPostProcessingPreset,
    onSelected: (NpuPostProcessingPreset) -> Unit,
) {
    val options = listOf(
        NpuPostProcessingPreset.OFF to stringResource(R.string.npu_preset_off),
        NpuPostProcessingPreset.SHARPEN to stringResource(R.string.npu_preset_sharpen),
        NpuPostProcessingPreset.DETAIL_BOOST to stringResource(R.string.npu_preset_detail_boost),
        NpuPostProcessingPreset.CHROMA_CLEAN to stringResource(R.string.npu_preset_chroma_clean),
        NpuPostProcessingPreset.GAME_CRISP to stringResource(R.string.npu_preset_game_crisp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.param_npu_preset),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.take(3).forEach { (preset, label) ->
                FilterChip(
                    selected = selected == preset,
                    onClick = { onSelected(preset) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.drop(3).forEach { (preset, label) ->
                FilterChip(
                    selected = selected == preset,
                    onClick = { onSelected(preset) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CpuPresetSelector(
    selected: CpuPostProcessingPreset,
    onSelected: (CpuPostProcessingPreset) -> Unit,
) {
    val options = listOf(
        CpuPostProcessingPreset.OFF to stringResource(R.string.cpu_preset_off),
        CpuPostProcessingPreset.ENHANCE_LUT to stringResource(R.string.cpu_preset_enhance_lut),
        CpuPostProcessingPreset.WARM to stringResource(R.string.cpu_preset_warm),
        CpuPostProcessingPreset.COOL to stringResource(R.string.cpu_preset_cool),
        CpuPostProcessingPreset.VIGNETTE to stringResource(R.string.cpu_preset_vignette),
        CpuPostProcessingPreset.GAMER_SHARP to stringResource(R.string.cpu_preset_gamer_sharp),
        CpuPostProcessingPreset.CINEMATIC to stringResource(R.string.cpu_preset_cinematic),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Text(
            text = stringResource(R.string.param_cpu_preset),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.take(3).forEach { (preset, label) ->
                FilterChip(
                    selected = selected == preset,
                    onClick = { onSelected(preset) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.drop(3).take(3).forEach { (preset, label) ->
                FilterChip(
                    selected = selected == preset,
                    onClick = { onSelected(preset) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.drop(6).forEach { (preset, label) ->
                FilterChip(
                    selected = selected == preset,
                    onClick = { onSelected(preset) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun OverlayModeSelector(
    selected: OverlayMode,
    onSelected: (OverlayMode) -> Unit,
) {
    var pendingDrawerConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.TouchApp, size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.param_overlay_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.param_overlay_mode_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selected == OverlayMode.ICON_BUTTON,
                onClick = {
                    if (selected != OverlayMode.ICON_BUTTON) onSelected(OverlayMode.ICON_BUTTON)
                },
                label = { Text(stringResource(R.string.overlay_mode_icon_button)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.TouchApp,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = selected == OverlayMode.DRAWER,
                onClick = {
                    if (selected != OverlayMode.DRAWER) {
                        pendingDrawerConfirm = true
                    }
                },
                label = { Text(stringResource(R.string.overlay_mode_drawer)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ViewSidebar,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (pendingDrawerConfirm) {
        AlertDialog(
            onDismissRequest = { pendingDrawerConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.overlay_mode_drawer_warning_title)) },
            text = { Text(stringResource(R.string.overlay_mode_drawer_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDrawerConfirm = false
                    onSelected(OverlayMode.DRAWER)
                }) { Text(stringResource(R.string.overlay_mode_drawer_warning_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDrawerConfirm = false }) {
                    Text(stringResource(R.string.overlay_mode_drawer_warning_cancel))
                }
            },
        )
    }
}

@Composable
private fun DrawerEdgeSelector(
    selected: DrawerEdge,
    onSelected: (DrawerEdge) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon = Icons.Filled.OpenInFull, size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.param_drawer_edge),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.param_drawer_edge_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val options = listOf(
            DrawerEdge.LEFT to stringResource(R.string.drawer_edge_left),
            DrawerEdge.RIGHT to stringResource(R.string.drawer_edge_right),
            DrawerEdge.TOP to stringResource(R.string.drawer_edge_top),
            DrawerEdge.BOTTOM to stringResource(R.string.drawer_edge_bottom),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.take(2).forEach { (edge, label) ->
                FilterChip(
                    selected = selected == edge,
                    onClick = { onSelected(edge) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.drop(2).forEach { (edge, label) ->
                FilterChip(
                    selected = selected == edge,
                    onClick = { onSelected(edge) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
