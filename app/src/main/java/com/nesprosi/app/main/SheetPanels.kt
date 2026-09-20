package com.nesprosi.app.main

import android.content.Intent
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.nesprosi.app.Geo
import com.nesprosi.app.Place
import com.nesprosi.app.Prefs
import com.nesprosi.app.R
import com.nesprosi.app.SettingsActivity
import com.nesprosi.app.TripService
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

/** Нижняя панель: переключает три шага и держит панель раскрытой. */
class Sheet(private val activity: AppCompatActivity) {
    private val behavior = BottomSheetBehavior.from(activity.findViewById<View>(R.id.sheet)).apply {
        isFitToContents = true
        state = BottomSheetBehavior.STATE_EXPANDED
    }
    private val scroll: MaxHeightScrollView = activity.findViewById(R.id.sheetScroll)
    private val sections = listOf<View>(
        activity.findViewById(R.id.idleSection),
        activity.findViewById(R.id.destSection),
        activity.findViewById(R.id.tripSection),
    )

    /** Панель не выше [px]; всё, что не помещается, прокручивается внутри. */
    fun setMaxHeight(px: Int) {
        behavior.maxHeight = px
        scroll.maxHeightPx = px - (72 * activity.resources.displayMetrics.density).toInt()
    }

    fun show(section: View) {
        sections.forEach { it.visibility = if (it == section) View.VISIBLE else View.GONE }
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    fun isShowing(section: View) = section.visibility == View.VISIBLE
}

/** Звуковой режим будильника — для подписи «Громко» / «Вибрация» / «Наушники». */
private fun soundMode(prefs: Prefs): Pair<Int, Int> = when {
    prefs.vibrationOnly -> R.string.mode_vibration to R.drawable.ic_vibration
    prefs.headphonesOnly -> R.string.mode_headphones to R.drawable.ic_headphones
    else -> R.string.mode_loud to R.drawable.ic_volume
}

// ---------- Шаг 1: куда едем ----------

class IdlePanel(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val onPlace: (Place) -> Unit,
    private val onQuickStart: (Place) -> Unit,
    private val onSavedLongPress: (index: Int, place: Place) -> Unit,
) {
    val view: View = activity.findViewById(R.id.idleSection)
    private val placesScroll: View = activity.findViewById(R.id.placesScroll)
    private val placesChips: ChipGroup = activity.findViewById(R.id.placesChips)
    private val recentTitle: View = activity.findViewById(R.id.recentTitle)
    private val recentList: LinearLayout = activity.findViewById(R.id.recentList)
    private val hint: View = activity.findViewById(R.id.idleHint)
    private val tileColors = listOf(R.color.accent, R.color.secondary, R.color.warn)

    fun render() {
        placesChips.removeAllViews()
        val saved = prefs.places
        saved.forEachIndexed { i, p ->
            val chip = activity.layoutInflater.inflate(R.layout.chip_place, placesChips, false) as Chip
            chip.text = p.name
            chip.setChipIconResource(iconFor(p.name))
            chip.setOnClickListener { onPlace(p) }
            chip.setOnLongClickListener { onSavedLongPress(i, p); true }
            placesChips.addView(chip)
        }
        placesScroll.visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE

        recentList.removeAllViews()
        val recent = prefs.recentTrips(3)
        recent.forEachIndexed { i, (place, time) ->
            val row = activity.layoutInflater.inflate(R.layout.item_recent, recentList, false)
            row.findViewById<TextView>(R.id.recentName).text = place.name
            val whenText = DateUtils.getRelativeDateTimeString(
                activity, time, DateUtils.DAY_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0,
            )
            row.findViewById<TextView>(R.id.recentSub).text = activity.getString(R.string.recent_last, whenText)
            row.findViewById<View>(R.id.recentTile).background.mutate()
                .setTint(ContextCompat.getColor(activity, tileColors[i % tileColors.size]))
            row.setOnClickListener { onPlace(place) }
            row.findViewById<Button>(R.id.recentGo).apply {
                contentDescription = activity.getString(R.string.start_trip)
                setOnClickListener { onQuickStart(place) }
            }
            recentList.addView(row)
        }
        recentTitle.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        hint.visibility = if (saved.isEmpty() && recent.isEmpty()) View.VISIBLE else View.GONE
    }

    /** «Дом» — домик, «Работа» — портфель, остальное — звёздочка. */
    private fun iconFor(name: String): Int {
        val n = name.lowercase()
        return when {
            listOf("home", "дом", "үй").any { n.startsWith(it) } -> R.drawable.ic_home
            listOf("work", "работ", "жумуш", "офис", "office").any { n.startsWith(it) } -> R.drawable.ic_work
            else -> R.drawable.ic_star
        }
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
        private val TIME_OPTIONS = listOf(2, 3, 5, 10)
        private val BACKUP_OPTIONS = listOf(0, 15, 30, 60)
    }

    val view: View = activity.findViewById(R.id.destSection)
    private val title: TextView = activity.findViewById(R.id.destTitle)
    private val subtitle: TextView = activity.findViewById(R.id.destSubtitle)
    private val saveBtn: MaterialButton = activity.findViewById(R.id.saveBtn)
    private val modeToggle: MaterialButtonToggleGroup = activity.findViewById(R.id.modeToggle)
    private val wakeLabel: TextView = activity.findViewById(R.id.wakeLabel)
    private val soundLink: TextView = activity.findViewById(R.id.soundModeLink)
    private val wakeOptions: LinearLayout = activity.findViewById(R.id.wakeOptions)
    private val backupOptions: LinearLayout = activity.findViewById(R.id.backupOptions)

    init {
        saveBtn.setOnClickListener { onSave() }
        activity.findViewById<View>(R.id.clearDestBtn).setOnClickListener { onClear() }
        activity.findViewById<View>(R.id.startBtn).setOnClickListener { onStart(false) }
        activity.findViewById<View>(R.id.simBtn).setOnClickListener { onStart(true) }
        activity.findViewById<View>(R.id.backupInfo).setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.backup_info_title)
                .setMessage(R.string.backup_info_text)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
        soundLink.setOnClickListener { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
        modeToggle.check(if (prefs.mode == Prefs.MODE_DIST) R.id.modeDist else R.id.modeTime)
        modeToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.mode = if (id == R.id.modeDist) Prefs.MODE_DIST else Prefs.MODE_TIME
            renderWakeOptions()
            onWakeChanged()
        }
    }

