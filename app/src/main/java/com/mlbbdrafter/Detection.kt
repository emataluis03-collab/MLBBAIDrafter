package com.mlbbdrafter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * READ-ONLY geometry of the 20 draft slots, as fractions (0..1) of the screen.
 * Defaults are approximate (from a 1536x1067 reference screenshot). On the phone, the SCAN tab of the overlay
 * can move/resize each group of boxes until they sit exactly on the portraits; that adjustment is saved.
 */
object DraftSlotLayout {
    data class SlotRect(val kind: Slot, val index: Int, val rect: RectF)

    // left, top, right, bottom
    private val bounds = listOf(
        Slot.ALLY_BAN to floatArrayOf(0.035f, 0.085f, 0.205f, 0.145f),
        Slot.ENEMY_BAN to floatArrayOf(0.705f, 0.085f, 0.965f, 0.145f),
        Slot.ALLY_PICK to floatArrayOf(0.035f, 0.17f, 0.19f, 0.88f),
        Slot.ENEMY_PICK to floatArrayOf(0.81f, 0.17f, 0.965f, 0.88f)
    )

    private fun prefs(c: Context) = c.getSharedPreferences("calib", Context.MODE_PRIVATE)

    /** [dx, dy, widthScale, heightScale] for one group of 5 slots. */
    fun adjust(c: Context, k: Slot): FloatArray {
        val out = floatArrayOf(0f, 0f, 1f, 1f)
        val s = prefs(c).getString("cal_" + k.name, null) ?: return out
        val p = s.split(",")
        if (p.size == 4) for (i in 0 until 4) p[i].toFloatOrNull()?.let { out[i] = it }
        return out
    }

    fun setAdjust(c: Context, k: Slot, a: FloatArray) {
        prefs(c).edit().putString("cal_" + k.name, a.joinToString(",")).apply()
    }

    fun resetAdjust(c: Context, k: Slot) {
        prefs(c).edit().remove("cal_" + k.name).apply()
    }

    /** Normalised (0..1) rectangles. Multiply by the frame / screen size to get pixels. */
    fun slots(c: Context): List<SlotRect> {
        val out = ArrayList<SlotRect>()
        for ((kind, b0) in bounds) {
            val a = adjust(c, kind)
            val cx = (b0[0] + b0[2]) / 2f + a[0]
            val cy = (b0[1] + b0[3]) / 2f + a[1]
            val hw = (b0[2] - b0[0]) / 2f * a[2]
            val hh = (b0[3] - b0[1]) / 2f * a[3]
            val b = floatArrayOf(cx - hw, cy - hh, cx + hw, cy + hh)
            val horizontal = kind == Slot.ALLY_BAN || kind == Slot.ENEMY_BAN
            for (i in 0 until 5) {
                val r = if (horizontal) {
                    val step = (b[2] - b[0]) / 5f
                    RectF(b[0] + i * step, b[1], b[0] + (i + 1) * step, b[3])
                } else {
                    val step = (b[3] - b[1]) / 5f
                    RectF(b[0], b[1] + i * step, b[2], b[1] + (i + 1) * step)
                }
                out.add(SlotRect(kind, i, r))
            }
        }
        return out
    }
}

/**
 * Lightweight local hero matcher.
 *
 * The first version compared only one tiny RGB square from each slot. That was very
 * sensitive to the MLBB frame/border, scaling and the different crop shape of bans
 * versus picks. This version compares several normalised centre crops and keeps the
 * best agreement. It is still completely local/read-only: it only looks at the slot
 * bitmap and the cached hero portraits.
 */
class HeroTemplateMatcher(private val templateDir: File, private val heroes: Map<String, Hero>) {
    class Match(val id: String, val score: Double, val margin: Double)

    private class Template(val id: String, val vectors: List<FloatArray>)

    companion object {
        private const val N = 20
        private const val INNER = 0.76f
    }

    private val templates = ArrayList<Template>()

    fun size(): Int = templates.size

