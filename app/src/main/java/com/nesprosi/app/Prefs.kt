package com.nesprosi.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject

data class Place(val name: String, val lat: Double, val lon: Double)

data class TripRecord(
    val dest: String,
    val startedAt: Long,
    val endedAt: Long,
    val result: String,
    val simulated: Boolean,
    val lat: Double? = null,
    val lon: Double? = null,
) {
    companion object {
        const val ARRIVED = "arrived"
        const val BACKUP = "backup"
        const val STOPPED = "stopped"
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nesprosi", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun putStr(key: String, v: String?) = sp.edit().putString(key, v).apply()
    private fun putBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    // ---------- Когда будить ----------
    var mode: String
        get() = str("mode", MODE_DIST)
        set(v) = putStr("mode", v)

    /** Будить, когда до остановки осталось столько метров. */
    var distMeters: Int
        get() = sp.getInt("dist", 500)
        set(v) = putInt("dist", v)

    /** Будить за столько минут до прибытия. */
    var minutes: Int
        get() = sp.getInt("minutes", 3)
        set(v) = putInt("minutes", v)

    /** Страховочный таймер: разбудить в любом случае через столько минут (0 — выключен). */
    var backupMinutes: Int
        get() = sp.getInt("backupMinutes", 0)
        set(v) = putInt("backupMinutes", v)

    // ---------- Будильник ----------
    var alarmSound: String?
        get() = sp.getString("alarmSound", null)
        set(v) = putStr("alarmSound", v)
    var vibrationOnly: Boolean
        get() = sp.getBoolean("vibrationOnly", false)
        set(v) = putBool("vibrationOnly", v)
    var flashlight: Boolean
        get() = sp.getBoolean("flashlight", false)
        set(v) = putBool("flashlight", v)
    var headphonesOnly: Boolean
        get() = sp.getBoolean("headphonesOnly", false)
        set(v) = putBool("headphonesOnly", v)
    var voice: Boolean
        get() = sp.getBoolean("voice", false)
        set(v) = putBool("voice", v)

    /** Через минуту после будильника спросить, не уснул ли человек. По умолчанию выключено. */
    var wakeCheck: Boolean
        get() = sp.getBoolean("wakeCheck", false)
        set(v) = putBool("wakeCheck", v)

    /** Проверку надёжности показываем сами один раз, при первом запуске. */
    var setupShown: Boolean
        get() = sp.getBoolean("setupShown", false)
        set(v) = putBool("setupShown", v)

    // ---------- Близкие ----------
    var contactPhone: String
        get() = str("contactPhone", "")
        set(v) = putStr("contactPhone", v)
    var smsEnabled: Boolean
        get() = sp.getBoolean("smsEnabled", false)
        set(v) = putBool("smsEnabled", v)

    // ---------- Внешний вид, единицы и карта ----------
    var theme: String
        get() = str("theme", THEME_SYSTEM)
        set(v) = putStr("theme", v)

    /** Единицы расстояния: как принято в стране, метрические или мили. */
    var units: String
        get() = str("units", UNITS_AUTO)
        set(v) = putStr("units", v)

    var offlineAlways: Boolean
        get() = sp.getBoolean("offlineAlways", false)
        set(v) = putBool("offlineAlways", v)

    /** Где карта была в прошлый раз; null — приложение ещё не открывали. */
    val mapPosition: Triple<Double, Double, Double>?
        get() {
            val lat = sp.getString("mapLat", null)?.toDoubleOrNull() ?: return null
            val lon = sp.getString("mapLon", null)?.toDoubleOrNull() ?: return null
            val zoom = sp.getString("mapZoom", null)?.toDoubleOrNull() ?: return null
            return Triple(lat, lon, zoom)
        }

    fun saveMapPosition(lat: Double, lon: Double, zoom: Double) {
        sp.edit().putString("mapLat", lat.toString()).putString("mapLon", lon.toString())
            .putString("mapZoom", zoom.toString()).apply()
    }

    // ---------- Места и история ----------
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
            putStr("places", arr.toString())
        }

    var history: List<TripRecord>
        get() {
            val arr = runCatching { JSONArray(sp.getString("history", "[]")) }.getOrElse { JSONArray() }
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                TripRecord(
                    o.getString("dest"), o.getLong("startedAt"), o.getLong("endedAt"),
                    o.getString("result"), o.optBoolean("simulated"),
                    o.optDouble("lat").takeIf { v -> !v.isNaN() },
                    o.optDouble("lon").takeIf { v -> !v.isNaN() },
                )
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach {
                arr.put(
                    JSONObject().put("dest", it.dest).put("startedAt", it.startedAt).put("endedAt", it.endedAt)
                        .put("result", it.result).put("simulated", it.simulated)
                        .apply { if (it.lat != null && it.lon != null) put("lat", it.lat).put("lon", it.lon) }
                )
            }
            putStr("history", arr.toString())
        }

    fun addHistory(record: TripRecord) {
        history = (listOf(record) + history).take(100)
    }

    /** Недавние поездки (без повторов и сохранённых мест) с временем последней поездки. */
    fun recentTrips(limit: Int): List<Pair<Place, Long>> {
        val saved = places.map { it.lat to it.lon }.toSet()
        return history.asSequence()
            .filter { !it.simulated && it.lat != null && it.lon != null }
            .map { Place(it.dest, it.lat!!, it.lon!!) to it.startedAt }
            .filter { (it.first.lat to it.first.lon) !in saved }
            .distinctBy { it.first.name }
            .take(limit)
            .toList()
    }

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    companion object {
        const val MODE_DIST = "dist"
        const val MODE_TIME = "time"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val UNITS_AUTO = "auto"
        const val UNITS_METRIC = "metric"
        const val UNITS_IMPERIAL = "imperial"
    }
}