    /** Радиус круга на карте: при будильнике «по времени» показываем маленький круг. */
    fun wakeRadiusMeters(): Double = if (prefs.mode == Prefs.MODE_DIST) prefs.distMeters.toDouble() else 150.0

    fun show(place: Place, distanceFromMe: Double?, saved: Boolean) {
        title.text = place.name
        subtitle.text = distanceFromMe?.let { activity.getString(R.string.from_you, Geo.formatDistance(activity, it)) }
            ?: activity.getString(R.string.where_hint_short)
        saveBtn.setIconResource(if (saved) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark)
        val (modeText, modeIcon) = soundMode(prefs)
        soundLink.setText(modeText)
        soundLink.setCompoundDrawablesRelativeWithIntrinsicBounds(modeIcon, 0, 0, 0)
        // единицы или звук могли поменяться в настройках
        renderWakeOptions()
        renderBackupOptions()
    }

    fun setTitle(text: String) { title.text = text }

    private fun renderWakeOptions() {
        val dist = prefs.mode == Prefs.MODE_DIST
        wakeLabel.setText(if (dist) R.string.wake_before_stop else R.string.wake_before_arrival)
        val options = if (dist) Geo.distanceOptions(activity) else TIME_OPTIONS
        // сохранённое значение не из списка — берём ближайший вариант
        val saved = if (dist) prefs.distMeters else prefs.minutes
        val current = options.minByOrNull { abs(it - saved) } ?: options.first()
        if (current != saved) { if (dist) prefs.distMeters = current else prefs.minutes = current }
        fillOptions(wakeOptions, options, current, recommended = options[1], secondary = false,
            label = { v -> if (dist) Geo.formatDistance(activity, v.toDouble()) else activity.getString(R.string.minutes_value, v) },
        ) { v ->
            if (dist) prefs.distMeters = v else prefs.minutes = v
            onWakeChanged()
        }
    }

    private fun renderBackupOptions() {
        val current = BACKUP_OPTIONS.minByOrNull { abs(it - prefs.backupMinutes) } ?: 0
        if (current != prefs.backupMinutes) prefs.backupMinutes = current
        fillOptions(backupOptions, BACKUP_OPTIONS, current, recommended = null, secondary = true,
            label = { v -> if (v == 0) activity.getString(R.string.backup_off) else activity.getString(R.string.minutes_value, v) },
        ) { v -> prefs.backupMinutes = v }
    }

    /** Ряд из четырёх «таблеток» одинаковой ширины; выбранная подсвечена. */
    private fun fillOptions(
        row: LinearLayout,
        options: List<Int>,
        selected: Int,
        recommended: Int?,
        secondary: Boolean,
        label: (Int) -> String,
        onSelect: (Int) -> Unit,
    ) {
        row.removeAllViews()
        options.forEachIndexed { i, v ->
            val item = activity.layoutInflater.inflate(R.layout.item_option, row, false)
            if (secondary) item.setBackgroundResource(R.drawable.option_bg_secondary)
            if (i == options.lastIndex) (item.layoutParams as ViewGroup.MarginLayoutParams).marginEnd = 0
            item.findViewById<TextView>(R.id.optionValue).text = label(v)
            item.findViewById<TextView>(R.id.optionTag).apply {
                visibility = if (v == recommended) View.VISIBLE else View.GONE
                setText(R.string.best)
            }
            item.isSelected = v == selected
            item.setOnClickListener {
                for (c in 0 until row.childCount) row.getChildAt(c).isSelected = row.getChildAt(c) == item
                onSelect(v)
            }
            row.addView(item)
        }
    }
}

