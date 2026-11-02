package com.gametabs.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Foreground service that:
 *  1. Draws a small floating, draggable tab bar ABOVE the game (games are fullscreen
 *     activities, so a normal in-app tab bar can't show on top of them).
 *  2. Keeps the host process at foreground priority, which makes Android much less likely
 *     to kill background game instances. This is what keeps switching instant.
 *
 * Latency notes:
 *  - The overlay is tiny, uses FLAG_NOT_FOCUSABLE (never steals input from the game outside
 *    its own bounds) and only redraws when tabs change, so it costs no frame time.
 *  - The bar can be collapsed to a single bubble so it doesn't cover the game.
 */
class TabOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private var collapsed = false
    private val listener: () -> Unit = { renderTabs() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        InstanceManager.addListener(listener)
        renderTabs()
    }

    override fun onDestroy() {
        InstanceManager.removeListener(listener)
        runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startAsForeground() {
        val chId = "tabs"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(chId, "Game instances", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n: Notification = Notification.Builder(this).apply {
            if (Build.VERSION.SDK_INT >= 26) setChannelId(chId)
        }
            .setContentTitle("GameTabs running")
            .setContentText("Instances kept alive for instant switching")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlay() {
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 24
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 40f; setColor(Color.parseColor("#CC111318"))
            }
            setPadding(10, 8, 10, 8)
        }

        // Drag handle / collapse toggle
        val handle = pill("⇆", Color.parseColor("#FF3D5AFE"))
        var downX = 0; var downY = 0; var startX = 0f; var startY = 0f; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = params.x; downY = params.y; startX = e.rawX; startY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX; val dy = e.rawY - startY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = downX + dx.toInt(); params.y = downY + dy.toInt()
                    wm.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> if (!moved) toggleCollapse()
            }
            true
        }
        root.addView(handle)

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })

        wm.addView(root, params)
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        (root.getChildAt(1)).visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        InstanceManager.all().forEach { inst ->
            val active = inst.userId == InstanceManager.activeUserId
            val color = when {
                active -> "#FF00C853"     // green = on screen
                inst.running -> "#FF546E7A" // grey-blue = alive in background
                else -> "#FF263238"        // dark = not started
            }
            val tab = pill(inst.label, Color.parseColor(color))
            tab.setOnClickListener { InstanceManager.switchTo(inst.userId) }
            tab.setOnLongClickListener { InstanceManager.close(inst.userId); true }
            tabRow.addView(tab)
        }
        // "Home" tab returns to the GameTabs setup screen
        tabRow.addView(pill("⌂", Color.parseColor("#FF263238")).apply {
            setOnClickListener {
                startActivity(Intent(this@TabOverlayService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        })
    }

    private fun pill(text: String, bg: Int) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(28, 14, 28, 14)
        background = GradientDrawable().apply { cornerRadius = 32f; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = 6; marginEnd = 6 }
    }
}
