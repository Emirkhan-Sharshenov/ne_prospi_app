package com.nesprosi.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

/** Поиск адресов (Nominatim) и остановки (встроенный файл + обновление из OpenStreetMap). */
object Net {
    private const val USER_AGENT = "NeProspi/1.1 (Android)"

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun get(url: String, timeoutMs: Int = 10_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** Результаты поиска: полный адрес для списка и место с коротким названием. */
    fun search(ctx: Context, q: String, extra: String): List<Pair<String, Place>> {
        val arr = JSONArray(
            get(
                "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&limit=5" +
                    "&accept-language=${Lang.searchLanguage(ctx)}$extra&q=" + URLEncoder.encode(q, "UTF-8")
            )
        )
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.optString("display_name") to
                Place(shortName(ctx, o), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }

    /** Короткое название точки: «Ошский базар, улица Бейшеналиевой 42». null — адрес не найден. */
    fun reverse(ctx: Context, lat: Double, lon: Double): String? {
        val o = JSONObject(
            get(
                "https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&addressdetails=1" +
                    "&accept-language=${Lang.searchLanguage(ctx)}&lat=$lat&lon=$lon"
            )
        )
        return if (o.has("error")) null else shortName(ctx, o).takeIf { it.isNotBlank() }
    }

    /** Улица с домом, или район, если улицы рядом нет. */
    fun street(ctx: Context, lat: Double, lon: Double): String? {
        val o = JSONObject(
            get(
                "https://nominatim.openstreetmap.org/reverse?format=json&zoom=17&addressdetails=1" +
                    "&accept-language=${Lang.searchLanguage(ctx)}&lat=$lat&lon=$lon"
            )
        )
        val a = o.optJSONObject("address") ?: return null
        return a.optString("road").ifBlank { area(a) }.takeIf { it.isNotBlank() }
    }

    private fun area(a: JSONObject?) = listOf("neighbourhood", "quarter", "suburb", "village", "town", "city")
        .map { a?.optString(it).orEmpty() }
        .firstOrNull { it.isNotBlank() }.orEmpty()

    private fun shortName(ctx: Context, o: JSONObject): String {
        val a = o.optJSONObject("address")
        val road = a?.optString("road").orEmpty()
        val street = listOf(road, a?.optString("house_number").orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
        val title = o.optString("name").takeIf { it.isNotBlank() && it != road }.orEmpty()
        val area = area(a)
        return listOf(title, street).filter { it.isNotBlank() }.joinToString(", ")
            .ifBlank { if (area.isNotBlank()) ctx.getString(R.string.point_near, area) else "" }
            .ifBlank { o.optString("display_name").split(",").take(2).joinToString(",").trim() }
    }

    /** Скачивает свежий список остановок Кыргызстана (раз в месяц). */
    fun refreshStops(ctx: Context) {
        val query = "[out:json][timeout:60];area[\"ISO3166-1\"=\"KG\"][admin_level=2]->.kg;" +
            "(node[\"highway\"=\"bus_stop\"](area.kg);node[\"public_transport\"=\"platform\"][\"bus\"=\"yes\"](area.kg););out;"
        val json = JSONObject(
            get("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8"), 90_000)
        )
        val elements = json.getJSONArray("elements")
        if (elements.length() < 500) return // подозрительно мало — оставляем старый файл
        val clean = { s: String -> s.replace('\t', ' ').replace('\n', ' ').trim() }
        val lines = (0 until elements.length()).map {
            val e = elements.getJSONObject(it)
            val t = e.optJSONObject("tags") ?: JSONObject()
            val ru = clean(t.optString("name:ru").ifBlank { t.optString("name") })
            val ky = clean(t.optString("name:ky").ifBlank { t.optString("name") })
            "${e.getDouble("lat")}\t${e.getDouble("lon")}\t$ru\t$ky"
        }
        val tmp = File(ctx.filesDir, "stops.tsv.tmp")
        tmp.writeText(lines.joinToString("\n"))
        tmp.renameTo(File(ctx.filesDir, "stops.tsv"))
        Stops.reset()
    }
}

data class Stop(val lat: Double, val lon: Double, val nameRu: String, val nameKy: String) {
    fun name(ctx: Context) = (if (Lang.isKyrgyz(ctx)) nameKy else nameRu).ifBlank { nameRu }
}

object Stops {
    @Volatile private var cache: List<Stop>? = null

    fun reset() { cache = null }

    fun all(ctx: Context): List<Stop> {
        cache?.let { return it }
        val file = File(ctx.filesDir, "stops.tsv")
        val text = if (file.exists()) file.readText() else ctx.assets.open("stops.tsv").bufferedReader().use { it.readText() }
        val list = text.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            Stop(lat, lon, p.getOrElse(2) { "" }, p.getOrElse(3) { "" })
        }.toList()
        cache = list
        return list
    }

    fun nearest(ctx: Context, lat: Double, lon: Double, maxMeters: Double): Stop? {
        // быстрый отбор по прямоугольнику, потом точное расстояние
        val dLat = maxMeters / 111_000.0
        val dLon = dLat / cos(Math.toRadians(lat))
        return all(ctx)
            .filter { it.lat in (lat - dLat)..(lat + dLat) && it.lon in (lon - dLon)..(lon + dLon) }
            .map { it to Geo.distance(lat, lon, it.lat, it.lon) }
            .filter { it.second <= maxMeters }
            .minByOrNull { it.second }?.first
    }

    fun inBox(ctx: Context, south: Double, west: Double, north: Double, east: Double, limit: Int): List<Stop> =
        all(ctx).asSequence()
            .filter { it.lat in south..north && it.lon in west..east }
            .take(limit)
            .toList()
}
