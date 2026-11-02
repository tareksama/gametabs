package com.gametabs.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

/**
 * One tab = one copy of the game in its own BlackBox virtual user
 * (own process, own data dir, own login).
 */
data class GameInstance(
    val userId: Int,
    val packageName: String,
    var label: String,
    var running: Boolean = false,
    /** Android task that holds this copy's window, once we've seen it appear. */
    var taskId: Int = -1
)

object InstanceManager {

    private val instances = mutableListOf<GameInstance>()
    private val main = Handler(Looper.getMainLooper())

    var packageName: String? = null
        private set
    var activeUserId: Int = -1
        private set

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = main.post { listeners.toList().forEach { it() } }

    fun all(): List<GameInstance> = instances.toList()

    private fun am(ctx: Context) = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    @Suppress("DEPRECATION")
    private fun ActivityManager.AppTask.id(): Int =
        if (Build.VERSION.SDK_INT >= 29) taskInfo.taskId else taskInfo.persistentId

    /** Our tasks that hold a game window (skips GameTabs' own screen and the engine splash). */
    private fun liveTaskIds(ctx: Context): Set<Int> =
        runCatching {
            am(ctx).appTasks.filter { t ->
                val base = if (Build.VERSION.SDK_INT >= 23) t.taskInfo.baseActivity?.className else null
                base == null || (!base.endsWith("LauncherActivity") && !base.endsWith(".MainActivity"))
            }.map { it.id() }.toSet()
        }.getOrDefault(emptySet())

    /** Clone the game into [count] virtual users (first time is slow, later instant). */
    suspend fun prepare(ctx: Context, pkg: String, count: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (pkg != packageName) instances.clear()
                packageName = pkg
                for (userId in 0 until count) ensureInstalled(pkg, userId)
                Prefs.save(ctx, pkg, count)
                notifyChanged()
            }
        }

    /** Adds one more copy (the "+" tab). Call off the main thread. */
    fun addInstance(ctx: Context): GameInstance? {
        val pkg = packageName ?: return null
        val next = (instances.maxOfOrNull { it.userId } ?: -1) + 1
        return try {
            ensureInstalled(pkg, next)
            Prefs.save(ctx, pkg, instances.size)
            notifyChanged()
            instances.first { it.userId == next }
        } catch (e: Throwable) {
            Log.e("Could not add copy ${next + 1}", e)
            toast(ctx, "Could not add a copy: ${e.message}")
            null
        }
    }

    private fun ensureInstalled(pkg: String, userId: Int) {
        val core = BlackBoxCore.get()
        if (core.users.none { it.id == userId }) {
            core.createUser(userId)
            Log.i("Created virtual user $userId")
        }
        if (!core.isInstalled(pkg, userId)) {
            Log.i("Installing $pkg for copy ${userId + 1}…")
            val res = core.installPackageAsUser(pkg, userId)
            check(res.success) { "Install failed for copy ${userId + 1}: ${res.msg}" }
            Log.i("Installed copy ${userId + 1}")
        }
        if (instances.none { it.userId == userId }) {
            instances += GameInstance(userId, pkg, "Game ${userId + 1}")
        }
    }

    /**
     * Switch tabs.
     *  - Copy already running → bring its existing window/task to the front (instant, no reload).
     *  - Not running yet → cold-start it through the engine (shows the engine's splash, then the game).
     */
    fun switchTo(ctx: Context, userId: Int) {
        val inst = instances.firstOrNull { it.userId == userId } ?: return

        if (inst.taskId != -1) {
            val task = runCatching { am(ctx).appTasks.firstOrNull { it.id() == inst.taskId } }.getOrNull()
            if (task != null) {
                try {
                    task.moveToFront()
                    activeUserId = userId
                    notifyChanged()
                    return
                } catch (e: Throwable) {
                    Log.e("moveToFront failed for ${inst.label}, relaunching", e)
                }
            } else {
                Log.i("${inst.label} window was closed, relaunching")
            }
            inst.taskId = -1
        }

        val before = liveTaskIds(ctx)
        Log.i("Launching ${inst.label} (${inst.packageName}, user $userId)")
        val ok = try {
            BlackBoxCore.get().launchApk(inst.packageName, inst.userId)
        } catch (e: Throwable) {
            Log.e("Launch threw for ${inst.label}", e)
            false
        }
        if (!ok) {
            Log.e("Engine could not launch ${inst.label}: no launch activity found in the clone")
            toast(ctx, "Could not start ${inst.label}. Open GameTabs → Share log and send it.")
            return
        }
        inst.running = true
        activeUserId = userId
        notifyChanged()
        watchForNewTask(ctx, inst, before, attempt = 0)
    }

    /** The game window lives in a brand-new task. Find it so later switches can jump straight to it. */
    private fun watchForNewTask(ctx: Context, inst: GameInstance, before: Set<Int>, attempt: Int) {
        main.postDelayed({
            val mainTask = MainActivity.taskIdOrMinus1
            val fresh = liveTaskIds(ctx) - before - mainTask
            val taken = instances.map { it.taskId }.toSet()
            val found = fresh.firstOrNull { it !in taken }
            when {
                found != null -> {
                    inst.taskId = found
                    Log.i("${inst.label} is on screen (task $found)")
                }
                attempt < 40 -> watchForNewTask(ctx, inst, before, attempt + 1)
                else -> {
                    Log.e("${inst.label}: no game window appeared after 20 s. " +
                        "The game may have crashed inside the engine, or this Android version is not supported.")
                    toast(ctx, "${inst.label} didn't appear. Open GameTabs → Share log and send it.")
                }
            }
        }, 500)
    }

    fun close(userId: Int) {
        val inst = instances.firstOrNull { it.userId == userId } ?: return
        runCatching { BlackBoxCore.get().stopPackage(inst.packageName, inst.userId) }
            .onFailure { Log.e("stop failed", it) }
        inst.running = false
        inst.taskId = -1
        if (activeUserId == userId) activeUserId = -1
        Log.i("Closed ${inst.label}")
        notifyChanged()
    }

    private fun toast(ctx: Context, s: String) =
        main.post { Toast.makeText(ctx.applicationContext, s, Toast.LENGTH_LONG).show() }
}
