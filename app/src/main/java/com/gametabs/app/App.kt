package com.gametabs.app

import android.app.Application
import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Boots the BlackBox virtual engine. This runs in EVERY process (host app + each
 * virtual game process), so keep it minimal.
 */
class App : Application() {

    companion object {
        lateinit var ctx: Context
            private set
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        ctx = base
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                // Keep the engine's splash screen ON. The game window is opened by the engine's
                // background process; Android 10+ only lets it appear if a foreground activity
                // (the splash) is on screen at that moment. Turning it off = game starts invisibly.
                override fun isEnableLauncherActivity(): Boolean = true
            })
        } catch (e: Throwable) {
            Log.e("engine attach failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            BlackBoxCore.get().doCreate()
        } catch (e: Throwable) {
            Log.e("engine create failed", e)
        }
    }
}
