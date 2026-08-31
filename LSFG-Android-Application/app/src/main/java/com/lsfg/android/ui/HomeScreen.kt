package com.lsfg.android.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.SHOW_IMAGE_QUALITY
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.session.CrashReporter
import com.lsfg.android.session.LsfgForegroundService
import com.lsfg.android.session.PermissionsHelper
import com.lsfg.android.session.ShizukuCaptureEngine
import com.lsfg.android.ui.components.IconBadge
import com.lsfg.android.ui.components.LsfgCard
import com.lsfg.android.ui.components.LsfgLogoMark
import com.lsfg.android.ui.components.LsfgSecondaryButton
import com.lsfg.android.ui.components.SessionCTA
import com.lsfg.android.ui.components.StatusTone
import com.lsfg.android.ui.components.StepCard
import com.lsfg.android.ui.theme.LsfgPrimary
import com.lsfg.android.ui.theme.LsfgStatusGood
import com.lsfg.android.ui.theme.LsfgStatusWarn
import rikka.shizuku.Shizuku

@Composable
fun HomeScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()

    var lastError by remember { mutableStateOf<String?>(null) }
    var pendingTargetPkg by remember { mutableStateOf<String?>(null) }
    var pendingCaptureSource by remember { mutableStateOf(CaptureSource.MEDIA_PROJECTION) }
    var showCrashDialog by remember { mutableStateOf(false) }
    var crashPreview by remember { mutableStateOf("") }
    var showCrashDetail by remember { mutableStateOf(false) }
    var shizukuPermissionRetry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showTutorialPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!prefs.isTutorialPromptShown()) {
            showTutorialPrompt = true
        }
        if (CrashReporter.hasPendingCrash(ctx)) {
            crashPreview = CrashReporter.readCrashSummary(ctx)
            // Move the crash file aside immediately so the dialog never
            // reappears on the next launch (e.g. if the user swipes the app
            // away instead of tapping a button). The file is still kept
            // under last_crash_seen.txt so the share chip can attach it.
            CrashReporter.markPendingCrashSeen(ctx)
            showCrashDialog = true
        }
    }

    if (showCrashDialog) {
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            icon = {
                IconBadge(
                    icon = Icons.Filled.BugReport,
                    tint = MaterialTheme.colorScheme.tertiary,
                    size = 44.dp,
                )
            },
            title = { Text(stringResource(R.string.crash_dialog_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.crash_dialog_body))
                    if (showCrashDetail) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = crashPreview.take(4000),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val intent = CrashReporter.buildShareIntent(ctx)
                    if (intent != null) {
                        ctx.startActivity(
                            Intent.createChooser(intent, "Share crash report")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    CrashReporter.clearPendingCrash(ctx)
                    showCrashDialog = false
                }) { Text(stringResource(R.string.crash_dialog_share)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showCrashDetail = !showCrashDetail }) {
                        Text(stringResource(R.string.crash_dialog_view))
                    }
                    TextButton(onClick = {
                        CrashReporter.clearPendingCrash(ctx)
                        showCrashDialog = false
                    }) { Text(stringResource(R.string.crash_dialog_dismiss)) }
                }
            },
        )
    }

    if (showTutorialPrompt) {
        AlertDialog(
            onDismissRequest = {
                prefs.setTutorialPromptShown(true)
                showTutorialPrompt = false
            },
            icon = {
                IconBadge(
                    icon = Icons.Filled.School,
                    tint = LsfgPrimary,
                    size = 44.dp,
                )
            },
            title = { Text(stringResource(R.string.tutorial_prompt_title)) },
            text = { Text(stringResource(R.string.tutorial_prompt_body)) },
            confirmButton = {
                TextButton(onClick = {
                    prefs.setTutorialPromptShown(true)
                    showTutorialPrompt = false
                    nav.navigate(Routes.TUTORIAL)
                }) { Text(stringResource(R.string.tutorial_prompt_view)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    prefs.setTutorialPromptShown(true)
                    showTutorialPrompt = false
                }) { Text(stringResource(R.string.tutorial_prompt_skip)) }
            },
        )
    }

    val mpm = remember(ctx) {
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        val target = pendingTargetPkg
        val source = pendingCaptureSource
        pendingTargetPkg = null
        pendingCaptureSource = CaptureSource.MEDIA_PROJECTION
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            lastError = ctx.getString(R.string.perm_capture_denied)
            return@rememberLauncherForActivityResult
        }
        val intent = LsfgForegroundService.buildStartIntent(
            ctx = ctx,
            resultCode = result.resultCode,
            resultData = data,
            targetPackage = target,
            fpsCounter = prefs.load().fpsCounterEnabled,
            captureSource = source,
        )
        ContextCompat.startForegroundService(ctx, intent)
    }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST && grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                shizukuPermissionRetry?.invoke()
                shizukuPermissionRetry = null
            } else if (requestCode == SHIZUKU_PERMISSION_REQUEST) {
                lastError = "Shizuku permission denied."
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    val canStart = state.shadersReady && state.targetPackage != null
    val a11yEnabled = PermissionsHelper.isAccessibilityServiceEnabled(ctx)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Top bar: logo + title + quick actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            LsfgLogoMark(size = 36.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Frame generation via Vulkan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconChip(
                icon = Icons.Filled.Accessibility,
                tint = if (a11yEnabled) LsfgStatusGood else LsfgStatusWarn,
                onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
            Spacer(Modifier.size(8.dp))
            IconChip(
                icon = Icons.Filled.BugReport,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = {
                    val intent = CrashReporter.buildShareIntent(ctx)
                    if (intent == null) {
                        Toast.makeText(ctx, R.string.crash_export_none, Toast.LENGTH_SHORT).show()
                    } else {
                        ctx.startActivity(
                            Intent.createChooser(intent, "Export diagnostic log")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }

        // Hero session card with CTA
        LsfgCard(accent = true) {
            SessionCTA(
                running = false,
                enabled = canStart,
                onStart = {
                    lastError = null
                    if (!Settings.canDrawOverlays(ctx)) {
                        val uri = Uri.parse("package:${ctx.packageName}")
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        lastError = ctx.getString(R.string.perm_overlay_missing)
                        return@SessionCTA
                    }
                    val targetPkg = state.targetPackage
                    pendingTargetPkg = targetPkg
                    if (targetPkg != null) {
                        ctx.packageManager.getLaunchIntentForPackage(targetPkg)?.let { launch ->
                            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    if (state.captureSource == CaptureSource.SHIZUKU) {
                        val startShizukuHybrid = {
                            ContextCompat.startForegroundService(
                                ctx,
                                LsfgForegroundService.buildShizukuStartIntent(
                                    ctx = ctx,
                                    targetPackage = targetPkg,
                                    fpsCounter = prefs.load().fpsCounterEnabled,
                                ),
                            )
                        }
                        val ready = runCatching {
                            Shizuku.pingBinder() &&
                                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
                        }.getOrDefault(false)
                        if (ready) {
                            startShizukuHybrid()
                        } else {
                            shizukuPermissionRetry = startShizukuHybrid
                            runCatching { ShizukuCaptureEngine.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
                                .onFailure {
                                    lastError = "Shizuku is not running or is too old. Start Shizuku, then try again."
                                    shizukuPermissionRetry = null
                                }
                        }
                        return@SessionCTA
                    }
                    if (state.captureSource == CaptureSource.ROOT) {
                        ContextCompat.startForegroundService(
                            ctx,
                            LsfgForegroundService.buildRootStartIntent(
                                ctx = ctx,
                                targetPackage = targetPkg,
                                fpsCounter = prefs.load().fpsCounterEnabled,
                            ),
                        )
                        return@SessionCTA
                    }
                    pendingCaptureSource = CaptureSource.MEDIA_PROJECTION
                    val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
                    } else {
                        mpm.createScreenCaptureIntent()
                    }
                    projectionLauncher.launch(captureIntent)
                },
                onStop = { LsfgForegroundService.stop(ctx) },
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .padding(top = 6.dp),
                )
                Text(
                    text = if (canStart) when (state.captureSource) {
                        CaptureSource.SHIZUKU ->
                            "Ready to start in Shizuku capture mode. Frames are captured from the target UID so the LSFG overlay is not fed back into itself."
                        CaptureSource.ROOT ->
                            "Ready to start in root capture mode. Frames are captured from the target UID with root privilege — no recording dialog."
                        else ->
                            "Ready to start. When Android asks what to share, choose the target app, not the entire screen."
                    } else "Complete steps 1 and 2 below to enable the session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (lastError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = lastError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

        }

        // Limitation notice
        LsfgCard {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .padding(top = 8.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.limitation_title).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LsfgPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.limitation_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.captureSource == CaptureSource.SHIZUKU) {
            LsfgCard {
                Column {
                    Text(
                        text = "SHIZUKU CAPTURE",
                        style = MaterialTheme.typography.labelSmall,
                        color = LsfgPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Uses Shizuku's privileged UID-filtered capture for the target app, avoiding MediaProjection overlay feedback. Requires the Shizuku app, Shizuku permission, and ADB or wireless debugging active before starting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.captureSource == CaptureSource.ROOT) {
            LsfgCard {
                Column {
                    Text(
                        text = "ROOT CAPTURE",
                        style = MaterialTheme.typography.labelSmall,
                        color = LsfgPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Uses root UID-filtered capture for the target app — no MediaProjection consent dialog. Requires root access granted to LLS in your root manager (Magisk / KernelSU / APatch).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Steps — 5 cards grouped by concept (prerequisite → target → frame gen/pacing →
        // image quality → display). Post-process accelerators (GPU/NPU/CPU) are no longer
        // separate top-level steps; they share the "Image quality" screen.
        val dllStatus = if (state.shadersReady) StatusTone.Good
        else if (state.dllDisplayName != null) StatusTone.Warn
        else StatusTone.Neutral
        val dllLabel = if (state.shadersReady) "Ready"
        else if (state.dllDisplayName != null) "Pending"
        else "Required"

        StepCard(
            number = 1,
            title = stringResource(R.string.nav_dll),
            subtitle = if (state.dllDisplayName != null)
                state.dllDisplayName!!
            else stringResource(R.string.dll_status_none),
            status = dllStatus,
            statusLabel = dllLabel,
            onClick = {
                if (!state.legalAccepted) nav.navigate(Routes.LEGAL) else nav.navigate(Routes.DLL)
            },
        )

        StepCard(
            number = 2,
            title = stringResource(R.string.nav_app),
            subtitle = state.targetPackage ?: "No app selected",
            status = if (state.targetPackage != null) StatusTone.Good else StatusTone.Neutral,
            statusLabel = if (state.targetPackage != null) "Set" else "Required",
            onClick = { nav.navigate(Routes.APP_PICKER) },
        )

        val frameGenSummary = if (state.lsfgEnabled) {
            buildString {
                append("Multiplier ${state.multiplier}× · flow ${"%.2f".format(state.flowScale)}")
                if (state.performanceMode) append(" · perf")
                if (state.hdrMode) append(" · HDR")
                if (state.antiArtifacts) append(" · anti-artifacts")
            }
        } else {
            "Off — raw capture passthrough"
        }
        StepCard(
            number = 3,
            title = stringResource(R.string.nav_framegen_pacing),
            subtitle = frameGenSummary,
            status = if (state.lsfgEnabled) StatusTone.Good else StatusTone.Neutral,
            statusLabel = if (state.lsfgEnabled) "On" else "Off",
            onClick = { nav.navigate(Routes.PARAMS_FRAMEGEN_PACING) },
        )

        if (SHOW_IMAGE_QUALITY) {
            val imageQualitySummary = buildString {
                val parts = mutableListOf<String>()
                if (state.gpuPostProcessingEnabled) parts += "GPU " + gpuMethodLabel(state.gpuPostProcessingMethod)
                if (state.npuPostProcessingEnabled) parts += "NPU ${(state.npuAmount * 100f).toInt()}%"
                if (state.cpuPostProcessingEnabled) parts += "CPU ${(state.cpuStrength * 100f).toInt()}%"
                if (parts.isEmpty()) append("Off") else append(parts.joinToString(" · "))
            }
            val imageQualityOn = state.gpuPostProcessingEnabled ||
                state.npuPostProcessingEnabled ||
                state.cpuPostProcessingEnabled
            StepCard(
                number = 4,
                title = stringResource(R.string.nav_image_quality),
                subtitle = imageQualitySummary,
                status = if (imageQualityOn) StatusTone.Good else StatusTone.Neutral,
                statusLabel = if (imageQualityOn) "On" else "Off",
                onClick = { nav.navigate(Routes.PARAMS_IMAGE_QUALITY) },
            )
        }

        val overlayDisplaySummary = buildString {
            append(
                when (state.captureSource) {
                    CaptureSource.SHIZUKU -> "Shizuku capture"
                    CaptureSource.ROOT -> "Root capture"
                    else -> "MediaProjection"
                },
            )
            append(" · ")
            append(
                when (state.drawerEdge) {
                    com.lsfg.android.prefs.DrawerEdge.LEFT -> "left handle"
                    com.lsfg.android.prefs.DrawerEdge.RIGHT -> "right handle"
                    com.lsfg.android.prefs.DrawerEdge.TOP -> "top handle"
                    com.lsfg.android.prefs.DrawerEdge.BOTTOM -> "bottom handle"
                },
            )
            if (state.fpsCounterEnabled) append(" · FPS")
        }
        StepCard(
            number = if (SHOW_IMAGE_QUALITY) 5 else 4,
            title = stringResource(R.string.nav_overlay_display),
            subtitle = overlayDisplaySummary,
            status = StatusTone.Neutral,
            statusLabel = when (state.captureSource) {
                CaptureSource.SHIZUKU -> "Shizuku"
                CaptureSource.ROOT -> "Root"
                else -> "MP"
            },
            onClick = { nav.navigate(Routes.OVERLAY_DISPLAY) },
        )

        val autoCount = state.autoEnabledApps.size
        val autoSubtitle = if (autoCount == 0) {
            stringResource(R.string.automatic_overlay_count_zero)
        } else {
            stringResource(R.string.automatic_overlay_count_n, autoCount)
        }
        StepCard(
            number = if (SHOW_IMAGE_QUALITY) 6 else 5,
            title = stringResource(R.string.nav_automatic_overlay),
            subtitle = autoSubtitle,
            status = if (autoCount > 0) StatusTone.Good else StatusTone.Neutral,
            statusLabel = if (autoCount > 0) "On" else "Off",
            onClick = { nav.navigate(Routes.AUTOMATIC_OVERLAY) },
        )

        // Footer actions
        LsfgCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "MORE",
                    style = MaterialTheme.typography.labelSmall,
                    color = LsfgPrimary,
                )
                LsfgSecondaryButton(
                    text = stringResource(R.string.nav_tutorial),
                    onClick = { nav.navigate(Routes.TUTORIAL) },
                    leadingIcon = Icons.Filled.School,
                    modifier = Modifier.fillMaxWidth(),
                )
                LsfgSecondaryButton(
                    text = stringResource(R.string.benchmark_home_button),
                    onClick = { nav.navigate(Routes.BENCHMARK) },
                    leadingIcon = Icons.Filled.Speed,
                    modifier = Modifier.fillMaxWidth(),
                )
                LsfgSecondaryButton(
                    text = "Re-read legal notice",
                    onClick = { nav.navigate(Routes.LEGAL) },
                    leadingIcon = Icons.Filled.Gavel,
                    modifier = Modifier.fillMaxWidth(),
                )
                LsfgSecondaryButton(
                    text = stringResource(R.string.crash_export_log),
                    onClick = {
                        val intent = CrashReporter.buildShareIntent(ctx)
                        if (intent == null) {
                            Toast.makeText(ctx, R.string.crash_export_none, Toast.LENGTH_SHORT).show()
                        } else {
                            ctx.startActivity(
                                Intent.createChooser(intent, "Export diagnostic log")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    leadingIcon = Icons.Filled.BugReport,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "v${versionName(ctx)}",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun versionName(ctx: Context): String {
    return runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "—"
    }.getOrDefault("—")
}

private const val SHIZUKU_PERMISSION_REQUEST = 6104

private fun gpuMethodLabel(m: com.lsfg.android.prefs.GpuPostProcessingMethod): String = when (m) {
    com.lsfg.android.prefs.GpuPostProcessingMethod.FSR1_EASU_RCAS -> "FSR1"
    com.lsfg.android.prefs.GpuPostProcessingMethod.AMD_CAS -> "CAS"
    com.lsfg.android.prefs.GpuPostProcessingMethod.NVIDIA_NIS -> "NIS"
    com.lsfg.android.prefs.GpuPostProcessingMethod.LANCZOS -> "Lanczos"
    com.lsfg.android.prefs.GpuPostProcessingMethod.BICUBIC -> "Bicubic"
    com.lsfg.android.prefs.GpuPostProcessingMethod.BILINEAR -> "Bilinear"
    com.lsfg.android.prefs.GpuPostProcessingMethod.CATMULL_ROM -> "Catmull"
    com.lsfg.android.prefs.GpuPostProcessingMethod.MITCHELL_NETRAVALI -> "Mitchell"
    com.lsfg.android.prefs.GpuPostProcessingMethod.ANIME4K_ULTRAFAST -> "Anime4K Fast"
    com.lsfg.android.prefs.GpuPostProcessingMethod.ANIME4K_RESTORE -> "Anime4K Restore"
    com.lsfg.android.prefs.GpuPostProcessingMethod.XBRZ -> "xBRZ"
    com.lsfg.android.prefs.GpuPostProcessingMethod.EDGE_DIRECTED -> "Edge"
    com.lsfg.android.prefs.GpuPostProcessingMethod.UNSHARP_MASK -> "Unsharp"
    com.lsfg.android.prefs.GpuPostProcessingMethod.LUMA_SHARPEN -> "Luma"
    com.lsfg.android.prefs.GpuPostProcessingMethod.CONTRAST_ADAPTIVE -> "Contrast"
    com.lsfg.android.prefs.GpuPostProcessingMethod.DEBAND -> "Deband"
}
