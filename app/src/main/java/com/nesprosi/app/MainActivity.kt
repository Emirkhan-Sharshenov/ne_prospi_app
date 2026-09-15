package com.nesprosi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration as UiConfiguration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_QUICK_TRIP = "com.nesprosi.app.QUICK_TRIP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val SNAP_METERS = 80.0
        private const val STOPS_MIN_ZOOM = 16.0 // при меньшем масштабе значки закрывают улицы
        private const val STOPS_REFRESH_MS = 30L * 24 * 60 * 60 * 1000
    }

    private lateinit var prefs: Prefs
    private lateinit var map: MapView
    private val handler = Handler(Looper.getMainLooper())
    private var dest: Place? = null
    private var destMarker: Marker? = null
    private var destCircle: Polygon? = null
    private var meMarker: Marker? = null
    private var myLocation: MyLocationNewOverlay? = null
    private val stopsFolder = FolderOverlay()
    private var usingOffline: Boolean? = null
    private var pendingSimulate = false

    private lateinit var searchInput: EditText
    private lateinit var results: LinearLayout
    private lateinit var destText: TextView
    private lateinit var saveBtn: Button
    private lateinit var places: LinearLayout
    private lateinit var modeGroup: RadioGroup
    private lateinit var seek: SeekBar
    private lateinit var valueText: TextView
    private lateinit var backupSeek: SeekBar
    private lateinit var backupText: TextView
    private lateinit var phaseText: TextView
    private lateinit var distText: TextView
    private lateinit var etaText: TextView
    private lateinit var speedText: TextView
    private lateinit var accText: TextView
    private lateinit var tripStatus: TextView
    private lateinit var startBtn: Button
    private lateinit var simBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var shareBtn: Button
    private lateinit var batteryBtn: Button

    private val stateListener: (TripService.State) -> Unit = { render(it) }
    private val updateStopsRunnable = Runnable { updateStops() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) {
                enableMyLocation()
                continueStart()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.perm_location_title)
                    .setMessage(R.string.perm_location_msg)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "NeProspi/1.1 ($packageName)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            // просмотренные места карты остаются в кэше и видны без интернета
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        }
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        prefs = Prefs(this)

        searchInput = findViewById(R.id.searchInput)
        results = findViewById(R.id.results)
        destText = findViewById(R.id.destText)
        saveBtn = findViewById(R.id.saveBtn)
        places = findViewById(R.id.places)
        modeGroup = findViewById(R.id.modeGroup)
        seek = findViewById(R.id.seek)
        valueText = findViewById(R.id.valueText)
        backupSeek = findViewById(R.id.backupSeek)
        backupText = findViewById(R.id.backupText)
        phaseText = findViewById(R.id.phaseText)
        distText = findViewById(R.id.distText)
        etaText = findViewById(R.id.etaText)
        speedText = findViewById(R.id.speedText)
        accText = findViewById(R.id.accText)
        tripStatus = findViewById(R.id.tripStatus)
        startBtn = findViewById(R.id.startBtn)
        simBtn = findViewById(R.id.simBtn)
        stopBtn = findViewById(R.id.stopBtn)
        shareBtn = findViewById(R.id.shareBtn)
        batteryBtn = findViewById(R.id.batteryBtn)

        setupMap()
        setupSearch()
        setupSettings()
        renderPlaces()
        updateShortcuts()

        findViewById<View>(R.id.historyBtn).setOnClickListener { showHistory() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        saveBtn.setOnClickListener { savePlace() }
        startBtn.setOnClickListener { requestStart(simulate = false) }
        simBtn.setOnClickListener { requestStart(simulate = true) }
        stopBtn.setOnClickListener { TripService.stop(this) }
        shareBtn.setOnClickListener { shareTrip() }
        batteryBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }

        warmUpStops()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        TripService.addListener(stateListener)
        render(TripService.state)
    }

    override fun onResume() {
        super.onResume()
        applyTileSource()
        map.onResume()
        if (hasLocationPermission()) enableMyLocation()
        myLocation?.enableMyLocation()
        updateButtons()
    }

    override fun onPause() {
        prefs.mapLat = map.mapCenter.latitude
        prefs.mapLon = map.mapCenter.longitude
        prefs.mapZoom = map.zoomLevelDouble
        myLocation?.disableMyLocation()
        map.onPause()
        super.onPause()
    }

    override fun onStop() {
        TripService.removeListener(stateListener)
        super.onStop()
    }

    /** Ярлык «Домой» на главном экране запускает поездку сразу. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_QUICK_TRIP || TripService.state.active) return
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val place = Place(name, intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
        intent.action = null
        setDest(place)
        requestStart(simulate = false)
    }

    // ---------- Карта ----------

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(prefs.mapZoom)
        map.controller.setCenter(GeoPoint(prefs.mapLat, prefs.mapLon))
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!TripService.state.active) onMapTap(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint) = false
        }))
        map.overlays.add(stopsFolder)
        map.overlays.add(CopyrightOverlay(this))
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?) = scheduleStopsUpdate()
            override fun onZoom(event: ZoomEvent?) = scheduleStopsUpdate()
        })
    }

    /** Онлайн-карта OpenStreetMap или скачанная офлайн-карта (если нет интернета или так выбрано). */
    private fun applyTileSource() {
        val offline = OfflineMap.isReady(this) && (prefs.offlineAlways || !Net.isOnline(this))
        if (offline != usingOffline) {
            if (offline) {
                map.setTileProvider(OfflineMap.tileProvider(this))
            } else {
                map.setTileProvider(MapTileProviderBasic(applicationContext, TileSourceFactory.MAPNIK))
            }
            usingOffline = offline
        }
        // в тёмной теме затемняем онлайн-карту, чтобы она не слепила ночью
        val night = (resources.configuration.uiMode and UiConfiguration.UI_MODE_NIGHT_MASK) == UiConfiguration.UI_MODE_NIGHT_YES
        map.overlayManager.tilesOverlay.setColorFilter(if (night && !offline) TilesOverlay.INVERT_COLORS else null)
        map.invalidate()
    }

    private fun enableMyLocation() {
        if (myLocation != null) return
        val provider = GpsMyLocationProvider(this).apply { addLocationSource(LocationManager.NETWORK_PROVIDER) }
        val overlay = MyLocationNewOverlay(provider, map)
        overlay.enableMyLocation()
        overlay.runOnFirstFix {
            runOnUiThread {
                val me = overlay.myLocation
                if (dest == null && me != null) {
                    map.controller.setZoom(15.0)
                    map.controller.animateTo(me)
                }
            }
        }
        map.overlays.add(overlay)
        myLocation = overlay
    }

    private fun onMapTap(p: GeoPoint) {
        val stop = Stops.nearest(this, p.latitude, p.longitude, SNAP_METERS)
        if (stop != null) {
            chooseStop(stop)
        } else {
            val place = Place(getString(R.string.point_on_map), p.latitude, p.longitude)
            setDest(place, moveMap = false)
            resolveName(place) { Net.reverse(this, place.lat, place.lon) }
        }
    }

    /** Выбор остановки: название из OpenStreetMap, а если его нет — «Остановка · улица». */
    private fun chooseStop(stop: Stop) {
        val name = stop.name(this)
        if (name.isNotBlank()) {
            setDest(Place(name, stop.lat, stop.lon), moveMap = false)
        } else {
            val place = Place(getString(R.string.stop_unnamed), stop.lat, stop.lon)
            setDest(place, moveMap = false)
            resolveName(place) { Net.street(this, place.lat, place.lon)?.let { getString(R.string.stop_near, it) } }
        }
    }

    /** Узнаёт название точки в фоне и подписывает её, если пользователь не выбрал другую. */
    private fun resolveName(place: Place, fetch: () -> String?) {
        destText.text = getString(R.string.resolving_address)
        Thread {
            val name = runCatching(fetch).getOrNull()
            runOnUiThread {
                if (isDestroyed || dest != place) return@runOnUiThread
                if (name != null && !TripService.state.active) setDest(place.copy(name = name), moveMap = false)
                else destText.text = getString(R.string.dest_label, place.name)
            }
        }.start()
    }

    private fun setDest(place: Place, moveMap: Boolean = true) {
        dest = place
        val point = GeoPoint(place.lat, place.lon)
        val marker = destMarker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(it)
            destMarker = it
        }
        marker.position = point
        marker.title = place.name
        updateCircle()
        if (moveMap) {
            if (map.zoomLevelDouble < 14) map.controller.setZoom(15.0)
            map.controller.animateTo(point)
        }
        destText.text = getString(R.string.dest_label, place.name)
        saveBtn.visibility = View.VISIBLE
        results.removeAllViews()
        updateButtons()
        map.invalidate()
    }

    private fun updateCircle() {
        val d = dest ?: return
        val radius = if (prefs.mode == Prefs.MODE_DIST) prefs.distMeters.toDouble() else 150.0
        val circle = destCircle ?: Polygon(map).also {
            it.fillPaint.color = Color.argb(40, 255, 59, 59)
            it.outlinePaint.color = Color.rgb(255, 59, 59)
            it.outlinePaint.strokeWidth = 4f
            it.infoWindow = null
            it.setOnClickListener { _, _, _ -> false }
            map.overlays.add(1, it) // над обработчиком нажатий, под остановками и метками
            destCircle = it
        }
        circle.points = Polygon.pointsAsCircle(GeoPoint(d.lat, d.lon), radius)
        map.invalidate()
    }

    private fun showMe(lat: Double?, lon: Double?) {
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

    private fun warmUpStops() {
        Thread {
            Stops.all(applicationContext)
            runOnUiThread { if (!isDestroyed) updateStops() }
            // встроенный список свежий; дальше обновляем раз в месяц
            if (prefs.stopsUpdatedAt == 0L) prefs.stopsUpdatedAt = System.currentTimeMillis()
            if (System.currentTimeMillis() - prefs.stopsUpdatedAt > STOPS_REFRESH_MS && Net.isOnline(this)) {
                prefs.stopsUpdatedAt = System.currentTimeMillis()
                runCatching { Net.refreshStops(applicationContext) }
            }
        }.start()
    }

    private fun scheduleStopsUpdate(): Boolean {
        handler.removeCallbacks(updateStopsRunnable)
        handler.postDelayed(updateStopsRunnable, 300)
        return false
    }

    private fun updateStops() {
        stopsFolder.items.clear()
        if (map.zoomLevelDouble >= STOPS_MIN_ZOOM) {
            val icon = ContextCompat.getDrawable(this, R.drawable.ic_stop)
            val b = map.boundingBox
            Stops.inBox(this, b.latSouth, b.lonWest, b.latNorth, b.lonEast, 250).forEach { stop ->
                stopsFolder.add(Marker(map).apply {
                    position = GeoPoint(stop.lat, stop.lon)
                    this.icon = icon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    infoWindow = null
                    setOnMarkerClickListener { _, _ ->
                        if (!TripService.state.active) chooseStop(stop)
                        true
                    }
                })
            }
        }
        map.invalidate()
    }

    // ---------- Поиск ----------

    private fun setupSearch() {
        findViewById<Button>(R.id.searchBtn).setOnClickListener { search() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
    }

    private fun search() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
        results.removeAllViews()
        addRow(results, getString(R.string.searching))
        // сначала ищем в Кыргызстане, ближе к текущему месту на карте; если пусто — по всему миру
        val box = map.boundingBox
        val viewbox = "&viewbox=${box.lonWest},${box.latNorth},${box.lonEast},${box.latSouth}"
        Thread {
            val found = runCatching {
                Net.search(this, q, "$viewbox&countrycodes=kg").ifEmpty { Net.search(this, q, viewbox) }
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                results.removeAllViews()
                when {
                    found == null -> addRow(results, getString(R.string.search_unavailable))
                    found.isEmpty() -> addRow(results, getString(R.string.nothing_found))
                    else -> found.forEach { (fullAddress, place) ->
                        addRow(results, fullAddress, onClick = { setDest(place) })
                    }
                }
            }
        }.start()
    }

    // ---------- Сохранённые места и ярлыки ----------

    private fun renderPlaces() {
        places.removeAllViews()
        prefs.places.forEachIndexed { i, p ->
            addRow(places, "⭐ ${p.name}",
                onClick = { if (!TripService.state.active) setDest(p) },
                onLongClick = { placeActions(i, p) })
        }
    }

    private fun placeActions(index: Int, p: Place) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.place_actions_title, p.name))
            .setItems(arrayOf(getString(R.string.place_pin), getString(R.string.delete))) { _, which ->
                if (which == 0) pinShortcut(p)
                else AlertDialog.Builder(this)
                    .setMessage(getString(R.string.place_delete_confirm, p.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        prefs.places = prefs.places.toMutableList().also { it.removeAt(index) }
                        renderPlaces()
                        updateShortcuts()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun savePlace() {
        val d = dest ?: return
        val input = EditText(this).apply { setText(d.name); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this)
            .setTitle(R.string.place_name_title)
            .setMessage(R.string.place_name_hint)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { d.name }
                prefs.places = prefs.places + d.copy(name = name)
                setDest(d.copy(name = name), moveMap = false)
                renderPlaces()
                updateShortcuts()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shortcutFor(p: Place): ShortcutInfoCompat {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_QUICK_TRIP)
            .putExtra(EXTRA_NAME, p.name)
            .putExtra(EXTRA_LAT, p.lat)
            .putExtra(EXTRA_LON, p.lon)
        return ShortcutInfoCompat.Builder(this, "place_" + "${p.name}|${p.lat}|${p.lon}".hashCode())
            .setShortLabel(p.name.take(25))
            .setLongLabel(getString(R.string.shortcut_long_label, p.name).take(45))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
    }

    /** Долгое нажатие на значок приложения показывает сохранённые места. */
    private fun updateShortcuts() {
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(this, prefs.places.take(4).map { shortcutFor(it) }) }
    }

    private fun pinShortcut(p: Place) {
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            ShortcutManagerCompat.requestPinShortcut(this, shortcutFor(p), null)
        } else {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    private fun addRow(container: LinearLayout, text: String, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
        val row = layoutInflater.inflate(R.layout.row, container, false) as TextView
        row.text = text
        onClick?.let { cb -> row.setOnClickListener { cb() } }
        onLongClick?.let { cb -> row.setOnLongClickListener { cb(); true } }
        container.addView(row)
    }

    // ---------- Когда будить ----------

    private fun setupSettings() {
        modeGroup.check(if (prefs.mode == Prefs.MODE_DIST) R.id.modeDist else R.id.modeTime)
        modeGroup.setOnCheckedChangeListener { _, id ->
            prefs.mode = if (id == R.id.modeDist) Prefs.MODE_DIST else Prefs.MODE_TIME
            applyMode()
        }
        seek.setOnSeekBarChangeListener(seekListener { progress ->
            if (prefs.mode == Prefs.MODE_DIST) prefs.distMeters = 100 + progress * 50
            else prefs.minutes = 1 + progress
            updateValueText()
            updateCircle()
        })
        applyMode()

        backupSeek.max = 24 // 0…120 минут шагом 5
        backupSeek.progress = prefs.backupMinutes / 5
        backupSeek.setOnSeekBarChangeListener(seekListener { progress ->
            prefs.backupMinutes = progress * 5
            updateBackupText()
        })
        updateBackupText()
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun applyMode() {
        if (prefs.mode == Prefs.MODE_DIST) {
            seek.max = (3000 - 100) / 50
            seek.progress = (prefs.distMeters - 100) / 50
        } else {
            seek.max = 14
            seek.progress = prefs.minutes - 1
        }
        updateValueText()
        updateCircle()
    }

    private fun updateValueText() {
        valueText.text = if (prefs.mode == Prefs.MODE_DIST) Geo.formatDistance(this, prefs.distMeters.toDouble())
        else getString(R.string.minutes_value, prefs.minutes)
    }

    private fun updateBackupText() {
        backupText.text = if (prefs.backupMinutes == 0) getString(R.string.backup_off)
        else getString(R.string.minutes_value, prefs.backupMinutes)
    }

    // ---------- Запуск поездки ----------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestStart(simulate: Boolean) {
        if (dest == null) return
        pendingSimulate = simulate
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) continueStart() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun continueStart(batteryChecked: Boolean = false) {
        if (!hasLocationPermission()) return

        val lm = getSystemService(LocationManager::class.java)
        if (!pendingSimulate && Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_off_title)
                .setMessage(R.string.location_off_msg)
                .setPositiveButton(R.string.enable) { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val (level, charging) = batteryLevel(this)
        if (!batteryChecked && level in 0 until 15 && !charging) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.battery_low_title, level))
                .setMessage(R.string.battery_low_msg)
                .setPositiveButton(R.string.start_anyway_2) { _, _ -> continueStart(batteryChecked = true) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.notif_denied, Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.fsi_title)
                .setMessage(R.string.fsi_msg)
                .setPositiveButton(R.string.allow) { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                }
                .setNegativeButton(R.string.start_anyway) { _, _ -> launchTrip() }
                .show()
            return
        }
        launchTrip()
    }

    private fun launchTrip() {
        val d = dest ?: return
        TripService.start(this, d, pendingSimulate)
        destCircle?.let { map.zoomToBoundingBox(it.bounds.increaseByScale(1.5f), true) }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun shareTrip() {
        val s = TripService.state
        val d = s.dest ?: return
        val text = if (s.lat != null && s.lon != null) getString(R.string.share_text, d.name, Geo.mapLink(s.lat, s.lon))
        else getString(R.string.share_text_no_loc, d.name, Geo.mapLink(d.lat, d.lon))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    // ---------- История ----------

    private fun showHistory() {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val items = prefs.history.map { r ->
            val result = getString(
                when (r.result) {
                    TripRecord.ARRIVED -> R.string.result_arrived
                    TripRecord.BACKUP -> R.string.result_backup
                    else -> R.string.result_stopped
                }
            )
            val minutes = ((r.endedAt - r.startedAt) / 60_000).toInt()
            val line = getString(R.string.history_item, df.format(Date(r.startedAt)), r.dest, result, minutes)
            if (r.simulated) getString(R.string.history_simulated, line) else line
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.history_title)
        if (items.isEmpty()) dialog.setMessage(R.string.history_empty)
        else dialog.setItems(items.toTypedArray(), null)
            .setNeutralButton(R.string.history_clear) { _, _ -> prefs.history = emptyList() }
        dialog.setPositiveButton(R.string.ok, null).show()
    }

    // ---------- Состояние ----------

    private fun render(s: TripService.State) {
        if (s.active && s.dest != null && s.dest != dest) setDest(s.dest, moveMap = false)
        distText.text = s.distance?.let { Geo.formatDistance(this, it) } ?: "—"
        etaText.text = Geo.formatEta(this, s.etaSec)
        speedText.text = s.speed?.let { getString(R.string.speed_value, (it * 3.6).roundToInt()) } ?: "—"
        accText.text = s.accuracy?.let { getString(R.string.accuracy_value, it.roundToInt()) } ?: "—"
        phaseText.setText(
            when {
                !s.active -> R.string.phase_idle
                s.gpsLost -> R.string.phase_gps_lost
                s.phase == TripService.Phase.WAITING_GPS -> R.string.phase_waiting
                s.phase == TripService.Phase.RIDING -> R.string.phase_riding
                s.phase == TripService.Phase.WARNED -> R.string.phase_warned
                s.phase == TripService.Phase.CHECK -> R.string.phase_check
                else -> R.string.phase_alarm
            }
        )
        val badge = when {
            !s.active -> R.color.muted
            s.gpsLost || s.phase == TripService.Phase.WARNED -> R.color.warn
            s.phase == TripService.Phase.ALARM -> R.color.danger
            else -> R.color.ok
        }
        phaseText.background.mutate().setTint(ContextCompat.getColor(this, badge))

        val status = mutableListOf<String>()
        if (s.active && s.backupAt != null) {
            status += getString(R.string.backup_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.backupAt)))
        }
        if (s.active && s.simulated) status += getString(R.string.simulation_note)
        tripStatus.text = status.joinToString("\n")
        tripStatus.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE

        showMe(s.lat.takeIf { s.active }, s.lon.takeIf { s.active })
        updateButtons()
    }

    private fun updateButtons() {
        val active = TripService.state.active
        startBtn.visibility = if (active) View.GONE else View.VISIBLE
        simBtn.visibility = if (active) View.GONE else View.VISIBLE
        stopBtn.visibility = if (active) View.VISIBLE else View.GONE
        shareBtn.visibility = if (active) View.VISIBLE else View.GONE
        startBtn.isEnabled = dest != null
        simBtn.isEnabled = dest != null
        seek.isEnabled = !active
        backupSeek.isEnabled = !active
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = !active
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryBtn.visibility = if (ignoring) View.GONE else View.VISIBLE
    }
}
