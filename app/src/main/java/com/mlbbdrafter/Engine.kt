package com.mlbbdrafter

class Draft {
    val allyBans = mutableListOf<String>(); val enemyBans = mutableListOf<String>()
    val allyPicks = mutableListOf<String>(); val enemyPicks = mutableListOf<String>()
    private val stack = mutableListOf<MutableList<String>>()
    fun used() = allyBans + enemyBans + allyPicks + enemyPicks
    fun add(l: MutableList<String>, id: String) { l += id; stack += l }
    fun undo() { stack.removeLastOrNull()?.let { it.removeLastOrNull() } }
    fun clear() { allyBans.clear(); enemyBans.clear(); allyPicks.clear(); enemyPicks.clear(); stack.clear() }
}

data class Scored(val hero: Hero, val score: Double, val incomplete: Boolean, val why: List<String>)
data class Prob(val ally: Double, val notes: List<String>)

object Engine {
    private fun avg(l: List<Double>): Double? = if (l.isEmpty()) null else l.average()

    fun takenRoles(picks: List<String>, ds: Dataset): Set<String> {
        val t = mutableSetOf<String>()
        for (p in picks) ds.heroes[p]?.roles?.firstOrNull { it !in t }?.let { t += it }
        return t
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

    fun picks(ds: Dataset, d: Draft, w: Map<String, Double>): Map<String, List<Scored>> {
        // Keep every role visible even after that role has already been picked.
        // The filled hero is excluded from alternatives, so the user always gets
        // useful "other picks" instead of NO DATA.
        val tierValue = mapOf("SS" to 6.0, "S" to 5.0, "A" to 4.0, "B" to 3.0, "C" to 2.0, "D" to 1.0)
        val pool = ds.heroes.values.filter { it.id !in d.used() }
        return ROLES.associateWith { r ->
            val c = pool.filter { r in it.roles }
            val raw = c.map { h ->
                val pairSy = avg(d.allyPicks.mapNotNull { ds.syn(h.id, it) })
                val pairCounter = avg(d.enemyPicks.mapNotNull { ds.mu(h.id, it) })
                val laneSy = ds.synergyByLane[r]?.firstOrNull { it.first == h.id }?.second
                val laneCounter = ds.counterByLane[r]?.firstOrNull { it.first == h.id }?.second
                mapOf<String, Double?>(
                    "synergy" to (pairSy ?: laneSy?.let { 50.0 + it * 20.0 } ?: h.synergyScore?.let { 50.0 + it * 50.0 }),
                    "counter" to (pairCounter ?: laneCounter?.let { 50.0 + it * 20.0 } ?: h.counterScore?.let { 50.0 + it * 50.0 }),
                    "winRate" to (h.rankWinRate ?: h.winRate),
                    "pickRate" to (h.rankPickRate ?: h.pickRate),
                    "personal" to h.personal
                )
            }
            combine(c, raw, w).sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.hero.rankTier?.let { t -> tierValue[t] } ?: 0.0 }
                    .thenByDescending { it.hero.metaScore ?: -999.0 }
                    .thenByDescending { it.hero.firstPickScore ?: -999.0 }
            ).take(2)
        }
    }

    fun bans(ds: Dataset, d: Draft, w: Map<String, Double>): List<Scored> {
        val c = ds.heroes.values.filter { it.id !in d.used() }
        val raw = c.map { h ->
            val vs = d.allyPicks.mapNotNull { ds.mu(h.id, it) }
            mapOf<String, Double?>(
                "threat" to vs.maxOrNull(), "counter" to avg(vs),
                "enemySynergy" to avg(d.enemyPicks.mapNotNull { ds.syn(h.id, it) }),
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
