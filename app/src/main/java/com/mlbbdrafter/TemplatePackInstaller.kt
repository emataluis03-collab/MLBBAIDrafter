package com.mlbbdrafter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Downloads and caches one small portrait per hero (used only for local, read-only recognition). */
object TemplatePackInstaller {
    private const val HERO_JSON = "https://raw.githubusercontent.com/Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang/master/hero.json"
    private const val IMAGE_BASE = "https://raw.githubusercontent.com/Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang/master/images-hero/"

    private fun dir(c: Context) = File(c.filesDir, "hero_templates")
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun count(c: Context): Int = dir(c).listFiles()?.count { it.isFile && it.extension == "png" } ?: 0

    fun downloadAsync(c: Context, onProgress: (Int, Int) -> Unit, onDone: (Boolean, String) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            try {
                val ds = Store.load(c)
                val json = URL(HERO_JSON).openStream().bufferedReader().use { it.readText() }
                val arr = JSONArray(json)
                val remote = HashMap<String, String>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name_hero")
                    val file = o.optString("images-hero")
                    if (name.isNotBlank() && file.isNotBlank()) remote[norm(name)] = file
                }
                val out = dir(c)
                out.mkdirs()
                val total = ds.heroes.size
                var done = 0
                val missing = ArrayList<String>()
                for ((id, hero) in ds.heroes) {
                    val remoteFile = remote[norm(hero.name)] ?: remote[norm(id)]
                    if (remoteFile == null) {
                        missing.add(hero.name)
                    } else if (!downloadOne(IMAGE_BASE + remoteFile, File(out, "$id.png"))) {
                        missing.add(hero.name)
                    }
                    done++
                    onProgress(done, total)
                }
                val n = count(c)
                val note = if (missing.isEmpty()) "" else " | missing ${missing.size}: " + missing.take(6).joinToString(", ")
                onDone(n > 0, "$n hero templates cached$note")
            } catch (e: Exception) {
                onDone(false, "Template download failed: " + (e.message ?: "network error"))
            }
        }
    }

    private fun downloadOne(url: String, target: File): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val cn = URL(url).openConnection() as HttpURLConnection
            conn = cn
            cn.connectTimeout = 12000
            cn.readTimeout = 20000
            cn.instanceFollowRedirects = true
            cn.connect()
            if (cn.responseCode !in 200..299) return false
            val bmp = cn.inputStream.use { BitmapFactory.decodeStream(it) } ?: return false
            val small = Bitmap.createScaledBitmap(bmp, 96, 96, true)
            target.outputStream().use { os -> small.compress(Bitmap.CompressFormat.PNG, 92, os) }
            if (small !== bmp) small.recycle()
            bmp.recycle()
            true
        } catch (e: Exception) {
            false   // one unavailable image must not stop the whole pack
        } finally {
            conn?.disconnect()
        }
    }
}
