package com.lsfg.android.benchmark

/**
 * Static benchmark configuration. The Modalita Benchmark runs three back-to-back
 * passes with these parameters held fixed across runs; only the LSFG multiplier
 * changes between runs.
 *
 * Rationale for the chosen preset:
 *   - flowScale 0.70 mimics the most common user setting on flagship Adreno 7xx
 *     devices and produces measurable optical-flow load without saturating it.
 *   - performance mode ON selects LSFG_3_1P (faster generator at the cost of
 *     small visual quality regressions vs 3_1).
 *   - all post-process categories OFF (NPU/CPU/GPU) so the benchmark measures
 *     framegen + blit only — post-process is an orthogonal axis tackled in
 *     later sprints.
 *   - vsync override AUTO so the active display refresh rate drives pacing.
 *   - antiArtifacts OFF for deterministic output count: every captured frame
 *     produces multiplier outputs, no skip-due-to-motion conditioning.
 */
object BenchmarkConfig {
    /** Multipliers exercised across the benchmark, in run order. */
    val MULTIPLIERS: IntArray = intArrayOf(2, 3, 4)

    /**
     * Precision modes exercised in order. When the device exposes
     * `shaderFloat16` (and the FP16 SPIR-V cache is populated) the benchmark
     * runs every multiplier twice — once with FP32 shaders, once with FP16 —
     * so a single shareable report contains directly comparable numbers from
     * the same thermal envelope. On devices without FP16 support, the
     * controller filters this list down to FP32 only at runtime.
     *
     * The ordering matters: FP32 first means the device starts cold for the
     * heavier path, mirroring the behaviour Lossless Scaling itself produces
     * on the desktop side.
     */
    val PRECISION_MODES: Array<PrecisionMode> = arrayOf(
        PrecisionMode.FP32,
        PrecisionMode.FP16,
    )

    /** Per-run sampling window length (seconds). */
    const val RUN_DURATION_SEC: Int = 60

    /** Sample collection interval (ms). 100 ms = 10 Hz. */
    const val SAMPLE_INTERVAL_MS: Long = 100L

    /** Wait this long after applying a new multiplier before sampling — covers
     *  the native context reinit (destroyContext + initContext) plus the first
     *  framegen window's worth of frames so initial spikes don't bias stats. */
    const val WARMUP_MS: Long = 2_000L

    /** Fixed flow scale across runs. */
    const val FLOW_SCALE: Float = 0.70f

    /** Fixed performance mode (LSFG_3_1P). */
    const val PERFORMANCE_MODE: Boolean = true
}

/**
 * Which shader precision a benchmark run targets.
 *
 *  - [FP32] uses the DXBC FP32 shader set (Lossless.dll resource IDs 255-302)
 *    translated to SPIR-V at extraction time.
 *  - [FP16] uses the precompiled SPIR-V FP16 variants (IDs 304-351), enabling
 *    `OpCapability Float16` and mixed FP16/FP32 ops on the GPU.
 */
enum class PrecisionMode(val nativeFp16Flag: Boolean, val label: String) {
    FP32(false, "FP32"),
    FP16(true, "FP16"),
}
