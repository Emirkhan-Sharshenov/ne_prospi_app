package com.nesprosi.app

import android.content.Context
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val METERS_PER_MILE = 1609.344
    private const val FEET_PER_METER = 3.28084

    /** Расстояние между двумя точками в метрах (формула гаверсинусов). */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    /** «500 м», «1,2 км» или «0.3 mi», «800 ft» — как принято в стране пользователя. */
    fun formatDistance(ctx: Context, m: Double): String {
        if (Region.usesMiles(ctx)) {
            val miles = m / METERS_PER_MILE
            return when {
                miles < 0.1 -> ctx.getString(R.string.feet_value, ((m * FEET_PER_METER) / 10).roundToInt() * 10)
                miles < 10 && miles % 1.0 > 0.05 -> ctx.getString(R.string.miles_value, miles)
                else -> ctx.getString(R.string.miles_value_int, miles.roundToInt())
            }
        }
        return when {
            m < 1000 -> ctx.getString(R.string.meters_value, m.roundToInt())
            m < 10_000 && (m / 1000) % 1.0 > 0.05 -> ctx.getString(R.string.km_value, m / 1000)
            else -> ctx.getString(R.string.km_value_int, (m / 1000).roundToInt())
        }
    }

    fun formatSpeed(ctx: Context, metersPerSecond: Double): String =
        if (Region.usesMiles(ctx)) ctx.getString(R.string.speed_mph, (metersPerSecond * 2.236936).roundToInt())
        else ctx.getString(R.string.speed_value, (metersPerSecond * 3.6).roundToInt())

    fun formatEta(ctx: Context, sec: Double?): String {
        if (sec == null) return "—"
        val s = sec.roundToInt()
        return if (s < 60) ctx.getString(R.string.sec_value, s) else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Четыре варианта «когда будить» в метрах: круглые числа в метрах или в милях. Второй — рекомендуемый. */
    fun distanceOptions(ctx: Context): List<Int> =
        if (Region.usesMiles(ctx)) listOf(0.2, 0.3, 0.5, 1.0).map { (it * METERS_PER_MILE).roundToInt() }
        else listOf(300, 500, 1000, 2000)

    /** Время до прибытия для людей: «≈ 3 мин» или «< 1 мин». */
    fun formatEtaMinutes(ctx: Context, sec: Double): String {
        val minutes = (sec / 60).roundToInt()
        return if (minutes < 1) ctx.getString(R.string.eta_less_min) else ctx.getString(R.string.eta_min, minutes)
    }

    /** «850 м» → «850» и «м», чтобы число показать крупно. */
    fun splitValueUnit(formatted: String): Pair<String, String> {
        val i = formatted.lastIndexOf(' ')
        return if (i > 0) formatted.substring(0, i) to formatted.substring(i + 1) else formatted to ""
    }

    /** Ссылка на точку, которая откроется в любом телефоне. */
    fun mapLink(lat: Double, lon: Double) = String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", lat, lon)
}
