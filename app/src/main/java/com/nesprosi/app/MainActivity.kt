package com.nesprosi.app

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
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

/**
 * Главный экран: карта на весь экран, поиск сверху и нижняя панель в три шага —
 * «Куда едем?» → «Когда разбудить» → поездка с прогрессом.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_QUICK_TRIP = "com.nesprosi.app.QUICK_TRIP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val SNAP_METERS = 80.0
        private const val STOPS_MIN_ZOOM = 16.0 // при меньшем масштабе значки закрывают улицы
        private const val STOPS_REFRESH_MS = 30L * 24 * 60 * 60 * 1000
        private val DIST_OPTIONS = listOf(200, 300, 500, 800, 1000, 2000)
        private val TIME_OPTIONS = listOf(1, 2, 3, 5, 10)
        private val BACKUP_OPTIONS = listOf(0, 15, 30, 45, 60, 90)

        fun quickTripIntent(ctx: Context, p: Place): Intent = Intent(ctx, MainActivity::class.java)
            .setAction(ACTION_QUICK_TRIP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_NAME, p.name)
            .putExtra(EXTRA_LAT, p.lat)
            .putExtra(EXTRA_LON, p.lon)

        fun quickTripIntent(ctx: Context, p: Place, requestCode: Int): PendingIntent = PendingIntent.getActivity(
            ctx, requestCode, quickTripIntent(ctx, p),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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

    private lateinit var sheet: BottomSheetBehavior<View>
    private lateinit var searchInput: EditText
    private lateinit var resultsCard: MaterialCardView
    private lateinit var results: LinearLayout
    private lateinit var setupBanner: MaterialCardView
    private lateinit var idleSection: View
    private lateinit var destSection: View
    private lateinit var tripSection: View
    private lateinit var placesChips: ChipGroup
    private lateinit var recentChips: ChipGroup
    private lateinit var destTitle: TextView
    private lateinit var destSubtitle: TextView
    private lateinit var saveBtn: Button
    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var valueChips: ChipGroup
    private lateinit var backupChips: ChipGroup
    private lateinit var tripDest: TextView
    private lateinit var phaseText: TextView
    private lateinit var distText: TextView
    private lateinit var etaText: TextView
    private lateinit var tripProgress: LinearProgressIndicator
    private lateinit var tripDetails: TextView
    private lateinit var tripStatus: TextView

    private val stateListener: (TripService.State) -> Unit = { render(it) }
    private val updateStopsRunnable = Runnable { updateStops() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) {
                enableMyLocation()
                if (dest != null) continueStart()
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
            userAgentValue = "NeProspi/1.2 ($packageName)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            // просмотренные места карты остаются в кэше и видны без интернета
            tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        }
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        bindViews()
        applyInsets()

        setupMap()
        setupSearch()
        setupWakeOptions()
        renderIdle()
        updateShortcuts()

        findViewById<View>(R.id.historyBtn).setOnClickListener { showHistory() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.myLocationFab).setOnClickListener { centerOnMe() }
        findViewById<View>(R.id.clearDestBtn).setOnClickListener { clearDest() }
        setupBanner.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        saveBtn.setOnClickListener { onSaveClicked() }
        findViewById<View>(R.id.startBtn).setOnClickListener { requestStart(simulate = false) }
        findViewById<View>(R.id.simBtn).setOnClickListener { requestStart(simulate = true) }
        findViewById<View>(R.id.stopBtn).setOnClickListener { TripService.stop(this) }
        findViewById<View>(R.id.shareBtn).setOnClickListener { shareTrip() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    resultsCard.visibility == View.VISIBLE -> hideResults()
                    dest != null && !TripService.state.active -> clearDest()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        warmUpStops()
        if (!prefs.setupShown) startActivity(Intent(this, SetupActivity::class.java))
        handleIntent(intent)
    }

    private fun bindViews() {
        val sheetView = findViewById<View>(R.id.sheet)
        sheet = BottomSheetBehavior.from(sheetView)
        sheet.isFitToContents = true
        sheet.state = BottomSheetBehavior.STATE_EXPANDED
        searchInput = findViewById(R.id.searchInput)
        resultsCard = findViewById(R.id.resultsCard)
        results = findViewById(R.id.results)
        setupBanner = findViewById(R.id.setupBanner)
        idleSection = findViewById(R.id.idleSection)
        destSection = findViewById(R.id.destSection)
        tripSection = findViewById(R.id.tripSection)
        placesChips = findViewById(R.id.placesChips)
        recentChips = findViewById(R.id.recentChips)
        destTitle = findViewById(R.id.destTitle)
        destSubtitle = findViewById(R.id.destSubtitle)
        saveBtn = findViewById(R.id.saveBtn)
        modeToggle = findViewById(R.id.modeToggle)
        valueChips = findViewById(R.id.valueChips)
        backupChips = findViewById(R.id.backupChips)
        tripDest = findViewById(R.id.tripDest)
        phaseText = findViewById(R.id.phaseText)
        distText = findViewById(R.id.distText)
        etaText = findViewById(R.id.etaText)
        tripProgress = findViewById(R.id.tripProgress)
        tripDetails = findViewById(R.id.tripDetails)
        tripStatus = findViewById(R.id.tripStatus)
    }

    /** Карта уходит под строку состояния, а поиск и панель отодвигаются от системных полос. */
    private fun applyInsets() {
        val topBar = findViewById<View>(R.id.topBar)
        val sheetView = findViewById<View>(R.id.sheet)
        val dp = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updatePadding(top = bars.top + (8 * dp).toInt(), left = bars.left + (12 * dp).toInt(), right = bars.right + (12 * dp).toInt())
            sheetView.updatePadding(bottom = bars.bottom + (16 * dp).toInt())
            sheet.maxHeight = (resources.displayMetrics.heightPixels * 0.72).toInt()
            insets
        }
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
        updateSetupBanner()
        if (dest == null && !TripService.state.active) renderIdle()
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

    /** Ярлык или виджет «Домой» запускает поездку сразу. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_QUICK_TRIP || TripService.state.active) return
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val place = Place(name, intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0))
        intent.action = null
        setDest(place)
        requestStart(simulate = false)
    }

    private fun updateSetupBanner() {
        val issues = Reliability.issueCount(this)
        setupBanner.visibility = if (issues > 0 && !TripService.state.active) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.setupBannerText).text = getString(R.string.setup_banner, issues)
    }

    // ---------- Карта ----------

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true
        map.controller.setZoom(prefs.mapZoom)
        map.controller.setCenter(GeoPoint(prefs.mapLat, prefs.mapLon))
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                hideResults()
                if (!TripService.state.active) onMapTap(p)
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

    private fun centerOnMe() {
        if (!hasLocationPermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        enableMyLocation()
        val me = TripService.state.let { s -> if (s.active && s.lat != null && s.lon != null) GeoPoint(s.lat, s.lon) else null }
            ?: myLocation?.myLocation
        if (me != null) {
            if (map.zoomLevelDouble < 15) map.controller.setZoom(16.0)
            map.controller.animateTo(me)
        } else {
            Toast.makeText(this, R.string.phase_waiting, Toast.LENGTH_SHORT).show()
        }
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
        destTitle.text = getString(R.string.resolving_address)
        Thread {
            val name = runCatching(fetch).getOrNull()
            runOnUiThread {
                if (isDestroyed || dest != place) return@runOnUiThread
                if (name != null && !TripService.state.active) setDest(place.copy(name = name), moveMap = false)
                else destTitle.text = place.name
            }
        }.start()
    }

    private fun setDest(place: Place, moveMap: Boolean = true) {
        dest = place
        val point = GeoPoint(place.lat, place.lon)
        val marker = destMarker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.infoWindow = null
            map.overlays.add(it)
            destMarker = it
        }
        marker.position = point
        updateCircle()
        if (moveMap) {
            if (map.zoomLevelDouble < 14) map.controller.setZoom(15.0)
            map.controller.animateTo(point)
        }
        hideResults()
        if (!TripService.state.active) showDestSection()
        map.invalidate()
    }

    private fun clearDest() {
        dest = null
        destMarker?.let { map.overlays.remove(it) }
        destCircle?.let { map.overlays.remove(it) }
        destMarker = null
        destCircle = null
        map.invalidate()
        renderIdle()
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
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }
    }

    private fun hideResults() {
        resultsCard.visibility = View.GONE
        results.removeAllViews()
        searchInput.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun search() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchInput.windowToken, 0)
        results.removeAllViews()
        resultsCard.visibility = View.VISIBLE
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
                        addRow(results, fullAddress, onClick = {
                            searchInput.setText("")
                            setDest(place)
                        })
                    }
                }
            }
        }.start()
    }

    private fun addRow(container: LinearLayout, text: String, onClick: (() -> Unit)? = null) {
        val row = layoutInflater.inflate(R.layout.row, container, false) as TextView
        row.text = text
        onClick?.let { cb -> row.setOnClickListener { cb() } }
        container.addView(row)
    }

    // ---------- Панель: куда едем ----------

    private fun showSection(section: View) {
        for (s in listOf(idleSection, destSection, tripSection)) s.visibility = if (s == section) View.VISIBLE else View.GONE
        sheet.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun renderIdle() {
        if (TripService.state.active) return
        showSection(idleSection)
        placesChips.removeAllViews()
        val saved = prefs.places
        saved.forEachIndexed { i, p ->
            placeChip(placesChips, "⭐ ${p.name}", onClick = { setDest(p) }, onLongClick = { placeActions(i, p) })
        }
        findViewById<View>(R.id.placesTitle).visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE

        recentChips.removeAllViews()
        val recent = prefs.recentPlaces(6)
        recent.forEach { p -> placeChip(recentChips, "🕘 ${p.name}", onClick = { setDest(p) }) }
        findViewById<View>(R.id.recentTitle).visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun placeChip(group: ChipGroup, text: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
        val chip = layoutInflater.inflate(R.layout.chip_place, group, false) as Chip
        chip.text = text
        chip.setOnClickListener { onClick() }
        onLongClick?.let { cb -> chip.setOnLongClickListener { cb(); true } }
        group.addView(chip)
    }

    // ---------- Панель: когда разбудить ----------

    private fun showDestSection() {
        val d = dest ?: return
        showSection(destSection)
        destTitle.text = d.name
        val me = myLocation?.myLocation
        destSubtitle.text = if (me != null) {
            getString(R.string.from_you, Geo.formatDistance(this, Geo.distance(me.latitude, me.longitude, d.lat, d.lon)))
        } else {
            getString(R.string.where_hint_short)
        }
        destSubtitle.visibility = View.VISIBLE
        saveBtn.text = if (savedIndex(d) >= 0) "★" else "☆"
    }

    private fun setupWakeOptions() {
        modeToggle.check(if (prefs.mode == Prefs.MODE_DIST) R.id.modeDist else R.id.modeTime)
        modeToggle.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.mode = if (id == R.id.modeDist) Prefs.MODE_DIST else Prefs.MODE_TIME
            renderValueChips()
            updateCircle()
        }
        renderValueChips()
        renderBackupChips()
    }

    private fun renderValueChips() {
        valueChips.removeAllViews()
        val dist = prefs.mode == Prefs.MODE_DIST
        val current = if (dist) prefs.distMeters else prefs.minutes
        ((if (dist) DIST_OPTIONS else TIME_OPTIONS) + current).distinct().sorted().forEach { v ->
            val label = if (dist) Geo.formatDistance(this, v.toDouble()) else getString(R.string.minutes_value, v)
            choiceChip(valueChips, label, v == current) {
                if (dist) prefs.distMeters = v else prefs.minutes = v
                updateCircle()
            }
        }
    }

    private fun renderBackupChips() {
        backupChips.removeAllViews()
        val current = prefs.backupMinutes
        (BACKUP_OPTIONS + current).distinct().sorted().forEach { v ->
            val label = if (v == 0) getString(R.string.backup_off) else getString(R.string.minutes_value, v)
            choiceChip(backupChips, label, v == current) { prefs.backupMinutes = v }
        }
    }

    private fun choiceChip(group: ChipGroup, text: String, checked: Boolean, onSelect: () -> Unit) {
        val chip = layoutInflater.inflate(R.layout.chip_choice, group, false) as Chip
        chip.id = View.generateViewId()
        chip.text = text
        group.addView(chip)
        chip.isChecked = checked
        chip.setOnClickListener { onSelect() }
    }

    // ---------- Сохранённые места и ярлыки ----------

    private fun savedIndex(p: Place) = prefs.places.indexOfFirst { it.lat == p.lat && it.lon == p.lon }

    private fun onSaveClicked() {
        val d = dest ?: return
        val index = savedIndex(d)
        if (index >= 0) placeActions(index, prefs.places[index]) else savePlace()
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
                        placesChanged()
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
                dest = d.copy(name = name)
                placesChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun placesChanged() {
        updateShortcuts()
        TripWidget.updateAll(this)
        if (dest != null && !TripService.state.active) showDestSection() else renderIdle()
    }

    private fun shortcutFor(p: Place): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(this, "place_" + "${p.name}|${p.lat}|${p.lon}".hashCode())
            .setShortLabel(p.name.take(25))
            .setLongLabel(getString(R.string.shortcut_long_label, p.name).take(45))
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setIntent(quickTripIntent(this, p))
            .build()

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
        val history = prefs.history
        val items = history.map { r ->
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
        if (items.isEmpty()) {
            dialog.setMessage(R.string.history_empty)
        } else {
            // нажатие на поездку — поехать туда снова
            dialog.setItems(items.toTypedArray()) { _, which ->
                val r = history[which]
                if (r.lat != null && r.lon != null && !TripService.state.active) setDest(Place(r.dest, r.lat, r.lon))
            }
            dialog.setNeutralButton(R.string.history_clear) { _, _ ->
                prefs.history = emptyList()
                renderIdle()
            }
        }
        dialog.setPositiveButton(R.string.ok, null).show()
    }

    // ---------- Поездка ----------

    private fun render(s: TripService.State) {
        if (!s.active) {
            showMe(null, null)
            if (tripSection.visibility == View.VISIBLE) {
                // поездка закончилась — возвращаемся к выбору места
                if (dest != null) showDestSection() else renderIdle()
                updateSetupBanner()
            }
            return
        }
        if (s.dest != null && s.dest != dest) setDest(s.dest, moveMap = false)
        if (tripSection.visibility != View.VISIBLE) {
            showSection(tripSection)
            setupBanner.visibility = View.GONE
        }
        tripDest.text = s.dest?.name.orEmpty()
        distText.text = s.distance?.let { Geo.formatDistance(this, it) } ?: "—"
        etaText.text = s.etaSec?.let { getString(R.string.eta_short, Geo.formatEta(this, it)) }.orEmpty()
        val progress = s.progress
        val indeterminate = progress == null
        if (tripProgress.isIndeterminate != indeterminate) {
            // переключать режим можно только у скрытого индикатора
            tripProgress.visibility = View.INVISIBLE
            tripProgress.isIndeterminate = indeterminate
            tripProgress.visibility = View.VISIBLE
        }
        if (progress != null) tripProgress.setProgressCompat((progress * 1000).roundToInt(), true)

        val details = mutableListOf<String>()
        s.speed?.let { details += getString(R.string.speed_value, (it * 3.6).roundToInt()) }
        s.accuracy?.let { details += getString(R.string.accuracy_value_gps, it.roundToInt()) }
        tripDetails.text = details.joinToString("  ·  ")

        phaseText.setText(
            when {
                s.gpsLost -> R.string.phase_gps_lost
                s.phase == TripService.Phase.WAITING_GPS -> R.string.phase_waiting
                s.phase == TripService.Phase.RIDING -> R.string.phase_riding
                s.phase == TripService.Phase.WARNED -> R.string.phase_warned
                s.phase == TripService.Phase.CHECK -> R.string.phase_check
                else -> R.string.phase_alarm
            }
        )
        val badge = when {
            s.gpsLost || s.phase == TripService.Phase.WARNED -> R.color.warn
            s.phase == TripService.Phase.ALARM -> R.color.danger
            else -> R.color.ok
        }
        phaseText.background.mutate().setTint(ContextCompat.getColor(this, badge))

        val status = mutableListOf<String>()
        s.backupAt?.let { status += getString(R.string.backup_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))) }
        if (s.simulated) status += getString(R.string.simulation_note)
        tripStatus.text = status.joinToString("\n")
        tripStatus.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE

        showMe(s.lat, s.lon)
    }
}
