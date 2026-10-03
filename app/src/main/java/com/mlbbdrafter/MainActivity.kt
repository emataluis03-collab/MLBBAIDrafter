package com.mlbbdrafter

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var info: TextView
    private val fields = linkedMapOf<String, EditText>()

    private val neon = Color.parseColor("#4DFF88")
    private val red = Color.parseColor("#FF5A6E")
    private val amber = Color.parseColor("#FFC857")
    private val bg = Color.parseColor("#0A0F14")
    private val cardBg = Color.parseColor("#131B22")
    private val white = Color.parseColor("#EAF4EE")
    private val muted = Color.parseColor("#7E9488")

    private val importer = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            Store.save(this, contentResolver.openInputStream(uri)!!.bufferedReader().readText())
            toast("Imported")
        } catch (e: Exception) { toast("Import failed: ${e.message}") }
        status()
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(Store.raw(this)) }
        toast("Exported")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun rounded(fill: Int, stroke: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(fill); setStroke(dp(1), stroke); cornerRadius = dp(r).toFloat()
    }
    private fun tint(c: Int) = Color.parseColor("#33" + String.format("%06X", c and 0xFFFFFF))

    private fun b(t: String, accent: Int = neon, f: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(accent)
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        background = rounded(tint(accent), accent, 12)
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) }
        setOnClickListener { f() }
    }

    private fun card(title: String, build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(10))
        background = rounded(cardBg, Color.parseColor("#33FFFFFF"), 16)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
        addView(TextView(this@MainActivity).apply {
            text = title; textSize = 12f; setTextColor(neon); letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        build()
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(36), dp(16), dp(24)) }

        col.addView(TextView(this).apply {
            text = "MLBB AI Lineup Drafter"; textSize = 24f; setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Draft overlay with lane-by-lane hero suggestions"; textSize = 13f; setTextColor(muted)
            setPadding(0, dp(2), 0, dp(14))
        })

        col.addView(card("SETUP") {
            addView(b("1. Grant 'Display over other apps'") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
            addView(b("2. Import dataset (JSON)", amber) { importer.launch(arrayOf("*/*")) })
            addView(b("3. Start overlay") {
                if (Settings.canDrawOverlays(this@MainActivity)) startService(Intent(this@MainActivity, OverlayService::class.java))
                else toast("Grant overlay permission first")
            })
            addView(b("Stop overlay", red) { stopService(Intent(this@MainActivity, OverlayService::class.java)) })
        })

        col.addView(card("DATASET") {
            info = TextView(this@MainActivity).apply { setTextColor(white); textSize = 12f }
            addView(info)
            addView(TextView(this@MainActivity).apply { setPadding(0, 0, 0, dp(6)) })
            addView(b("Export dataset", amber) { exporter.launch("mlbb_dataset.json") })
            addView(b("Reset to SAMPLE data", red) { Store.reset(this@MainActivity); status() })
        })

        col.addView(card("SCORE WEIGHTS  (any scale, auto-normalised)") {
            fun wRow(prefix: String, m: Map<String, Double>) = m.forEach { (k, v) ->
                val e = EditText(this@MainActivity).apply {
                    setText(v.toString()); inputType = 8194; hint = "$prefix $k"
                    setTextColor(white); setHintTextColor(muted)
                    background = rounded(Color.parseColor("#80182028"), Color.parseColor("#33FFFFFF"), 10)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                }
                fields["${prefix}_$k"] = e
                addView(TextView(this@MainActivity).apply {
                    text = "${prefix.uppercase()}  •  $k"; setTextColor(muted); textSize = 11f; setPadding(0, dp(6), 0, dp(2))
                })
                addView(e)
            }
            wRow("pick", SettingsManager.pick(this@MainActivity)); wRow("ban", SettingsManager.ban(this@MainActivity))
            addView(TextView(this@MainActivity).apply { setPadding(0, 0, 0, dp(8)) })
            addView(b("Save weights") {
                fields.forEach { (k, e) -> SettingsManager.set(this@MainActivity, k, e.text.toString().toFloatOrNull() ?: 0f) }
                toast("Saved. Reopen the overlay to apply.")
            })
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(col) })
        status()
    }

    private fun status() {
        try {
            val ds = Store.load(this)
            info.text = "${ds.label}\n${ds.heroes.size} heroes" +
                if (ds.warnings.isEmpty()) "" else "\n\nWarnings (${ds.warnings.size}):\n" + ds.warnings.take(8).joinToString("\n") { "• $it" }
        } catch (e: Exception) { info.text = "Dataset error: ${e.message}" }
    }
}
