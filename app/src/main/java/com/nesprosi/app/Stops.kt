package com.nesprosi.app

import android.content.Context
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor

enum class StopKind { BUS, TRAM, RAIL, FERRY }

data class Stop(
    val lat: Double,
    val lon: Double,
    val kind: StopKind,
    val name: String,
    val names: Map<String, String>,
) {
    /** Название на языке приложения, если оно есть в OpenStreetMap, иначе местное. */
    fun name(ctx: Context): String = names[Lang.language(ctx)]?.takeIf { it.isNotBlank() } ?: name
}

/**
 * Остановки автобусов, трамваев, поездов, метро и паромов в любой стране.
 *
 * Кыргызстан встроен в приложение (работает без интернета). Для остальных стран остановки
 * загружаются из OpenStreetMap по квадратам ~5×5 км, когда пользователь смотрит на карту,
 * и сохраняются на телефоне на 30 дней — так уже просмотренные места работают без сети.
 */
object Stops {
    private const val CELL_DEG = 0.05
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val RETRY_MS = 5L * 60 * 1000
    private const val MAX_CELLS_PER_REQUEST = 12
    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter", // запасной сервер
    )

    @Volatile private var bundled: List<Stop>? = null
    private val cells = ConcurrentHashMap<Long, List<Stop>>()
    private val failedAt = ConcurrentHashMap<Long, Long>()
    private val pending = Collections.synchronizedSet(mutableSetOf<Long>())
    private val executor = Executors.newSingleThreadExecutor()

    private fun inBundle(lat: Double, lon: Double) = lat in 39.1..43.3 && lon in 69.2..80.3

    private fun idx(v: Double) = floor(v / CELL_DEG).toInt()
    private fun key(latIdx: Int, lonIdx: Int) = latIdx.toLong() * 100_000L + (lonIdx + 50_000)

    private fun cellsIn(south: Double, west: Double, north: Double, east: Double): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (la in idx(south)..idx(north)) for (lo in idx(west)..idx(east)) result += la to lo
        return result
    }

    private fun bundled(ctx: Context): List<Stop> {
        bundled?.let { return it }
        val text = ctx.assets.open("stops.tsv").bufferedReader().use { it.readText() }
        val list = text.lineSequence().mapNotNull { line ->
            val p = line.split('\t')
            val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val ru = p.getOrElse(2) { "" }
            val ky = p.getOrElse(3) { "" }
            Stop(lat, lon, StopKind.BUS, ru.ifBlank { ky }, mapOf("ru" to ru, "ky" to ky))
        }.toList()
        bundled = list
        return list
    }

    /** Остановки из памяти в прямоугольнике (без обращения к сети). */
    fun inBox(ctx: Context, south: Double, west: Double, north: Double, east: Double, limit: Int): List<Stop> {
        val fromCells = cellsIn(south, west, north, east).asSequence().flatMap { (la, lo) -> cells[key(la, lo)].orEmpty() }
        val fromBundle = if (inBundle((south + north) / 2, (west + east) / 2) ||
            inBundle(south, west) || inBundle(north, east)
        ) bundled(ctx).asSequence() else emptySequence()
        return (fromBundle + fromCells)
            .filter { it.lat in south..north && it.lon in west..east }
            .take(limit)
            .toList()
    }

    fun nearest(ctx: Context, lat: Double, lon: Double, maxMeters: Double): Stop? {
        val dLat = maxMeters / 111_000.0
        val dLon = dLat / cos(Math.toRadians(lat))
        return inBox(ctx, lat - dLat, lon - dLon, lat + dLat, lon + dLon, Int.MAX_VALUE)
            .map { it to Geo.distance(lat, lon, it.lat, it.lon) }
            .filter { it.second <= maxMeters }
            .minByOrNull { it.second }?.first
    }

    /**
     * Готовит остановки видимой области: с диска или из OpenStreetMap.
     * [onLoaded] вызывается в фоновом потоке, если появились новые остановки.
     */
    fun ensure(ctx: Context, south: Double, west: Double, north: Double, east: Double, onLoaded: () -> Unit) {
        val app = ctx.applicationContext
        val wanted = cellsIn(south, west, north, east)
            .filterNot { (la, lo) -> inBundle((la + 0.5) * CELL_DEG, (lo + 0.5) * CELL_DEG) }
            .filterNot { (la, lo) -> cells.containsKey(key(la, lo)) || key(la, lo) in pending }
        if (wanted.isEmpty() || wanted.size > MAX_CELLS_PER_REQUEST) return
        wanted.forEach { (la, lo) -> pending += key(la, lo) }
        executor.execute {
            try {
                if (load(app, wanted)) onLoaded()
            } finally {
                wanted.forEach { (la, lo) -> pending -= key(la, lo) }
            }
        }
    }

    /** Синхронно (в фоне) догружает квадрат вокруг точки и ищет ближайшую остановку. */
    fun fetchNearest(ctx: Context, lat: Double, lon: Double, maxMeters: Double): Stop? {
        if (!inBundle(lat, lon)) load(ctx.applicationContext, listOf(idx(lat) to idx(lon)))
        return nearest(ctx, lat, lon, maxMeters)
    }

    private fun cellFile(ctx: Context, la: Int, lo: Int) = File(File(ctx.filesDir, "stops"), "${la}_$lo.tsv")

    /** true — в памяти появились новые остановки. */
    private fun load(ctx: Context, wanted: List<Pair<Int, Int>>): Boolean {
        var changed = false
        val online = Net.isOnline(ctx)
        val now = System.currentTimeMillis()
        val toFetch = mutableListOf<Pair<Int, Int>>()
        for ((la, lo) in wanted) {
            val k = key(la, lo)
            if (cells.containsKey(k)) continue
            val file = cellFile(ctx, la, lo)
            val fresh = file.exists() && now - file.lastModified() < MAX_AGE_MS
            if (file.exists() && (fresh || !online)) {
                cells[k] = readCell(file)
                changed = true
            } else if (online && (failedAt[k]?.let { now - it < RETRY_MS } != true)) {
                toFetch += la to lo
            } else if (file.exists()) {
                cells[k] = readCell(file)
                changed = true
            }
        }
        if (toFetch.isEmpty()) return changed

        val laMin = toFetch.minOf { it.first }
        val laMax = toFetch.maxOf { it.first }
        val loMin = toFetch.minOf { it.second }
        val loMax = toFetch.maxOf { it.second }
        val stops = runCatching {
            fetch(ctx, laMin * CELL_DEG, loMin * CELL_DEG, (laMax + 1) * CELL_DEG, (loMax + 1) * CELL_DEG)
        }.getOrNull()
        if (stops == null) {
            toFetch.forEach { (la, lo) ->
                failedAt[key(la, lo)] = now
                val file = cellFile(ctx, la, lo)
                if (file.exists()) {
                    cells[key(la, lo)] = readCell(file)
                    changed = true
                }
            }
            return changed
        }
        val byCell = stops.groupBy { idx(it.lat) to idx(it.lon) }
        for ((la, lo) in toFetch) {
            val list = byCell[la to lo].orEmpty()
            cells[key(la, lo)] = list
            runCatching { writeCell(cellFile(ctx, la, lo), list) }
        }
        return true
    }

    private fun fetch(ctx: Context, south: Double, west: Double, north: Double, east: Double): List<Stop> {
        val lang = Lang.language(ctx)
        val nameFields = (listOf("en", "ru", "ky") + lang).distinct().joinToString(",") { "\"name:$it\"" }
        val bbox = "$south,$west,$north,$east"
        val query = "[out:csv(::lat,::lon,highway,railway,amenity,name,$nameFields;false;\"\\t\")][timeout:25];" +
            "(node[\"highway\"=\"bus_stop\"]($bbox);" +
            "node[\"railway\"~\"^(tram_stop|halt|station)$\"]($bbox);" +
            "node[\"amenity\"=\"ferry_terminal\"]($bbox););out;"
        val langs = (listOf("en", "ru", "ky") + lang).distinct()
        var lastError: Exception? = null
        for (server in OVERPASS) {
            try {
                val csv = Net.postForm(server, mapOf("data" to query), 30_000)
                return csv.lineSequence().mapNotNull { line -> parseCsv(line, langs) }.toList()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("overpass")
    }

    private fun parseCsv(line: String, langs: List<String>): Stop? {
        val p = line.split('\t')
        val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return null
        val kind = when {
            p.getOrNull(2) == "bus_stop" -> StopKind.BUS
            p.getOrNull(3) == "tram_stop" -> StopKind.TRAM
            p.getOrNull(3).isNullOrBlank().not() -> StopKind.RAIL
            p.getOrNull(4) == "ferry_terminal" -> StopKind.FERRY
            else -> StopKind.BUS
        }
        val names = langs.mapIndexedNotNull { i, l -> p.getOrNull(6 + i)?.takeIf { it.isNotBlank() }?.let { l to it } }.toMap()
        return Stop(lat, lon, kind, p.getOrElse(5) { "" }, names)
    }

    private fun writeCell(file: File, stops: List<Stop>) {
        file.parentFile?.mkdirs()
        val clean = { s: String -> s.replace('\t', ' ').replace('\n', ' ').replace('|', '/') }
        file.writeText(stops.joinToString("\n") { s ->
            val names = s.names.entries.joinToString("|") { "${it.key}=${clean(it.value)}" }
            "${s.lat}\t${s.lon}\t${s.kind.name}\t${clean(s.name)}\t$names"
        })
    }

    private fun readCell(file: File): List<Stop> = runCatching {
        file.readLines().mapNotNull { line ->
            val p = line.split('\t')
            val lat = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            val kind = runCatching { StopKind.valueOf(p.getOrElse(2) { "BUS" }) }.getOrDefault(StopKind.BUS)
            val names = p.getOrElse(4) { "" }.split('|').mapNotNull {
                val i = it.indexOf('=')
                if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
            }.toMap()
            Stop(lat, lon, kind, p.getOrElse(3) { "" }, names)
        }
    }.getOrDefault(emptyList())
}
