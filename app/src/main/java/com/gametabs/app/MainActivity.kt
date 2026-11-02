package com.gametabs.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
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
 * Setup screen: pick the game, see how many copies were found, learn how to add one, Start.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var selectedPkg: String? = null

    data class GameEntry(val pkg: String, val label: String, val icon: Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        current = java.lang.ref.WeakReference(this)
        b.exit.setOnClickListener { Copies.exitApp(this) }

        b.log.text = Log.recent()
        Log.onNewLine = { line -> runOnUiThread { b.log.append(if (b.log.text.isEmpty()) line else "\n$line") } }
        b.shareLog.setOnClickListener { shareLog() }
        b.rescan.setOnClickListener { refreshCopies() }
        b.start.setOnClickListener { start() }
        b.howTo.text = HOW_TO

        selectedPkg = Prefs.load(this)
        b.list.layoutManager = LinearLayoutManager(this)
        loadGames()
    }

    override fun onResume() {
        super.onResume()
        refreshCopies() // picks up a copy installed while you were away
    }

    override fun onDestroy() {
        Log.onNewLine = null
        super.onDestroy()
    }

    private fun refreshCopies() {
        val pkg = selectedPkg ?: run {
            b.status.text = "Pick your game below."
            return
        }
        val found = Copies.scan(this, pkg)
        b.status.text = when (found.size) {
            0 -> "This game wasn't found. Is it installed?"
            1 -> "1 copy found. To get a second copy, follow the steps below, then come back."
            else -> "${found.size} copies found. Tap Start."
        }
    }

    private fun loadGames() = lifecycleScope.launch {
        val games = withContext(Dispatchers.IO) {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != packageName }
                .distinctBy { it.packageName }
                .sortedWith(compareByDescending<ApplicationInfo> { it.category == ApplicationInfo.CATEGORY_GAME }
                    .thenBy { pm.getApplicationLabel(it).toString().lowercase() })
                .map { GameEntry(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
        }
        b.list.adapter = GameAdapter(games)
    }

    private fun start() {
        val pkg = selectedPkg ?: return toast("Pick your game first")
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow 'Display over other apps' so the tabs can show above the game")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val found = Copies.scan(this, pkg)
        if (found.isEmpty()) return toast("Game not found")
        if (found.size == 1) toast("Only 1 copy so far. Tabs will work, add a second copy when ready.")
        startForegroundService(Intent(this, TabOverlayService::class.java))
        Copies.switchTo(this, found.first())
    }

    private fun shareLog() = lifecycleScope.launch {
        val text = withContext(Dispatchers.IO) { Log.dumpForSharing() }
        val trimmed = if (text.length > 90_000) text.takeLast(90_000) else text
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "GameTabs log")
                .putExtra(Intent.EXTRA_TEXT, trimmed),
            "Share GameTabs log"
        ))
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
                Prefs.save(this@MainActivity, g.pkg)
                notifyDataSetChanged()
                refreshCopies()
            }
        }
    }

    companion object {
        /** Lets the switcher's Exit button close this screen too. */
        var current: java.lang.ref.WeakReference<MainActivity>? = null
        private const val HOW_TO =
            "How to get a second copy (one time, ~5 minutes):\n" +
            "1. Install \"Shelter\" (free, from F-Droid) or \"Island\" (Play Store).\n" +
            "2. Open it and let it create a work profile. Accept Android's prompts.\n" +
            "3. In Shelter, find your game in the Main list and tap it → \"Clone to work profile\". " +
            "(In Island: select the game → Clone.)\n" +
            "4. Open the cloned copy once from the work apps (briefcase icon) and log in with your second account.\n" +
            "5. Come back here and tap Rescan. You should see \"2 copies found\".\n\n" +
            "Android allows one work profile per phone, so the usual maximum is 2 copies. " +
            "Each copy is the real game installed by Android, so it runs at full speed.\n\n" +
            "Keep both copies alive (Samsung):\n" +
            "• Settings → Battery and device care → Battery → Background usage limits: " +
            "turn OFF \"Put unused apps to sleep\", and add both Dofus copies to \"Never sleeping apps\".\n" +
            "• Settings → Apps → each Dofus copy → Battery → Unrestricted.\n" +
            "• Open Recents, tap each game's icon → \"Lock this app\".\n\n" +
            "Both copies animating at the same time:\n" +
            "Android only runs the game that's on screen. To have both live at once, use split screen: " +
            "open Recents, tap the first game's icon → \"Open in split screen view\", then pick the second copy.\n\n" +
            "Switcher: tap S to jump to the next copy, drag to move it. " +
            "Long-press S to show ① ② ×: tap a number to go to that copy, × to close a copy " +
            "(red number, then Force stop) or ⏻ to exit GameTabs. Long-press a number to come back here."
    }
}
