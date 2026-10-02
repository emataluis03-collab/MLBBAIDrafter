package com.mlbbdrafter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var info: TextView
    private val fields = linkedMapOf<String, EditText>()

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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun b(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        col.addView(TextView(this).apply { text = "MLBB AI Lineup Drafter"; textSize = 22f })
        col.addView(b("1. Grant 'Display over other apps'") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(b("2. Import dataset (JSON)") { importer.launch(arrayOf("*/*")) })
        col.addView(b("Export dataset") { exporter.launch("mlbb_dataset.json") })
        col.addView(b("Reset to SAMPLE data") { Store.reset(this); status() })
        col.addView(b("3. Start overlay") {
            if (Settings.canDrawOverlays(this)) startService(Intent(this, OverlayService::class.java)) else toast("Grant overlay permission first")
        })
        col.addView(b("Stop overlay") { stopService(Intent(this, OverlayService::class.java)) })
        col.addView(TextView(this).apply { text = "\nScore weights (any scale; normalised automatically)"; textSize = 16f })
        fun wRow(prefix: String, m: Map<String, Double>) = m.forEach { (k, v) ->
            val e = EditText(this).apply { setText(v.toString()); inputType = 8194; hint = "$prefix $k" }
            fields["${prefix}_$k"] = e
            col.addView(TextView(this).apply { text = "$prefix: $k" }); col.addView(e)
        }
        wRow("pick", SettingsManager.pick(this)); wRow("ban", SettingsManager.ban(this))
        col.addView(b("Save weights") {
            fields.forEach { (k, e) -> SettingsManager.set(this, k, e.text.toString().toFloatOrNull() ?: 0f) }
            toast("Saved. Press ANALYZE in overlay.")
        })
        info = TextView(this); col.addView(info)
        setContentView(ScrollView(this).apply { addView(col) })
        status()
    }

    private fun status() {
        try {
            val ds = Store.load(this)
            info.text = "\nDataset: ${ds.label}\nHeroes: ${ds.heroes.size}\nWarnings (${ds.warnings.size}):\n" + ds.warnings.joinToString("\n") { "• $it" }
        } catch (e: Exception) { info.text = "\nDataset error: ${e.message}" }
    }
}
