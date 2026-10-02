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
    private lateinit var content: LinearLayout
    private lateinit var barA: View
    private lateinit var barE: View
    private lateinit var probA: TextView
    private lateinit var probE: TextView
    private lateinit var ds: Dataset
    private lateinit var prefs: SharedPreferences
    private val d = Draft()

    private val neon = Color.parseColor("#55FF78")
    private val neonDim = Color.parseColor("#27A84C")
    private val bg = Color.parseColor("#E9080D12")
    private val card = Color.parseColor("#C9131A18")
    private val white = Color.parseColor("#E9F7EC")
    private val muted = Color.parseColor("#83A78B")
    private val red = Color.parseColor("#FF5265")
    private val amber = Color.parseColor("#FFD166")

    override fun onBind(i: Intent?): IBinder? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ds = Store.load(this)

        lp = WindowManager.LayoutParams(
            prefs.getInt("w", dp(360)),
            prefs.getInt("h", dp(620)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 12)
            y = prefs.getInt("y", 90)
        }

        build()
        wm.addView(root, lp)
        refresh()
    }

    override fun onDestroy() {
        if (::root.isInitialized) runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    private fun mono(v: TextView, size: Float = 11f, color: Int = white) {
        v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        v.textSize = size
        v.setTextColor(color)
    }

    private fun label(
        text: String,
        size: Float = 10f,
        color: Int = muted,
        bold: Boolean = false
    ) = TextView(this).apply {
        this.text = text
        this.textSize = size
        this.setTextColor(color)
        this.typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        this.gravity = Gravity.CENTER_VERTICAL
    }

    private fun box(color: Int = card, stroke: Int = Color.TRANSPARENT, radius: Float = 4f) =
        GradientDrawable().apply {
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
            cornerRadius = dp(radius.toInt()).toFloat()
        }

    private fun row(vararg views: View, height: Int = -2) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(-1, if (height == -2) LinearLayout.LayoutParams.WRAP_CONTENT else dp(height))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        mono(this, 10f, neon)
        gravity = Gravity.CENTER
        setPadding(0, dp(5), 0, dp(4))
        letterSpacing = 0.08f
    }

    private fun slot(text: String, onClick: (() -> Unit)? = null): TextView {
        return TextView(this).apply {
            this.text = text
            mono(this, 8.5f, if (text == "[  ]") muted else white)
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
            background = box(Color.parseColor("#A60C1510"), neonDim, 3f)
            layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    private fun drag(v: View, onTap: (() -> Unit)? = null) {
        var ox = 0
        var oy = 0
        var sx = 0f
        var sy = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ox = lp.x
                    oy = lp.y
                    sx = e.rawX
                    sy = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    if (onTap != null &&
                        abs(e.rawX - sx) < dp(8) &&
                        abs(e.rawY - sy) < dp(8)
                    ) onTap()
                }
            }
            true
        }
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            mono(this, 8.5f, neon)
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, dp(5), 0)
            background = box(Color.parseColor("#B20D1711"), neonDim, 3f)
            layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setMinimized(m: Boolean) {
        panel.visibility = if (m) View.GONE else View.VISIBLE
        mini.visibility = if (m) View.VISIBLE else View.GONE
        lp.width = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("w", dp(360))
        lp.height = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("h", dp(620))
        wm.updateViewLayout(root, lp)
    }

    private fun build() {
        root = FrameLayout(this)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(5))
            background = box(bg, neon, 2f)
        }

        // Header
        val title = label("[ AI LINEUP DRAFTER ]", 11f, neon, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
        }
        drag(title)

        val close = TextView(this).apply {
            text = "[X]"
            mono(this, 10f, neon)
            gravity = Gravity.CENTER
            background = box(Color.TRANSPARENT, Color.TRANSPARENT, 2f)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(30))
            setOnClickListener { stopSelf() }
        }
        val min = TextView(this).apply {
            text = "[-]"
            mono(this, 10f, neon)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(30))
            setOnClickListener { setMinimized(true) }
        }
        panel.addView(row(title, min, close, height = 34))

        val dataLabel = label(
            "DATA  ${ds.label.uppercase()}",
            8f,
            muted
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(3))
        }
        panel.addView(dataLabel)

        // Bans
        panel.addView(sectionTitle("ALLY BANS"))
        panel.addView(banRow(d.allyBans, "Ally ban"))
        panel.addView(sectionTitle("ENEMY BANS"))
        panel.addView(banRow(d.enemyBans, "Enemy ban"))

        // Manual controls kept compact so the visual overlay stays clean.
        panel.addView(
            row(
                actionButton("+ ALLY BAN") { pick("Ally ban", d.allyBans) },
                actionButton("+ ENEMY BAN") { pick("Enemy ban", d.enemyBans) },
                actionButton("UNDO") { d.undo(); refresh() },
                actionButton("RESET") { d.clear(); refresh() },
                actionButton("ANALYZE") { ds = Store.load(this); refresh() }
            )
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom resize handle
        val handle = label("◢  RESIZE", 8f, muted).apply {
            gravity = Gravity.END
            setPadding(0, dp(3), dp(3), 0)
        }
        var w0 = 0
        var h0 = 0
        var sx = 0f
        var sy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    w0 = lp.width
                    h0 = lp.height
                    sx = e.rawX
                    sy = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = maxOf(dp(300), w0 + (e.rawX - sx).toInt())
                    lp.height = maxOf(dp(420), h0 + (e.rawY - sy).toInt())
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("w", lp.width).putInt("h", lp.height).apply()
                }
            }
            true
        }
        panel.addView(handle, LinearLayout.LayoutParams(-1, dp(18)))

        mini = TextView(this).apply {
            text = "AI"
            mono(this, 12f, neon)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            visibility = View.GONE
            background = box(bg, neon, 20f)
        }
        drag(mini) { setMinimized(false) }

        root.addView(panel)
        root.addView(mini)
    }

    private fun banRow(list: List<String>, title: String): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, dp(34))
        }
        for (i in 0 until 5) {
            val id = list.getOrNull(i)
            val name = if (id == null) "[  ]" else "[${ds.heroes[id]?.name?.take(8) ?: id.take(8)}]"
            r.addView(
                slot(name) {
                    if (i == list.size && list.size < 5) pick(title, if (title.startsWith("Ally")) d.allyBans else d.enemyBans)
                }
            )
        }
        return r
    }

    private fun pickRow(list: List<String>, title: String): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, dp(32))
        }
        for (i in 0 until 5) {
            val id = list.getOrNull(i)
            val name = if (id == null) "[  ]" else "[${ds.heroes[id]?.name?.take(8) ?: id.take(8)}]"
            r.addView(slot(name) {
                if (i == list.size && list.size < 5) {
                    pick(title, if (title.startsWith("Ally")) d.allyPicks else d.enemyPicks)
                }
            })
        }
        return r
    }

    private fun addSectionHeader(parent: LinearLayout, text: String) {
        parent.addView(sectionTitle(text))
    }

    private fun refresh() {
        val nm: (String) -> String = { id -> ds.heroes[id]?.name ?: id }

        content.removeAllViews()

        // Picks are shown because the recommendation engine needs the current draft.
        addSectionHeader(content, "ALLY PICKS")
        content.addView(pickRow(d.allyPicks, "Ally pick"))
        addSectionHeader(content, "ENEMY PICKS")
        content.addView(pickRow(d.enemyPicks, "Enemy pick"))

        val pickMap = Engine.picks(ds, d, SettingsManager.pick(this))

        addSectionHeader(content, ">> OPTIMAL PICK VECTORS <<")
        val vectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(5))
            background = box(card, Color.parseColor("#214F2D"), 4f)
        }

        for (role in ROLES) {
            val scores = pickMap[role].orEmpty()
            val main = scores.getOrNull(0)
            val flex = scores.getOrNull(1)
            val text = if (main == null) {
                "NO DATA"
            } else {
                "${main.hero.name} / ${flex?.hero?.name ?: "—"}"
            }
            val scoreText = if (main == null) "" else "  ${"%.0f".format(main.score)}"
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, dp(31))
            }
            r.addView(label(role, 9f, neon, true).apply {
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(58), -1)
            })
            r.addView(label(text + scoreText, 9f, white).apply {
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            })
            vectorCard.addView(r)
        }
        content.addView(vectorCard)

        // Main + widest picks.
        val all = pickMap.values.flatten()
        val mainPick = all.maxByOrNull { it.score }

        val widest = ds.heroes.values
            .filter { it.id !in d.used() }
            .mapNotNull { h ->
                val roleCount = h.roles.distinct().size
                if (roleCount == 0) null
                else {
                    val bestScore = all.filter { it.hero.id == h.id }.maxOfOrNull { it.score } ?: 0.0
                    Triple(h, roleCount, bestScore)
                }
            }
            .maxWithOrNull(compareBy<Triple<Hero, Int, Double>> { it.second }.thenBy { it.third })

        addSectionHeader(content, ">> PRIORITY PICKS <<")
        val priority = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        fun priorityCard(title: String, hero: Hero?, subtitle: String, accent: Int): TextView {
            return TextView(this).apply {
                text = if (hero == null) "$title\nNO DATA\n$subtitle"
                else "$title\n${hero.name}\n$subtitle"
                mono(this, 9f, white)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = box(Color.parseColor("#A6101812"), accent, 4f)
                layoutParams = LinearLayout.LayoutParams(0, dp(62), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
        }

        priority.addView(
            priorityCard(
                "★ MAIN PICK",
                mainPick?.hero,
                if (mainPick == null) "no scored candidate" else "score ${"%.0f".format(mainPick.score)}",
                neon
            )
        )
        priority.addView(
            priorityCard(
                "◎ WIDEST PICK",
                widest?.first,
                if (widest == null) "no role data" else "${widest.second} role${if (widest.second == 1) "" else "s"} • score ${"%.0f".format(widest.third)}",
                amber
            )
        )
        content.addView(priority)

        // Ban priority, compact.
        addSectionHeader(content, ">> BAN PRIORITY <<")
        val bans = Engine.bans(ds, d, SettingsManager.ban(this))
        if (bans.isEmpty()) {
            content.addView(label("NO DATA", 9f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(4))
            })
        } else {
            bans.take(3).forEachIndexed { i, s ->
                val why = s.why.take(1).joinToString()
                content.addView(label(
                    "${i + 1}. ${s.hero.name}   ${"%.0f".format(s.score)}${if (s.incomplete) "*" else ""}" +
                        if (why.isNotEmpty()) "  •  $why" else "",
                    8.5f,
                    white
                ).apply {
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                })
            }
        }

        // Probability
        addSectionHeader(content, ">> MATCHUP PROBABILITY <<")
        val p = Engine.prob(ds, d)
        val a = p?.ally ?: 50.0
        val e = 100.0 - a

        val probWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(5))
            background = box(card, Color.parseColor("#214F2D"), 4f)
        }

        val probLabels = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        probA = label("ALLY  ${"%.1f".format(a)}%", 8.5f, neon, true).apply {
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, dp(20), 1f)
        }
        probE = label("ENEMY  ${"%.1f".format(e)}%", 8.5f, red, true).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, dp(20), 1f)
        }
        probLabels.addView(probA)
        probLabels.addView(probE)
        probWrap.addView(probLabels)

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(Color.parseColor("#3A303838"), Color.TRANSPARENT, 2f)
            layoutParams = LinearLayout.LayoutParams(-1, dp(10))
        }
        barA = View(this).apply {
            setBackgroundColor(neon)
            layoutParams = LinearLayout.LayoutParams(0, -1, a.toFloat())
        }
        barE = View(this).apply {
            setBackgroundColor(red)
            layoutParams = LinearLayout.LayoutParams(0, -1, e.toFloat())
        }
        track.addView(barA)
        track.addView(barE)
        probWrap.addView(track)

        if (p == null) {
            probWrap.addView(label("No data — add picks with supplied stats.", 8f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }
        probWrap.addView(label(
            "Estimate from supplied data only • not a guarantee",
            7.5f,
            muted
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        content.addView(probWrap)
    }

    private fun pick(title: String, target: MutableList<String>) {
        if (target.size >= 5) return

        val avail = ds.heroes.values
            .filter { it.id !in d.used() }
            .sortedBy { it.name }

        if (avail.isEmpty()) return

        AlertDialog.Builder(
            ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        )
            .setTitle(title)
            .setItems(avail.map { it.name }.toTypedArray()) { _, i ->
                d.add(target, avail[i].id)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                show()
            }
    }
}
