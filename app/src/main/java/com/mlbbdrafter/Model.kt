package com.mlbbdrafter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

val ROLES = listOf("EXP", "JUNGLE", "MID", "GOLD", "ROAM")

data class Hero(
    val id: String, val name: String, val roles: List<String>,
    val winRate: Double?, val pickRate: Double?, val banRate: Double?,
    val roleWr: Map<String, Double>, val personal: Double?,
    val metaScore: Double? = null, val firstPickScore: Double? = null,
    val tournamentTier: String? = null, val laneRankScore: Map<String, Double> = emptyMap(),
    val synergyScore: Double? = null, val counterScore: Double? = null,
    val rankTier: String? = null, val rankWinRate: Double? = null, val rankBanRate: Double? = null, val rankPickRate: Double? = null
)
data class PairStat(val a: String, val b: String, val wr: Double, val games: Int?)

class Dataset(
    val heroes: Map<String, Hero>, matchups: List<PairStat>, synergies: List<PairStat>,
    val warnings: List<String>, val label: String,
    val counterByLane: Map<String, List<Pair<String, Double>>> = emptyMap(),
    val synergyByLane: Map<String, List<Pair<String, Double>>> = emptyMap(),
    val counterByRole: Map<String, List<Pair<String, Double>>> = emptyMap(),
    val synergyByRole: Map<String, List<Pair<String, Double>>> = emptyMap()
) {
    private val m = HashMap<String, Double>()
    private val s = HashMap<String, Double>()
    init {
        matchups.forEach { m["${it.a}|${it.b}"] = it.wr; m.putIfAbsent("${it.b}|${it.a}", 100 - it.wr) }
        synergies.forEach { s["${it.a}|${it.b}"] = it.wr; s["${it.b}|${it.a}"] = it.wr }
    }
    /** Win rate of a against b, or null if no data. */
    fun mu(a: String, b: String): Double? = m["$a|$b"]
    fun syn(a: String, b: String): Double? = s["$a|$b"]
}

object DataImporter {
    fun parse(text: String): Dataset {
        val root = JSONObject(text)
        val w = mutableListOf<String>()
        fun pct(o: JSONObject, k: String, ctx: String): Double? {
            if (!o.has(k) || o.isNull(k)) return null
            val v = o.getDouble(k)
            if (v < 0 || v > 100) { w += "$ctx: invalid percentage $k=$v"; return null }
            return v
        }
        val heroes = LinkedHashMap<String, Hero>()
        val arr = root.optJSONArray("heroes") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id", "")
            if (id.isEmpty()) { w += "hero #$i: missing id"; continue }
            if (id in heroes) { w += "duplicate hero: $id"; continue }
            val ra = o.optJSONArray("roles") ?: JSONArray()
            val roles = (0 until ra.length()).map { ra.getString(it).uppercase() }
            val bad = roles.filter { it !in ROLES }
            if (bad.isNotEmpty()) w += "$id: invalid role $bad"
            val rw = HashMap<String, Double>()
            o.optJSONObject("roleWinRate")?.let { r -> r.keys().forEach { k -> pct(r, k, id)?.let { rw[k.uppercase()] = it } } }
            heroes[id] = Hero(id, o.optString("name", id), roles.filter { it in ROLES },
                pct(o, "winRate", id), pct(o, "pickRate", id), pct(o, "banRate", id), rw, pct(o, "personal", id),
                if (o.has("metaScore") && !o.isNull("metaScore")) o.optDouble("metaScore") else null,
                if (o.has("firstPickScore") && !o.isNull("firstPickScore")) o.optDouble("firstPickScore") else null,
                o.optString("tournamentTier", "").ifBlank { null },
                buildMap { o.optJSONObject("laneRankScore")?.let { lr -> lr.keys().forEach { k -> put(k.uppercase(), lr.optDouble(k)) } } },
                if (o.has("synergyScore") && !o.isNull("synergyScore")) o.optDouble("synergyScore") else null,
                if (o.has("counterScore") && !o.isNull("counterScore")) o.optDouble("counterScore") else null,
                o.optJSONObject("rankStats")?.optString("tier", "")?.ifBlank { null },
                o.optJSONObject("rankStats")?.let { if (it.has("winRate")) it.optDouble("winRate") else null },
                o.optJSONObject("rankStats")?.let { if (it.has("banRate")) it.optDouble("banRate") else null },
                o.optJSONObject("rankStats")?.let { if (it.has("pickRate")) it.optDouble("pickRate") else null })
        }
        fun pairs(key: String): List<PairStat> {
            val a = root.optJSONArray(key) ?: return emptyList()
            var noGames = 0
            val out = mutableListOf<PairStat>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val x = o.optString("a"); val y = o.optString("b")
                if (x !in heroes || y !in heroes) { w += "$key #$i: missing hero ($x / $y)"; continue }
                val v = pct(o, "winRate", "$key #$i") ?: continue
                val g = if (o.has("games")) o.optInt("games") else null
                if (g == null) noGames++
                out += PairStat(x, y, v, g)
            }
            if (noGames > 0) w += "$key: $noGames entries missing sample size"
            return out
        }
        val mu = pairs("matchups"); val sy = pairs("synergies")
        fun rankedMap(key: String): Map<String, List<Pair<String, Double>>> {
            val out = mutableMapOf<String, List<Pair<String, Double>>>()
            root.optJSONObject(key)?.let { obj ->
                obj.keys().forEach { lane ->
                    val a = obj.optJSONArray(lane) ?: return@forEach
                    out[lane.uppercase()] = (0 until a.length()).mapNotNull { i ->
                        val pair = a.optJSONArray(i) ?: return@mapNotNull null
                        if (pair.length() < 2) return@mapNotNull null
                        pair.optString(0).takeIf { it.isNotBlank() }?.let { it to pair.optDouble(1) }
                    }
                }
            }
            return out
        }
        // The bundled user dataset contains aggregate matchup/synergy rankings rather
        // than a full pairwise matrix. Do not label that intentional structure as missing data.
        if (heroes.isEmpty()) w += "No heroes found"
        return Dataset(heroes, mu, sy, w, root.optString("label", "User data"),
            rankedMap("counterByLane"), rankedMap("synergyByLane"),
            rankedMap("counterByRole"), rankedMap("synergyByRole"))
    }
}

