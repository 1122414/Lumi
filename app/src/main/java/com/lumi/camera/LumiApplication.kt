package com.lumi.camera

import android.app.Application
import com.lumi.camera.observability.LumiCrashGuard
import com.lumi.camera.observability.LumiTelemetry

class LumiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LumiTelemetry.markProcessStart()
        LumiCrashGuard.install(this)
    }
}
