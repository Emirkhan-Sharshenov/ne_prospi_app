package com.nesprosi.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Сеть: поиск адресов (Nominatim) и запросы к OpenStreetMap. */
object Net {
    const val USER_AGENT = "NeProspi/1.3 (Android; https://github.com/Emirkhan-Sharshenov/ne_prospi_app)"

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun get(url: String, timeoutMs: Int = 10_000): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.doOutput = true
        conn.requestMethod = "POST"
        val body = fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Поиск адреса в любой стране. Сначала — только в видимой части карты,
     * если там ничего нет — по всему миру, но ближе к видимой части.
     */
    fun search(ctx: Context, q: String, south: Double, west: Double, north: Double, east: Double): List<Pair<String, Place>> {
        val viewbox = "&viewbox=$west,$north,$east,$south"
        return searchOnce(ctx, q, "$viewbox&bounded=1").ifEmpty { searchOnce(ctx, q, viewbox) }
    }

    private fun searchOnce(ctx: Context, q: String, extra: String): List<Pair<String, Place>> {
        val arr = JSONArray(
            get(
                "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&limit=6" +
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

    /** Улица, или район, если улицы рядом нет. */
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
}
