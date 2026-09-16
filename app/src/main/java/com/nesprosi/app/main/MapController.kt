package com.nesprosi.app.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration as UiConfiguration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nesprosi.app.Net
import com.nesprosi.app.OfflineMap
import com.nesprosi.app.Place
import com.nesprosi.app.Prefs
import com.nesprosi.app.R
import com.nesprosi.app.Region
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
        // в тёмной теме затемняем онлайн-карту, чтобы она не слепила ночью
        val night = (activity.resources.configuration.uiMode and UiConfiguration.UI_MODE_NIGHT_MASK) ==
            UiConfiguration.UI_MODE_NIGHT_YES
        map.overlayManager.tilesOverlay.setColorFilter(if (night && !offline) TilesOverlay.INVERT_COLORS else null)
        map.invalidate()
    }

    fun enableMyLocation() {
        if (myLocationOverlay != null) return
        val provider = GpsMyLocationProvider(activity).apply { addLocationSource(LocationManager.NETWORK_PROVIDER) }
        val overlay = MyLocationNewOverlay(provider, map)
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

    fun showDestination(place: Place?, radiusMeters: Double, moveMap: Boolean) {
        if (place == null) {
            destMarker?.let { map.overlays.remove(it) }
            destCircle?.let { map.overlays.remove(it) }
            destMarker = null
            destCircle = null
            map.invalidate()
            return
        }
        val point = GeoPoint(place.lat, place.lon)
        val marker = destMarker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.infoWindow = null
            map.overlays.add(it)
            destMarker = it
        }
        marker.position = point
        setRadius(place, radiusMeters)
        if (moveMap) centerOn(point, 14.0)
        map.invalidate()
    }

    fun setRadius(place: Place, radiusMeters: Double) {
        val circle = destCircle ?: Polygon(map).also {
            it.fillPaint.color = Color.argb(40, 255, 59, 59)
            it.outlinePaint.color = Color.rgb(255, 59, 59)
            it.outlinePaint.strokeWidth = 4f
            it.infoWindow = null
            it.setOnClickListener { _, _, _ -> false }
            map.overlays.add(1, it) // над обработчиком нажатий, под остановками и метками
            destCircle = it
        }
        circle.points = Polygon.pointsAsCircle(GeoPoint(place.lat, place.lon), radiusMeters)
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
                it.icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.rgb(79, 140, 255))
                    setStroke(5, Color.WHITE)
                    setSize(44, 44)
                }
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
