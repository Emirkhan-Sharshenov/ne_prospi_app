package com.nesprosi.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

    /** Проверку надёжности показываем сами один раз, при первом запуске. */
    var setupShown: Boolean
        get() = sp.getBoolean("setupShown", false)
        set(v) = putBool("setupShown", v)

    /** Недавние поездки (без повторов и сохранённых мест) — для быстрого выбора. */
    fun recentPlaces(limit: Int): List<Place> {
        val saved = places.map { it.lat to it.lon }.toSet()
        return history.asSequence()
            .filter { !it.simulated && it.lat != null && it.lon != null }
            .map { Place(it.dest, it.lat!!, it.lon!!) }
            .filter { (it.lat to it.lon) !in saved }
            .distinctBy { it.name }
            .take(limit)
            .toList()
    }

    // ---------- Близкие ----------
    var contactPhone: String
        get() = str("contactPhone", "")
        set(v) = putStr("contactPhone", v)
    var smsEnabled: Boolean
        get() = sp.getBoolean("smsEnabled", false)
        set(v) = putBool("smsEnabled", v)

    // ---------- Внешний вид и карта ----------
    var theme: String
        get() = str("theme", THEME_SYSTEM)
        set(v) = putStr("theme", v)
    var offlineAlways: Boolean
        get() = sp.getBoolean("offlineAlways", false)
        set(v) = putBool("offlineAlways", v)
    var offlineDownloadId: Long
        get() = sp.getLong("offlineDownloadId", -1)
        set(v) = sp.edit().putLong("offlineDownloadId", v).apply()
    var stopsUpdatedAt: Long
        get() = sp.getLong("stopsUpdatedAt", 0)
        set(v) = sp.edit().putLong("stopsUpdatedAt", v).apply()

    /** Где карта была в прошлый раз. По умолчанию — центр Бишкека. */
    var mapLat: Double
        get() = sp.getString("mapLat", null)?.toDoubleOrNull() ?: 42.8746
        set(v) = putStr("mapLat", v.toString())
    var mapLon: Double
        get() = sp.getString("mapLon", null)?.toDoubleOrNull() ?: 74.5698
        set(v) = putStr("mapLon", v.toString())
    var mapZoom: Double
        get() = sp.getString("mapZoom", null)?.toDoubleOrNull() ?: 12.0
        set(v) = putStr("mapZoom", v.toString())

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
    }
}

object Lang {
    /** Выбранный в приложении язык: "ru", "ky" или пусто (как в телефоне). */
    fun current(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')

    fun isKyrgyz(context: Context): Boolean {
        val tag = current().ifEmpty { context.resources.configuration.locales[0].language }
        return tag.startsWith("ky")
    }

    /** Язык для поиска адресов. */
    fun searchLanguage(context: Context) = if (isKyrgyz(context)) "ky,ru" else "ru"

    /**
     * Контекст с языком приложения. На Android 13+ система делает это сама,
     * на старых версиях службе нужно подставить язык вручную.
     */
    fun wrap(context: Context): Context {
        val tag = current()
        if (Build.VERSION.SDK_INT >= 33 || tag.isEmpty()) return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
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

    fun formatDistance(ctx: Context, m: Double): String = when {
        m < 1000 -> ctx.getString(R.string.meters_value, m.roundToInt())
        m < 10_000 -> ctx.getString(R.string.km_value, m / 1000)
        else -> ctx.getString(R.string.km_value_int, (m / 1000).roundToInt())
    }

    fun formatEta(ctx: Context, sec: Double?): String {
        if (sec == null) return "—"
        val s = sec.roundToInt()
        return if (s < 60) ctx.getString(R.string.sec_value, s) else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** Ссылка на точку, которая откроется в любом телефоне. */
    fun mapLink(lat: Double, lon: Double) = String.format(java.util.Locale.US, "https://maps.google.com/?q=%.5f,%.5f", lat, lon)
}
