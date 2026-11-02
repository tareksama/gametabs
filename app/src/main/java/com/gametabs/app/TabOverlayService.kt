package com.gametabs.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Minimal floating switcher: a small pill with one circle per copy.
 *
 *      ( ① ② × )          close mode:  ( ❶ ❷ ⏻ ↩ )
 *
 *  - Tap a circle → switch to that copy (filled = on screen).
 *  - Tap × → close mode: tap a red number to close that copy, ⏻ to exit GameTabs, ↩ to cancel.
 *  - Drag anywhere on the pill → move it; position is remembered.
 *  - Long-press → open GameTabs.
 *
 * Costs nothing per frame: it's a tiny static window, redrawn only when you switch.
 */
class TabOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var pill: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private val listener: () -> Unit = { render() }
    private var closeMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        build()
        Copies.addListener(listener)
        render()
    }

    override fun onDestroy() {
        Copies.removeListener(listener)
        runCatching { wm.removeView(pill) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun startAsForeground() {
        val chId = "tabs"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(chId, "Game tabs", NotificationManager.IMPORTANCE_MIN)
        )
        val n: Notification = Notification.Builder(this, chId)
            .setContentTitle("GameTabs")
            .setContentText("Switcher active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private val prefs by lazy { getSharedPreferences("overlay", Context.MODE_PRIVATE) }

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(8))
            y = prefs.getInt("y", dp(80))
        }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#99000000"))
            }
            alpha = 0.85f
        }
        wm.addView(pill, params)
    }

    private fun render() {
        pill.removeAllViews()
        val red = Color.parseColor("#FFEF4444")
        Copies.all().forEachIndexed { i, c ->
            val n = (i + 1).toString()
            if (closeMode) {
                pill.addView(circle(n, fill = red) { closeMode = false; Copies.close(this, c); render() })
            } else {
                val active = c.user == Copies.active
                pill.addView(circle(n, fill = if (active) Color.parseColor("#FF34D399") else null) {
                    Copies.switchTo(this, c)
                })
            }
        }
        if (closeMode) {
            pill.addView(circle("⏻", fill = red) { Copies.exitApp(this) })
            pill.addView(circle("↩", fill = null) { closeMode = false; render() })
        } else {
            pill.addView(circle("×", fill = null, small = true) { closeMode = true; render() })
        }
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** A 30dp circle that taps, drags the whole pill, or long-presses to open GameTabs. */
    @SuppressLint("ClickableViewAccessibility")
    private fun circle(label: String, fill: Int?, small: Boolean = false, onTap: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = if (small) 12f else 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (fill != null) Color.BLACK else Color.WHITE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (fill != null) setColor(fill)
            else { setColor(Color.TRANSPARENT); setStroke(dp(if (small) 1 else 2), Color.WHITE) }
        }
        val size = if (small) dp(22) else dp(30)
        layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(dp(2), 0, dp(2), 0) }

        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        var sx = 0f; var sy = 0f; var px = 0; var py = 0; var moved = false; var longFired = false
        val longRunnable = Runnable { longFired = true; openHome() }

        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; px = params.x; py = params.y
                    moved = false; longFired = false
                    v.postDelayed(longRunnable, longPress)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true; v.removeCallbacks(longRunnable)
                    }
                    if (moved) {
                        params.x = px + dx.toInt(); params.y = py + dy.toInt()
                        wm.updateViewLayout(pill, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longRunnable)
                    if (moved) prefs.edit().putInt("x", params.x).putInt("y", params.y).apply()
                    else if (!longFired) onTap()
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longRunnable)
            }
            true
        }
    }
}
