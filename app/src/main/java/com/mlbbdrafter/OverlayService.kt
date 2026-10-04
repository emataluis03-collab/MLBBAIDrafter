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
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
    private lateinit var ds: Dataset
    private lateinit var prefs: SharedPreferences
    private val d = Draft()
    private var dialogRef: AlertDialog? = null
    private var defW = 0
    private var defH = 0

    // ---- palette -------------------------------------------------------------------------
    private val neon = Color.parseColor("#4DFF88")
    private val neonDim = Color.parseColor("#1F8F4A")
    private val bg = Color.parseColor("#F00A0F14")
    private val cardBg = Color.parseColor("#E6131B22")
    private val slotEmpty = Color.parseColor("#80182028")
    private val slotFilled = Color.parseColor("#E61B2A24")
    private val white = Color.parseColor("#EAF4EE")
    private val muted = Color.parseColor("#7E9488")
    private val red = Color.parseColor("#FF5A6E")
    private val amber = Color.parseColor("#FFC857")
    private val blue = Color.parseColor("#5EB6FF")
    private val hairline = Color.parseColor("#33FFFFFF")

    private val laneColor = mapOf(
        "EXP" to Color.parseColor("#FF8A5B"),
        "JUNGLE" to Color.parseColor("#7BE26B"),
        "MID" to Color.parseColor("#6FB7FF"),
        "GOLD" to Color.parseColor("#FFD166"),
        "ROAM" to Color.parseColor("#C58CFF")
    )
    private fun short(role: String) = when (role) { "JUNGLE" -> "JG"; "GOLD" -> "GLD"; else -> role }

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

        val dm = resources.displayMetrics
        defW = minOf(dp(340), dm.widthPixels - dp(8))
        defH = minOf(dp(600), dm.heightPixels - dp(70))

        lp = WindowManager.LayoutParams(
            prefs.getInt("w2", defW),
            prefs.getInt("h2", defH),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(4))
            y = prefs.getInt("y", dp(40))
        }

        build()
        clampPosition()
        wm.addView(root, lp)
        refresh()
    }

    override fun onDestroy() {
        runCatching { dialogRef?.dismiss() }
        if (::root.isInitialized) runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    // ---- small view helpers --------------------------------------------------------------
    private fun tv(text: CharSequence, size: Float = 11f, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }

    private fun box(color: Int = cardBg, stroke: Int = Color.TRANSPARENT, radius: Float = 8f) =
        GradientDrawable().apply {
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
            cornerRadius = dp(radius.toInt()).toFloat()
        }

    private fun hRow(h: Int = -2) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, if (h == -2) ViewGroup.LayoutParams.WRAP_CONTENT else dp(h))
    }

    private fun weighted(h: Int, mx: Int = 2) = LinearLayout.LayoutParams(0, dp(h), 1f).apply {
        marginStart = dp(mx); marginEnd = dp(mx)
    }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(h)) }

    /** Rounded section card with a coloured title. */
    private fun card(title: String, accent: Int = neon, subtitle: String? = null, fill: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(8))
            background = box(cardBg, hairline, 12f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        val head = hRow()
        head.addView(tv(title, 11f, accent, true).apply { letterSpacing = 0.08f })
        if (subtitle != null) head.addView(tv(subtitle, 9f, muted).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        c.addView(head)
        c.addView(spacer(5))
        c.fill()
        return c
    }

    private fun smallLabel(text: String, color: Int = muted) = tv(text, 9f, color, true).apply {
        setPadding(dp(2), dp(2), 0, dp(2))
    }

    private fun button(text: String, accent: Int = neon, onClick: () -> Unit) = tv(text, 11f, accent, true).apply {
        gravity = Gravity.CENTER
        background = box(Color.parseColor("#33" + String.format("%06X", accent and 0xFFFFFF)), accent, 10f)
        layoutParams = weighted(36)
        setOnClickListener { onClick() }
    }

    /** Two-line tappable chip: hero name + small sub-text. */
    private fun chip(name: String, sub: String, accent: Int, onClick: () -> Unit) = TextView(this).apply {
        val sb = SpannableStringBuilder(name)
        sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
        val st = sb.length
        sb.append(sub)
        sb.setSpan(RelativeSizeSpan(0.8f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(muted), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = sb
        textSize = 11f
        setTextColor(white)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(3), dp(2), dp(3), dp(2))
        background = box(Color.parseColor("#26" + String.format("%06X", accent and 0xFFFFFF)), accent, 9f)
        layoutParams = weighted(46)
        setOnClickListener { onClick() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- window drag / resize ------------------------------------------------------------
    private fun clampPosition() {
        val dm = resources.displayMetrics
        val left = dp(2)
        val top = dp(24)
        val maxX = (dm.widthPixels - lp.width - left).coerceAtLeast(left)
        val maxY = (dm.heightPixels - lp.height - dp(4)).coerceAtLeast(top)
        lp.x = lp.x.coerceIn(left, maxX)
        lp.y = lp.y.coerceIn(top, maxY)
    }

    private fun updateOverlayLayout() {
        if (!::root.isInitialized || !root.isAttachedToWindow) return
        runCatching { wm.updateViewLayout(root, lp) }
    }

    private fun drag(v: View, onTap: (() -> Unit)? = null) {
        var ox = 0; var oy = 0; var sx = 0f; var sy = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ox = lp.x; oy = lp.y; sx = e.rawX; sy = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val nx = ox + (e.rawX - sx).toInt()
                    val ny = oy + (e.rawY - sy).toInt()
                    if (abs(nx - ox) >= 1 || abs(ny - oy) >= 1) moved = true
                    lp.x = nx; lp.y = ny
                    clampPosition(); updateOverlayLayout()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    clampPosition(); updateOverlayLayout()
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    if (onTap != null && !moved && abs(e.rawX - sx) < dp(10) && abs(e.rawY - sy) < dp(10)) onTap()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    clampPosition(); updateOverlayLayout()
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    true
                }
                else -> true
            }
        }
    }

    private fun setMinimized(m: Boolean) {
        panel.visibility = if (m) View.GONE else View.VISIBLE
        mini.visibility = if (m) View.VISIBLE else View.GONE
        lp.width = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("w2", defW)
        lp.height = if (m) WindowManager.LayoutParams.WRAP_CONTENT else prefs.getInt("h2", defH)
        wm.updateViewLayout(root, lp)
    }

    private fun build() {
        root = FrameLayout(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(3))
            background = box(bg, neonDim, 16f)
        }

        val title = tv("AI LINEUP DRAFTER", 12f, neon, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
        }
        drag(title)
        val min = tv("–", 20f, neon, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { setMinimized(true) }
        }
        val close = tv("×", 20f, red, true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener { stopSelf() }
        }
        panel.addView(hRow(38).apply { addView(title); addView(min); addView(close) })

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(6))
        }
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val handle = tv("≡  DRAG TO RESIZE  ≡", 9f, muted).apply { gravity = Gravity.CENTER }
        var w0 = 0; var h0 = 0; var sx = 0f; var sy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { w0 = lp.width; h0 = lp.height; sx = e.rawX; sy = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dm = resources.displayMetrics
                    val maxW = (dm.widthPixels - dp(8)).coerceAtLeast(dp(300))
                    val maxH = (dm.heightPixels - dp(34)).coerceAtLeast(dp(420))
                    lp.width = (w0 + (e.rawX - sx).toInt()).coerceIn(dp(300), maxW)
                    lp.height = (h0 + (e.rawY - sy).toInt()).coerceIn(dp(420), maxH)
                    clampPosition(); updateOverlayLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampPosition(); updateOverlayLayout()
                    prefs.edit().putInt("w2", lp.width).putInt("h2", lp.height).putInt("x", lp.x).putInt("y", lp.y).apply()
                }
            }
            true
        }
        panel.addView(handle, LinearLayout.LayoutParams(-1, dp(24)))

        mini = tv("AI", 13f, neon, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
            background = box(bg, neon, 22f)
        }
        drag(mini) { setMinimized(false) }

        root.addView(panel)
        root.addView(mini)
    }

    // ---- draft slots ---------------------------------------------------------------------
    private fun slotView(kind: Slot, i: Int, accent: Int, tall: Boolean, laneTag: String? = null): TextView {
        val heroId = d.at(kind, i)
        return TextView(this).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(2), 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (heroId == null) {
                text = "+"
                textSize = 18f
                setTextColor(muted)
                background = box(slotEmpty, hairline, 10f)
            } else {
                val sb = SpannableStringBuilder(ds.heroes[heroId]?.name ?: heroId)
                sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (laneTag != null) {
                    sb.append("\n")
                    val st = sb.length
                    sb.append(short(laneTag))
                    sb.setSpan(RelativeSizeSpan(0.78f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(laneColor[laneTag] ?: muted), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                text = sb
                textSize = 10.5f
                setTextColor(white)
                background = box(slotFilled, accent, 10f)
            }
            layoutParams = weighted(if (tall) 44 else 36)
            setOnClickListener { if (heroId == null) openPicker(kind, i) else openSlotMenu(kind, i) }
        }
    }

    private fun slotRow(kind: Slot, accent: Int, tall: Boolean, laneOf: Map<String, String> = emptyMap()) =
        hRow().apply { for (i in 0 until 5) addView(slotView(kind, i, accent, tall, d.at(kind, i)?.let { laneOf[it] })) }

    /** Add to ally picks. If [replaceId] is given, that hero is swapped out instead. */
    private fun putAllyPick(hero: Hero, replaceId: String? = null) {
        val idx = if (replaceId != null) (0 until 5).firstOrNull { d.at(Slot.ALLY_PICK, it) == replaceId }
                  else (0 until 5).firstOrNull { d.at(Slot.ALLY_PICK, it) == null }
        if (idx == null) { toast("Ally picks are full — tap a slot to change one"); return }
        d.set(Slot.ALLY_PICK, idx, hero.id)
        refresh()
    }

    private fun putAllyBan(hero: Hero) {
        if (!d.add(Slot.ALLY_BAN, hero.id)) toast("Ally bans are full — tap a slot to change one") else refresh()
    }

    // ---- main render ---------------------------------------------------------------------
    private fun refresh() {
        content.removeAllViews()
        val laneOf = Engine.assignLanes(d.allyPicks, ds)
        val filledByLane = laneOf.entries.associate { it.value to it.key }   // lane -> heroId

        content.addView(tv("${ds.label.uppercase()}  •  ${ds.heroes.size} HEROES", 8f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(5))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })

        // BANS
        content.addView(card("BANS", amber, "tap any slot") {
            addView(smallLabel("ALLY BANS", neon))
            addView(slotRow(Slot.ALLY_BAN, neonDim, false))
            addView(spacer(4))
            addView(smallLabel("ENEMY BANS", red))
            addView(slotRow(Slot.ENEMY_BAN, red, false))
        })

        // PICKS
        content.addView(card("PICKS", neon, "tap any slot") {
            addView(smallLabel("ALLY PICKS", neon))
            addView(slotRow(Slot.ALLY_PICK, neonDim, true, laneOf))
            addView(spacer(4))
            addView(smallLabel("ENEMY PICKS", red))
            addView(slotRow(Slot.ENEMY_PICK, red, true))
            addView(spacer(6))
            addView(hRow(36).apply {
                addView(button("↶ UNDO", amber) { d.undo(); refresh() })
                addView(button("RESET ALL", red) { d.clear(); refresh() })
            })
        })

        // SUGGESTIONS PER LANE (every lane, always)
        val pickMap = Engine.picks(ds, d, SettingsManager.pick(this), 3)
        content.addView(card("SUGGESTED PICKS PER LANE", neon, "tap to pick") {
            for (role in ROLES) {
                val filled = filledByLane[role]
                val list = pickMap[role].orEmpty()
                val accent = laneColor[role] ?: neon
                val head = hRow().apply { setPadding(dp(2), dp(4), 0, dp(3)) }
                head.addView(tv(role, 11f, accent, true).apply { layoutParams = LinearLayout.LayoutParams(dp(62), -2) })
                head.addView(
                    if (filled != null) tv("✓ ${ds.heroes[filled]?.name ?: filled}  •  alternatives", 10f, neon)
                    else tv("OPEN LANE", 10f, amber, true)
                )
                addView(head)
                if (list.isEmpty()) {
                    addView(tv("No heroes left in the pool", 10f, muted).apply { setPadding(dp(4), 0, 0, dp(4)) })
                } else {
                    addView(hRow().apply {
                        list.forEach { s ->
                            val h = s.hero
                            val wr = (h.rankWinRate ?: h.winRate)?.let { "%.1f%%".format(it) } ?: "—"
                            addView(chip(h.name, "${"%.0f".format(s.score)} • $wr", accent) { putAllyPick(h, filled) })
                        }
                    })
                }
            }
        })

        // PRIORITY PICKS (prefer lanes that are still open)
        val openRoles = ROLES.filter { it !in filledByLane }
        val poolMap = if (openRoles.isEmpty()) pickMap else pickMap.filterKeys { it in openRoles }
        val rankPick = poolMap.values.flatten().maxByOrNull { it.score }
        val tourPick = tournamentPick(poolMap)
        content.addView(card("PRIORITY PICKS", amber, if (openRoles.isEmpty()) "all lanes filled" else "open lanes") {
            addView(hRow().apply {
                if (rankPick != null) addView(chip(rankPick.hero.name, "★ RANK ${"%.0f".format(rankPick.score)}", neon) { putAllyPick(rankPick.hero) })
                else addView(chip("—", "★ RANK", neon) { })
                if (tourPick != null) addView(chip(tourPick.name, "◎ TIER ${tourPick.tournamentTier}", amber) { putAllyPick(tourPick) })
                else addView(chip("—", "◎ TOURNAMENT", amber) { })
            })
        })

        // BAN PRIORITY
        val bans = Engine.bans(ds, d, SettingsManager.ban(this))
        content.addView(card("BAN PRIORITY", red, "tap = ally ban") {
            if (bans.isEmpty()) addView(tv("No heroes left", 10f, muted))
            else addView(hRow().apply {
                bans.take(4).forEach { s ->
                    val br = (s.hero.rankBanRate ?: s.hero.banRate)?.let { "BR %.0f%%".format(it) } ?: "${"%.0f".format(s.score)}"
                    addView(chip(s.hero.name, br, red) { putAllyBan(s.hero) })
                }
            })
        })

        // WIN PROBABILITY
        val p = Engine.prob(ds, d)
        val a = p?.ally ?: 50.0
        val e = 100.0 - a
        content.addView(card("MATCHUP PROBABILITY", blue) {
            addView(hRow().apply {
                addView(tv("ALLY  ${"%.1f".format(a)}%", 12f, neon, true).apply { layoutParams = LinearLayout.LayoutParams(0, dp(20), 1f) })
                addView(tv("ENEMY  ${"%.1f".format(e)}%", 12f, red, true).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, dp(20), 1f)
                })
            })
            addView(LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                background = box(Color.parseColor("#3A303838"), Color.TRANSPARENT, 4f)
                layoutParams = LinearLayout.LayoutParams(-1, dp(8))
                addView(View(this@OverlayService).apply { setBackgroundColor(neon); layoutParams = LinearLayout.LayoutParams(0, -1, a.toFloat()) })
                addView(View(this@OverlayService).apply { setBackgroundColor(red); layoutParams = LinearLayout.LayoutParams(0, -1, e.toFloat()) })
            })
            if (p == null) addView(tv("Add ally AND enemy picks to see the estimate", 9f, muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0)
            })
        })
    }

    private fun tournamentPick(pm: Map<String, List<Scored>>): Hero? {
        val tierValue = mapOf("SS" to 6, "S" to 5, "A" to 4, "B" to 3, "C" to 2, "D" to 1)
        val used = d.used().toSet()
        val scores = pm.values.flatten()
        return ds.heroes.values
            .filter { it.id !in used && it.tournamentTier != null }
            .maxWithOrNull(
                compareBy<Hero> { h -> h.tournamentTier?.let { t -> tierValue[t] } ?: 0 }
                    .thenBy { h -> scores.filter { s -> s.hero.id == h.id }.maxOfOrNull { s -> s.score } ?: 0.0 }
            )
    }

    // ---- dialogs -------------------------------------------------------------------------
    private fun showDlg(b: AlertDialog.Builder): AlertDialog {
        val dialog = b.create()
        dialogRef = dialog
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
        return dialog
    }

    private fun dlgBuilder() = AlertDialog.Builder(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert))

    private fun kindTitle(k: Slot) = when (k) {
        Slot.ALLY_BAN -> "Ally ban"
        Slot.ENEMY_BAN -> "Enemy ban"
        Slot.ALLY_PICK -> "Ally pick"
        Slot.ENEMY_PICK -> "Enemy pick"
    }

    private fun openSlotMenu(kind: Slot, i: Int) {
        val name = d.at(kind, i)?.let { ds.heroes[it]?.name ?: it } ?: return
        showDlg(
            dlgBuilder().setTitle("${kindTitle(kind)}: $name")
                .setItems(arrayOf("Change hero", "Remove hero")) { _, which ->
                    if (which == 0) openPicker(kind, i) else { d.set(kind, i, null); refresh() }
                }
                .setNegativeButton("Cancel", null)
        )
    }

    /** Searchable hero picker with lane filter. Edits exactly the tapped slot. */
    private fun openPicker(kind: Slot, index: Int) {
        val available = ds.heroes.values.filter { it.id !in d.used() }.sortedBy { it.name.lowercase() }
        if (available.isEmpty()) { toast("No heroes left"); return }

        var roleFilter: String? = null
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), 0)
        }
        val search = EditText(this).apply {
            hint = "Search hero name..."
            setSingleLine(true)
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            background = box(Color.parseColor("#C90D1711"), neonDim, 8f)
            setPadding(dp(10), 0, dp(10), 0)
        }
        wrap.addView(search, LinearLayout.LayoutParams(-1, dp(40)))

        // Quick picks for ally picks: use the same engine as the main cards.
        if (kind == Slot.ALLY_PICK) {
            val pm = Engine.picks(ds, d, SettingsManager.pick(this), 1)
            val rank = pm.values.flatten().maxByOrNull { it.score }?.hero
            val tour = tournamentPick(pm)
            fun quick(title: String, hero: Hero?, accent: Int) = TextView(this).apply {
                text = if (hero == null) title else "$title\n${hero.name}"
                textSize = 10f
                setTextColor(white)
                gravity = Gravity.CENTER
                background = box(Color.parseColor("#26" + String.format("%06X", accent and 0xFFFFFF)), accent, 9f)
                layoutParams = weighted(44)
                if (hero != null) setOnClickListener { d.set(kind, index, hero.id); refresh(); dialogRef?.dismiss() }
            }
            wrap.addView(hRow().apply {
                setPadding(0, dp(4), 0, dp(2))
                addView(quick("★ RANK PICK", rank, neon)); addView(quick("◎ TOURNAMENT", tour, amber))
            })
        }

        // Lane filter chips
        val filterNames = listOf<String?>(null) + ROLES
        val filterViews = ArrayList<TextView>()
        val count = tv("", 10f, muted).apply { setPadding(dp(2), dp(4), dp(2), dp(3)) }
        val list = ListView(this).apply {
            dividerHeight = dp(1)
            divider = box(Color.parseColor("#3327A84C"), Color.TRANSPARENT, 0f)
        }

        fun visible(): List<Hero> {
            val q = search.text.toString().trim().lowercase()
            val rf = roleFilter
            return available.filter { h ->
                (rf == null || h.roles.contains(rf)) &&
                    (q.isEmpty() || h.name.lowercase().contains(q) || h.id.lowercase().contains(q))
            }
        }

        fun render() {
            val items = visible()
            count.text = if (items.isEmpty()) "No matching hero" else "${items.size} heroes"
            list.adapter = object : ArrayAdapter<Hero>(this@OverlayService, android.R.layout.simple_list_item_1, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    val h = getItem(position)
                    val sb = SpannableStringBuilder(h?.name ?: "")
                    sb.append("   ")
                    val st = sb.length
                    sb.append(h?.roles?.joinToString(" · ") { short(it) } ?: "")
                    sb.setSpan(ForegroundColorSpan(muted), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.75f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    v.text = sb
                    v.textSize = 14f
                    v.setTextColor(white)
                    v.setPadding(dp(10), 0, dp(10), 0)
                    v.setBackgroundColor(Color.TRANSPARENT)
                    return v
                }
            }
            list.setOnItemClickListener { _, _, position, _ ->
                d.set(kind, index, items[position].id)
                refresh()
                dialogRef?.dismiss()
            }
        }

        fun styleFilters() {
            filterViews.forEachIndexed { i, v ->
                val on = filterNames[i] == roleFilter
                val accent = filterNames[i]?.let { laneColor[it] } ?: neon
                v.setTextColor(if (on) Color.BLACK else accent)
                v.background = box(if (on) accent else Color.TRANSPARENT, accent, 8f)
            }
        }

        wrap.addView(hRow().apply {
            setPadding(0, dp(4), 0, dp(2))
            filterNames.forEachIndexed { i, r ->
                val t = tv(if (r == null) "ALL" else short(r), 10f, neon, true).apply {
                    gravity = Gravity.CENTER
                    layoutParams = weighted(28, 1)
                    setOnClickListener { roleFilter = r; styleFilters(); render() }
                }
                filterViews += t
                addView(t)
            }
        })
        wrap.addView(count)
        wrap.addView(list, LinearLayout.LayoutParams(-1, dp(250)))

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        styleFilters()
        render()
        val dialog = showDlg(
            dlgBuilder().setTitle(kindTitle(kind) + "  #${index + 1}").setView(wrap).setNegativeButton("Cancel", null)
        )
        search.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }
}
