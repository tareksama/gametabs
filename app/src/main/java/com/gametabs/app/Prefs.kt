package com.gametabs.app

import android.content.Context

/** Remembers the chosen game. */
object Prefs {
    private const val FILE = "gametabs"

    fun save(ctx: Context, pkg: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("pkg", pkg).apply()
    }

    fun load(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("pkg", null)
}
