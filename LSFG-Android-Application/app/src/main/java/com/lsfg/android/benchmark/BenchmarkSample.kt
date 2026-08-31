package com.lsfg.android.benchmark

/**
 * One sampling tick captured at SAMPLE_INTERVAL_MS cadence inside a run.
 * All counters are cumulative-since-session-start (we diff successive samples
 * to derive per-window rates and intervals when aggregating).
 */
data class BenchmarkSample(
    val elapsedMs: Long,
    val uniqueCaptures: Long,
    val generatedFrames: Long,
    val postedFrames: Long,
    /** Newest-first nanosecond intervals between consecutive overlay posts. */
    val recentIntervalsNs: LongArray,
    /** From getProfileWindowNs: [copyNs, presentNs, waitIdleNs, blitNs, totalNs, samples].
     *  null when no native profiling window has closed yet. */
    val profileWindow: LongArray?,
)

/**
 * Aggregated stats for a single benchmark run (one multiplier value).
 * Computed by [BenchmarkRunner] from the collected [BenchmarkSample]s.
 */
data class BenchmarkRunResult(
    val multiplier: Int,
    val flowScale: Float,
    val performanceMode: Boolean,
    /** Which shader-precision path produced this result. The benchmark runs
     *  every multiplier twice on FP16-capable devices so the report can show
     *  FP32 vs FP16 numbers side by side. False on devices without FP16. */
    val framegenFp16: Boolean,
    val runDurationMs: Long,
    val totalUniqueCaptures: Long,
    val totalGeneratedFrames: Long,
    val totalPostedFrames: Long,
    val realFps: Double,
    val generatedFps: Double,
    val postedFps: Double,
    val pacingMs: PacingStats?,
    val profile: ProfileStats?,
    val vsyncAlignmentPercent: Double?,
    val stallCount: Int,
)

data class PacingStats(
    val sampleCount: Int,
    val minMs: Double,
    val p50Ms: Double,
    val p90Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val meanMs: Double,
    val stddevMs: Double,
    val jitterRatio: Double,
)

data class ProfileStats(
    val samples: Long,
    val copyMs: Double,
    val presentMs: Double,
    val waitIdleMs: Double,
    val blitMs: Double,
    val totalMs: Double,
)
