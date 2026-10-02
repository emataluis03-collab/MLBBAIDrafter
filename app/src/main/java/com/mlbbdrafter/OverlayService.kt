package com.mlbbdrafter

import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var mini: TextView
    private lateinit var out: TextView
    private lateinit var barA: View
    private lateinit var barE: View
    private lateinit var ds: Dataset
    private lateinit var prefs: SharedPreferences
    private val d = Draft()
    private val CY = Color.parseColor("#00E5FF")

    override fun onBind(i: Intent?): IBinder? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ds = Store.load(this)
        lp = WindowManager.LayoutParams(
            prefs.getInt("w", dp(330)), prefs.getInt("h", dp(520)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = prefs.getInt("x", 20); y = prefs.getInt("y", 100) }
        build()
        wm.addView(root, lp)
        refresh()
    }

    override fun onDestroy() { if (::root.isInitialized) runCatching { wm.removeView(root) }; super.onDestroy() }

    private fun btn(t: String, on: () -> Unit) = Button(this).apply {
        text = t; textSize = 10f; setPadding(0, 0, 0, 0); minHeight = 0; minimumHeight = dp(36)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { on() }
    }
    private fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it) } }

    private fun drag(v: View, onTap: (() -> Unit)? = null) {
        var ox = 0; var oy = 0; var sx = 0f; var sy = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ox = lp.x; oy = lp.y; sx = e.rawX; sy = e.rawY }
                MotionEvent.ACTION_MOVE -> { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(root, lp) }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    if (onTap != null && abs(e.rawX - sx) < 10 && abs(e.rawY - sy) < 10) onTap()
                }
            }
            true
        }
    }

    private fun setMinimized(m: Boolean) {
        panel.visibility = if (m) View.GONE else View.VISIBLE
        mini.visibility = if (m) View.VISIBLE else View.GONE
        lp.width = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("w", dp(330))
        lp.height = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("h", dp(520))
        wm.updateViewLayout(root, lp)
    }

    private fun build() {
        root = FrameLayout(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#EE0B1020")); setStroke(dp(1), CY); cornerRadius = dp(8).toFloat() }
        }
        val title = TextView(this).apply {
            text = "[ AI DRAFTER ]  (drag)"; setTextColor(CY); typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        drag(title)
        panel.addView(row(title, btn("−") { setMinimized(true) }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(36)) },
            btn("×") { stopSelf() }.apply { layoutParams = LinearLayout.LayoutParams(dp(40), dp(36)) }))
        panel.addView(row(btn("ALLY BAN") { pick("Ally ban", d.allyBans) }, btn("ENEMY BAN") { pick("Enemy ban", d.enemyBans) },
            btn("ALLY PICK") { pick("Ally pick", d.allyPicks) }, btn("ENEMY PICK") { pick("Enemy pick", d.enemyPicks) }))
        panel.addView(row(btn("UNDO") { d.undo(); refresh() }, btn("RESET") { d.clear(); refresh() }, btn("ANALYZE") { ds = Store.load(this); refresh() }))
        out = TextView(this).apply { setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; textSize = 11f }
        panel.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(-1, 0, 1f))
        barA = View(this).apply { setBackgroundColor(Color.parseColor("#00C853")) }
        barE = View(this).apply { setBackgroundColor(Color.parseColor("#D50000")) }
        panel.addView(row(barA, barE).apply {
            weightSum = 100f; (getChildAt(0)).layoutParams = LinearLayout.LayoutParams(0, dp(8), 50f); (getChildAt(1)).layoutParams = LinearLayout.LayoutParams(0, dp(8), 50f)
        })
        val handle = TextView(this).apply { text = "◢ resize"; setTextColor(CY); gravity = Gravity.END; textSize = 10f }
        var w0 = 0; var h0 = 0; var sx = 0f; var sy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { w0 = lp.width; h0 = lp.height; sx = e.rawX; sy = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = maxOf(dp(240), w0 + (e.rawX - sx).toInt()); lp.height = maxOf(dp(240), h0 + (e.rawY - sy).toInt())
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> prefs.edit().putInt("w", lp.width).putInt("h", lp.height).apply()
            }
            true
        }
        panel.addView(handle, LinearLayout.LayoutParams(-1, -2))
        mini = TextView(this).apply {
            text = "🤖"; textSize = 26f; setPadding(dp(10), dp(6), dp(10), dp(6)); visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.parseColor("#EE0B1020")); setStroke(dp(1), CY); cornerRadius = dp(24).toFloat() }
        }
        drag(mini) { setMinimized(false) }
        root.addView(panel); root.addView(mini)
    }

    private fun pick(title: String, target: MutableList<String>) {
        val avail = ds.heroes.values.filter { it.id !in d.used() }.sortedBy { it.name }
        if (avail.isEmpty()) return
        AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))
            .setTitle(title).setItems(avail.map { it.name }.toTypedArray()) { _, i -> d.add(target, avail[i].id); refresh() }
            .setNegativeButton("Cancel", null).create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY); show()
            }
    }

    private fun refresh() {
        fun nm(id: String) = ds.heroes[id]?.name ?: id
        fun slots(l: List<String>) = (0 until 5).joinToString(" ") { l.getOrNull(it)?.let { n -> "[${nm(n).take(8)}]" } ?: "[  ]" }
        val sb = StringBuilder()
        sb.append("DATA: ${ds.label}\n")
        if (ds.warnings.isNotEmpty()) sb.append("(${ds.warnings.size} data warnings - see app)\n")
        sb.append("\nALLY BANS\n${slots(d.allyBans)}\n\nENEMY BANS\n${slots(d.enemyBans)}\n")
        sb.append("\nALLY PICKS:  ${d.allyPicks.joinToString(", ") { nm(it) }}\nENEMY PICKS: ${d.enemyPicks.joinToString(", ") { nm(it) }}\n")
        sb.append("\n>> OPTIMAL PICK VECTORS <<\n")
        Engine.picks(ds, d, SettingsManager.pick(this)).forEach { (role, l) ->
            sb.append(role.padEnd(8))
            sb.append(if (l.isEmpty()) "No data" else l.joinToString(" / ") { "${it.hero.name} ${"%.0f".format(it.score)}${if (it.incomplete) "*" else ""}" })
            sb.append("\n")
        }
        sb.append("(* = incomplete data)\n\n>> BAN PRIORITY <<\n")
        Engine.bans(ds, d, SettingsManager.ban(this)).forEachIndexed { i, s ->
            sb.append("${i + 1}. ${s.hero.name} — ${"%.1f".format(s.score)}${if (s.incomplete) "*" else ""}\n")
            s.why.forEach { sb.append("   - $it\n") }
        }
        sb.append("\n>> MATCH PROBABILITY (estimate) <<\n")
        val p = Engine.prob(ds, d)
        val a = p?.ally ?: 50.0
        if (p == null) sb.append("No data (need picks on both sides with stats)\n")
        sb.append("ALLY   ${"%.2f".format(a)}%\nENEMY  ${"%.2f".format(100 - a)}%\n")
        p?.notes?.forEach { sb.append("  · $it\n") }
        sb.append("Estimate from supplied data only; not a guarantee.\n")
        out.text = sb.toString()
        (barA.layoutParams as LinearLayout.LayoutParams).weight = a.toFloat()
        (barE.layoutParams as LinearLayout.LayoutParams).weight = (100 - a).toFloat()
        barA.requestLayout(); barE.requestLayout()
    }
}
