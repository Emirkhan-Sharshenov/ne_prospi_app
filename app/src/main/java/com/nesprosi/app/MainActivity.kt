package com.nesprosi.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.nesprosi.app.main.DestinationPanel
import com.nesprosi.app.main.IdlePanel
import com.nesprosi.app.main.MapController
import com.nesprosi.app.main.PlaceActions
import com.nesprosi.app.main.SearchPanel
import com.nesprosi.app.main.Sheet
import com.nesprosi.app.main.TripLauncher
import com.nesprosi.app.main.TripPanel
import org.osmdroid.util.GeoPoint

/**
 * Главный экран. Сам по себе только связывает части:
 * [MapController] — карта, [SearchPanel] — поиск, [IdlePanel]/[DestinationPanel]/[TripPanel] — три шага
 * нижней панели, [TripLauncher] — запуск поездки, [PlaceActions] — места, ярлыки и история.
 */
class MainActivity : AppCompatActivity(), MapController.Listener {

    companion object {
        const val ACTION_QUICK_TRIP = "com.nesprosi.app.QUICK_TRIP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val SNAP_METERS = 80.0

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
    private lateinit var mapController: MapController
    private lateinit var search: SearchPanel
    private lateinit var sheet: Sheet
    private lateinit var idle: IdlePanel
    private lateinit var destination: DestinationPanel
    private lateinit var trip: TripPanel
    private lateinit var places: PlaceActions
    private lateinit var setupBanner: View

    private val launcher = TripLauncher(
        this,
        onLocationGranted = { mapController.enableMyLocation() },
        onStarted = { mapController.zoomToDestination() },
    )

    /** Выбранный пункт назначения. */
    private var dest: Place? = null
    private val stateListener: (TripService.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapController.configure(this)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        mapController = MapController(this, findViewById(R.id.map), prefs, this).also { it.setup() }
        search = SearchPanel(this, { mapController.boundingBox }) { setDest(it) }
        sheet = Sheet(this)
        places = PlaceActions(this, prefs) { renamed -> onPlacesChanged(renamed) }
        idle = IdlePanel(
            this, prefs,
            onPlace = { setDest(it) },
            onQuickStart = { place -> setDest(place); launcher.start(place, simulate = false) },
            onSavedLongPress = places::showActions,
        )
        destination = DestinationPanel(
            this, prefs,
            onWakeChanged = { dest?.let { mapController.setRadius(it, destination.wakeRadiusMeters(), zoneLabel()) } },
            onSave = { dest?.let(places::toggleSave) },
            onClear = { clearDest() },
            onStart = { simulate -> dest?.let { launcher.start(it, simulate) } },
        )
        trip = TripPanel(this, prefs, onShare = places::shareTrip, onStop = { TripService.stop(this) })
        setupBanner = findViewById(R.id.setupBanner)

        applyInsets()
        showIdle()
        places.updateShortcuts()

        findViewById<View>(R.id.historyBtn).setOnClickListener { places.showHistory { setDest(it) } }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.myLocationFab).setOnClickListener { centerOnMe() }
        setupBanner.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (!prefs.setupShown) startActivity(Intent(this, SetupActivity::class.java))
        handleIntent(intent)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                search.isOpen -> search.hide()
                dest != null && !TripService.state.active -> clearDest()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    /** Карта уходит под строку состояния, а поиск и панель отодвигаются от системных полос. */
    private fun applyInsets() {
        val appBar = findViewById<View>(R.id.appBar)
        val sheetView = findViewById<View>(R.id.sheet)
        val dp = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appBar.updatePadding(
                top = bars.top + (4 * dp).toInt(),
                left = bars.left + (20 * dp).toInt(),
                right = bars.right + (12 * dp).toInt(),
            )
            sheetView.updatePadding(bottom = bars.bottom + (16 * dp).toInt())
            // панель не должна заходить под верхнюю панель приложения
            // панель занимает максимум 62% экрана, чтобы карту было видно
            sheet.setMaxHeight((resources.displayMetrics.heightPixels * 0.62).toInt())
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
        mapController.onResume()
        updateSetupBanner()
        if (!TripService.state.active) {
            val d = dest
            if (d == null) showIdle() else showDestination(d)
        }
    }

    override fun onPause() {
        mapController.onPause()
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
        launcher.start(place, simulate = false)
    }

    private fun updateSetupBanner() {
        val issues = Reliability.issueCount(this)
        setupBanner.visibility = if (issues > 0 && !TripService.state.active) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.setupBannerText).text = getString(R.string.setup_banner, issues)
    }

