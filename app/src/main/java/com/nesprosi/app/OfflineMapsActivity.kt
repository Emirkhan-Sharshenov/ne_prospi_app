package com.nesprosi.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch

/** Офлайн-карты: карта своей страны одним нажатием и каталог всех стран и регионов. */
class OfflineMapsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout
    private lateinit var suggestCard: View
    private lateinit var suggestText: TextView
    private lateinit var suggestBtn: Button
    private var suggestion: OfflineMap.Entry? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (render()) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_offline_maps)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.offlineRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        prefs = Prefs(this)
        list = findViewById(R.id.mapsList)
        suggestCard = findViewById(R.id.suggestCard)
        suggestText = findViewById(R.id.suggestText)
        suggestBtn = findViewById(R.id.suggestBtn)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.browseBtn).setOnClickListener { browse(null) }
        suggestBtn.setOnClickListener {
            suggestion?.let { if (it.isDir) browse(it) else confirmDownload(it) }
        }

        val always = findViewById<MaterialSwitch>(R.id.offlineAlwaysSwitch)
        always.isChecked = prefs.offlineAlways
        always.setOnCheckedChangeListener { _, checked -> prefs.offlineAlways = checked }

        loadSuggestion()
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        super.onPause()
    }

    private fun loadSuggestion() {
        suggestCard.visibility = View.GONE
        if (!Net.isOnline(this)) return
        Thread {
            val entry = runCatching { OfflineMap.suggestForCountry(this) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || entry == null) return@runOnUiThread
                if (OfflineMap.files(this).any { it.name == entry.fileName }) return@runOnUiThread
                suggestion = entry
                if (entry.isDir) {
                    suggestText.text = getString(R.string.offline_suggest_regions, entry.title)
                    suggestBtn.setText(R.string.offline_choose_region)
                } else {
                    suggestText.text = getString(R.string.offline_suggest, entry.title, entry.size)
                    suggestBtn.setText(R.string.offline_download_btn)
                }
                suggestCard.visibility = View.VISIBLE
            }
        }.start()
    }

    /** true — идут загрузки, экран нужно обновлять дальше. */
    private fun render(): Boolean {
        list.removeAllViews()
        val (active, failed) = OfflineMap.downloads(this)
        if (failed) Toast.makeText(this, R.string.offline_failed, Toast.LENGTH_LONG).show()

        for (d in active) {
            addRow(
                getString(R.string.offline_downloading_name, OfflineMap.prettyName(d.fileName), d.percent),
                getString(R.string.cancel),
            ) { OfflineMap.cancel(this, d); render() }
        }
        val files = OfflineMap.files(this)
        for (f in files) {
            addRow(
                getString(R.string.offline_ready_name, OfflineMap.prettyName(f.name), (f.length() / 1_000_000).toInt()),
                getString(R.string.delete),
            ) {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.offline_delete_confirm, OfflineMap.prettyName(f.name)))
                    .setPositiveButton(R.string.delete) { _, _ -> OfflineMap.delete(f); render() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        if (active.isEmpty() && files.isEmpty()) {
            val empty = layoutInflater.inflate(R.layout.row, list, false) as TextView
            empty.setText(R.string.offline_none)
            list.addView(empty)
        }
        if (suggestion != null && files.any { it.name == suggestion?.fileName }) suggestCard.visibility = View.GONE
        return active.isNotEmpty()
    }

    private fun addRow(text: String, action: String, onAction: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_offline_map, list, false)
        row.findViewById<TextView>(R.id.mapName).text = text
        row.findViewById<Button>(R.id.mapAction).apply {
            this.text = action
            setOnClickListener { onAction() }
        }
        list.addView(row)
    }

    /** Каталог: части света → страны → регионы. */
    private fun browse(folder: OfflineMap.Entry?) {
        if (folder == null) {
            val continents = OfflineMap.continents()
            AlertDialog.Builder(this)
                .setTitle(R.string.offline_choose)
                .setItems(continents.map { it.title }.toTypedArray()) { _, which -> browse(continents[which]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (!Net.isOnline(this)) {
            Toast.makeText(this, R.string.offline_failed, Toast.LENGTH_LONG).show()
            return
        }
        val loading = AlertDialog.Builder(this).setMessage(R.string.searching).show()
        Thread {
            val entries = runCatching { OfflineMap.list(folder.path) }.getOrNull()
            runOnUiThread {
                loading.dismiss()
                if (isDestroyed) return@runOnUiThread
                if (entries.isNullOrEmpty()) {
                    Toast.makeText(this, R.string.offline_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val labels = entries.map { e ->
                    if (e.isDir) getString(R.string.offline_folder, e.title) else getString(R.string.offline_file, e.title, e.size)
                }
                AlertDialog.Builder(this)
                    .setTitle(folder.title)
                    .setItems(labels.toTypedArray()) { _, which ->
                        val e = entries[which]
                        if (e.isDir) browse(e) else confirmDownload(e)
                    }
                    .setNegativeButton(R.string.back) { _, _ -> browse(null) }
                    .show()
            }
        }.start()
    }

    private fun confirmDownload(entry: OfflineMap.Entry) {
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setMessage(getString(R.string.offline_confirm, entry.size))
            .setPositiveButton(R.string.offline_download_btn) { _, _ ->
                OfflineMap.startDownload(this, entry)
                suggestCard.visibility = View.GONE
                handler.removeCallbacks(poll)
                handler.post(poll)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
