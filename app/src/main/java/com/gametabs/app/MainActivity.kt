package com.gametabs.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gametabs.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setup screen: pick a game, choose how many instances, press Start.
 * After that you live in the floating tab bar; this screen is only for setup.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var selectedPkg: String? = null
    private var count = 2

    data class GameEntry(val pkg: String, val label: String, val icon: android.graphics.drawable.Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        Prefs.load(this)?.let { (pkg, c) -> selectedPkg = pkg; count = c }
        updateCount()

        b.minus.setOnClickListener { if (count > 1) { count--; updateCount() } }
        b.plus.setOnClickListener { if (count < 8) { count++; updateCount() } }
        b.start.setOnClickListener { start() }

        b.list.layoutManager = LinearLayoutManager(this)
        loadGames()
    }

    private fun updateCount() {
        b.count.text = count.toString()
        b.ramHint.text = "Each instance uses about as much RAM as the game itself. " +
            "Most phones handle 2–4 instances of a heavy 3D game; 8 needs 12 GB+ of RAM."
    }

    private fun loadGames() = lifecycleScope.launch {
        val games = withContext(Dispatchers.IO) {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != packageName }
                .distinctBy { it.packageName }
                // Show games first
                .sortedWith(compareByDescending<ApplicationInfo> {
                    Build.VERSION.SDK_INT >= 26 && it.category == ApplicationInfo.CATEGORY_GAME
                }.thenBy { pm.getApplicationLabel(it).toString().lowercase() })
                .map { GameEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
        }
        b.list.adapter = GameAdapter(games)
    }

    private fun start() {
        val pkg = selectedPkg ?: return toast("Pick a game first")

        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            toast("Allow 'Display over other apps' so the tab bar can show above the game")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        b.start.isEnabled = false
        b.start.text = "Cloning game… (first time only)"
        lifecycleScope.launch {
            val r = InstanceManager.prepare(this@MainActivity, pkg, count)
            b.start.isEnabled = true
            b.start.text = "Start"
            r.onFailure { return@launch toast("Failed: ${it.message}") }

            val svc = Intent(this@MainActivity, TabOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            InstanceManager.switchTo(0)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    inner class GameAdapter(private val items: List<GameEntry>) : RecyclerView.Adapter<GameAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.icon)
            val name: TextView = v.findViewById(R.id.name)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_game, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val g = items[i]
            h.icon.setImageDrawable(g.icon)
            h.name.text = g.label
            h.itemView.isSelected = g.pkg == selectedPkg
            h.itemView.setOnClickListener {
                selectedPkg = g.pkg
                notifyDataSetChanged()
            }
        }
    }
}
