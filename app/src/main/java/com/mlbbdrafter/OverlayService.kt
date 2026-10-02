package com.mlbbdrafter

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshPosted = false
    private var destroyed = false

    private val neon = Color.parseColor("#55FF78")
    private val neonDim = Color.parseColor("#27A84C")
    private val bg = Color.parseColor("#E9080D12")
    private val card = Color.parseColor("#C9131A18")
    private val white = Color.parseColor("#E9F7EC")
    private val muted = Color.parseColor("#83A78B")
    private val red = Color.parseColor("#FF5265")
    private val amber = Color.parseColor("#FFD166")

    override fun onBind(i: Intent?): IBinder? = null

    private fun startPersistentNotification() {
        val channelId = "mlbb_overlay"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "MLBB AI Drafter",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the MLBB draft overlay active while gaming"
                    setShowBadge(false)
                }
            )
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MLBB AI Lineup Drafter")
            .setContentText("Draft overlay active")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        startForeground(1001, notification)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // Keep the overlay alive when the user switches from the drafter to MLBB.
        // Without a foreground service, Android can stop a background service as soon
        // as the launcher activity loses the foreground, making the overlay disappear
        // or the process appear to crash.
        startPersistentNotification()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ds = Store.load(this)

        lp = WindowManager.LayoutParams(
            prefs.getInt("w", dp(290)),
            prefs.getInt("h", dp(470)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(8))
            y = prefs.getInt("y", dp(55))
        }

        build()
        try {
            wm.addView(root, lp)
            refresh()
        } catch (_: Exception) {
            // If the overlay window cannot be attached, cleanly stop the service
            // instead of crashing the app process.
            runCatching { if (::root.isInitialized) wm.removeViewImmediate(root) }
            stopSelf()
        }
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (::root.isInitialized) runCatching { wm.removeViewImmediate(root) }
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
            mono(this, 8.0f, if (text == "[  ]") muted else white)
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
            background = box(Color.parseColor("#A60C1510"), neonDim, 3f)
            layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    private fun clampPosition() {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - lp.width).coerceAtLeast(dp(4))
        val maxY = (dm.heightPixels - lp.height).coerceAtLeast(dp(4))
        lp.x = lp.x.coerceIn(dp(4), maxX)
        lp.y = lp.y.coerceIn(dp(30), maxY)
    }

    private fun drag(v: View, onTap: (() -> Unit)? = null) {
        var ox = 0
        var oy = 0
        var sx = 0f
        var sy = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ox = lp.x
                    oy = lp.y
                    sx = e.rawX
                    sy = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (e.rawX - sx).toInt()
                    lp.y = oy + (e.rawY - sy).toInt()
                    clampPosition()
                    runCatching { wm.updateViewLayout(root, lp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampPosition()
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    if (onTap != null &&
                        abs(e.rawX - sx) < dp(10) &&
                        abs(e.rawY - sy) < dp(10)
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
            layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setMinimized(m: Boolean) {
        panel.visibility = if (m) View.GONE else View.VISIBLE
        mini.visibility = if (m) View.VISIBLE else View.GONE
        lp.width = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("w", dp(290))
        lp.height = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("h", dp(470))
        runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun build() {
        root = FrameLayout(this)

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(3), dp(4), dp(2))
            background = box(bg, neon, 3f)
        }

        // Compact header: larger touch target for reliable dragging over MLBB.
        val title = label("[ AI LINEUP DRAFTER ]", 9.5f, neon, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
        }
        drag(title)

        val close = TextView(this).apply {
            text = "×"
            mono(this, 16f, neon)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { stopSelf() }
        }
        val min = TextView(this).apply {
            text = "−"
            mono(this, 16f, neon)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { setMinimized(true) }
        }
        panel.addView(row(title, min, close, height = 34))

        val dataLabel = label("DATA  ${ds.label.uppercase()}  •  ${ds.heroes.size} HEROES", 7.0f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(2))
        }
        panel.addView(dataLabel)

        panel.addView(sectionTitle("ALLY BANS"))
        panel.addView(banRow(d.allyBans, "Ally ban"))
        panel.addView(sectionTitle("ENEMY BANS"))
        panel.addView(banRow(d.enemyBans, "Enemy ban"))

        val controls = row(
            actionButton("+ AB") { pick("Ally ban", d.allyBans) },
            actionButton("+ EB") { pick("Enemy ban", d.enemyBans) },
            actionButton("UNDO") { d.undo(); refresh() },
            actionButton("RESET") { d.clear(); refresh() }
        )
        panel.addView(controls)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, dp(2), 0, dp(2))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(1), 0, dp(8))
        }
        scroll.addView(content)
        // Everything below the draft slots scrolls together. This keeps Ban Priority
        // and Matchup Probability reachable even on smaller screens.
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val handle = label("≡  DRAG / RESIZE", 7f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
        }
        var w0 = 0
        var h0 = 0
        var sx = 0f
        var sy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    w0 = lp.width
                    h0 = lp.height
                    sx = e.rawX
                    sy = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (w0 + (e.rawX - sx).toInt()).coerceAtLeast(dp(270))
                    lp.height = (h0 + (e.rawY - sy).toInt()).coerceAtLeast(dp(400))
                    clampPosition()
                    runCatching { wm.updateViewLayout(root, lp) }
                }
                MotionEvent.ACTION_UP -> {
                    clampPosition()
                    prefs.edit().putInt("w", lp.width).putInt("h", lp.height).putInt("x", lp.x).putInt("y", lp.y).apply()
                }
            }
            true
        }
        panel.addView(handle, LinearLayout.LayoutParams(-1, dp(22)))

        mini = TextView(this).apply {
            text = "AI"
            mono(this, 11f, neon)
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(8), dp(11), dp(8))
            visibility = View.GONE
            background = box(bg, neon, 20f)
        }
        drag(mini) { setMinimized(false) }

        root.addView(panel)
        root.addView(mini)
    }

    private fun heroDisplayName(id: String?): String {
        if (id.isNullOrBlank()) return ""
        val key = id.trim()
        ds.heroes[key]?.name?.let { return it }
        ds.heroes.values.firstOrNull { it.id.equals(key, ignoreCase = true) }?.name?.let { return it }
        ds.heroes.values.firstOrNull { it.name.equals(key, ignoreCase = true) }?.name?.let { return it }
        return key
    }

    private fun slotName(id: String?): String {
        if (id.isNullOrBlank()) return "[  ]"
        val name = heroDisplayName(id)
        return "[${name.take(11)}]"
    }

    private fun banRow(list: List<String>, title: String): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, dp(29))
        }
        for (i in 0 until 5) {
            val id = list.getOrNull(i)
            val name = slotName(id)
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
            val name = slotName(id)
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
        if (destroyed || !::content.isInitialized) return
        if (refreshPosted) return
        refreshPosted = true
        mainHandler.post {
            refreshPosted = false
            if (destroyed || !::content.isInitialized) return@post
            refreshNow()
        }
    }

    private fun refreshNow() {
        content.removeAllViews()

        addSectionHeader(content, "ALLY PICKS")
        content.addView(pickRow(d.allyPicks, "Ally pick"))
        addSectionHeader(content, "ENEMY PICKS")
        content.addView(pickRow(d.enemyPicks, "Enemy pick"))

        val pickMap = Engine.picks(ds, d, SettingsManager.pick(this))
        addSectionHeader(content, ">> OPTIMAL PICK VECTORS <<")
        val vectorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(1), dp(3), dp(2))
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
                layoutParams = LinearLayout.LayoutParams(-1, dp(24))
            }
            r.addView(label(role, 7.8f, neon, true).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), -1)
            })
            r.addView(label(text + scoreText, 7.8f, white).apply {
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            })
            vectorCard.addView(r)
        }
        content.addView(vectorCard)

        val all = pickMap.values.flatten()
        val rankPick = all.maxByOrNull { it.score }
        val tierValue = mapOf("SS" to 6, "S" to 5, "A" to 4, "B" to 3, "C" to 2, "D" to 1)
        val tournamentPick = ds.heroes.values
            .filter { it.id !in d.used() && it.tournamentTier != null }
            .mapNotNull { h ->
                val tier = tierValue[h.tournamentTier] ?: return@mapNotNull null
                val roleCount = h.roles.distinct().size
                val rankScore = all.filter { it.hero.id == h.id }.maxOfOrNull { it.score } ?: 0.0
                Triple(h, tier, rankScore + roleCount * 0.001)
            }
            .maxWithOrNull(compareBy<Triple<Hero, Int, Double>> { it.second }.thenByDescending { it.third })

        addSectionHeader(content, ">> PRIORITY PICKS <<")
        val priority = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(1), 0, dp(1), dp(1))
        }
        fun priorityCard(title: String, hero: Hero?, subtitle: String, accent: Int): TextView = TextView(this).apply {
            text = if (hero == null) "$title\nNO DATA\n$subtitle" else "$title\n${hero.name}\n$subtitle"
            mono(this, 6.8f, white)
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(1), dp(2), dp(1))
            background = box(Color.parseColor("#A6101812"), accent, 3f)
            layoutParams = LinearLayout.LayoutParams(0, dp(39), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
        priority.addView(priorityCard("★ RANK PICK", rankPick?.hero, if (rankPick == null) "no data" else "score ${"%.0f".format(rankPick.score)}", neon))
        priority.addView(priorityCard("◎ TOURNAMENT PICK", tournamentPick?.first, if (tournamentPick == null) "no tier data" else "${tournamentPick.first.tournamentTier} TIER", amber))
        content.addView(priority)

        addSectionHeader(content, ">> BAN PRIORITY <<")
        val bans = Engine.bans(ds, d, SettingsManager.ban(this))
        if (bans.isEmpty()) {
            content.addView(label("NO DATA", 7f, muted).apply { gravity = Gravity.CENTER })
        } else {
            val banLine = bans.take(3).joinToString("  •  ") { s ->
                "${s.hero.name} ${"%.0f".format(s.score)}${if (s.incomplete) "*" else ""}"
            }
            content.addView(label(banLine, 7f, white).apply {
                gravity = Gravity.CENTER
                setPadding(dp(3), 0, dp(3), dp(1))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

        addSectionHeader(content, ">> MATCHUP PROBABILITY <<")
        val p = Engine.prob(ds, d)
        val a = p?.ally ?: 50.0
        val e = 100.0 - a
        val probWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(1), dp(5), dp(3))
            background = box(card, Color.parseColor("#214F2D"), 3f)
        }
        val probLabels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        probA = label("ALLY  ${"%.1f".format(a)}%", 7.2f, neon, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(15), 1f)
        }
        probE = label("ENEMY  ${"%.1f".format(e)}%", 7.2f, red, true).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, dp(15), 1f)
        }
        probLabels.addView(probA); probLabels.addView(probE); probWrap.addView(probLabels)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = box(Color.parseColor("#3A303838"), Color.TRANSPARENT, 2f)
            layoutParams = LinearLayout.LayoutParams(-1, dp(6))
        }
        barA = View(this).apply { setBackgroundColor(neon); layoutParams = LinearLayout.LayoutParams(0, -1, a.toFloat()) }
        barE = View(this).apply { setBackgroundColor(red); layoutParams = LinearLayout.LayoutParams(0, -1, e.toFloat()) }
        track.addView(barA); track.addView(barE); probWrap.addView(track)
        if (p == null) probWrap.addView(label("No data — add picks with supplied stats.", 6.5f, muted).apply { gravity = Gravity.CENTER })
        content.addView(probWrap)
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
        wrap.addView(search, LinearLayout.LayoutParams(-1, dp(38)))

        // Quick-pick shortcuts are available when filling an ally pick.
        // They use the same supplied-data engine as the cards on the main overlay.
        if (title == "Ally pick") {
            val scored = Engine.picks(ds, d, SettingsManager.pick(this)).values.flatten()
            val mainHero = scored.maxByOrNull { it.score }?.hero
            val tierValue = mapOf("SS" to 6, "S" to 5, "A" to 4, "B" to 3, "C" to 2, "D" to 1)
            val tournamentHero = ds.heroes.values
                .filter { it.id !in d.used() && it.tournamentTier != null }
                .maxWithOrNull(compareBy<Hero> { tierValue[it.tournamentTier] ?: 0 }
                    .thenBy { scored.filter { s -> s.hero.id == it.id }.maxOfOrNull { s -> s.score } ?: 0.0 })

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
                    mainHero?.let { hero ->
                        runCatching { d.add(target, hero.id) }
                            .onSuccess { dialogRef?.dismiss(); refresh() }
                    }
                }
            })
            quick.addView(quickButton("◎ TOURNAMENT PICK", tournamentHero, amber).apply {
                setOnClickListener {
                    tournamentHero?.let { hero ->
                        runCatching { d.add(target, hero.id) }
                            .onSuccess { dialogRef?.dismiss(); refresh() }
                    }
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
        wrap.addView(list, LinearLayout.LayoutParams(-1, dp(250)))

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
                if (position !in items.indices) return@setOnItemClickListener
                val chosen = items[position]
                runCatching { d.add(target, chosen.id) }
                    .onSuccess { dialogRef?.dismiss(); refresh() }
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
        dialog.setOnShowListener {
            // Set the overlay window type only after the dialog has a real window.
            runCatching {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                dialog.window?.setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                )
            }
        }
        runCatching {
            dialog.show()
            render(available)
        }.onFailure {
            dialogRef = null
        }
    }

    private var dialogRef: AlertDialog? = null
}