    fun reload() {
        templates.clear()
        val files = templateDir.listFiles() ?: return
        for (f in files) {
            if (!f.isFile) continue
            val ext = f.extension.lowercase(Locale.US)
            if (ext != "png" && ext != "jpg" && ext != "jpeg") continue
            val id = f.nameWithoutExtension
            if (!heroes.containsKey(id)) continue
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            val vectors = buildVectors(bmp)
            bmp.recycle()
            if (vectors.isNotEmpty()) templates.add(Template(id, vectors))
        }
    }

    /** Build several robust descriptors from the same portrait. */
    private fun buildVectors(src: Bitmap): List<FloatArray> {
        val side = minOf(src.width, src.height)
        if (side < 2) return emptyList()
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, left, top, side, side)
        val out = ArrayList<FloatArray>(3)
        out += vectorOf(square, 1f)
        out += vectorOf(square, INNER)
        // A slightly tighter crop helps when the downloaded portrait has a border
        // that is not present in the in-game hero portrait.
        out += vectorOf(square, 0.62f)
        square.recycle()
        return out
    }

    /** Crop the centre, resize to NxN, then normalise RGB channels independently. */
    private fun vectorOf(src: Bitmap, fraction: Float): FloatArray {
        val side = (minOf(src.width, src.height) * fraction).toInt().coerceAtLeast(2)
        val sq = Bitmap.createBitmap(
            src,
            (src.width - side) / 2,
            (src.height - side) / 2,
            side,
            side
        )
        val small = Bitmap.createScaledBitmap(sq, N, N, true)
        val px = IntArray(N * N)
        small.getPixels(px, 0, N, 0, 0, N, N)
        if (small !== sq) small.recycle()
        sq.recycle()

        // Use luminance plus two chroma channels. This is more tolerant of the
        // capture's brightness/overlay changes than raw RGB alone.
        val v = FloatArray(N * N * 3)
        for (i in px.indices) {
            val r = ((px[i] shr 16) and 255) / 255f
            val g = ((px[i] shr 8) and 255) / 255f
            val b = (px[i] and 255) / 255f
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            v[i * 3] = y
            v[i * 3 + 1] = r - g
            v[i * 3 + 2] = b - g
        }
        normalise(v)
        return v
    }

    private fun normalise(v: FloatArray) {
        var mean = 0f
        for (x in v) mean += x
        mean /= v.size
        var norm = 0f
        for (i in v.indices) {
            v[i] -= mean
            norm += v[i] * v[i]
        }
        val len = kotlin.math.sqrt(norm.toDouble()).toFloat()
        if (len > 1e-6f) for (i in v.indices) v[i] /= len
    }

    private fun score(a: FloatArray, b: FloatArray): Double {
        var dot = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        // cosine is [-1,1]; map it to [0,1] for an easier confidence value.
        return ((dot / n + 1f) / 2f).toDouble()
    }

    fun match(crop: Bitmap): Match? {
        if (templates.isEmpty() || crop.width < 2 || crop.height < 2) return null
        val query = buildVectors(crop)
        if (query.isEmpty()) return null

        var best = -1.0
        var second = -1.0
        var bestId: String? = null

        for (t in templates) {
            var heroBest = -1.0
            // Compare corresponding crop variants and also allow the strongest
            // variant to win. This handles both ban and pick slot framing.
            for (q in query) {
                for (tv in t.vectors) {
                    val s = score(q, tv)
                    if (s > heroBest) heroBest = s
                }
            }
            if (heroBest > best) {
                second = best
                best = heroBest
                bestId = t.id
            } else if (heroBest > second) {
                second = heroBest
            }
        }

        val id = bestId ?: return null
        return Match(id, best.coerceIn(0.0, 1.0), (best - second).coerceAtLeast(0.0))
    }
}

fun Bitmap.cropSafe(rect: Rect): Bitmap? {
    val l = rect.left.coerceIn(0, width - 1)
    val t = rect.top.coerceIn(0, height - 1)
    val r = rect.right.coerceIn(l + 1, width)
    val b = rect.bottom.coerceIn(t + 1, height)
    return runCatching { Bitmap.createBitmap(this, l, t, r - l, b - t) }.getOrNull()
}
