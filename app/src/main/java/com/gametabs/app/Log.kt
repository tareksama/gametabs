package com.gametabs.app

import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-app log so problems are visible on the phone without a PC.
 * [dumpForSharing] also includes Android's logcat for this app (apps may read their own logs),
 * which contains the engine's internal messages and any crash stack traces.
 */
object Log {
    private const val TAG = "GameTabs"
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    var onNewLine: ((String) -> Unit)? = null

    @Synchronized
    private fun add(level: String, msg: String) {
        val line = "${fmt.format(Date())} $level $msg"
        lines.addLast(line)
        while (lines.size > 200) lines.removeFirst()
        onNewLine?.invoke(line)
    }

    fun i(msg: String) { android.util.Log.i(TAG, msg); add("I", msg) }

    fun e(msg: String, t: Throwable? = null) {
        android.util.Log.e(TAG, msg, t)
        add("E", if (t != null) "$msg: ${t.javaClass.simpleName}: ${t.message}" else msg)
    }

    @Synchronized
    fun recent(): String = lines.joinToString("\n")

    fun dumpForSharing(): String {
        val device = "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), " +
            "ABIs=${android.os.Build.SUPPORTED_ABIS.joinToString()}"
        val logcat = try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "1500"))
            p.inputStream.bufferedReader().readText()
        } catch (e: Throwable) {
            "logcat unavailable: ${e.message}"
        }
        return "$device\nuid=${Process.myUid()}\n\n=== GameTabs log ===\n${recent()}\n\n=== logcat ===\n$logcat"
    }
}
