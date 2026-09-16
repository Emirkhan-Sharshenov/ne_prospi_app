package com.nesprosi.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File
import java.util.Locale

/**
 * Офлайн-карты любой страны или региона (векторные файлы mapsforge с download.mapsforge.org).
 * Можно скачать несколько карт — они показываются вместе.
 */
object OfflineMap {
    private const val BASE = "https://download.mapsforge.org/maps/v5/"
    private val CONTINENTS = listOf(
        "asia/", "europe/", "russia/", "north-america/", "south-america/",
        "central-america/", "africa/", "australia-oceania/",
    )
    private var initialized = false

    /** Страна, регион или папка на сервере. path — относительно BASE, у папок заканчивается на «/». */
    data class Entry(val path: String, val size: String) {
        val isDir get() = path.endsWith("/")
        val fileName get() = path.trimEnd('/').substringAfterLast('/')
        val title get() = prettyName(fileName)
    }

    data class Download(val id: Long, val fileName: String, val percent: Int)

    fun prettyName(fileName: String): String = fileName.removeSuffix(".map").split('-', '_')
        .joinToString(" ") { part ->
            if (part.length <= 2) part.uppercase(Locale.ROOT) else part.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }

    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "maps").apply { mkdirs() }

    /** Скачанные карты. */
    fun files(ctx: Context): List<File> {
        migrateOldMap(ctx)
        return dir(ctx).listFiles { f -> f.name.endsWith(".map") && f.length() > 100_000 }?.sortedBy { it.name }.orEmpty()
    }

    fun isReady(ctx: Context) = files(ctx).isNotEmpty()

    /** Версия 1.1 хранила карту Кыргызстана рядом с папкой maps. */
    private fun migrateOldMap(ctx: Context) {
        val old = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "kyrgyzstan.map")
        if (old.exists()) old.renameTo(File(dir(ctx), "kyrgyzstan.map"))
    }

    // ---------- Каталог карт на сервере ----------

    private val rowRegex = Regex(
        """<a href="([^"?/][^"]*)">[^<]*</a>\s*</td><td[^>]*>[^<]*</td><td[^>]*>\s*([^<]*?)\s*</td>"""
    )

    /** Содержимое папки на сервере (в фоне, нужен интернет). */
    fun list(path: String): List<Entry> {
        val html = Net.get(BASE + path, 20_000)
        return rowRegex.findAll(html).map { m -> Entry(path + m.groupValues[1], m.groupValues[2].trim()) }
            .filter { it.isDir || it.path.endsWith(".map") }
            .toList()
    }

    fun continents(): List<Entry> = CONTINENTS.map { Entry(it, "") }

    /**
     * Ищет карту страны пользователя по её английскому названию (в фоне).
     * Большие страны (США, Россия, Великобритания) разбиты на регионы — тогда вернётся папка.
     */
    fun suggestForCountry(ctx: Context): Entry? {
        val code = Region.countryCode(ctx).ifEmpty { return null }
        if (code == "RU") return Entry("russia/", "")
        if (code == "US") return Entry("north-america/us/", "")
        val english = Locale("", code).getDisplayCountry(Locale.ENGLISH)
        val slug = java.text.Normalizer.normalize(english, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace("&", "and").replace(Regex("[^a-z]+"), "-").trim('-')
        if (slug.isEmpty()) return null
        for (continent in CONTINENTS) {
            val entries = runCatching { list(continent) }.getOrNull() ?: continue
            entries.firstOrNull { !it.isDir && it.fileName == "$slug.map" }?.let { return it }
            entries.firstOrNull { it.isDir && it.fileName == slug }?.let { return it }
        }
        return null
    }

    // ---------- Загрузка ----------

    private fun store(ctx: Context) = ctx.getSharedPreferences("offline_maps", Context.MODE_PRIVATE)

    private fun downloadIds(ctx: Context): MutableMap<Long, String> {
        val json = runCatching { JSONObject(store(ctx).getString("downloads", "{}")!!) }.getOrElse { JSONObject() }
        return json.keys().asSequence().associate { it.toLong() to json.getString(it) }.toMutableMap()
    }

    private fun saveIds(ctx: Context, ids: Map<Long, String>) {
        val json = JSONObject()
        ids.forEach { (id, name) -> json.put(id.toString(), name) }
        store(ctx).edit().putString("downloads", json.toString()).apply()
    }

    fun startDownload(ctx: Context, entry: Entry) {
        val part = File(dir(ctx), entry.fileName + ".part")
        part.delete()
        val request = DownloadManager.Request(Uri.parse(BASE + entry.path))
            .setTitle(ctx.getString(R.string.offline_download_title, entry.title))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part))
        val id = ctx.getSystemService(DownloadManager::class.java).enqueue(request)
        saveIds(ctx, downloadIds(ctx).apply { put(id, entry.fileName) })
    }

    /** Идущие загрузки; заодно переименовывает законченные и убирает неудачные. true в failed — были ошибки. */
    fun downloads(ctx: Context): Pair<List<Download>, Boolean> {
        val ids = downloadIds(ctx)
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val active = mutableListOf<Download>()
        var failed = false
        for ((id, name) in ids.toMap()) {
            val part = File(dir(ctx), "$name.part")
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor == null) { ids.remove(id); continue }
            cursor.use { c ->
                if (!c.moveToFirst()) { ids.remove(id); return@use }
                val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (state) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val target = File(dir(ctx), name)
                        target.delete()
                        part.renameTo(target)
                        ids.remove(id)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        part.delete()
                        ids.remove(id)
                        failed = true
                    }
                    else -> active += Download(id, name, if (total > 0) (done * 100 / total).toInt() else 0)
                }
            }
        }
        saveIds(ctx, ids)
        return active to failed
    }

    fun cancel(ctx: Context, download: Download) {
        ctx.getSystemService(DownloadManager::class.java).remove(download.id)
        File(dir(ctx), download.fileName + ".part").delete()
        saveIds(ctx, downloadIds(ctx).apply { remove(download.id) })
    }

    fun delete(file: File) {
        file.delete()
    }

    fun tileProvider(ctx: Context): MapsForgeTileProvider {
        if (!initialized) {
            MapsForgeTileSource.createInstance(ctx.applicationContext as Application)
            initialized = true
        }
        val source = MapsForgeTileSource.createFromFiles(files(ctx).toTypedArray())
        return MapsForgeTileProvider(SimpleRegisterReceiver(ctx), source, null)
    }
}
