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
        val need = ROLES - takenRoles(d.allyPicks, ds)
        val pool = ds.heroes.values.filter { it.id !in d.used() }
        return need.associateWith { r ->
            val c = pool.filter { r in it.roles }
            val raw = c.map { h ->
                mapOf<String, Double?>(
                    "synergy" to (avg(d.allyPicks.mapNotNull { ds.syn(h.id, it) }) ?: h.synergyScore?.let { 50.0 + it * 50.0 }),
                    "counter" to (avg(d.enemyPicks.mapNotNull { ds.mu(h.id, it) }) ?: h.counterScore?.let { 50.0 + it * 50.0 }),
                    "winRate" to (h.roleWr[r] ?: h.winRate), "pickRate" to h.pickRate,
                    // Personal performance is optional. The supplied meta/first-pick data are
                    // shown separately and used as deterministic tie-breakers, not invented stats.
                    "personal" to h.personal)
            }
            combine(c, raw, w).sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.hero.metaScore ?: -999.0 }.thenByDescending { it.hero.firstPickScore ?: -999.0 }).take(2)
        }
    }

    fun bans(ds: Dataset, d: Draft, w: Map<String, Double>): List<Scored> {
        val c = ds.heroes.values.filter { it.id !in d.used() }
        val raw = c.map { h ->
            val vs = d.allyPicks.mapNotNull { ds.mu(h.id, it) }
            mapOf<String, Double?>(
                "threat" to vs.maxOrNull(), "counter" to avg(vs),
                "enemySynergy" to avg(d.enemyPicks.mapNotNull { ds.syn(h.id, it) }),
                "winRate" to h.winRate, "banRate" to h.banRate)
        }
        return combine(c, raw, w).take(5)
    }

    /**
     * Estimate from the supplied hero win-rate data, and pairwise matchup data when it
     * actually exists.  Do not turn a raw 59.4% hero win rate into an arbitrary
     * "59.4 / (59.4 + 50)" probability.  With only one side drafted, the displayed
     * value follows that side's supplied average win rate; with both sides drafted,
     * the difference between the two supplied team averages is centered around 50/50.
     */
    fun prob(ds: Dataset, d: Draft): Prob? {
        val allyWrs = d.allyPicks.mapNotNull { ds.heroes[it]?.winRate }
        val enemyWrs = d.enemyPicks.mapNotNull { ds.heroes[it]?.winRate }
        val notes = mutableListOf<String>()

        val allyWr = avg(allyWrs)
        val enemyWr = avg(enemyWrs)

        // Pairwise data, if present in the imported dataset, is stronger evidence than
        // the aggregate hero averages. It is deliberately ignored when unavailable.
        val allyMatchups = d.allyPicks.flatMap { a ->
            d.enemyPicks.mapNotNull { b -> ds.mu(a, b) }
        }
        val enemyMatchups = d.enemyPicks.flatMap { e ->
            d.allyPicks.mapNotNull { a -> ds.mu(e, a) }
        }
        val allyMu = avg(allyMatchups)
        val enemyMu = avg(enemyMatchups)

        val allyBase = allyMu ?: allyWr
        val enemyBase = enemyMu ?: enemyWr
        if (allyBase == null && enemyBase == null) return null

        if (allyWr != null) notes += "Ally supplied avg hero win rate ${"%.2f".format(allyWr)}%"
        if (enemyWr != null) notes += "Enemy supplied avg hero win rate ${"%.2f".format(enemyWr)}%"
        if (allyMu != null) notes += "Ally supplied matchup avg ${"%.2f".format(allyMu)}%"
        if (enemyMu != null) notes += "Enemy supplied matchup avg ${"%.2f".format(enemyMu)}%"

        val ally = when {
            allyBase != null && enemyBase != null -> 50.0 + (allyBase - enemyBase) / 2.0
            allyBase != null -> allyBase
            else -> 100.0 - enemyBase!!
        }.coerceIn(0.0, 100.0)

        return Prob(ally, notes)
    }
}