    private fun centerOnMe() {
        if (!launcher.hasLocation()) {
            launcher.requestLocation()
            return
        }
        mapController.enableMyLocation()
        val s = TripService.state
        val me = if (s.active && s.lat != null && s.lon != null) GeoPoint(s.lat, s.lon) else mapController.myLocation
        if (me != null) mapController.centerOn(me) else Toast.makeText(this, R.string.phase_waiting, Toast.LENGTH_SHORT).show()
    }

    // ---------- Карта ----------

    override fun onMapTap(point: GeoPoint) {
        search.hide()
        if (TripService.state.active) return
        val stop = Stops.nearest(this, point.latitude, point.longitude, SNAP_METERS)
        if (stop != null) {
            onStopTap(stop)
            return
        }
        val place = Place(getString(R.string.point_on_map), point.latitude, point.longitude)
        setDest(place, moveMap = false)
        resolveName(place) {
            // остановки этого места могли ещё не загрузиться — пробуем притянуть к ним
            Stops.fetchNearest(this, place.lat, place.lon, SNAP_METERS)?.let { stop ->
                runOnUiThread { if (dest == place) onStopTap(stop) }
                null
            } ?: Net.reverse(this, place.lat, place.lon)
        }
    }

    override fun onStopTap(stop: Stop) {
        if (TripService.state.active) return
        val name = stop.name(this)
        if (name.isNotBlank()) {
            setDest(Place(name, stop.lat, stop.lon), moveMap = false)
        } else {
            val place = Place(getString(R.string.stop_unnamed), stop.lat, stop.lon)
            setDest(place, moveMap = false)
            resolveName(place) { Net.street(this, place.lat, place.lon)?.let { getString(R.string.stop_near, it) } }
        }
    }

    override fun onFirstFix() {
        val me = mapController.myLocation ?: return
        if (dest == null && prefs.mapPosition == null) mapController.centerOn(me, 14.0)
        dest?.let { if (!TripService.state.active) showDestination(it) }
        mapController.updateGuide(me)
    }

    /** Узнаёт название точки в фоне и подписывает её, если пользователь не выбрал другую. */
    private fun resolveName(place: Place, fetch: () -> String?) {
        destination.setTitle(getString(R.string.resolving_address))
        Thread {
            val name = runCatching(fetch).getOrNull()
            runOnUiThread {
                if (isDestroyed || dest != place || TripService.state.active) return@runOnUiThread
                if (name != null) setDest(place.copy(name = name), moveMap = false) else destination.setTitle(place.name)
            }
        }.start()
    }

    // ---------- Пункт назначения ----------

    private fun setDest(place: Place, moveMap: Boolean = true) {
        dest = place
        search.hide()
        mapController.showDestination(place, destination.wakeRadiusMeters(), zoneLabel(), moveMap)
        if (!TripService.state.active) showDestination(place)
    }

    private fun clearDest() {
        dest = null
        mapController.showDestination(null, 0.0, "", false)
        showIdle()
    }

    private fun showIdle() {
        if (TripService.state.active) return
        idle.render()
        sheet.show(idle.view)
    }

    /** Подпись под остановкой на карте: «Зона будильника 500 м» или «За 3 мин». */
    private fun zoneLabel(): String =
        if (prefs.mode == Prefs.MODE_DIST) getString(R.string.wake_zone_map, Geo.formatDistance(this, prefs.distMeters.toDouble()))
        else getString(R.string.wake_zone_time, prefs.minutes)

    private fun showDestination(place: Place) {
        mapController.updateGuide(mapController.myLocation)
        val me = mapController.myLocation
        val fromMe = me?.let { Geo.distance(it.latitude, it.longitude, place.lat, place.lon) }
        destination.show(place, fromMe, places.savedIndex(place) >= 0)
        sheet.show(destination.view)
    }

    private fun onPlacesChanged(renamed: Place?) {
        if (renamed != null) dest = renamed
        if (TripService.state.active) return
        val d = dest
        if (d != null) showDestination(d) else showIdle()
    }

    // ---------- Поездка ----------

    private fun render(s: TripService.State) {
        mapController.interactive = !s.active
        mapController.setTripMode(s.active)
        if (!s.active) {
            mapController.showMe(null, null)
            if (sheet.isShowing(trip.view)) {
                // поездка закончилась — возвращаемся к выбору
                dest?.let { showDestination(it) } ?: showIdle()
                updateSetupBanner()
            }
            return
        }
        s.dest?.let { if (it != dest) setDest(it, moveMap = false) }
        if (!sheet.isShowing(trip.view)) {
            sheet.show(trip.view)
            setupBanner.visibility = View.GONE
        }
        trip.render(s)
        mapController.showMe(s.lat, s.lon)
        if (s.lat != null && s.lon != null) mapController.addTrackPoint(s.lat, s.lon)
    }
}
