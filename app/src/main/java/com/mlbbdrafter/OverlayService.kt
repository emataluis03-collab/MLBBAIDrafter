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
    private lateinit var summary: LinearLayout
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
            prefs.getInt("w", dp(320)),
            prefs.getInt("h", dp(520)),
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
        mono(this, 9f, neon)
        gravity = Gravity.CENTER
        setPadding(0, dp(3), 0, dp(2))
        letterSpacing = 0.08f
    }

    private fun slot(text: String, onClick: (() -> Unit)? = null): TextView {
        return TextView(this).apply {
            this.text = text
            mono(this, 8.5f, if (text == "[  ]") muted else white)
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
            background = box(Color.parseColor("#A60C1510"), neonDim, 3f)
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).apply {
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
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setMinimized(m: Boolean) {
        panel.visibility = if (m) View.GONE else View.VISIBLE
        mini.visibility = if (m) View.VISIBLE else View.GONE
        lp.width = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("w", dp(320))
        lp.height = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("h", dp(520))
        wm.updateViewLayout(root, lp)
    }

    private fun build() {
        root = FrameLayout(this)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(4), dp(5), dp(3))
            background = box(bg, neon, 2f)
        }

        // Header
        val title = label("[ AI LINEUP DRAFTER ]", 11f, neon, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, dp(29), 1f)
        }
        drag(title)

        val close = TextView(this).apply {
            text = "[X]"
            mono(this, 9f, neon)
            gravity = Gravity.CENTER
            background = box(Color.TRANSPARENT, Color.TRANSPARENT, 2f)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(27))
            setOnClickListener { stopSelf() }
        }
        val min = TextView(this).apply {
            text = "[-]"
            mono(this, 9f, neon)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(27))
            setOnClickListener { setMinimized(true) }
        }
        panel.addView(row(title, min, close, height = 29))

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
        val controls1 = row(
            actionButton("+ ALLY BAN") { pick("Ally ban", d.allyBans) },
            actionButton("+ ENEMY BAN") { pick("Enemy ban", d.enemyBans) },
            actionButton("UNDO") { d.undo(); refresh() }
        )
        val controls2 = row(
            actionButton("RESET") { d.clear(); refresh() },
            actionButton("ANALYZE") { ds = Store.load(this); refresh() }
        )
        panel.addView(controls1)
        panel.addView(controls2)

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

        // Fixed bottom summary: keeps Priority Picks, Ban Priority and Matchup Probability
        // visible without requiring the user to scroll the overlay.
        summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, 0)
        }
        panel.addView(summary, LinearLayout.LayoutParams(-1, dp(154)))

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
            layoutParams = LinearLayout.LayoutParams(-1, dp(29))
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
            layoutParams = LinearLayout.LayoutParams(-1, dp(28))
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
        content.removeAllViews()
        summary.removeAllViews()

        // Scrollable area: current picks + optimal vectors.
        addSectionHeader(content, "ALLY PICKS")
        content.addView(pickRow(d.allyPicks, "Ally pick"))
        addSectionHeader(content, "ENEMY PICKS")
        content.addView(pickRow(d.enemyPicks, "Enemy pick"))

        val pickMap = Engine.picks(ds, d, SettingsManager.pick(this))

        addSectionHeader(content, ">> OPTIMAL PICK VECTORS <<")
        val vectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(1), dp(4), dp(2))
            background = box(card, Color.parseColor("#214F2D"), 4f)
        }

        for (role in ROLES) {
            val scores = pickMap[role].orEmpty()
            val main = scores.getOrNull(0)
            val flex = scores.getOrNull(1)
            val text = if (main == null) "NO DATA" else "${main.hero.name} / ${flex?.hero?.name ?: "—"}"
            val scoreText = if (main == null) "" else "  ${"%.0f".format(main.score)}"
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, dp(26))
            }
            r.addView(label(role, 8.5f, neon, true).apply {
                layoutParams = LinearLayout.LayoutParams(dp(45), -1)
            })
            r.addView(label(text + scoreText, 8.5f, white).apply {
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            })
            vectorCard.addView(r)
        }
        content.addView(vectorCard)

        // Fixed summary area: always visible at the bottom of the compact overlay.
        val all = pickMap.values.flatten()
        val rankPick = all.maxByOrNull { it.score }
        val tournamentPick = ds.heroes.values
            .filter { it.id !in d.used() }
            .mapNotNull { h ->
                val roleCount = h.roles.distinct().size
                if (roleCount == 0) null
                else Triple(h, roleCount, all.filter { it.hero.id == h.id }.maxOfOrNull { it.score } ?: 0.0)
            }
            .maxWithOrNull(compareBy<Triple<Hero, Int, Double>> { it.second }.thenBy { it.third })

        addSectionHeader(summary, ">> PRIORITY PICKS <<")
        val priority = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), 0, dp(2), dp(1))
        }

        fun priorityCard(title: String, hero: Hero?, subtitle: String, accent: Int): TextView = TextView(this).apply {
            text = if (hero == null) "$title\nNO DATA\n$subtitle" else "$title\n${hero.name}\n$subtitle"
            mono(this, 7.2f, white)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(1), dp(2), dp(1))
            background = box(Color.parseColor("#A6101812"), accent, 3f)
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
        priority.addView(priorityCard(
            "★ RANK PICK", rankPick?.hero,
            if (rankPick == null) "no scored candidate" else "score ${"%.0f".format(rankPick.score)}", neon
        ))
        priority.addView(priorityCard(
            "◎ TOURNAMENT PICK", tournamentPick?.first,
            if (tournamentPick == null) "no role data" else "${tournamentPick.second} role${if (tournamentPick.second == 1) "" else "s"}", amber
        ))
        summary.addView(priority)

        addSectionHeader(summary, ">> BAN PRIORITY <<")
        val bans = Engine.bans(ds, d, SettingsManager.ban(this))
        if (bans.isEmpty()) {
            summary.addView(label("NO DATA", 7.5f, muted).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(1))
            })
        } else {
            val banLine = bans.take(2).joinToString("   •   ") { s ->
                "${s.hero.name} ${"%.0f".format(s.score)}${if (s.incomplete) "*" else ""}"
            }
            summary.addView(label(banLine, 7.3f, white).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), 0, dp(4), dp(1))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

        addSectionHeader(summary, ">> MATCHUP PROBABILITY <<")
        val p = Engine.prob(ds, d)
        val a = p?.ally ?: 50.0
        val e = 100.0 - a
        val probWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(1), dp(5), dp(2))
            background = box(card, Color.parseColor("#214F2D"), 3f)
        }
        val probLabels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        probA = label("ALLY  ${"%.1f".format(a)}%", 7.8f, neon, true).apply {
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, dp(16), 1f)
        }
        probE = label("ENEMY  ${"%.1f".format(e)}%", 7.8f, red, true).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, dp(16), 1f)
        }
        probLabels.addView(probA)
        probLabels.addView(probE)
        probWrap.addView(probLabels)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(Color.parseColor("#3A303838"), Color.TRANSPARENT, 2f)
            layoutParams = LinearLayout.LayoutParams(-1, dp(7))
        }
        barA = View(this).apply { setBackgroundColor(neon); layoutParams = LinearLayout.LayoutParams(0, -1, a.toFloat()) }
        barE = View(this).apply { setBackgroundColor(red); layoutParams = LinearLayout.LayoutParams(0, -1, e.toFloat()) }
        track.addView(barA)
        track.addView(barE)
        probWrap.addView(track)
        if (p == null) {
            probWrap.addView(label("No data — add picks with supplied stats.", 6.8f, muted).apply {
                gravity = Gravity.CENTER
            })
        }
        summary.addView(probWrap)
    }

    /**
     * Searchable hero picker used by every ban/pick slot.
     * The user can type a hero name, then tap the exact result.
     * No game state is read automatically; this only edits the manual draft.
     */
    private fun pick(title: String, target: MutableList<String>) {
        if (target.size >= 5) return

        val available = ds.heroes.values
            .filter { it.id !in d.used() }
            .sortedBy { it.name.lowercase() }

        if (available.isEmpty()) return

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), 0)
        }

        val search = EditText(this).apply {
            hint = "Search hero name..."
            setSingleLine(true)
            textSize = 13f
            setTextColor(white)
            setHintTextColor(muted)
            background = box(Color.parseColor("#C90D1711"), neonDim, 5f)
            setPadding(dp(10), 0, dp(10), 0)
        }
        wrap.addView(search, LinearLayout.LayoutParams(-1, dp(40)))

        // Quick-pick shortcuts are available when filling an ally pick.
        // They use the same supplied-data engine as the cards on the main overlay.
        if (title == "Ally pick") {
            val scored = Engine.picks(ds, d, SettingsManager.pick(this)).values.flatten()
            val mainHero = scored.maxByOrNull { it.score }?.hero
            val widestHero = ds.heroes.values
                .filter { it.id !in d.used() }
                .mapNotNull { h ->
                    val roleCount = h.roles.distinct().size
                    if (roleCount == 0) null
                    else Triple(h, roleCount, scored.filter { it.hero.id == h.id }.maxOfOrNull { it.score } ?: 0.0)
                }
                .maxWithOrNull(compareBy<Triple<Hero, Int, Double>> { it.second }.thenBy { it.third })?.first

            val quick = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }

            fun quickButton(text: String, hero: Hero?, accent: Int): TextView = TextView(this).apply {
                this.text = if (hero == null) text else "$text\n${hero.name}"
                mono(this, 7.5f, white)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(3), dp(4), dp(3))
                background = box(Color.parseColor("#A6101812"), accent, 4f)
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                isEnabled = hero != null
            }

            quick.addView(quickButton("★ RANK PICK", mainHero, neon).apply {
                setOnClickListener {
                    mainHero?.let { d.add(target, it.id); refresh(); dialogRef?.dismiss() }
                }
            })
            quick.addView(quickButton("◎ TOURNAMENT PICK", widestHero, amber).apply {
                setOnClickListener {
                    widestHero?.let { d.add(target, it.id); refresh(); dialogRef?.dismiss() }
                }
            })
            wrap.addView(quick, LinearLayout.LayoutParams(-1, dp(48)))
        }

        val count = label("${available.size} heroes available", 8f, muted).apply {
            setPadding(dp(2), dp(5), dp(2), dp(3))
        }
        wrap.addView(count)

        val list = ListView(this).apply {
            dividerHeight = dp(1)
            divider = box(Color.parseColor("#5527A84C"), Color.TRANSPARENT, 0f)
        }
        wrap.addView(list, LinearLayout.LayoutParams(-1, dp(280)))

        fun names(filter: String): List<Hero> {
            val q = filter.trim().lowercase()
            return if (q.isEmpty()) available
            else available.filter {
                it.name.lowercase().contains(q) || it.id.lowercase().contains(q)
            }
        }

        fun render(items: List<Hero>) {
            count.text = if (items.isEmpty()) "No matching hero" else "${items.size} heroes"
            list.adapter = object : ArrayAdapter<Hero>(
                this, android.R.layout.simple_list_item_1, items
            ) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.text = getItem(position)?.name ?: ""
                    v.textSize = 13f
                    v.setTextColor(white)
                    v.setPadding(dp(10), 0, dp(10), 0)
                    v.setBackgroundColor(Color.TRANSPARENT)
                    v.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    return v
                }
            }
            list.setOnItemClickListener { _, _, position, _ ->
                val chosen = items[position]
                d.add(target, chosen.id)
                refresh()
                dialogRef?.dismiss()
            }
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(names(s?.toString().orEmpty()))
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        val dialog = AlertDialog.Builder(
            ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        )
            .setTitle(title)
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .create()

        dialogRef = dialog
        render(available)
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.setOnShowListener {
            search.requestFocus()
            dialog.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
        }
        dialog.show()
    }

    private var dialogRef: AlertDialog? = null
}
