package com.lsfg.android

import android.app.Application
import com.lsfg.android.session.CrashReporter
import com.lsfg.android.session.LsfgLog

class LsfgApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install the crash reporter as early as possible so even a crash during
        // the first JNI load attempt gets captured (NativeBridge static init
        // already loads the .so; the call below only configures the handler).
        CrashReporter.install(this)
        LsfgLog.init(this)
    }
}
