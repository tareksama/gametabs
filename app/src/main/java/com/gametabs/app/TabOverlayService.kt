package com.gametabs.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Browser-style tab strip docked to the top of the screen, above whichever game copy is open.
 *
 *   [● Game 1][● Game 2][ + ]                     [⌂][▴]
 *
 *  - Tap a tab: switch to that copy (instant if it's already running).
 *  - +: how to add another copy.  ⌂: GameTabs.  ▴: hide the strip.
 *  - When hidden, a small "▾ Tabs" handle stays at the top edge to bring it back.
 *
 * Runs as a foreground service so Android keeps the tab bar alive.
 */
class TabOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bar: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var handle: TextView
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var handleParams: WindowManager.LayoutParams
    private val listener: () -> Unit = { renderTabs() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildBar()
        buildHandle()
        Copies.addListener(listener)
        renderTabs()
    }

    override fun onDestroy() {
        Copies.removeListener(listener)
        runCatching { wm.removeView(bar) }
        runCatching { wm.removeView(handle) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun startAsForeground() {
        val chId = "tabs"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(chId, "Game tabs", NotificationManager.IMPORTANCE_MIN)
            )
        }
        @Suppress("DEPRECATION")
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId)
        else Notification.Builder(this))
            .setContentTitle("GameTabs running")
            .setContentText("Tab bar active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun buildBar() {
        barParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, dp(40))
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F0202124"))
            setPadding(dp(4), 0, dp(4), 0)
        }
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))

        bar.addView(iconButton("⌂") { openHome() })
        bar.addView(iconButton("▴") { setCollapsed(true) })
        wm.addView(bar, barParams)
    }

    @SuppressLint("SetTextI18n")
    private fun buildHandle() {
        handleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        handle = TextView(this).apply {
            text = "▾ Tabs"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(14), dp(2), dp(14), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC202124"))
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
            }
            visibility = View.GONE
            setOnClickListener { setCollapsed(false) }
        }
        wm.addView(handle, handleParams)
    }

    private fun setCollapsed(c: Boolean) {
        bar.visibility = if (c) View.GONE else View.VISIBLE
        handle.visibility = if (c) View.VISIBLE else View.GONE
    }

    private fun renderTabs() {
        tabRow.removeAllViews()
        Copies.all().forEach { c -> tabRow.addView(tab(c)) }
        // "+" opens GameTabs, which explains how to add another copy and re-scans.
        tabRow.addView(iconButton("+") { openHome() })
    }

    private fun openHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun tab(c: GameCopy): View {
        val active = c.user == Copies.active
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (active) "#FF3C4043" else "#00000000"))
                cornerRadii = floatArrayOf(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), 0f, 0f, 0f, 0f)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
                .apply { topMargin = dp(6); marginEnd = dp(2) }
            setOnClickListener { Copies.switchTo(this@TabOverlayService, c) }
        }
        row.addView(TextView(this).apply {
            text = "●"
            setTextColor(Color.parseColor(if (active) "#FF34A853" else "#FF5F6368"))
            textSize = 10f
            setPadding(0, 0, dp(6), 0)
        })
        row.addView(TextView(this).apply {
            text = c.label
            setTextColor(Color.parseColor(if (active) "#FFFFFFFF" else "#FFBDC1C6"))
            textSize = 13f
            if (active) setTypeface(typeface, Typeface.BOLD)
        })
        return row
    }

    private fun iconButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        setOnClickListener { onClick() }
    }
}
