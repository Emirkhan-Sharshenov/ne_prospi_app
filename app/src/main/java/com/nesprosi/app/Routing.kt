package com.nesprosi.app

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * Маршрут по дорогам — только для наглядности: линия на карте и подпись «по дороге столько-то».
 * Будильник считает расстояние по прямой и работает без интернета, поэтому отсутствие маршрута
 * ни на что не влияет.
 *
 * Данные: публичный OSRM (проект OpenStreetMap). Запросы редкие: повторный маршрут берётся
 * из памяти, пока человек не отъехал заметно, и не чаще раза в минуту.
 */
object Routing {

    data class Route(val points: List<GeoPoint>, val meters: Double, val seconds: Double)

    private const val BASE = "https://router.project-osrm.org/route/v1/driving/"
    private const val MIN_INTERVAL_MS = 60_000L

    private var cachedKey: String? = null
    private var cached: Route? = null
    private var lastAt = 0L

    /** Ключ: свою точку огрубляем до ~100 м, цель — точно. */
    private fun key(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) =
        String.format(Locale.US, "%.3f,%.3f>%.5f,%.5f", fromLat, fromLon, toLat, toLon)

    /** Блокирующий вызов — только из фонового потока. null, если маршрута нет или сети нет. */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? {
        val k = key(fromLat, fromLon, toLat, toLon)
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (k == cachedKey) return cached
            // тот же пункт назначения, но человек едет: не дёргаем сервер чаще раза в минуту
            if (cached != null && now - lastAt < MIN_INTERVAL_MS && sameDest(k)) return cached
            lastAt = now
        }
        val url = String.format(
            Locale.US,
            "%s%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline&alternatives=false&steps=false",
            BASE, fromLon, fromLat, toLon, toLat
        )
        val route = runCatching {
            val o = JSONObject(Net.get(url, timeoutMs = 8_000))
            if (o.optString("code") != "Ok") return@runCatching null
            val r = o.getJSONArray("routes").optJSONObject(0) ?: return@runCatching null
            val points = decode(r.getString("geometry"))
            if (points.size < 2) null
            else Route(points, r.optDouble("distance", 0.0), r.optDouble("duration", 0.0))
        }.getOrNull()
        synchronized(this) {
            if (route != null) {
                cachedKey = k
                cached = route
            }
        }
        return route ?: synchronized(this) { if (sameDest(k)) cached else null }
    }

    private fun sameDest(k: String) = cachedKey?.substringAfter('>') == k.substringAfter('>')

    /** Пункт назначения сменился — старая линия больше не нужна. */
    fun forget() = synchronized(this) {
        cachedKey = null
        cached = null
        lastAt = 0L
    }

    /** Ломаная в формате encoded polyline (точность 5), как её отдаёт OSRM. */
    fun decode(encoded: String): List<GeoPoint> {
        val points = ArrayList<GeoPoint>(encoded.length / 4)
        var i = 0
        var lat = 0
        var lon = 0
        while (i < encoded.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && i < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            shift = 0
            result = 0
            do {
                b = encoded[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && i < encoded.length)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(GeoPoint(lat / 1e5, lon / 1e5))
        }
        return points
    }
}
