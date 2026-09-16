package com.nesprosi.app.main

import android.content.Intent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.nesprosi.app.Geo
import com.nesprosi.app.MainActivity
import com.nesprosi.app.Place
import com.nesprosi.app.Prefs
import com.nesprosi.app.R
import com.nesprosi.app.TripRecord
import com.nesprosi.app.TripService
import com.nesprosi.app.TripWidget
import java.text.DateFormat
import java.util.Date

/** Сохранённые места: сохранить, удалить, ярлык на главный экран; история поездок. */
class PlaceActions(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    /** Места изменились; если сохранили — передаётся место с новым названием. */
    private val onChanged: (renamed: Place?) -> Unit,
) {
    fun savedIndex(p: Place) = prefs.places.indexOfFirst { it.lat == p.lat && it.lon == p.lon }

    fun toggleSave(place: Place) {
        val index = savedIndex(place)
        if (index >= 0) showActions(index, prefs.places[index]) else save(place)
    }

    private fun save(place: Place) {
        val input = EditText(activity).apply { setText(place.name); setSelectAllOnFocus(true) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.place_name_title)
            .setMessage(R.string.place_name_hint)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val renamed = place.copy(name = input.text.toString().trim().ifEmpty { place.name })
                prefs.places = prefs.places + renamed
                changed(renamed)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun showActions(index: Int, p: Place) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.place_actions_title, p.name))
            .setItems(arrayOf(activity.getString(R.string.place_pin), activity.getString(R.string.delete))) { _, which ->
                if (which == 0) pinShortcut(p)
                else AlertDialog.Builder(activity)
                    .setMessage(activity.getString(R.string.place_delete_confirm, p.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        prefs.places = prefs.places.toMutableList().also { it.removeAt(index) }
                        changed(null)
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun changed(renamed: Place?) {
        updateShortcuts()
        TripWidget.updateAll(activity)
        onChanged(renamed)
    }

    private fun shortcutFor(p: Place): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(activity, "place_" + "${p.name}|${p.lat}|${p.lon}".hashCode())
            .setShortLabel(p.name.take(25))
            .setLongLabel(activity.getString(R.string.shortcut_long_label, p.name).take(45))
            .setIcon(IconCompat.createWithResource(activity, R.mipmap.ic_launcher))
            .setIntent(MainActivity.quickTripIntent(activity, p))
            .build()

    /** Долгое нажатие на значок приложения показывает сохранённые места. */
    fun updateShortcuts() {
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(activity, prefs.places.take(4).map { shortcutFor(it) }) }
    }

    private fun pinShortcut(p: Place) {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            ShortcutManagerCompat.requestPinShortcut(activity, shortcutFor(p), null)
        } else {
            Toast.makeText(activity, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /** Поделиться поездкой через мессенджер. */
    fun shareTrip() {
        val s = TripService.state
        val d = s.dest ?: return
        val text = if (s.lat != null && s.lon != null) activity.getString(R.string.share_text, d.name, Geo.mapLink(s.lat, s.lon))
        else activity.getString(R.string.share_text_no_loc, d.name, Geo.mapLink(d.lat, d.lon))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_chooser)))
    }

    /** История поездок; нажатие на поездку — поехать туда снова. */
    fun showHistory(onPick: (Place) -> Unit) {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val history = prefs.history
        val items = history.map { r ->
            val result = activity.getString(
                when (r.result) {
                    TripRecord.ARRIVED -> R.string.result_arrived
                    TripRecord.BACKUP -> R.string.result_backup
                    else -> R.string.result_stopped
                }
            )
            val minutes = ((r.endedAt - r.startedAt) / 60_000).toInt()
            val line = activity.getString(R.string.history_item, df.format(Date(r.startedAt)), r.dest, result, minutes)
            if (r.simulated) activity.getString(R.string.history_simulated, line) else line
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.history_title)
        if (items.isEmpty()) {
            dialog.setMessage(R.string.history_empty)
        } else {
            dialog.setItems(items.toTypedArray()) { _, which ->
                val r = history[which]
                if (r.lat != null && r.lon != null && !TripService.state.active) onPick(Place(r.dest, r.lat, r.lon))
            }
            dialog.setNeutralButton(R.string.history_clear) { _, _ ->
                prefs.history = emptyList()
                onChanged(null)
            }
        }
        dialog.setPositiveButton(R.string.ok, null).show()
    }
}
