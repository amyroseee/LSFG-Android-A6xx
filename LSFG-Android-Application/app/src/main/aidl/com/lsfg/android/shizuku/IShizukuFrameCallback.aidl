package com.lsfg.android.shizuku;

import android.hardware.HardwareBuffer;

interface IShizukuFrameCallback {
    void onFrame(in HardwareBuffer buffer, long timestampNs);
    void onFrameMetrics(long timestampNs, long frameTimeNs, long pacingJitterNs);
    void onError(String message);
}
