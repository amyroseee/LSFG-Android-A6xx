package com.lsfg.android.benchmark

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lsfg.android.prefs.LsfgPreferences
import com.lsfg.android.prefs.NpuPostProcessingPreset
import com.lsfg.android.prefs.CpuPostProcessingPreset
import com.lsfg.android.prefs.VsyncRefreshOverride
import com.lsfg.android.session.NativeBridge
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Orchestrates a 3-run benchmark on top of an already-active LSFG session.
 *
 * Lifecycle:
 *   1. The LsfgForegroundService starts a normal session (MediaProjection consent,
 *      target app launched, framegen active).
 *   2. The service sees EXTRA_BENCHMARK_REQUESTED=true and calls [start] passing
 *      a [Hooks] object that lets us trigger context reinit and read render-size.
 *   3. We snapshot the user's prefs, overwrite them with the benchmark preset,
 *      then loop over MULTIPLIERS — for each: set multiplier in prefs, request
 *      reinit (the service path used by the live drawer), warmup, sample, store.
 *   4. After the last run we restore prefs and persist the report file. The
 *      service is then asked to stop the session and surface the share intent.
 *
 * State is held as `AtomicReference<RunState>` so the UI (which lives in the
 * Activity / a separate Compose screen) can poll progress without locking.
 */
object BenchmarkController {

    private const val TAG = "BenchmarkController"

    sealed class State {
        data object Idle : State()
        data class Running(
            val runIndex: Int,            // 0-based, monotonic across all (precision, multiplier) pairs
            val totalRuns: Int,           // multiplier_count × precision_count
            val multiplier: Int,
            /** Which shader-precision pass this run belongs to. UI uses it to
             *  show e.g. "Run 4/6 — FP16 ×3". Defaults to FP32 on devices
             *  without FP16 support so the UI label is still meaningful. */
            val precision: PrecisionMode,
            val phase: Phase,
            val phaseStartedAtMs: Long,
            val phaseDurationMs: Long,
        ) : State()
        data class Completed(
            val reportFile: File,
            val results: List<BenchmarkRunResult>,
            val targetPackage: String?,
            val renderWidth: Int,
            val renderHeight: Int,
        ) : State()
        data class Failed(val message: String) : State()
    }

    enum class Phase { WARMUP, SAMPLING }

    /** Hooks the service exposes so the controller can drive lifecycle events
     *  it doesn't own (context reinit, render-size lookup, session stop). */
    interface Hooks {
        /** Trigger the same code path the live drawer uses: re-read prefs and
         *  call NativeBridge.destroyContext + initContext on a worker thread.
         *  Returns immediately; reinit completes asynchronously. */
        fun requestReinit()

        /** Currently active render width (set by the service after initContext).
         *  Returns 0 if not yet known. */
        fun activeRenderWidth(): Int
        fun activeRenderHeight(): Int

        /** The current foreground target package, if any. */
        fun targetPackage(): String?

        /** Display vsync period in ns (matches NativeBridge.setVsyncPeriodNs).
         *  Used by BenchmarkRunner for vsync-alignment metrics. */
        fun vsyncPeriodNs(): Long

        /** Called once after the final run to surface the report file to the
         *  user. The service is expected to stop the session and post a
         *  notification with the share intent built from this file. */
        fun onCompleted(state: State.Completed)

        /** Called on early termination (cancel / unrecoverable error). */
        fun onFailed(state: State.Failed)
    }

    private val state = AtomicReference<State>(State.Idle)

    /** Live-reading state for UI bindings. */
    fun currentState(): State = state.get()

    /** True while a benchmark is actively collecting samples. Used by the
     *  service to gate behaviour (e.g. don't allow the drawer to mutate prefs
     *  mid-run). */
    fun isRunning(): Boolean = state.get() is State.Running

    /**
     * Start the 3-run benchmark. Must be called from a thread other than the
     * Main looper, OR pass [forceWorkerThread]=true to launch a worker. The
     * actual benchmark loop sleeps between samples, so it can't run on Main.
     */
    fun start(ctx: Context, hooks: Hooks) {
        val current = state.get()
        if (current is State.Running) {
            Log.w(TAG, "start() called while already running ($current); ignoring")
            return
        }
        thread(name = "lsfg-benchmark", isDaemon = true) {
            try {
                runWorker(ctx.applicationContext, hooks)
            } catch (t: Throwable) {
                Log.e(TAG, "benchmark worker crashed", t)
                val failure = State.Failed("Worker error: ${t.javaClass.simpleName}: ${t.message}")
                state.set(failure)
                postToMain { hooks.onFailed(failure) }
            }
        }
    }

