package com.nesprosi.app.main

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.nesprosi.app.Geo
import com.nesprosi.app.Place
import com.nesprosi.app.Prefs
import com.nesprosi.app.R
import com.nesprosi.app.TripService
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Нижняя панель: переключает три шага и держит панель раскрытой. */
class Sheet(activity: AppCompatActivity) {
    private val behavior = BottomSheetBehavior.from(activity.findViewById<View>(R.id.sheet)).apply {
        isFitToContents = true
        state = BottomSheetBehavior.STATE_EXPANDED
    }
    private val sections = listOf<View>(
        activity.findViewById(R.id.idleSection),
        activity.findViewById(R.id.destSection),
        activity.findViewById(R.id.tripSection),
    )

    fun setMaxHeight(px: Int) { behavior.maxHeight = px }

    fun show(section: View) {
        sections.forEach { it.visibility = if (it == section) View.VISIBLE else View.GONE }
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun isShowing(section: View) = section.visibility == View.VISIBLE
}

// ---------- Шаг 1: куда едем ----------

class IdlePanel(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val onPlace: (Place) -> Unit,
    private val onSavedLongPress: (index: Int, place: Place) -> Unit,
) {
    val view: View = activity.findViewById(R.id.idleSection)
    private val placesChips: ChipGroup = activity.findViewById(R.id.placesChips)
    private val recentChips: ChipGroup = activity.findViewById(R.id.recentChips)
    private val placesTitle: View = activity.findViewById(R.id.placesTitle)
    private val recentTitle: View = activity.findViewById(R.id.recentTitle)

    fun render() {
        placesChips.removeAllViews()
        val saved = prefs.places
        saved.forEachIndexed { i, p -> chip(placesChips, "⭐ ${p.name}", { onPlace(p) }, { onSavedLongPress(i, p) }) }
        placesTitle.visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE

        recentChips.removeAllViews()
        val recent = prefs.recentPlaces(6)
        recent.forEach { p -> chip(recentChips, "🕘 ${p.name}", { onPlace(p) }, null) }
        recentTitle.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun chip(group: ChipGroup, text: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
        val chip = activity.layoutInflater.inflate(R.layout.chip_place, group, false) as Chip
        chip.text = text
        chip.setOnClickListener { onClick() }
        onLongClick?.let { cb -> chip.setOnLongClickListener { cb(); true } }
        group.addView(chip)
    }
}

// ---------- Шаг 2: когда разбудить ----------

class DestinationPanel(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val onWakeChanged: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onStart: (simulate: Boolean) -> Unit,
) {
    companion object {
        private val TIME_OPTIONS = listOf(1, 2, 3, 5, 10)
        private val BACKUP_OPTIONS = listOf(0, 15, 30, 45, 60, 90)
    }

    val view: View = activity.findViewById(R.id.destSection)
    private val title: TextView = activity.findViewById(R.id.destTitle)
    private val subtitle: TextView = activity.findViewById(R.id.destSubtitle)
    private val saveBtn: Button = activity.findViewById(R.id.saveBtn)
    private val modeToggle: MaterialButtonToggleGroup = activity.findViewById(R.id.modeToggle)
    private val valueChips: ChipGroup = activity.findViewById(R.id.valueChips)
    private val backupChips: ChipGroup = activity.findViewById(R.id.backupChips)

    init {
        saveBtn.setOnClickListener { onSave() }
        activity.findViewById<View>(R.id.clearDestBtn).setOnClickListener { onClear() }
        activity.findViewById<View>(R.id.startBtn).setOnClickListener { onStart(false) }
        activity.findViewById<View>(R.id.simBtn).setOnClickListener { onStart(true) }
        modeToggle.check(if (prefs.mode == Prefs.MODE_DIST) R.id.modeDist else R.id.modeTime)
        modeToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.mode = if (id == R.id.modeDist) Prefs.MODE_DIST else Prefs.MODE_TIME
            renderValueChips()
            onWakeChanged()
        }
        renderValueChips()
        renderBackupChips()
    }

    /** Радиус круга на карте: при будильнике «по времени» показываем маленький круг. */
    fun wakeRadiusMeters(): Double = if (prefs.mode == Prefs.MODE_DIST) prefs.distMeters.toDouble() else 150.0

    fun show(place: Place, distanceFromMe: Double?, saved: Boolean) {
        title.text = place.name
        subtitle.text = distanceFromMe?.let { activity.getString(R.string.from_you, Geo.formatDistance(activity, it)) }
            ?: activity.getString(R.string.where_hint_short)
        saveBtn.text = if (saved) "★" else "☆"
        // единицы могли поменяться в настройках
        renderValueChips()
    }

    fun setTitle(text: String) { title.text = text }

    private fun renderValueChips() {
        valueChips.removeAllViews()
        val dist = prefs.mode == Prefs.MODE_DIST
        val current = if (dist) prefs.distMeters else prefs.minutes
        val options = if (dist) Geo.distanceOptions(activity) else TIME_OPTIONS
        (options + current).distinct().sorted().forEach { v ->
            val label = if (dist) Geo.formatDistance(activity, v.toDouble()) else activity.getString(R.string.minutes_value, v)
            choiceChip(valueChips, label, v == current) {
                if (dist) prefs.distMeters = v else prefs.minutes = v
                onWakeChanged()
            }
        }
    }

    private fun renderBackupChips() {
        backupChips.removeAllViews()
        val current = prefs.backupMinutes
        (BACKUP_OPTIONS + current).distinct().sorted().forEach { v ->
            val label = if (v == 0) activity.getString(R.string.backup_off) else activity.getString(R.string.minutes_value, v)
            choiceChip(backupChips, label, v == current) { prefs.backupMinutes = v }
        }
    }

    private fun choiceChip(group: ChipGroup, text: String, checked: Boolean, onSelect: () -> Unit) {
        val chip = activity.layoutInflater.inflate(R.layout.chip_choice, group, false) as Chip
        chip.id = View.generateViewId()
        chip.text = text
        group.addView(chip)
        chip.isChecked = checked
        chip.setOnClickListener { onSelect() }
    }
}

// ---------- Шаг 3: поездка ----------

class TripPanel(
    private val activity: AppCompatActivity,
    onShare: () -> Unit,
    onStop: () -> Unit,
) {
    val view: View = activity.findViewById(R.id.tripSection)
    private val dest: TextView = activity.findViewById(R.id.tripDest)
    private val phase: TextView = activity.findViewById(R.id.phaseText)
    private val distance: TextView = activity.findViewById(R.id.distText)
    private val eta: TextView = activity.findViewById(R.id.etaText)
    private val progress: LinearProgressIndicator = activity.findViewById(R.id.tripProgress)
    private val details: TextView = activity.findViewById(R.id.tripDetails)
    private val status: TextView = activity.findViewById(R.id.tripStatus)

    init {
        activity.findViewById<View>(R.id.shareBtn).setOnClickListener { onShare() }
        activity.findViewById<View>(R.id.stopBtn).setOnClickListener { onStop() }
    }

    fun render(s: TripService.State) {
        dest.text = s.dest?.name.orEmpty()
        distance.text = s.distance?.let { Geo.formatDistance(activity, it) } ?: "—"
        eta.text = s.etaSec?.let { activity.getString(R.string.eta_short, Geo.formatEta(activity, it)) }.orEmpty()

        val p = s.progress
        val indeterminate = p == null
        if (progress.isIndeterminate != indeterminate) {
            // переключать режим можно только у скрытого индикатора
            progress.visibility = View.INVISIBLE
            progress.isIndeterminate = indeterminate
            progress.visibility = View.VISIBLE
        }
        if (p != null) progress.setProgressCompat((p * 1000).roundToInt(), true)

        details.text = listOfNotNull(
            s.speed?.let { Geo.formatSpeed(activity, it) },
            s.accuracy?.let { activity.getString(R.string.gps_accuracy, Geo.formatDistance(activity, it.toDouble())) },
        ).joinToString("  ·  ")

        phase.setText(
            when {
                s.gpsLost -> R.string.phase_gps_lost
                s.phase == TripService.Phase.WAITING_GPS -> R.string.phase_waiting
                s.phase == TripService.Phase.RIDING -> R.string.phase_riding
                s.phase == TripService.Phase.WARNED -> R.string.phase_warned
                s.phase == TripService.Phase.CHECK -> R.string.phase_check
                else -> R.string.phase_alarm
            }
        )
        val badge = when {
            s.gpsLost || s.phase == TripService.Phase.WARNED -> R.color.warn
            s.phase == TripService.Phase.ALARM -> R.color.danger
            else -> R.color.ok
        }
        phase.background.mutate().setTint(ContextCompat.getColor(activity, badge))

        val lines = listOfNotNull(
            s.backupAt?.let { activity.getString(R.string.backup_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) },
            if (s.simulated) activity.getString(R.string.simulation_note) else null,
        )
        status.text = lines.joinToString("\n")
        status.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
    }
}
