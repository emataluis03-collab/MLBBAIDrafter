package com.mlbbdrafter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Downloads a local, cached 133-hero avatar template pack for recognition. */
object TemplatePackInstaller {
    private const val HERO_JSON = "https://raw.githubusercontent.com/Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang/master/hero.json"
    private const val IMAGE_BASE = "https://raw.githubusercontent.com/Ceplin03/database-mlbb.Mobile-Legends-Bang-Bang/master/images-hero/"

    fun count(c: Context): Int = c.filesDir.resolve("hero_templates").listFiles()?.count { it.isFile && it.extension == "png" } ?: 0

    fun downloadAsync(c: Context, onProgress: (Int, Int) -> Unit, onDone: (Boolean, String) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            var conn: HttpURLConnection? = null
            try {
                val ds = Store.load(c)
                val json = URL(HERO_JSON).openStream().bufferedReader().use { it.readText() }
                val arr = JSONArray(json)
                val map = mutableMapOf<String, String>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name_hero")
                    val file = o.optString("images-hero")
                    if (name.isNotBlank() && file.isNotBlank()) map[name.lowercase(Locale.US)] = file
                }
                val dir = c.filesDir.resolve("hero_templates").apply { mkdirs() }
                val total = ds.heroes.size
                var done = 0
                for ((id, hero) in ds.heroes) {
                    val remoteFile = map[hero.name.lowercase(Locale.US)] ?: continue
                    val out = dir.resolve("$id.png")
                    try {
                        conn = (URL(IMAGE_BASE + remoteFile).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 12_000
                            readTimeout = 20_000
                            instanceFollowRedirects = true
                        }
                        conn!!.connect()
                        if (conn!!.responseCode in 200..299) {
                            BufferedInputStream(conn!!.inputStream).use { input ->
                                val bmp = BitmapFactory.decodeStream(input)
                                if (bmp != null) {
                                    val small = Bitmap.createScaledBitmap(bmp, 96, 96, true)
                                    out.outputStream().use { os -> small.compress(Bitmap.CompressFormat.PNG, 92, os) }
                                    small.recycle(); bmp.recycle()
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Keep going; one unavailable image must not stop the whole pack.
                    } finally {
                        conn?.disconnect(); conn = null
                    }
                    done++
                    onProgress(done, total)
                }
                val n = count(c)
                onDone(n > 0, "$n hero templates cached")
            } catch (e: Exception) {
                onDone(false, "Template download failed: ${e.message ?: "network error"}")
            }
        }
    }
}
