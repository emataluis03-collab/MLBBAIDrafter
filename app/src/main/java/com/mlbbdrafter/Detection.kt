package com.mlbbdrafter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * READ-ONLY geometry of the 20 draft slots, as fractions (0..1) of the screen.
 * These numbers are approximate (taken from a 1536x1067 reference screenshot). If detection looks at the
 * wrong place on your phone, this is the ONLY place to calibrate.
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

    /** Normalised (0..1) rectangles. Multiply by the frame / screen size to get pixels. */
    fun slots(): List<SlotRect> {
        val out = ArrayList<SlotRect>()
        for ((kind, b) in bounds) {
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

    companion object { private const val N = 16 }

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

    /** Centre square -> 16x16 grayscale -> zero mean, unit length. Does not recycle [src]. */
    private fun vectorOf(src: Bitmap): FloatArray {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(sq, N, N, true)
        val px = IntArray(N * N)
        small.getPixels(px, 0, N, 0, 0, N, N)
        if (small !== sq) small.recycle()
        if (sq !== src) sq.recycle()

        val g = FloatArray(N * N)
        for (i in px.indices) {
            val c = px[i]
            g[i] = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f
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
