package com.lsfg.android.ui

import android.app.Activity
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.lsfg.android.R
import com.lsfg.android.benchmark.BenchmarkController
import com.lsfg.android.benchmark.BenchmarkLogWriter
import com.lsfg.android.prefs.CaptureSource
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.session.LsfgForegroundService
import com.lsfg.android.ui.components.IconBadge
import com.lsfg.android.ui.components.LsfgCard
import com.lsfg.android.ui.components.LsfgSecondaryButton
import com.lsfg.android.ui.components.LsfgTopBar
import com.lsfg.android.ui.theme.LsfgPrimary
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun BenchmarkScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val prefs = remember { LsfgPreferences(ctx) }
    val state by produceConfigState(prefs).collectAsState()

    val mpm = remember(ctx) {
        ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    var lastError by remember { mutableStateOf<String?>(null) }
    // Polled controller state. We poll on a 200 ms cadence because the
    // controller has no Flow API — it stores a plain AtomicReference. 200 ms
    // is fine for a "remaining seconds" UI; the underlying sampling runs at
    // 100 ms native cadence, fully decoupled.
    var controllerState by remember { mutableStateOf<BenchmarkController.State>(BenchmarkController.State.Idle) }
    var lastReport by remember { mutableStateOf<File?>(null) }
    // Probe once: shows the user upfront whether the dual FP32/FP16 pass will
    // run. Same gate the in-app FP16 toggle uses — needs both shaderFloat16
    // on the GPU and the fp16/ shader cache populated.
    val fp16Supported = remember {
        val cacheDir = java.io.File(ctx.filesDir, "spirv").absolutePath
        runCatching { com.lsfg.android.session.NativeBridge.isFramegenFp16Supported(cacheDir) }.getOrDefault(false)
    }

    LaunchedEffect(Unit) {
        while (true) {
            controllerState = BenchmarkController.currentState()
            // Pick up a freshly produced report from the service. The service
            // sets this slot AFTER triggering its own share chooser, but the
            // user might dismiss it — keep the file so they can re-share.
            val produced = LsfgForegroundService.lastBenchmarkReport
            if (produced != null && produced != lastReport) {
                lastReport = produced
            }
            delay(200)
        }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            lastError = ctx.getString(R.string.perm_capture_denied)
            return@rememberLauncherForActivityResult
        }
        val target = state.targetPackage
        if (target == null) {
            lastError = ctx.getString(R.string.benchmark_requires_target)
            return@rememberLauncherForActivityResult
        }
        // Launch the target so MediaProjection has a foreground app to
        // capture; identical pattern to HomeScreen's start path.
        ctx.packageManager.getLaunchIntentForPackage(target)?.let { launch ->
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val intent = LsfgForegroundService.buildBenchmarkStartIntent(
            ctx = ctx,
            resultCode = result.resultCode,
            resultData = data,
            targetPackage = target,
            captureSource = CaptureSource.MEDIA_PROJECTION,
        )
        ContextCompat.startForegroundService(ctx, intent)
    }

    val completed = controllerState as? BenchmarkController.State.Completed
    val reportToShare: File? = completed?.reportFile ?: lastReport

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        LsfgTopBar(
            title = stringResource(R.string.benchmark_title),
            onBack = { nav.popBackStack() },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                IconBadge(icon = Icons.Filled.Speed, tint = LsfgPrimary, size = 56.dp)
            }

            LsfgCard {
                Text(
                    text = stringResource(R.string.benchmark_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.benchmark_preset_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.benchmark_duration_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fp16Supported) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.benchmark_dual_precision_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = LsfgPrimary,
                    )
                }
            }

            // Live progress card while a benchmark is running.
            val running = controllerState as? BenchmarkController.State.Running
            if (running != null) {
                LsfgCard(accent = true) {
                    val now = System.currentTimeMillis()
                    val elapsedMs = (now - running.phaseStartedAtMs).coerceAtLeast(0)
                    val remainingMs = (running.phaseDurationMs - elapsedMs).coerceAtLeast(0)
                    val remainingSec = (remainingMs / 1000).toInt()
                    val progress = if (running.phaseDurationMs > 0) {
                        (elapsedMs.toFloat() / running.phaseDurationMs).coerceIn(0f, 1f)
                    } else 0f
                    val label = when (running.phase) {
                        BenchmarkController.Phase.WARMUP ->
                            stringResource(R.string.benchmark_running_warmup, running.multiplier, remainingSec)
                        BenchmarkController.Phase.SAMPLING ->
                            stringResource(
                                R.string.benchmark_running_sampling,
                                running.runIndex + 1,
                                running.totalRuns,
                                running.multiplier,
                                remainingSec,
                            )
                    }
                    Text(text = label, style = MaterialTheme.typography.titleMedium)
                    // Surface the active precision pass so the user sees the
                    // FP32→FP16 transition mid-benchmark instead of guessing.
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Precision: ${running.precision.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = LsfgPrimary,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LsfgSecondaryButton(
                        text = stringResource(R.string.benchmark_cancel),
                        onClick = {
                            BenchmarkController.cancel()
                            LsfgForegroundService.stop(ctx)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Result card after a completed run.
            if (completed != null) {
                LsfgCard(accent = true) {
                    Text(
                        text = stringResource(R.string.benchmark_completed),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.benchmark_completed_body,
                            completed.reportFile.name,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            (controllerState as? BenchmarkController.State.Failed)?.let { failed ->
                LsfgCard {
                    Text(
                        text = stringResource(R.string.benchmark_failed, failed.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            lastError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Bottom action: start / share. Disabled while running.
        val canStart = state.shadersReady && state.targetPackage != null &&
            controllerState !is BenchmarkController.State.Running
        if (controllerState !is BenchmarkController.State.Running) {
            Button(
                onClick = {
                    lastError = null
                    BenchmarkController.acknowledge() // clear any stale Completed/Failed
                    val target = state.targetPackage
                    if (target == null) {
                        lastError = ctx.getString(R.string.benchmark_requires_target)
                        return@Button
                    }
                    if (!Settings.canDrawOverlays(ctx)) {
                        val uri = Uri.parse("package:${ctx.packageName}")
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        lastError = ctx.getString(R.string.perm_overlay_missing)
                        return@Button
                    }
                    // Honour the user's capture-source preference instead of
                    // forcing MediaProjection. Shizuku and Root skip the system
                    // recording dialog entirely — same pattern HomeScreen uses
                    // for normal sessions.
                    when (state.captureSource) {
                        CaptureSource.SHIZUKU -> {
                            ctx.packageManager.getLaunchIntentForPackage(target)?.let { launch ->
                                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            ContextCompat.startForegroundService(
                                ctx,
                                LsfgForegroundService.buildShizukuBenchmarkStartIntent(
                                    ctx = ctx,
                                    targetPackage = target,
                                ),
                            )
                        }
                        CaptureSource.ROOT -> {
                            ctx.packageManager.getLaunchIntentForPackage(target)?.let { launch ->
                                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            ContextCompat.startForegroundService(
                                ctx,
                                LsfgForegroundService.buildRootBenchmarkStartIntent(
                                    ctx = ctx,
                                    targetPackage = target,
                                ),
                            )
                        }
                        CaptureSource.MEDIA_PROJECTION -> {
                            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
                            } else {
                                mpm.createScreenCaptureIntent()
                            }
                            projectionLauncher.launch(captureIntent)
                        }
                    }
                },
                enabled = canStart,
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = LsfgPrimary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(R.string.benchmark_start))
            }
            Spacer(Modifier.height(8.dp))
            LsfgSecondaryButton(
                text = stringResource(R.string.benchmark_share_report),
                onClick = {
                    val file = reportToShare
                    if (file == null || !file.exists()) {
                        Toast.makeText(ctx, R.string.benchmark_no_report, Toast.LENGTH_SHORT).show()
                        return@LsfgSecondaryButton
                    }
                    val intent = BenchmarkLogWriter.buildShareIntent(ctx, file)
                    ctx.startActivity(
                        Intent.createChooser(intent, "Share LSFG benchmark report")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                leadingIcon = Icons.Filled.Share,
                modifier = Modifier.fillMaxWidth(),
                enabled = reportToShare != null,
            )
        }
    }
}
