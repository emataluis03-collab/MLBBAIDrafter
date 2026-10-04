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
 * Lightweight local template matcher (zero-mean normalised correlation on a 16x16 grayscale centre crop).
 * It only compares captured pixels with hero templates stored in filesDir/hero_templates.
 * It contains no game-control code of any kind.
 */
class HeroTemplateMatcher(private val templateDir: File, private val heroes: Map<String, Hero>) {
    class Match(val id: String, val score: Double, val margin: Double)
    private class Template(val id: String, val v: FloatArray)

    private val templates = ArrayList<Template>()

    companion object { private const val N = 12 }

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
            templates.add(Template(id, vectorOf(bmp)))
            bmp.recycle()
        }
    }

    /** Centre square -> 12x12 RGB -> zero mean, unit length. Does not recycle [src]. */
    private fun vectorOf(src: Bitmap): FloatArray {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(sq, N, N, true)
        val px = IntArray(N * N)
        small.getPixels(px, 0, N, 0, 0, N, N)
        if (small !== sq) small.recycle()
        if (sq !== src) sq.recycle()

        val g = FloatArray(N * N * 3)
        for (i in px.indices) {
            val c = px[i]
            g[i * 3] = ((c shr 16) and 255) / 255f
            g[i * 3 + 1] = ((c shr 8) and 255) / 255f
            g[i * 3 + 2] = (c and 255) / 255f
        }
        var mean = 0f
        for (x in g) mean += x
        mean /= g.size
        var norm = 0f
        for (i in g.indices) {
            g[i] -= mean
            norm += g[i] * g[i]
        }
        val len = sqrt(norm.toDouble()).toFloat()
        if (len > 1e-6f) for (i in g.indices) g[i] /= len
        return g
    }

    fun match(crop: Bitmap): Match? {
        if (templates.isEmpty()) return null
        val v = vectorOf(crop)
        var best = -2.0
        var second = -2.0
        var bestId: String? = null
        for (t in templates) {
            var dot = 0f
            for (i in v.indices) dot += v[i] * t.v[i]
            val s = dot.toDouble()
            if (s > best) {
                second = best
                best = s
                bestId = t.id
            } else if (s > second) {
                second = s
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