object Store {
    private fun f(c: Context) = File(c.filesDir, "dataset.json")
    private fun p(c: Context) = c.getSharedPreferences("dataset", Context.MODE_PRIVATE)

    /** True only when the user imported a dataset on purpose. A dataset.json with no such mark is stale and ignored. */
    fun isImported(c: Context): Boolean = f(c).exists() && p(c).getBoolean("imported", false)
    fun source(c: Context): String = if (isImported(c)) "IMPORTED" else "BUNDLED"

    fun raw(c: Context): String {
        if (isImported(c)) return f(c).readText()
        if (f(c).exists()) f(c).delete()   // stale leftover from an older version
        return c.assets.open("sample_dataset.json").bufferedReader().readText()
    }
    fun load(c: Context): Dataset = DataImporter.parse(raw(c))
    fun save(c: Context, text: String) {
        DataImporter.parse(text)
        f(c).writeText(text)
        p(c).edit().putBoolean("imported", true).apply()
    }
    fun reset(c: Context) { f(c).delete(); p(c).edit().putBoolean("imported", false).apply() }
}

object SettingsManager {
    val PICK = linkedMapOf("synergy" to 25.0, "counter" to 25.0, "winRate" to 20.0, "laneRank" to 15.0, "roleWinRate" to 5.0, "pickRate" to 5.0, "personal" to 5.0)
    val BAN = linkedMapOf("threat" to 30.0, "counter" to 25.0, "enemySynergy" to 20.0, "winRate" to 15.0, "banRate" to 10.0)
    private fun p(c: Context) = c.getSharedPreferences("weights", 0)
    fun pick(c: Context): Map<String, Double> = PICK.mapValues { p(c).getFloat("pick_${it.key}", it.value.toFloat()).toDouble() }
    fun ban(c: Context): Map<String, Double> = BAN.mapValues { p(c).getFloat("ban_${it.key}", it.value.toFloat()).toDouble() }
    fun set(c: Context, key: String, v: Float) { p(c).edit().putFloat(key, v).apply() }
}
