package com.nesprosi.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Виджет на главном экране: поездка к сохранённому месту одним нажатием, а в пути — сколько осталось. */
class TripWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, manager, ids)
    }

    companion object {
        private val placeButtons = intArrayOf(R.id.widgetPlace1, R.id.widgetPlace2, R.id.widgetPlace3)

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TripWidget::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val ctx = Lang.wrap(context)
            val views = RemoteViews(context.packageName, R.layout.widget_trip)
            val open = PendingIntent.getActivity(
                context, 100, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widgetTitle, open)

            val s = TripService.state
            if (s.active && s.dest != null) {
                val left = s.distance?.let { Geo.formatDistance(ctx, it) } ?: ctx.getString(R.string.phase_waiting)
                views.setTextViewText(R.id.widgetStatus, ctx.getString(R.string.widget_trip, s.dest.name, left))
                views.setViewVisibility(R.id.widgetStatus, View.VISIBLE)
                views.setViewVisibility(R.id.widgetPlaces, View.GONE)
                views.setViewVisibility(R.id.widgetStop, View.VISIBLE)
                views.setTextViewText(R.id.widgetStop, ctx.getString(R.string.stop_short))
                views.setOnClickPendingIntent(R.id.widgetStop, TripService.stopPendingIntent(context))
            } else {
                val places = Prefs(context).places.take(placeButtons.size)
                views.setViewVisibility(R.id.widgetStop, View.GONE)
                views.setViewVisibility(R.id.widgetPlaces, if (places.isEmpty()) View.GONE else View.VISIBLE)
                views.setViewVisibility(R.id.widgetStatus, if (places.isEmpty()) View.VISIBLE else View.GONE)
                views.setTextViewText(R.id.widgetStatus, ctx.getString(R.string.widget_no_places))
                placeButtons.forEachIndexed { i, id ->
                    val place = places.getOrNull(i)
                    if (place == null) {
                        views.setViewVisibility(id, View.GONE)
                    } else {
                        views.setViewVisibility(id, View.VISIBLE)
                        views.setTextViewText(id, place.name)
                        views.setOnClickPendingIntent(id, MainActivity.quickTripIntent(context, place, 200 + i))
                    }
                }
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