// ---------- Шаг 3: поездка ----------

class TripPanel(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    onShare: () -> Unit,
    onStop: () -> Unit,
) {
    val view: View = activity.findViewById(R.id.tripSection)
    private val phase: TextView = activity.findViewById(R.id.phaseText)
    private val soundPill: TextView = activity.findViewById(R.id.soundPill)
    private val distance: TextView = activity.findViewById(R.id.distText)
    private val unit: TextView = activity.findViewById(R.id.distUnit)
    private val eta: TextView = activity.findViewById(R.id.etaText)
    private val progressStart: TextView = activity.findViewById(R.id.progressStart)
    private val progressPercent: TextView = activity.findViewById(R.id.progressPercent)
    private val progressEnd: TextView = activity.findViewById(R.id.progressEnd)
    private val progress: TripProgressView = activity.findViewById(R.id.tripProgress)
    private val status: TextView = activity.findViewById(R.id.tripStatus)
    private val accuracy = Stat(activity.findViewById(R.id.statAccuracy), R.drawable.ic_gps, R.string.stat_accuracy)
    private val speed = Stat(activity.findViewById(R.id.statSpeed), R.drawable.ic_speed, R.string.stat_speed)

    private class Stat(root: View, icon: Int, label: Int) {
        val value: TextView = root.findViewById(R.id.statValue)
        init {
            root.findViewById<ImageView>(R.id.statIcon).setImageResource(icon)
            root.findViewById<TextView>(R.id.statLabel).setText(label)
        }
    }

    init {
        activity.findViewById<View>(R.id.shareBtn).setOnClickListener { onShare() }
        activity.findViewById<View>(R.id.stopBtn).setOnClickListener { onStop() }
    }

    fun render(s: TripService.State) {
        // статус: цвет «таблетки» и точки зависит от этапа
        val (textRes, bg, fg, dot) = when {
            s.gpsLost -> Quad(R.string.status_gps_lost, R.color.warn_container, R.color.on_warn_container, R.color.amber)
            s.phase == TripService.Phase.WAITING_GPS -> Quad(R.string.status_waiting, R.color.surface_mid, R.color.text, R.color.muted)
            s.phase == TripService.Phase.WARNED -> Quad(R.string.status_warned, R.color.warn_container, R.color.on_warn_container, R.color.amber)
            s.phase == TripService.Phase.ALARM -> Quad(R.string.status_alarm, R.color.danger_container, R.color.on_danger_container, R.color.danger)
            s.phase == TripService.Phase.CHECK -> Quad(R.string.status_check, R.color.secondary_container, R.color.on_secondary_container, R.color.secondary)
            else -> Quad(R.string.status_riding, R.color.accent_container, R.color.on_accent_container, R.color.ok)
        }
        phase.setText(textRes)
        phase.background.mutate().setTint(color(bg))
        phase.setTextColor(color(fg))
        phase.compoundDrawablesRelative[0]?.mutate()?.setTint(color(dot))

        val (modeText, modeIcon) = soundMode(prefs)
        soundPill.setText(modeText)
        soundPill.setCompoundDrawablesRelativeWithIntrinsicBounds(modeIcon, 0, 0, 0)
        soundPill.compoundDrawablesRelative[0]?.mutate()?.setTint(color(R.color.text))

        val (num, u) = s.distance?.let { Geo.splitValueUnit(Geo.formatDistance(activity, it)) } ?: ("—" to "")
        distance.text = num
        unit.text = u
        eta.text = s.etaSec?.let { Geo.formatEtaMinutes(activity, it) } ?: "…"

        val p = s.progress
        progress.setProgress(p ?: 0f)
        progressPercent.text = p?.let { activity.getString(R.string.progress_percent, (it * 100).roundToInt()) }.orEmpty()
        progressStart.setText(R.string.trip_start)
        progressEnd.text = s.wakeDistance?.let {
            activity.getString(R.string.wake_zone, Geo.formatDistance(activity, it))
        } ?: s.dest?.name.orEmpty()

        accuracy.value.text = s.accuracy?.let { "±" + Geo.formatDistance(activity, it.toDouble()) } ?: "—"
        speed.value.text = s.speed?.let { Geo.formatSpeed(activity, it) } ?: "—"

        val lines = listOfNotNull(
            s.backupAt?.let { activity.getString(R.string.backup_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) },
            if (s.simulated) activity.getString(R.string.simulation_note) else null,
        )
        status.text = lines.joinToString("\n")
        status.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun color(res: Int) = ContextCompat.getColor(activity, res)

    private data class Quad(val text: Int, val bg: Int, val fg: Int, val dot: Int)
}
