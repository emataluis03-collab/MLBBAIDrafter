package com.mlbbdrafter

/** The four editable draft areas. Each has exactly 5 independently clickable slots. */
enum class Slot { ALLY_BAN, ENEMY_BAN, ALLY_PICK, ENEMY_PICK }

class Draft {
    private fun fresh(): Map<Slot, Array<String?>> = Slot.values().associateWith { arrayOfNulls<String>(5) }
    private var s: Map<Slot, Array<String?>> = fresh()
    private val history = ArrayDeque<Map<Slot, List<String?>>>()

    fun at(k: Slot, i: Int): String? = s[k]!![i]
    fun list(k: Slot): List<String> = s[k]!!.filterNotNull()

    val allyBans: List<String> get() = list(Slot.ALLY_BAN)
    val enemyBans: List<String> get() = list(Slot.ENEMY_BAN)
    val allyPicks: List<String> get() = list(Slot.ALLY_PICK)
    val enemyPicks: List<String> get() = list(Slot.ENEMY_PICK)

    fun used(): List<String> = Slot.values().flatMap { list(it) }

    private fun snapshot() {
        history.addLast(s.mapValues { it.value.toList() })
        if (history.size > 60) history.removeFirst()
    }

    /** Put [id] (or clear with null) in an exact slot. */
    fun set(k: Slot, i: Int, id: String?) { snapshot(); s[k]!![i] = id }

    /** Put [id] in the first empty slot. Returns false when the area is full. */
    fun add(k: Slot, id: String): Boolean {
        val i = s[k]!!.indexOfFirst { it == null }
        if (i < 0) return false
        set(k, i, id)
        return true
    }

    fun undo() {
        val h = history.removeLastOrNull() ?: return
        s = h.mapValues { it.value.toTypedArray() }
    }

    fun clear() { snapshot(); s = fresh() }

    /** Saved so the draft survives the overlay being restarted by the system. */
    fun toJson(): String {
        val o = org.json.JSONObject()
        Slot.values().forEach { k ->
            val a = org.json.JSONArray()
            s[k]!!.forEach { a.put(it ?: "") }
            o.put(k.name, a)
        }
        return o.toString()
    }

    fun fromJson(text: String?, valid: (String) -> Boolean) {
        if (text == null) return
        try {
            val o = org.json.JSONObject(text)
            val n = fresh()
            Slot.values().forEach { k ->
                val a = o.optJSONArray(k.name) ?: return@forEach
                for (i in 0 until minOf(5, a.length())) {
                    val v = a.optString(i, "")
                    if (v.isNotEmpty() && valid(v)) n[k]!![i] = v
                }
            }
            s = n
            history.clear()
        } catch (_: Exception) { }
    }
}

data class Scored(val hero: Hero, val score: Double, val incomplete: Boolean, val why: List<String>)
data class Prob(val ally: Double, val notes: List<String>)
data class Next(val hero: Hero, val score: Double, val level: String)

object Engine {
    private fun avg(l: List<Double>): Double? = if (l.isEmpty()) null else l.average()

    fun takenRoles(picks: List<String>, ds: Dataset): Set<String> = assignLanes(picks, ds).values.toSet()

    /**
     * Works out which lane each picked hero fills. Heroes with two roles (e.g. JUNGLE/EXP)
     * are placed so that the most lanes end up covered. Returns heroId -> lane.
     */
    fun assignLanes(picks: List<String>, ds: Dataset): Map<String, String> {
        var best: List<String?> = emptyList()
        var bestN = -1
        fun go(i: Int, taken: Set<String>, cur: List<String?>) {
            if (i == picks.size) {
                val n = cur.count { it != null }
                if (n > bestN) { bestN = n; best = cur }
                return
            }
            val roles = ds.heroes[picks[i]]?.roles.orEmpty().filter { it !in taken }
            for (r in roles) go(i + 1, taken + r, cur + r)
            go(i + 1, taken, cur + (null as String?))
        }
        go(0, emptySet(), emptyList())
        val out = LinkedHashMap<String, String>()
        picks.forEachIndexed { i, id -> best.getOrNull(i)?.let { out[id] = it } }
        return out
    }