    /** Cancel an in-flight benchmark. Causes the worker to exit at the next
     *  sample tick and report a failure. Restoring prefs is the worker's job. */
    fun cancel() {
        cancelRequested = true
    }

    @Volatile private var cancelRequested: Boolean = false

    private fun runWorker(ctx: Context, hooks: Hooks) {
        cancelRequested = false
        val prefs = LsfgPreferences(ctx)
        val originalConfig = prefs.load()

        // Snapshot now so even an early failure restores the user's prefs.
        val savedMultiplier = originalConfig.multiplier
        val savedFlowScale = originalConfig.flowScale
        val savedPerformance = originalConfig.performanceMode
        val savedAntiArtifacts = originalConfig.antiArtifacts
        val savedFramegenFp16 = originalConfig.framegenFp16
        val savedNpuEnabled = originalConfig.npuPostProcessingEnabled
        val savedNpuPreset = originalConfig.npuPostProcessingPreset
        val savedCpuEnabled = originalConfig.cpuPostProcessingEnabled
        val savedCpuPreset = originalConfig.cpuPostProcessingPreset
        val savedGpuEnabled = originalConfig.gpuPostProcessingEnabled
        val savedVsyncOverride = originalConfig.vsyncRefreshOverride

        // Decide up-front which precision modes are exercisable on this device.
        // FP16 needs both shaderFloat16 on the GPU and a populated FP16 SPIR-V
        // cache. The probe is the same one the UI uses to gate its toggle.
        val cacheDir = File(ctx.filesDir, "spirv").absolutePath
        val fp16Supported = runCatching {
            NativeBridge.isFramegenFp16Supported(cacheDir)
        }.getOrDefault(false)
        val precisionModes: List<PrecisionMode> = BenchmarkConfig.PRECISION_MODES
            .filter { it != PrecisionMode.FP16 || fp16Supported }
        Log.i(TAG, "precision modes for this run: ${precisionModes.joinToString { it.label }} " +
            "(fp16Supported=$fp16Supported)")

        try {
            // Apply the fixed benchmark preset (everything except multiplier
            // and the per-pass framegenFp16 flag).
            prefs.setFlowScale(BenchmarkConfig.FLOW_SCALE)
            prefs.setPerformance(BenchmarkConfig.PERFORMANCE_MODE)
            prefs.setAntiArtifacts(false)
            prefs.setNpuPostProcessingEnabled(false)
            prefs.setNpuPostProcessingPreset(NpuPostProcessingPreset.OFF)
            prefs.setCpuPostProcessingEnabled(false)
            prefs.setCpuPostProcessingPreset(CpuPostProcessingPreset.OFF)
            prefs.setGpuPostProcessingEnabled(false)
            // AUTO honours the display's max refresh — refresh rate is selected
            // at the system level (via the WindowManager.LayoutParams set by
            // the launching Activity), so we don't change it from here. The
            // MainActivity already requests a high refresh hint when the
            // benchmark route is on screen (see BenchmarkScreen).
            prefs.setVsyncRefreshOverride(VsyncRefreshOverride.AUTO)

            val totalRuns = precisionModes.size * BenchmarkConfig.MULTIPLIERS.size
            val results = ArrayList<BenchmarkRunResult>(totalRuns)
            val benchmarkStartMs = System.currentTimeMillis()
            var globalIdx = 0

            for (precision in precisionModes) {
                if (cancelRequested) break
                prefs.setFramegenFp16(precision.nativeFp16Flag)
                Log.i(TAG, "switching to precision=${precision.label}")

                for (mult in BenchmarkConfig.MULTIPLIERS) {
                    if (cancelRequested) {
                        val cancelState = State.Failed("Cancelled by user during run ${globalIdx + 1}")
                        state.set(cancelState)
                        postToMain { hooks.onFailed(cancelState) }
                        return
                    }
                    Log.i(TAG, "starting run ${globalIdx + 1}/$totalRuns " +
                        "precision=${precision.label} multiplier=$mult")
                    prefs.setMultiplier(mult)
                    hooks.requestReinit()

                    publishRunning(
                        runIndex = globalIdx,
                        totalRuns = totalRuns,
                        multiplier = mult,
                        precision = precision,
                        phase = Phase.WARMUP,
                        phaseDurationMs = BenchmarkConfig.WARMUP_MS,
                    )
                    sleepInterruptibly(BenchmarkConfig.WARMUP_MS)
                    if (cancelRequested) break

                    publishRunning(
                        runIndex = globalIdx,
                        totalRuns = totalRuns,
                        multiplier = mult,
                        precision = precision,
                        phase = Phase.SAMPLING,
                        phaseDurationMs = BenchmarkConfig.RUN_DURATION_SEC * 1000L,
                    )
                    val result = BenchmarkRunner.collect(
                        multiplier = mult,
                        flowScale = BenchmarkConfig.FLOW_SCALE,
                        performanceMode = BenchmarkConfig.PERFORMANCE_MODE,
                        framegenFp16 = precision.nativeFp16Flag,
                        durationMs = BenchmarkConfig.RUN_DURATION_SEC * 1000L,
                        sampleIntervalMs = BenchmarkConfig.SAMPLE_INTERVAL_MS,
                        nominalVsyncPeriodNs = hooks.vsyncPeriodNs(),
                        shouldStop = { cancelRequested },
                    )
                    results.add(result)
                    Log.i(TAG, "run ${globalIdx + 1} complete: precision=${precision.label} " +
                        "posted=${result.totalPostedFrames} " +
                        "fps=${"%.2f".format(result.postedFps)} stalls=${result.stallCount}")
                    globalIdx++
                }
            }

            if (cancelRequested) {
                val cancelState = State.Failed("Cancelled by user")
                state.set(cancelState)
                postToMain { hooks.onFailed(cancelState) }
                return
            }

            val benchmarkEndMs = System.currentTimeMillis()
            val text = BenchmarkLogWriter.format(
                ctx = ctx,
                results = results,
                startedAtMs = benchmarkStartMs,
                endedAtMs = benchmarkEndMs,
                targetPackage = hooks.targetPackage(),
                renderWidth = hooks.activeRenderWidth(),
                renderHeight = hooks.activeRenderHeight(),
            )
            val file = BenchmarkLogWriter.write(ctx, text)
            val completed = State.Completed(
                reportFile = file,
                results = results,
                targetPackage = hooks.targetPackage(),
                renderWidth = hooks.activeRenderWidth(),
                renderHeight = hooks.activeRenderHeight(),
            )
            state.set(completed)
            postToMain { hooks.onCompleted(completed) }
        } finally {
            // Restore user prefs unconditionally. Reinit is NOT triggered here
            // because the service is going to stop the session right after the
            // share intent fires; a reinit now would just wastefully rebuild
            // the native context for a few ms before tear-down.
            runCatching {
                prefs.setMultiplier(savedMultiplier)
                prefs.setFlowScale(savedFlowScale)
                prefs.setPerformance(savedPerformance)
                prefs.setAntiArtifacts(savedAntiArtifacts)
                prefs.setFramegenFp16(savedFramegenFp16)
                prefs.setNpuPostProcessingEnabled(savedNpuEnabled)
                prefs.setNpuPostProcessingPreset(savedNpuPreset)
                prefs.setCpuPostProcessingEnabled(savedCpuEnabled)
                prefs.setCpuPostProcessingPreset(savedCpuPreset)
                prefs.setGpuPostProcessingEnabled(savedGpuEnabled)
                prefs.setVsyncRefreshOverride(savedVsyncOverride)
            }.onFailure { Log.w(TAG, "restoring user prefs failed", it) }
        }
    }

    /** Reset to Idle. Caller (UI) invokes this after consuming a Completed/Failed
     *  state so a future run can start fresh. */
    fun acknowledge() {
        state.set(State.Idle)
    }

    private fun publishRunning(
        runIndex: Int,
        totalRuns: Int,
        multiplier: Int,
        precision: PrecisionMode,
        phase: Phase,
        phaseDurationMs: Long,
    ) {
        state.set(
            State.Running(
                runIndex = runIndex,
                totalRuns = totalRuns,
                multiplier = multiplier,
                precision = precision,
                phase = phase,
                phaseStartedAtMs = System.currentTimeMillis(),
                phaseDurationMs = phaseDurationMs,
            )
        )
    }

    private fun sleepInterruptibly(durationMs: Long) {
        val deadline = System.currentTimeMillis() + durationMs
        while (true) {
            val now = System.currentTimeMillis()
            if (now >= deadline || cancelRequested) return
            val remaining = (deadline - now).coerceAtMost(50L)
            try {
                Thread.sleep(remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun postToMain(block: () -> Unit) = mainHandler.post(block)
}
