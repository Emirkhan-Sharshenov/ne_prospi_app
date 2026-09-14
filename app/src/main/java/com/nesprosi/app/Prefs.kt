package com.nesprosi.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Place(val name: String, val lat: Double, val lon: Double)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nesprosi", Context.MODE_PRIVATE)

    var mode: String
        get() = sp.getString("mode", MODE_DIST) ?: MODE_DIST
        set(v) = sp.edit().putString("mode", v).apply()

    /** Будить, когда до остановки осталось столько метров. */
    var distMeters: Int
        get() = sp.getInt("dist", 500)
        set(v) = sp.edit().putInt("dist", v).apply()

    /** Будить за столько минут до прибытия. */
    var minutes: Int
        get() = sp.getInt("minutes", 3)
        set(v) = sp.edit().putInt("minutes", v).apply()

    /** Где карта была в прошлый раз. По умолчанию — центр Бишкека. */
    var mapLat: Double
        get() = sp.getString("mapLat", null)?.toDoubleOrNull() ?: 42.8746
        set(v) = sp.edit().putString("mapLat", v.toString()).apply()
    var mapLon: Double
        get() = sp.getString("mapLon", null)?.toDoubleOrNull() ?: 74.5698
        set(v) = sp.edit().putString("mapLon", v.toString()).apply()
    var mapZoom: Double
        get() = sp.getString("mapZoom", null)?.toDoubleOrNull() ?: 12.0
        set(v) = sp.edit().putString("mapZoom", v.toString()).apply()

    var places: List<Place>
        get() {
            val arr = runCatching { JSONArray(sp.getString("places", "[]")) }.getOrElse { JSONArray() }
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon)) }
            sp.edit().putString("places", arr.toString()).apply()
        }

    companion object {
        const val MODE_DIST = "dist"
        const val MODE_TIME = "time"
    }
}

object Geo {
    /** Расстояние между двумя точками в метрах (формула гаверсинусов). */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    fun formatDistance(m: Double): String = when {
        m < 1000 -> "${m.roundToInt()} м"
        m < 10_000 -> String.format("%.1f км", m / 1000)
        else -> "${(m / 1000).roundToInt()} км"
    }

    fun formatEta(sec: Double?): String {
        if (sec == null) return "—"
        val s = sec.roundToInt()
        return if (s < 60) "$s с" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}