    /** Min-max normalise each factor across candidates; missing stays null. */
    private fun normalize(raw: List<Map<String, Double?>>): List<Map<String, Double?>> {
        val keys = raw.flatMap { it.keys }.toSet()
        val mm = keys.associateWith { k -> raw.mapNotNull { it[k] }.let { if (it.isEmpty()) null else it.min() to it.max() } }
        return raw.map { m ->
            m.mapValues { (k, v) ->
                val r = mm[k]
                if (v == null || r == null) null else if (r.second - r.first < 1e-9) 0.5 else (v - r.first) / (r.second - r.first)
            }
        }
    }

    /** Weighted sum over available factors only (weights redistributed); flags incomplete data. */
    private fun combine(c: List<Hero>, raw: List<Map<String, Double?>>, w: Map<String, Double>): List<Scored> {
        val norm = normalize(raw)
        val active = w.filter { it.value > 0 }
        return c.indices.map { i ->
            val avail = active.filter { norm[i][it.key] != null }
            val tw = avail.values.sum()
            val score = if (tw <= 0) 0.0 else avail.entries.sumOf { it.value * norm[i][it.key]!! } / tw * 100
            val why = avail.entries.sortedByDescending { it.value * norm[i][it.key]!! }.take(2)
                .map { "${it.key} ${"%.1f".format(raw[i][it.key])}" }
            Scored(c[i], score, avail.size < active.size, why)
        }.sortedByDescending { it.score }
    }

    /**
     * Suggestions for EVERY lane, always. A lane that is already filled still gets alternatives
     * (the filled hero is simply excluded), and a lane never comes back empty unless every hero
     * in the dataset is already used.
     */
    fun picks(ds: Dataset, d: Draft, w: Map<String, Double>, n: Int = 3): Map<String, List<Scored>> {
        val tierValue = mapOf("SS" to 6.0, "S" to 5.0, "A" to 4.0, "B" to 3.0, "C" to 2.0, "D" to 1.0)
        val used = d.used().toSet()
        val pool = ds.heroes.values.filter { it.id !in used }
        val allyPicks = d.allyPicks
        val enemyPicks = d.enemyPicks
        return ROLES.associateWith { r ->
            val rolePool = pool.filter { r in it.roles }
            // Not enough heroes for the lane left? top it up from the rest of the pool.
            val c = if (rolePool.size >= n) rolePool else rolePool + pool.filter { it !in rolePool }
            val raw = c.map { h ->
                val pairSy = avg(allyPicks.mapNotNull { ds.syn(h.id, it) })
                val pairCounter = avg(enemyPicks.mapNotNull { ds.mu(h.id, it) })
                val laneSy = ds.synergyByLane[r]?.firstOrNull { it.first == h.id }?.second
                val laneCounter = ds.counterByLane[r]?.firstOrNull { it.first == h.id }?.second
                val roleSy = ds.synergyByRole[r]?.firstOrNull { it.first == h.id }?.second
                val roleCounter = ds.counterByRole[r]?.firstOrNull { it.first == h.id }?.second
                mapOf<String, Double?>(
                    "synergy" to (pairSy ?: laneSy?.let { 50.0 + it * 20.0 } ?: roleSy?.let { 50.0 + it * 20.0 } ?: h.synergyScore?.let { 50.0 + it * 50.0 }),
                    "counter" to (pairCounter ?: laneCounter?.let { 50.0 + it * 20.0 } ?: roleCounter?.let { 50.0 + it * 20.0 } ?: h.counterScore?.let { 50.0 + it * 50.0 }),
                    "winRate" to (h.rankWinRate ?: h.winRate),
                    "pickRate" to (h.rankPickRate ?: h.pickRate),
                    "personal" to h.personal,
                    "roleWinRate" to h.roleWr[r],
                    "laneRank" to h.laneRankScore[r]
                )
            }
            // A hero that really plays this lane always outranks an off-role filler.
            combine(c, raw, w).sortedWith(
                compareByDescending<Scored> { r in it.hero.roles }
                    .thenByDescending { it.score }
                    .thenByDescending { it.hero.rankTier?.let { t -> tierValue[t] } ?: 0.0 }
                    .thenByDescending { it.hero.metaScore ?: -999.0 }
                    .thenByDescending { it.hero.firstPickScore ?: -999.0 }
            ).take(n)
        }
    }

