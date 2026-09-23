package com.nesprosi.app.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration as UiConfiguration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nesprosi.app.Geo
import com.nesprosi.app.Net
import com.nesprosi.app.OfflineMap
import com.nesprosi.app.Place
import com.nesprosi.app.Prefs
import com.nesprosi.app.R
import com.nesprosi.app.Region
import com.nesprosi.app.Routing
import com.nesprosi.app.Stop
import com.nesprosi.app.StopKind
import com.nesprosi.app.Stops
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/** Карта: слой плиток (онлайн/офлайн), остановки, пункт назначения, «я» и нажатия. */
class MapController(
    private val activity: AppCompatActivity,
    private val map: MapView,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onMapTap(point: GeoPoint)
        fun onStopTap(stop: Stop)
        /** Первая точка GPS, пока пункт назначения не выбран. */
        fun onFirstFix()
    }

    companion object {
        private const val STOPS_MIN_ZOOM = 16.0 // при меньшем масштабе значки закрывают улицы
        private val BISHKEK = GeoPoint(42.8746, 74.5698)

        /** Настройки osmdroid до создания MapView. */
        fun configure(activity: AppCompatActivity) {
            Configuration.getInstance().apply {
                userAgentValue = Net.USER_AGENT
                osmdroidBasePath = File(activity.cacheDir, "osmdroid")
                osmdroidTileCache = File(activity.cacheDir, "osmdroid/tiles")
                // просмотренные места карты остаются в кэше и видны без интернета
                tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
                tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stopsFolder = FolderOverlay()
    private var destMarker: Marker? = null
    private var destCircle: Polygon? = null
    private var meMarker: Marker? = null
    private var zoneLabel: Marker? = null
    private var youLabel: Marker? = null
    private var guideLine: Polyline? = null
    private var routeLine: Polyline? = null
    private var routeMeters: Double? = null
    private var track: Polyline? = null
    private var destPlace: Place? = null
    private var tripMode = false
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var usingOffline: Boolean? = null
    var interactive = true
    private val updateStopsRunnable = Runnable { updateStops() }

    val boundingBox: BoundingBox get() = map.boundingBox
    val myLocation: GeoPoint? get() = myLocationOverlay?.myLocation

    fun setup() {
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        moveToStartPosition()
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (interactive) listener.onMapTap(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint) = false
        }))
        map.overlays.add(stopsFolder)
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?) = scheduleStopsUpdate()
            override fun onZoom(event: ZoomEvent?) = scheduleStopsUpdate()
        })
    }

    /** Где открыть карту: где были в прошлый раз → где телефон был недавно → Бишкек → весь мир. */
    @SuppressLint("MissingPermission")
    private fun moveToStartPosition() {
        prefs.mapPosition?.let { (lat, lon, zoom) ->
            map.controller.setZoom(zoom)
            map.controller.setCenter(GeoPoint(lat, lon))
            return
        }
        if (hasLocationPermission()) {
            val lm = activity.getSystemService(LocationManager::class.java)
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
            if (last != null) {
                map.controller.setZoom(14.0)
                map.controller.setCenter(GeoPoint(last.latitude, last.longitude))
                return
            }
        }
        if (Region.countryCode(activity) == "KG") {
            map.controller.setZoom(12.0)
            map.controller.setCenter(BISHKEK)
        } else {
            map.controller.setZoom(3.0)
            map.controller.setCenter(GeoPoint(25.0, 15.0))
        }
    }

    fun onResume() {
        applyTileSource()
        map.onResume()
        if (hasLocationPermission()) enableMyLocation()
        myLocationOverlay?.enableMyLocation()
    }

    fun onPause() {
        prefs.saveMapPosition(map.mapCenter.latitude, map.mapCenter.longitude, map.zoomLevelDouble)
        myLocationOverlay?.disableMyLocation()
        map.onPause()
    }

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Онлайн-карта OpenStreetMap или скачанные офлайн-карты (если нет интернета или так выбрано). */
    fun applyTileSource() {
        val offline = OfflineMap.isReady(activity) && (prefs.offlineAlways || !Net.isOnline(activity))
        if (offline != usingOffline) {
            if (offline) {
                map.setTileProvider(OfflineMap.tileProvider(activity))
            } else {
                map.setTileProvider(MapTileProviderBasic(activity.applicationContext, TileSourceFactory.MAPNIK))
            }
            usingOffline = offline
        }
        // приглушённая карта, как в макете: метки заметнее; ночью — тёмная, чтобы не слепила
        val night = (activity.resources.configuration.uiMode and UiConfiguration.UI_MODE_NIGHT_MASK) ==
            UiConfiguration.UI_MODE_NIGHT_YES
        val matrix = ColorMatrix().apply { setSaturation(if (night) 0.25f else 0.45f) }
        if (night) {
            // инверсия яркости + лёгкий синий оттенок под тёмно-синюю тему
            matrix.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -0.85f, 0f, 0f, 0f, 225f,
                        0f, -0.85f, 0f, 0f, 230f,
                        0f, 0f, -0.8f, 0f, 245f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
        map.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(matrix))
        map.invalidate()
    }

    fun enableMyLocation() {
        if (myLocationOverlay != null) return
        val provider = GpsMyLocationProvider(activity).apply { addLocationSource(LocationManager.NETWORK_PROVIDER) }
        val overlay = MyLocationNewOverlay(provider, map)
        val me = meBitmap()
        overlay.setPersonIcon(me)
        overlay.setDirectionIcon(me)
        overlay.setPersonAnchor(0.5f, 0.5f)
        overlay.setDirectionAnchor(0.5f, 0.5f)
        overlay.enableMyLocation()
        overlay.runOnFirstFix { activity.runOnUiThread { listener.onFirstFix() } }
        map.overlays.add(overlay)
        myLocationOverlay = overlay
    }

    fun centerOn(point: GeoPoint, minZoom: Double = 15.0) {
        if (map.zoomLevelDouble < minZoom) map.controller.setZoom(minZoom + 1)
        map.controller.animateTo(point)
    }

    // ---------- Пункт назначения ----------

    private fun meBitmap(): Bitmap {
        val d = ContextCompat.getDrawable(activity, R.drawable.marker_me)!!
        val size = (40 * activity.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bitmap))
        return bitmap
    }

    /** Остановка: красная метка, пунктирный круг зоны будильника и подпись «Зона будильника 500 м». */
    fun showDestination(place: Place?, radiusMeters: Double, label: String, moveMap: Boolean) {
        destPlace = place
        if (place == null) {
            listOfNotNull(destMarker, destCircle, zoneLabel, youLabel, guideLine).forEach { map.overlays.remove(it) }
            destMarker = null
            destCircle = null
            zoneLabel = null
            youLabel = null
            guideLine = null
            map.invalidate()
            return
        }
        val point = GeoPoint(place.lat, place.lon)
        val marker = destMarker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            it.infoWindow = null
            map.overlays.add(it)
            destMarker = it
        }
        marker.icon = ContextCompat.getDrawable(activity, if (tripMode) R.drawable.marker_dest_alarm else R.drawable.marker_dest)
        marker.position = point
        setRadius(place, radiusMeters, label)
        updateGuide(myLocation)
        if (moveMap) centerOn(point, 14.0)
        map.invalidate()
    }

    fun setRadius(place: Place, radiusMeters: Double, label: String) {
        val circle = destCircle ?: Polygon(map).also {
            it.fillPaint.color = Color.argb(34, 239, 68, 68)
            it.outlinePaint.color = Color.rgb(239, 68, 68)
            it.outlinePaint.strokeWidth = 5f
            it.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
            it.infoWindow = null
            it.setOnClickListener { _, _, _ -> false }
            map.overlays.add(1, it) // над обработчиком нажатий, под остановками и метками
            destCircle = it
        }
        circle.points = Polygon.pointsAsCircle(GeoPoint(place.lat, place.lon), radiusMeters)

        val zone = zoneLabel ?: Marker(map).also {
            it.infoWindow = null
            it.setOnMarkerClickListener { _, _ -> true }
            map.overlays.add(it)
            zoneLabel = it
        }
        zone.icon = MapPills.make(activity, label, dark = false, dotColor = ContextCompat.getColor(activity, R.color.warn))
        zone.position = GeoPoint(place.lat, place.lon)
        zone.setAnchor(Marker.ANCHOR_CENTER, -0.9f) // под меткой остановки
        map.invalidate()
    }

    /** Линия маршрута по дорогам. null — убрать линию (нет сети, нет маршрута, нет точки). */
    fun showRoute(route: Routing.Route?) {
        if (route == null) {
            routeLine?.let { map.overlays.remove(it) }
            routeLine = null
            routeMeters = null
            map.invalidate()
            return
        }
        routeMeters = route.meters
        val line = routeLine ?: Polyline(map).also {
            it.outlinePaint.color = ContextCompat.getColor(activity, R.color.accent)
            it.outlinePaint.alpha = 190
            it.outlinePaint.strokeWidth = 14f
            it.outlinePaint.strokeCap = Paint.Cap.ROUND
            it.outlinePaint.strokeJoin = Paint.Join.ROUND
            it.infoWindow = null
            map.overlays.add(1, it)
            routeLine = it
        }
        line.setPoints(route.points)
        // пунктир по прямой рядом с маршрутом не нужен
        guideLine?.let { map.overlays.remove(it) }
        guideLine = null
        map.invalidate()
    }

    /** Пунктир от «меня» до остановки и подпись «Вы · 1,2 км» — пока поездка не начата. */
    fun updateGuide(me: GeoPoint?) {
        val dest = destPlace
        if (dest == null || me == null || tripMode) {
            listOfNotNull(youLabel, guideLine).forEach { map.overlays.remove(it) }
            youLabel = null
            guideLine = null
            if (dest == null) showRoute(null)
            map.invalidate()
            return
        }
        if (routeLine == null) {
            val line = guideLine ?: Polyline(map).also {
                it.outlinePaint.color = ContextCompat.getColor(activity, R.color.accent)
                it.outlinePaint.strokeWidth = 8f
                it.outlinePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 16f), 0f)
                it.infoWindow = null
                map.overlays.add(1, it)
                guideLine = it
            }
            line.setPoints(listOf(me, GeoPoint(dest.lat, dest.lon)))
        }

        val you = youLabel ?: Marker(map).also {
            it.infoWindow = null
            it.setOnMarkerClickListener { _, _ -> true }
            map.overlays.add(it)
            youLabel = it
        }
        val road = routeMeters?.let { Geo.formatDistance(activity, it) }
        val label = if (road != null) {
            activity.getString(R.string.you_away_road, road)
        } else {
            val km = Geo.formatDistance(activity, Geo.distance(me.latitude, me.longitude, dest.lat, dest.lon))
            activity.getString(R.string.you_away, km)
        }
        you.icon = MapPills.make(activity, label, dark = true)
        you.position = me
        you.setAnchor(0.5f, 1.6f) // над синей точкой
        map.invalidate()
    }

    /** Во время поездки метка превращается в будильник, а пройденный путь рисуется линией. */
    fun setTripMode(active: Boolean) {
        if (tripMode == active) return
        tripMode = active
        destMarker?.icon = ContextCompat.getDrawable(activity, if (active) R.drawable.marker_dest_alarm else R.drawable.marker_dest)
        if (active) {
            updateGuide(null)
        } else {
            track?.let { map.overlays.remove(it) }
            track = null
            updateGuide(myLocation)
        }
        map.invalidate()
    }

    fun addTrackPoint(lat: Double, lon: Double) {
        val line = track ?: Polyline(map).also {
            it.outlinePaint.color = ContextCompat.getColor(activity, R.color.secondary)
            it.outlinePaint.strokeWidth = 12f
            it.outlinePaint.strokeCap = Paint.Cap.ROUND
            it.infoWindow = null
            map.overlays.add(1, it)
            track = it
        }
        val last = line.actualPoints.lastOrNull()
        if (last == null || Geo.distance(last.latitude, last.longitude, lat, lon) > 5) line.addPoint(GeoPoint(lat, lon))
        map.invalidate()
    }

    fun zoomToDestination() {
        destCircle?.let { map.zoomToBoundingBox(it.bounds.increaseByScale(1.5f), true) }
    }

    /** Точка «я» во время поездки (в симуляции GPS-слой её не покажет). */
    fun showMe(lat: Double?, lon: Double?) {
        if (lat == null || lon == null) {
            meMarker?.let { map.overlays.remove(it) }
            meMarker = null
        } else {
            val marker = meMarker ?: Marker(map).also {
                it.icon = ContextCompat.getDrawable(activity, R.drawable.marker_me)
                it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                it.infoWindow = null
                map.overlays.add(it)
                meMarker = it
            }
            marker.position = GeoPoint(lat, lon)
        }
        map.invalidate()
    }

    // ---------- Остановки ----------

    private fun scheduleStopsUpdate(): Boolean {
        handler.removeCallbacks(updateStopsRunnable)
        handler.postDelayed(updateStopsRunnable, 400)
        return false
    }

    fun updateStops() {
        stopsFolder.items.clear()
        if (map.zoomLevelDouble >= STOPS_MIN_ZOOM) {
            val b = map.boundingBox
            val icons = mapOf(
                StopKind.BUS to ContextCompat.getDrawable(activity, R.drawable.ic_stop),
                StopKind.TRAM to ContextCompat.getDrawable(activity, R.drawable.ic_station),
                StopKind.RAIL to ContextCompat.getDrawable(activity, R.drawable.ic_station),
                StopKind.FERRY to ContextCompat.getDrawable(activity, R.drawable.ic_stop),
            )
            Stops.inBox(activity, b.latSouth, b.lonWest, b.latNorth, b.lonEast, 300).forEach { stop ->
                stopsFolder.add(Marker(map).apply {
                    position = GeoPoint(stop.lat, stop.lon)
                    icon = icons[stop.kind]
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        if (interactive) listener.onStopTap(stop)
                        true
                    }
                })
            }
            // догружаем остановки этого места из OpenStreetMap
            Stops.ensure(activity, b.latSouth, b.lonWest, b.latNorth, b.lonEast) {
                activity.runOnUiThread { if (!activity.isDestroyed) updateStopsNow() }
            }
        }
        map.invalidate()
    }

    private fun updateStopsNow() {
        handler.removeCallbacks(updateStopsRunnable)
        updateStops()
    }
}
