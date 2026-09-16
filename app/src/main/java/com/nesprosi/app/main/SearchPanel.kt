package com.nesprosi.app.main

import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nesprosi.app.Net
import com.nesprosi.app.Place
import com.nesprosi.app.R
import org.osmdroid.util.BoundingBox

/** Строка поиска сверху и список найденных адресов. */
class SearchPanel(
    private val activity: AppCompatActivity,
    private val bounds: () -> BoundingBox,
    private val onPlace: (Place) -> Unit,
) {
    private val input: EditText = activity.findViewById(R.id.searchInput)
    private val card: View = activity.findViewById(R.id.resultsCard)
    private val results: LinearLayout = activity.findViewById(R.id.results)

    val isOpen get() = card.visibility == View.VISIBLE

    init {
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
    }

    fun hide() {
        card.visibility = View.GONE
        results.removeAllViews()
        input.clearFocus()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun search() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()
        results.removeAllViews()
        card.visibility = View.VISIBLE
        addRow(activity.getString(R.string.searching))
        val box = bounds()
        Thread {
            val found = runCatching {
                Net.search(activity, q, box.latSouth, box.lonWest, box.latNorth, box.lonEast)
            }.getOrNull()
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                results.removeAllViews()
                when {
                    found == null -> addRow(activity.getString(R.string.search_unavailable))
                    found.isEmpty() -> addRow(activity.getString(R.string.nothing_found))
                    else -> found.forEach { (fullAddress, place) ->
                        addRow(fullAddress) {
                            input.setText("")
                            hide()
                            onPlace(place)
                        }
                    }
                }
            }
        }.start()
    }

    private fun addRow(text: String, onClick: (() -> Unit)? = null) {
        val row = activity.layoutInflater.inflate(R.layout.row, results, false) as TextView
        row.text = text
        onClick?.let { cb -> row.setOnClickListener { cb() } }
        results.addView(row)
    }
}
