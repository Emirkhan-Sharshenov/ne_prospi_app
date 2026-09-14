package com.nesprosi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var map: MapView
    private var dest: Place? = null
    private var destMarker: Marker? = null
    private var destCircle: Polygon? = null
    private var meMarker: Marker? = null
    private var myLocation: MyLocationNewOverlay? = null
    private var pendingSimulate = false

    private lateinit var searchInput: EditText
    private lateinit var results: LinearLayout
    private lateinit var destText: TextView
    private lateinit var saveBtn: Button
    private lateinit var places: LinearLayout
    private lateinit var modeGroup: RadioGroup
    private lateinit var seek: SeekBar
    private lateinit var valueText: TextView
    private lateinit var phaseText: TextView
    private lateinit var distText: TextView
    private lateinit var etaText: TextView
    private lateinit var speedText: TextView
    private lateinit var accText: TextView
    private lateinit var startBtn: Button
    private lateinit var simBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var batteryBtn: Button

    private val stateListener: (TripService.State) -> Unit = { render(it) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) {
                enableMyLocation()
                continueStart()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Нужен доступ к местоположению")
                    .setMessage("Без GPS приложение не узнает, что вы подъезжаете к остановке.")
                    .setPositiveButton("Открыть настройки") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            userAgentValue = "NeProspi/1.0 ($packageName)"
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
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
        phaseText = findViewById(R.id.phaseText)
        distText = findViewById(R.id.distText)
        etaText = findViewById(R.id.etaText)
        speedText = findViewById(R.id.speedText)
        accText = findViewById(R.id.accText)
        startBtn = findViewById(R.id.startBtn)
        simBtn = findViewById(R.id.simBtn)
        stopBtn = findViewById(R.id.stopBtn)
        batteryBtn = findViewById(R.id.batteryBtn)

        setupMap()
        setupSearch()
        setupSettings()
        renderPlaces()

        saveBtn.setOnClickListener { savePlace() }
        startBtn.setOnClickListener { requestStart(simulate = false) }
        simBtn.setOnClickListener { requestStart(simulate = true) }
        stopBtn.setOnClickListener { TripService.stop(this) }
        batteryBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }
    }

    override fun onStart() {
        super.onStart()
        TripService.addListener(stateListener)
        render(TripService.state)
    }

    override fun onResume() {
        super.onResume()
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

    // ---------- Карта ----------

    private fun setupMap() {
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(prefs.mapZoom)
        map.controller.setCenter(GeoPoint(prefs.mapLat, prefs.mapLon))
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!TripService.state.active) {
                    val place = Place("Точка на карте", p.latitude, p.longitude)
                    setDest(place, moveMap = false)
                    destText.text = "📍 Определяю адрес…"
                    resolveAddress(place)
                }
                return true
            }

            override fun longPressHelper(p: GeoPoint) = false
        }))
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
        destText.text = "📍 ${place.name}"
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
            map.overlays.add(1, it) // под маркером, над обработчиком нажатий
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
        addRow(results, "Ищу…")
        // сначала ищем в Кыргызстане, ближе к текущему месту на карте; если пусто — по всему миру
        val box = map.boundingBox
        val viewbox = "&viewbox=${box.lonWest},${box.latNorth},${box.lonEast},${box.latSouth}"
        Thread {
            val found = try {
                searchNominatim(q, "$viewbox&countrycodes=kg").ifEmpty { searchNominatim(q, viewbox) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                results.removeAllViews()
                when {
                    found == null -> addRow(results, "Поиск недоступен — отметьте точку на карте")
                    found.isEmpty() -> addRow(results, "Ничего не найдено")
                    else -> found.forEach { (fullAddress, place) ->
                        addRow(results, fullAddress, onClick = { setDest(place) })
                    }
                }
            }
        }.start()
    }

    /** Результаты поиска: полный адрес для списка и место с коротким названием. */
    private fun searchNominatim(q: String, extra: String): List<Pair<String, Place>> {
        val arr = JSONArray(
            httpGet(
                "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&limit=5&accept-language=ru$extra&q=" +
                    URLEncoder.encode(q, "UTF-8")
            )
        )
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.optString("display_name") to Place(shortName(o), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }

    /** Узнаёт адрес точки, отмеченной на карте, и подписывает её. */
    private fun resolveAddress(place: Place) {
        Thread {
            val name = try {
                val o = JSONObject(
                    httpGet(
                        "https://nominatim.openstreetmap.org/reverse?format=json&zoom=18&addressdetails=1" +
                            "&accept-language=ru&lat=${place.lat}&lon=${place.lon}"
                    )
                )
                if (o.has("error")) null else shortName(o)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed || dest != place) return@runOnUiThread // пользователь уже выбрал другую точку
                if (name != null && !TripService.state.active) setDest(place.copy(name = name), moveMap = false)
                else destText.text = "📍 ${place.name}"
            }
        }.start()
    }

    /** Короткое понятное название: «Ошский базар, улица Бейшеналиевой 42» или «проспект Чуй 100». */
    private fun shortName(o: JSONObject): String {
        val a = o.optJSONObject("address")
        val road = a?.optString("road").orEmpty()
        val street = listOf(road, a?.optString("house_number").orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
        val title = o.optString("name").takeIf { it.isNotBlank() && it != road }.orEmpty()
        val area = listOf("neighbourhood", "quarter", "suburb", "village", "town", "city")
            .map { a?.optString(it).orEmpty() }
            .firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(title, street).filter { it.isNotBlank() }.joinToString(", ")
            .ifBlank { if (area.isNotBlank()) "Точка на карте · $area" else "" }
            .ifBlank { o.optString("display_name").split(",").take(2).joinToString(",").trim() }
            .ifBlank { "Точка на карте" }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "NeProspi/1.0 (Android)")
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    // ---------- Сохранённые места ----------

    private fun renderPlaces() {
        places.removeAllViews()
        prefs.places.forEachIndexed { i, p ->
            addRow(places, "⭐ ${p.name}",
                onClick = { if (!TripService.state.active) setDest(p) },
                onLongClick = {
                    AlertDialog.Builder(this)
                        .setMessage("Удалить «${p.name}»?")
                        .setPositiveButton("Удалить") { _, _ ->
                            prefs.places = prefs.places.toMutableList().also { it.removeAt(i) }
                            renderPlaces()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                })
        }
    }

    private fun savePlace() {
        val d = dest ?: return
        val input = EditText(this).apply { setText(d.name); setSelectAllOnFocus(true) }
        AlertDialog.Builder(this)
            .setTitle("Название места")
            .setMessage("Например, «Дом» или «Работа»")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { d.name }
                prefs.places = prefs.places + d.copy(name = name)
                setDest(d.copy(name = name), moveMap = false)
                renderPlaces()
            }
            .setNegativeButton("Отмена", null)
            .show()
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
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (prefs.mode == Prefs.MODE_DIST) prefs.distMeters = 100 + progress * 50
                else prefs.minutes = 1 + progress
                updateValueText()
                updateCircle()
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        applyMode()
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
        valueText.text = if (prefs.mode == Prefs.MODE_DIST) Geo.formatDistance(prefs.distMeters.toDouble())
        else "${prefs.minutes} мин"
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

    private fun continueStart() {
        if (!hasLocationPermission()) return

        val lm = getSystemService(LocationManager::class.java)
        if (!pendingSimulate && Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            AlertDialog.Builder(this)
                .setTitle("Геолокация выключена")
                .setMessage("Включите определение местоположения, чтобы приложение видело, где вы едете.")
                .setPositiveButton("Включить") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Уведомления выключены: экран будильника не появится, но звук и вибрация будут", Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            AlertDialog.Builder(this)
                .setTitle("Будильник на экране блокировки")
                .setMessage("Разрешите приложению показывать будильник на весь экран, чтобы он появлялся на заблокированном телефоне.")
                .setPositiveButton("Разрешить") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Начать без этого") { _, _ -> launchTrip() }
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

    // ---------- Состояние ----------

    private fun render(s: TripService.State) {
        if (s.active && s.dest != null && s.dest != dest) setDest(s.dest, moveMap = false)
        distText.text = s.distance?.let { Geo.formatDistance(it) } ?: "—"
        etaText.text = Geo.formatEta(s.etaSec)
        speedText.text = s.speed?.let { "${(it * 3.6).roundToInt()} км/ч" } ?: "—"
        accText.text = s.accuracy?.let { "±${it.roundToInt()} м" } ?: "—"
        phaseText.text = when {
            !s.active -> "не начата"
            s.phase == TripService.Phase.WAITING_GPS -> "ищу GPS…"
            s.phase == TripService.Phase.RIDING -> "в пути"
            s.phase == TripService.Phase.WARNED -> "скоро выходить"
            else -> "будильник!"
        }
        val badge = when {
            !s.active -> R.color.line
            s.phase == TripService.Phase.WARNED -> R.color.warn
            s.phase == TripService.Phase.ALARM -> R.color.danger
            else -> R.color.ok
        }
        phaseText.background.mutate().setTint(ContextCompat.getColor(this, badge))
        showMe(s.lat.takeIf { s.active }, s.lon.takeIf { s.active })
        updateButtons()
    }

    private fun updateButtons() {
        val active = TripService.state.active
        startBtn.visibility = if (active) View.GONE else View.VISIBLE
        simBtn.visibility = if (active) View.GONE else View.VISIBLE
        stopBtn.visibility = if (active) View.VISIBLE else View.GONE
        startBtn.isEnabled = dest != null
        simBtn.isEnabled = dest != null
        seek.isEnabled = !active
        for (i in 0 until modeGroup.childCount) modeGroup.getChildAt(i).isEnabled = !active
        val ignoring = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        batteryBtn.visibility = if (ignoring) View.GONE else View.VISIBLE
    }
}