    /**
     * ENEMY NEXT PICK. Ranks heroes by priority using ONLY the supplied statistics (ban/pick/win rate,
     * rank tier, tournament tier, meta, first-pick, and matchup data vs our picks when it exists).
     * This is a priority score, NOT a probability: the dataset has no "after X the enemy picks Y" data.
     */
    fun enemyNext(ds: Dataset, d: Draft, n: Int = 5): List<Next> {
        val tier = mapOf("SS" to 6.0, "S" to 5.0, "A" to 4.0, "B" to 3.0, "C" to 2.0, "D" to 1.0)
        val used = d.used().toSet()
        var c = ds.heroes.values.filter { it.id !in used }
        // Once the enemy has picks, prefer heroes that fill a lane they still need.
        val enemyLanes = assignLanes(d.enemyPicks, ds).values.toSet()
        val open = ROLES.filter { it !in enemyLanes }
        if (d.enemyPicks.isNotEmpty() && open.isNotEmpty()) {
            val fit = c.filter { h -> h.roles.any { it in open } }
            if (fit.isNotEmpty()) c = fit
        }
        val ally = d.allyPicks
        val raw = c.map { h ->
            mapOf<String, Double?>(
                "banRate" to (h.rankBanRate ?: h.banRate),
                "pickRate" to (h.rankPickRate ?: h.pickRate),
                "winRate" to (h.rankWinRate ?: h.winRate),
                "rankTier" to h.rankTier?.let { tier[it] },
                "tourTier" to h.tournamentTier?.let { tier[it] },
                "meta" to h.metaScore,
                "firstPick" to h.firstPickScore,
                "vsAlly" to avg(ally.mapNotNull { ds.mu(h.id, it) })
            )
        }
        val w = mapOf("banRate" to 25.0, "pickRate" to 20.0, "winRate" to 15.0, "rankTier" to 15.0,
            "tourTier" to 15.0, "meta" to 5.0, "firstPick" to 5.0, "vsAlly" to 15.0)
        return combine(c, raw, w).take(n).mapIndexed { i, s ->
            Next(s.hero, s.score, if (i < 3) "HIGH" else if (i < 5) "MEDIUM" else "LOW")
        }
    }

    fun bans(ds: Dataset, d: Draft, w: Map<String, Double>): List<Scored> {
        val used = d.used().toSet()
        val c = ds.heroes.values.filter { it.id !in used }
        val ally = d.allyPicks
        val enemy = d.enemyPicks
        val raw = c.map { h ->
            val vs = ally.mapNotNull { ds.mu(h.id, it) }
            mapOf<String, Double?>(
                "threat" to vs.maxOrNull(), "counter" to avg(vs),
                "enemySynergy" to avg(enemy.mapNotNull { ds.syn(h.id, it) }),
                "winRate" to (h.rankWinRate ?: h.winRate), "banRate" to (h.rankBanRate ?: h.banRate))
        }
        return combine(c, raw, w).take(5)
    }

    /** Estimate from supplied data only; null when no usable data. */
    fun prob(ds: Dataset, d: Draft): Prob? {
        fun side(own: List<String>, opp: List<String>, label: String): Pair<Double?, List<String>> {
            val sig = mutableListOf<Double>(); val n = mutableListOf<String>()
            val wrs = own.mapNotNull { ds.heroes[it]?.rankWinRate ?: ds.heroes[it]?.winRate }
            avg(wrs)?.let { sig += it; n += "$label avg hero win rate ${"%.1f".format(it)}" }
            val sy = mutableListOf<Double>()
            for (i in own.indices) for (j in i + 1 until own.size) ds.syn(own[i], own[j])?.let { sy += it }
            avg(sy)?.let { sig += it; n += "$label synergy ${"%.1f".format(it)}" }
            avg(own.flatMap { a -> opp.mapNotNull { ds.mu(a, it) } })?.let { sig += it; n += "$label matchups ${"%.1f".format(it)}" }
            return avg(sig) to n
        }
        val (a, an) = side(d.allyPicks, d.enemyPicks, "Ally")
        val (e, en) = side(d.enemyPicks, d.allyPicks, "Enemy")
        if (a == null || e == null) return null
        return Prob(a / (a + e) * 100, an + en)
    }
}
