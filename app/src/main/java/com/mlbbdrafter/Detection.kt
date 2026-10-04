package com.mlbbdrafter

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Read-only draft-slot geometry based on the supplied 1536x1067 MLBB draft screenshot. */
object DraftSlotLayout {
    data class Slot(val kind: Kind, val index: Int, val rect: RectF)
    enum class Kind { ALLY_BAN, ENEMY_BAN, ALLY_PICK, ENEMY_PICK }

    // Normalized coordinates (0..1). These are intentionally approximate and can be
    // calibrated later for another aspect ratio/device layout.
    private val refs = listOf(
        Kind.ALLY_BAN to listOf(0.035f, 0.085f, 0.205f, 0.145f),
        Kind.ENEMY_BAN to listOf(0.705f, 0.085f, 0.965f, 0.145f),
        Kind.ALLY_PICK to listOf(0.035f, 0.17f, 0.19f, 0.88f),
        Kind.ENEMY_PICK to listOf(0.81f, 0.17f, 0.965f, 0.88f)
    )

    fun slots(width: Int, height: Int): List<Slot> {
        val out = mutableListOf<Slot>()
        for ((kind, bounds) in refs) {
            val left = bounds[0] * width
            val top = bounds[1] * height
            val right = bounds[2] * width
            val bottom = bounds[3] * height
            if (kind == Kind.ALLY_BAN || kind == Kind.ENEMY_BAN) {
                for (i in 0 until 5) {
                    val step = (right - left) / 5f
                    out += Slot(kind, i, RectF(left + i * step, top, left + (i + 1) * step, bottom))
                }
            } else {
                for (i in 0 until 5) {
                    val step = (bottom - top) / 5f
                    out += Slot(kind, i, RectF(left, top + i * step, right, top + (i + 1) * step))
                }
            }
        }
        return out
    }
}

data class DetectionResult(
    val slot: DraftSlotLayout.Slot,
    val heroId: String?,
    val confidence: Double,
    val state: String
)

/**
 * Lightweight local template matcher. It deliberately has no game-control code:
 * it only compares captured pixels against hero templates stored in filesDir/hero_templates.
 */
class HeroTemplateMatcher(private val templateDir: File, private val heroes: Map<String, Hero>) {
    private data class Template(val id: String, val pixels: IntArray, val mean: Double)
    private val templates = mutableListOf<Template>()

    fun reload() {
        templates.clear()
        if (!templateDir.exists()) return
        templateDir.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("png", "jpg", "jpeg") }
            ?.forEach { file ->
                val id = file.nameWithoutExtension
                if (heroes.containsKey(id)) {
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                        val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
                        val arr = IntArray(16 * 16)
                        small.getPixels(arr, 0, 16, 0, 0, 16, 16)
                        val gray = arr.map { c ->
                            0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)
                        }.map { it / 255.0 }
                        templates += Template(id, gray.map { (it * 255).toInt() }.toIntArray(), gray.average())
                        small.recycle()
                        bmp.recycle()
                    }
                }
            }
    }

    fun size(): Int = templates.size

    fun match(crop: Bitmap): Pair<String, Double>? {
        if (templates.isEmpty()) return null
        val small = Bitmap.createScaledBitmap(crop, 16, 16, true)
        val arr = IntArray(256)
        small.getPixels(arr, 0, 16, 0, 0, 16, 16)
        val g = DoubleArray(256)
        for (i in arr.indices) {
            val c = arr[i]
            g[i] = (0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)) / 255.0
        }
        val gm = g.average()
        var bestId: String? = null
        var best = Double.NEGATIVE_INFINITY
        for (t in templates) {
            var mse = 0.0
            var cov = 0.0
            for (i in g.indices) {
                val d = g[i] - t.pixels[i] / 255.0
                mse += d * d
                cov += (g[i] - gm) * (t.pixels[i] / 255.0 - t.mean)
            }
            mse /= g.size
            val rmse = sqrt(mse)
            val score = 1.0 - rmse
            if (score > best) {
                best = score
                bestId = t.id
            }
        }
        small.recycle()
        return bestId?.let { it to best.coerceIn(0.0, 1.0) }
    }
}

fun Bitmap.cropSafe(rect: Rect): Bitmap? {
    val l = rect.left.coerceIn(0, width - 1)
    val t = rect.top.coerceIn(0, height - 1)
    val r = rect.right.coerceIn(l + 1, width)
    val b = rect.bottom.coerceIn(t + 1, height)
    return runCatching { Bitmap.createBitmap(this, l, t, r - l, b - t) }.getOrNull()
}
