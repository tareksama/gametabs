package com.gametabs.app

import android.content.Context

/** Remembers the last game + instance count so setup only happens once. */
object Prefs {
    private const val FILE = "gametabs"

    fun save(ctx: Context, pkg: String, count: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("pkg", pkg).putInt("count", count).apply()
    }

    fun load(ctx: Context): Pair<String, Int>? {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val pkg = p.getString("pkg", null) ?: return null
        return pkg to p.getInt("count", 2)
    }
}
