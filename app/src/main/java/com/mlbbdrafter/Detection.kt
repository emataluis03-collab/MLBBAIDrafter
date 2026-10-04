package com.mlbbdrafter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/** READ-ONLY geometry of the 20 draft slots. */
object DraftSlotLayout {
    data class SlotRect(val kind: Slot, val index: Int, val rect: RectF)

    // These are the user's existing box groups. Calibration, if present, is preserved.
    private val bounds = listOf(
        Slot.ALLY_BAN to floatArrayOf(0.035f, 0.085f, 0.205f, 0.145f),
        Slot.ENEMY_BAN to floatArrayOf(0.705f, 0.085f, 0.965f, 0.145f),
        Slot.ALLY_PICK to floatArrayOf(0.035f, 0.17f, 0.19f, 0.88f),
        Slot.ENEMY_PICK to floatArrayOf(0.81f, 0.17f, 0.965f, 0.88f)
    )

    private fun prefs(c: Context) = c.getSharedPreferences("calib", Context.MODE_PRIVATE)
    fun adjust(c: Context, k: Slot): FloatArray {
        val out = floatArrayOf(0f, 0f, 1f, 1f)
        val s = prefs(c).getString("cal_" + k.name, null) ?: return out
        val p = s.split(",")
        if (p.size == 4) for (i in 0 until 4) p[i].toFloatOrNull()?.let { out[i] = it }
        return out
    }
    fun setAdjust(c: Context, k: Slot, a: FloatArray) = prefs(c).edit().putString("cal_" + k.name, a.joinToString(",")).apply()
    fun resetAdjust(c: Context, k: Slot) = prefs(c).edit().remove("cal_" + k.name).apply()

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
 * Local hero matcher. It deliberately uses several visual signatures instead of a single
 * raw RGB correlation because the in-game portrait can have different scaling, brightness,
 * borders and crop from the downloaded reference image.
 */
class HeroTemplateMatcher(private val templateDir: File, private val heroes: Map<String, Hero>) {
    class Match(val id: String, val score: Double, val margin: Double)

    private data class Template(val id: String, val gray: FloatArray, val color: FloatArray, val edge: FloatArray)
    private val templates = ArrayList<Template>()

    companion object {
        private const val N = 20
        private const val EDGE = 19
    }

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
            templates.add(makeTemplate(id, bmp))
            bmp.recycle()
        }
    }

    private fun square(src: Bitmap, inset: Float = 0f): Bitmap {
        val side = minOf(src.width, src.height)
        val cut = (side * inset).toInt()
        val size = (side - cut * 2).coerceAtLeast(2)
        return Bitmap.createBitmap(src, (src.width - side) / 2 + cut, (src.height - side) / 2 + cut, size, size)
    }

    private fun makeTemplate(id: String, src: Bitmap): Template {
        val sq = square(src, 0.05f)
        val small = Bitmap.createScaledBitmap(sq, N, N, true)
        val px = IntArray(N * N)
        small.getPixels(px, 0, N, 0, 0, N, N)
        if (small !== sq) small.recycle()
        sq.recycle()
        return features(id, px)
    }

    private fun features(id: String, px: IntArray): Template {
        val gray = FloatArray(N * N)
        val color = FloatArray(12) // 4x3 coarse RGB histogram
        for (i in px.indices) {
            val r = ((px[i] shr 16) and 255) / 255f
            val g = ((px[i] shr 8) and 255) / 255f
            val b = (px[i] and 255) / 255f
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
            val bin = (i % N) / 5
            color[bin * 3] += r
            color[bin * 3 + 1] += g
            color[bin * 3 + 2] += b
        }
        normalize(color)
        normalize(gray)
        val edge = FloatArray(EDGE * EDGE)
        for (y in 0 until EDGE) for (x in 0 until EDGE) {
            val i = y * N + x
            val gx = gray[i + 1] - gray[i]
            val gy = gray[i + N] - gray[i]
            edge[y * EDGE + x] = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
        }
        normalize(edge)
        return Template(id, gray, color, edge)
    }

    private fun normalize(a: FloatArray) {
        var mean = 0f
        for (v in a) mean += v
        mean /= a.size
        var norm = 0f
        for (i in a.indices) { a[i] -= mean; norm += a[i] * a[i] }
        val len = sqrt(norm.toDouble()).toFloat()
        if (len > 1e-6f) for (i in a.indices) a[i] /= len
    }

    private fun candidate(src: Bitmap, inset: Float): Triple<FloatArray, FloatArray, FloatArray> {
        val sq = square(src, inset)
        val small = Bitmap.createScaledBitmap(sq, N, N, true)
        val px = IntArray(N * N)
        small.getPixels(px, 0, N, 0, 0, N, N)
        if (small !== sq) small.recycle()
        sq.recycle()
        val t = features("", px)
        return Triple(t.gray, t.color, t.edge)
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s.coerceIn(-1.0, 1.0)
    }

    fun match(crop: Bitmap): Match? {
        if (templates.isEmpty()) return null
        // Two crops reduce sensitivity to portrait borders and the exact box padding.
        val c1 = candidate(crop, 0.05f)
        val c2 = candidate(crop, 0.14f)
        var best = -2.0
        var second = -2.0
        var bestId: String? = null
        for (t in templates) {
            fun score(c: Triple<FloatArray, FloatArray, FloatArray>): Double {
                val gray = ((dot(c.first, t.gray) + 1.0) / 2.0)
                val color = ((dot(c.second, t.color) + 1.0) / 2.0)
                val edge = ((dot(c.third, t.edge) + 1.0) / 2.0)
                return gray * 0.55 + color * 0.20 + edge * 0.25
            }
            val s = maxOf(score(c1), score(c2))
            if (s > best) { second = best; best = s; bestId = t.id }
            else if (s > second) second = s
        }
        val id = bestId ?: return null
        return Match(id, best.coerceIn(0.0, 1.0), (best - second).coerceAtLeast(0.0))
    }
}

fun Bitmap.cropSafe(rect: Rect): Bitmap? {
    if (width <= 1 || height <= 1) return null
    val l = rect.left.coerceIn(0, width - 1)
    val t = rect.top.coerceIn(0, height - 1)
    val r = rect.right.coerceIn(l + 1, width)
    val b = rect.bottom.coerceIn(t + 1, height)
    return runCatching { Bitmap.createBitmap(this, l, t, r - l, b - t) }.getOrNull()
}
