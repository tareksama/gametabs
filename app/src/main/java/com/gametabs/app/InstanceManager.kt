package com.gametabs.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

/**
 * One "tab" = one copy of the game running inside its own BlackBox virtual user.
 * Each virtual user has its own data dir, so each instance has its own login / save / account.
 */
data class GameInstance(
    val userId: Int,
    val packageName: String,
    var label: String,
    var running: Boolean = false
)

object InstanceManager {

    private val instances = mutableListOf<GameInstance>()
    var activeUserId: Int = -1
        private set

    /** Listeners (tab bar) get notified when tabs change. */
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = listeners.toList().forEach { it() }

    fun all(): List<GameInstance> = instances.toList()

    /**
     * Clones [packageName] into [count] isolated virtual users.
     * Installing is slow (copies/optimises the APK), so it runs off the main thread, and only once:
     * later launches reuse the installed copy.
     */
    suspend fun prepare(ctx: Context, packageName: String, count: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val core = BlackBoxCore.get()
                instances.removeAll { it.packageName == packageName }
                for (userId in 0 until count) {
                    if (core.getUsers().none { it.id == userId }) core.createUser(userId)
                    if (!core.isInstalled(packageName, userId)) {
                        val res = core.installPackageAsUser(packageName, userId)
                        check(res.success) { "Install failed for user $userId: ${res.msg}" }
                    }
                    instances += GameInstance(userId, packageName, "Game ${userId + 1}")
                }
                Prefs.save(ctx, packageName, count)
                withContext(Dispatchers.Main) { notifyChanged() }
            }
        }

    /**
     * Switch to an instance. When the instance is already running, BlackBox just brings its
     * existing task to the front: no restart, no reload, near-instant. A first launch cold-starts it.
     */
    fun switchTo(userId: Int) {
        val inst = instances.firstOrNull { it.userId == userId } ?: return
        if (activeUserId == userId && inst.running) return
        BlackBoxCore.get().launchApk(inst.packageName, inst.userId)
        inst.running = true
        activeUserId = userId
        notifyChanged()
    }

    /** Kills one instance to free RAM / CPU (closing a tab). */
    fun close(userId: Int) {
        val inst = instances.firstOrNull { it.userId == userId } ?: return
        BlackBoxCore.get().stopPackage(inst.packageName, inst.userId)
        inst.running = false
        if (activeUserId == userId) activeUserId = -1
        notifyChanged()
    }

    fun rename(userId: Int, label: String) {
        instances.firstOrNull { it.userId == userId }?.label = label
        notifyChanged()
    }
}
