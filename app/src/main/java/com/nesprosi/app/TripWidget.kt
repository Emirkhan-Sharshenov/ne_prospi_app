package com.nesprosi.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** Виджет на главном экране: поездка к сохранённому месту одним нажатием, а в пути — сколько осталось. */
class TripWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, manager, ids)
    }

    companion object {
        private val placeButtons = intArrayOf(R.id.widgetPlace1, R.id.widgetPlace2, R.id.widgetPlace3, R.id.widgetPlace4)

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TripWidget::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val ctx = Lang.wrap(context)
            val s = TripService.state
            val views = if (s.active && s.dest != null) active(context, ctx, s) else idle(context, ctx)
            val open = PendingIntent.getActivity(
                context, 100, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            manager.updateAppWidget(ids, views)
        }

        private fun active(context: Context, ctx: Context, s: TripService.State): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_active)
            views.setTextViewText(R.id.widgetDest, ctx.getString(R.string.widget_approaching, s.dest?.name.orEmpty()))
            val (num, unit) = s.distance?.let { Geo.splitValueUnit(Geo.formatDistance(ctx, it)) }
                ?: ("…" to ctx.getString(R.string.phase_waiting))
            views.setTextViewText(R.id.widgetDistance, num)
            views.setTextViewText(R.id.widgetUnit, if (s.distance != null) ctx.getString(R.string.unit_remaining, unit) else unit)
            views.setTextViewText(R.id.widgetEta, s.etaSec?.let { Geo.formatEtaMinutes(ctx, it) } ?: "…")
            views.setViewVisibility(R.id.widgetEta, if (s.etaSec != null) View.VISIBLE else View.GONE)
            views.setProgressBar(R.id.widgetProgress, 100, ((s.progress ?: 0f) * 100).roundToInt(), false)
            views.setTextViewText(
                R.id.widgetZone,
                s.wakeDistance?.let { ctx.getString(R.string.wake_zone, Geo.formatDistance(ctx, it)) }.orEmpty(),
            )
            views.setTextViewText(R.id.widgetStop, ctx.getString(R.string.notif_finish))
            views.setOnClickPendingIntent(R.id.widgetStop, TripService.stopPendingIntent(context))
            return views
        }

        private fun idle(context: Context, ctx: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_idle)
            val places = Prefs(context).places.take(placeButtons.size)
            views.setViewVisibility(R.id.widgetEmpty, if (places.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widgetRow1, if (places.isEmpty()) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widgetRow2, if (places.size > 2) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widgetTitle, ctx.getString(R.string.widget_quick_title))
            views.setTextViewText(R.id.widgetSubtitle, ctx.getString(R.string.widget_quick_sub))
            views.setTextViewText(R.id.widgetEmpty, ctx.getString(R.string.widget_no_places))
            placeButtons.forEachIndexed { i, id ->
                val place = places.getOrNull(i)
                if (place == null) {
                    views.setViewVisibility(id, if (i % 2 == 1 && places.size > i - 1) View.INVISIBLE else View.GONE)
                } else {
                    views.setViewVisibility(id, View.VISIBLE)
                    views.setTextViewText(id, "★  " + place.name)
                    views.setOnClickPendingIntent(id, MainActivity.quickTripIntent(context, place, 200 + i))
                }
            }
            return views
        }
    }
}
