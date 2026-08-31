package com.lsfg.android.session

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import com.lsfg.android.BuildConfig
import com.lsfg.android.shizuku.IShizukuCaptureService
import com.lsfg.android.shizuku.IShizukuFrameCallback
import rikka.shizuku.Shizuku

class ShizukuCaptureEngine(
    private val ctx: Context,
) {
    fun interface ErrorListener {
        fun onError(message: String)
    }

    @Volatile
    private var service: IShizukuCaptureService? = null
    @Volatile
    private var pendingStart: StartArgs? = null
    @Volatile
    private var errorListener: ErrorListener? = null
    @Volatile
    private var metricsOnly: Boolean = false
    @Volatile
    private var fpsListener: CaptureEngine.FpsListener? = null
    @Volatile
    private var graphListener: CaptureEngine.FrameGraphListener? = null
    private var metricsThread: HandlerThread? = null
    private var metricsHandler: Handler? = null
    private var fpsPoller: Runnable? = null
    private var graphPoller: Runnable? = null
    @Volatile
    private var fpsFrameCount: Long = 0
    @Volatile
    private var graphFrameCount: Long = 0
    private var fpsWindowStartMs: Long = 0L
    private var graphWindowStartMs: Long = 0L
    private var lastGeneratedCount: Long = 0L
    private var graphLastGeneratedCount: Long = 0L
    private var lastUniqueCaptureCount: Long = 0L
    private var graphLastUniqueCaptureCount: Long = 0L
    private var lastPostedCount: Long = 0L
    private var graphRealEma: Float = 0f
    private var graphGenEma: Float = 0f

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IShizukuCaptureService.Stub.asInterface(it) }
            val start = pendingStart
            if (start != null) {
                startCapture(start.targetPackage, start.width, start.height, start.maxFps)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    fun setErrorListener(listener: ErrorListener?) {
        errorListener = listener
    }

    fun setFpsListener(listener: CaptureEngine.FpsListener?) {
        fpsListener = listener
    }

    fun setFrameGraphListener(listener: CaptureEngine.FrameGraphListener?) {
        graphListener = listener
    }

    fun isReady(): Boolean {
        return runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun startCapture(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        metricsOnly = false
        startCaptureInternal(targetPackage, width, height, maxFps)
    }

    fun startMetricsOnly(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        metricsOnly = true
        startCaptureInternal(targetPackage, width, height, maxFps)
    }

    private fun startCaptureInternal(targetPackage: String, width: Int, height: Int, maxFps: Int) {
        val targetUid = runCatching {
            ctx.packageManager.getApplicationInfo(targetPackage, 0).uid
        }.getOrElse {
            errorListener?.onError("Target package not found: $targetPackage")
            return
        }

        val args = StartArgs(targetPackage, width, height, maxFps)
        pendingStart = args
        val svc = service
        if (svc == null || !svc.asBinder().pingBinder()) {
            bind()
            return
        }
        runCatching {
            svc.startCapture(targetUid, width, height, maxFps, frameCallback)
            LsfgLog.i(TAG, "Shizuku ${if (metricsOnly) "metrics" else "capture"} started package=$targetPackage uid=$targetUid ${width}x${height}")
        }.onFailure {
            LsfgLog.w(TAG, "Shizuku startCapture failed", it)
            errorListener?.onError("Shizuku start failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun stop() {
        pendingStart = null
        stopFpsCounter()
        stopFrameGraph()
        runCatching { service?.stopCapture() }
        runCatching { Shizuku.unbindUserService(userServiceArgs(), connection, true) }
        service = null
    }

    fun pauseCapture() {
        pendingStart = null
        runCatching { service?.stopCapture() }
    }

    @Synchronized
    fun startFpsCounter() {
        if (fpsPoller != null) return
        val h = ensureMetricsThread()
        fpsWindowStartMs = SystemClock.elapsedRealtime()
        lastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        lastPostedCount = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - fpsWindowStartMs).coerceAtLeast(1L)
                fpsWindowStartMs = now

                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - lastUniqueCaptureCount).coerceAtLeast(0L)
                lastUniqueCaptureCount = uniqNow
                val realFps = uniqDelta * 1000f / elapsed

                val postedNow = runCatching { NativeBridge.getPostedFrameCount() }.getOrDefault(0L)
                val postedDelta = (postedNow - lastPostedCount).coerceAtLeast(0L)
                lastPostedCount = postedNow
                val totalFps = postedDelta * 1000f / elapsed

                fpsListener?.onFpsUpdate(realFps, totalFps)
                metricsHandler?.postDelayed(this, 1000L)
            }
        }
        fpsPoller = poll
        h.postDelayed(poll, 1000L)
        LsfgLog.i(TAG, "Shizuku FPS counter started (native counters)")
    }

    @Synchronized
    fun stopFpsCounter() {
        fpsPoller?.let { metricsHandler?.removeCallbacks(it) }
        fpsPoller = null
        fpsFrameCount = 0
        maybeQuitMetricsThread()
    }

    @Synchronized
    fun startFrameGraph() {
        if (graphPoller != null) return
        val h = ensureMetricsThread()
        graphWindowStartMs = SystemClock.elapsedRealtime()
        graphLastUniqueCaptureCount = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
        graphLastGeneratedCount = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
        graphRealEma = 0f
        graphGenEma = 0f
        val poll = object : Runnable {
            override fun run() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - graphWindowStartMs).coerceAtLeast(1L)
                graphWindowStartMs = now

                val uniqNow = runCatching { NativeBridge.getUniqueCaptureCount() }.getOrDefault(0L)
                val uniqDelta = (uniqNow - graphLastUniqueCaptureCount).coerceAtLeast(0L)
                graphLastUniqueCaptureCount = uniqNow
                val realFpsRaw = uniqDelta * 1000f / elapsed

                val generated = runCatching { NativeBridge.getGeneratedFrameCount() }.getOrDefault(0L)
                val genDelta = (generated - graphLastGeneratedCount).coerceAtLeast(0L)
                graphLastGeneratedCount = generated
                val genFpsRaw = genDelta * 1000f / elapsed

                val alpha = 0.35f
                graphRealEma = if (graphRealEma <= 0.01f) realFpsRaw
                               else alpha * realFpsRaw + (1f - alpha) * graphRealEma
                graphGenEma = if (graphGenEma <= 0.01f) genFpsRaw
                              else alpha * genFpsRaw + (1f - alpha) * graphGenEma

                graphListener?.onFrameGraphSample(graphRealEma, graphGenEma)
                metricsHandler?.postDelayed(this, 200L)
            }
        }
        graphPoller = poll
        h.postDelayed(poll, 200L)
        LsfgLog.i(TAG, "Shizuku frame graph started (native counters)")
    }

    @Synchronized
    fun stopFrameGraph() {
        graphPoller?.let { metricsHandler?.removeCallbacks(it) }
        graphPoller = null
        graphFrameCount = 0
        maybeQuitMetricsThread()
    }

    private fun ensureMetricsThread(): Handler {
        val existing = metricsHandler
        if (existing != null) return existing
        val t = HandlerThread("lsfg-shizuku-metrics").also { it.start() }
        val h = Handler(t.looper)
        metricsThread = t
        metricsHandler = h
        return h
    }

    private fun maybeQuitMetricsThread() {
        if (fpsPoller == null && graphPoller == null) {
            metricsThread?.quitSafely()
            metricsThread = null
            metricsHandler = null
        }
    }

    private fun bind() {
        if (!isReady()) {
            errorListener?.onError("Shizuku is not running or permission is missing")
            return
        }
        runCatching {
            Shizuku.bindUserService(userServiceArgs(), connection)
        }.onFailure {
            LsfgLog.w(TAG, "bindUserService failed", it)
            errorListener?.onError("Shizuku bind failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, ShizukuCaptureUserService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("capture")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("lsfg_capture")

    private val frameCallback = object : IShizukuFrameCallback.Stub() {
        override fun onFrame(buffer: HardwareBuffer, timestampNs: Long) {
            fpsFrameCount++
            graphFrameCount++
            try {
                if (!metricsOnly) {
                    NativeBridge.pushFrame(buffer, timestampNs)
                }
            } catch (t: Throwable) {
                LsfgLog.w(TAG, "pushFrame from Shizuku failed", t)
            } finally {
                runCatching { buffer.close() }
            }
        }

        override fun onError(message: String?) {
            errorListener?.onError(message ?: "Unknown Shizuku capture error")
        }

        override fun onFrameMetrics(timestampNs: Long, frameTimeNs: Long, pacingJitterNs: Long) {
            NativeBridge.reportShizukuTiming(timestampNs, frameTimeNs, pacingJitterNs)
            if (frameTimeNs > 0L && pacingJitterNs > frameTimeNs) {
                LsfgLog.w(
                    TAG,
                    "Shizuku pacing spike frame=${frameTimeNs / 1_000_000.0}ms jitter=${pacingJitterNs / 1_000_000.0}ms",
                )
            }
        }
    }

    private data class StartArgs(
        val targetPackage: String,
        val width: Int,
        val height: Int,
        val maxFps: Int,
    )

    companion object {
        private const val TAG = "ShizukuCapture"

        fun requestPermission(requestCode: Int) {
            if (Shizuku.isPreV11()) throw RemoteException("Shizuku pre-v11 is not supported")
            Shizuku.requestPermission(requestCode)
        }
    }
}
