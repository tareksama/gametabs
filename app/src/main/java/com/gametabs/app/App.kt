package com.gametabs.app

import android.app.Application
import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Boots the BlackBox virtual engine. This runs in EVERY process (host app + each
 * virtual game process), so keep it minimal: no heavy work here or game start-up slows down.
 *
 * NOTE: package names / ClientConfiguration may differ slightly between BlackBox forks.
 * Match them to the Bcore version you copied in.
 */
class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                // Skip the engine's splash/loading screen: switching tabs goes straight to the game.
                override fun isEnableLauncherActivity(): Boolean = false
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        BlackBoxCore.get().doCreate()
    }
}
