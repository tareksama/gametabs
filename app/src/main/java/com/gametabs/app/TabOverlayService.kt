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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Browser-style tab strip docked to the top of the screen, above whichever game copy is open.
 *
 *   [ Game 1  × ][ Game 2  × ][ + ]                     [⌂][▴]
 *
 *  - Tap a tab: switch to that copy (instant if it's already running).
 *  - ×: close that copy.  +: add another copy.  ⌂: GameTabs settings.  ▴: hide the strip.
 *  - When hidden, a small "▾ Tabs" handle stays at the top edge to bring it back.
 *
 * It is also a foreground service, which keeps background copies alive for instant switching.
 */
class TabOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var bar: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var handle: TextView
    private lateinit var barParams: WindowManager.LayoutParams
    private lateinit var handleParams: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listener: () -> Unit = { renderTabs() }
    private var adding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildBar()
        buildHandle()
        InstanceManager.addListener(listener)
        renderTabs()
    }

    override fun onDestroy() {
        InstanceManager.removeListener(listener)
        scope.cancel()
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
            .setContentText("Keeping your game copies ready")
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

        bar.addView(iconButton("⌂") {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        })
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
        InstanceManager.all().forEach { inst -> tabRow.addView(tab(inst)) }
        tabRow.addView(iconButton(if (adding) "…" else "+") { addTab() })
    }

    private fun addTab() {
        if (adding) return
        adding = true
        renderTabs()
        scope.launch {
            val inst = kotlinx.coroutines.withContext(Dispatchers.IO) { InstanceManager.addInstance(this@TabOverlayService) }
            adding = false
            renderTabs()
            inst?.let { InstanceManager.switchTo(this@TabOverlayService, it.userId) }
        }
    }

    private fun tab(inst: GameInstance): View {
        val active = inst.userId == InstanceManager.activeUserId
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (active) "#FF3C4043" else "#00000000"))
                cornerRadii = floatArrayOf(dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), 0f, 0f, 0f, 0f)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34))
                .apply { topMargin = dp(6); marginEnd = dp(2) }
            setOnClickListener { InstanceManager.switchTo(this@TabOverlayService, inst.userId) }
        }
        // status dot: green = on screen, grey = running in background, hollow = not started
        row.addView(TextView(this).apply {
            text = if (inst.running) "●" else "○"
            setTextColor(Color.parseColor(if (active) "#FF34A853" else "#FF9AA0A6"))
            textSize = 10f
            setPadding(0, 0, dp(6), 0)
        })
        row.addView(TextView(this).apply {
            text = inst.label
            setTextColor(Color.parseColor(if (active) "#FFFFFFFF" else "#FFBDC1C6"))
            textSize = 13f
            if (active) setTypeface(typeface, Typeface.BOLD)
        })
        if (inst.running) {
            row.addView(TextView(this).apply {
                text = "×"
                setTextColor(Color.parseColor("#FF9AA0A6"))
                textSize = 16f
                setPadding(dp(10), 0, dp(8), 0)
                setOnClickListener { InstanceManager.close(inst.userId) }
            })
        } else {
            row.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        }
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
