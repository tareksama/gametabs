package com.gametabs.app

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.widget.Toast

/**
 * One tab = one real install of the game in one Android profile:
 *   - your normal phone profile
 *   - a work profile (made by Shelter or Island), or any other profile Android exposes
 *
 * No cloning or injection: each copy is the genuine game, installed and run by Android itself.
 * Switching uses the same system call a home-screen launcher uses, so a copy that is
 * already running comes straight back to the front with no reload.
 */
data class GameCopy(val user: UserHandle, val label: String)

object Copies {

    var packageName: String? = null
        private set
    private var copies: List<GameCopy> = emptyList()
    var active: UserHandle? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() { main.post { listeners.toList().forEach { it() } } }

    fun all() = copies

    private fun launcher(ctx: Context) = ctx.getSystemService(LauncherApps::class.java)

    /** Finds every profile where [pkg] is installed. */
    fun scan(ctx: Context, pkg: String): List<GameCopy> {
        val la = launcher(ctx)
        val me = Process.myUserHandle()
        val profiles = la.profiles.sortedBy { if (it == me) 0 else 1 }
        var n = 0
        val found = profiles.mapNotNull { user ->
            val ok = runCatching { la.getActivityList(pkg, user).isNotEmpty() }.getOrDefault(false)
            if (ok) GameCopy(user, "Game ${++n}") else null
        }
        packageName = pkg
        copies = found
        Log.i("Found $pkg in ${found.size} profile(s) out of ${profiles.size}")
        notifyChanged()
        return found
    }

    /** Re-scan, e.g. after the user installs the game in a new work profile. */
    fun rescan(ctx: Context) { packageName?.let { scan(ctx, it) } }

    fun switchTo(ctx: Context, copy: GameCopy) {
        val pkg = packageName ?: return
        try {
            val la = launcher(ctx)
            val act = la.getActivityList(pkg, copy.user).firstOrNull()
                ?: return fail(ctx, "${copy.label} is no longer installed in that profile")
            la.startMainActivity(act.componentName, copy.user, null, null)
            active = copy.user
            Log.i("Switched to ${copy.label}")
            notifyChanged()
        } catch (e: Throwable) {
            Log.e("Could not open ${copy.label}", e)
            fail(ctx, "Could not open ${copy.label}: ${e.message}")
        }
    }

    private fun fail(ctx: Context, msg: String) {
        Log.e(msg)
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
