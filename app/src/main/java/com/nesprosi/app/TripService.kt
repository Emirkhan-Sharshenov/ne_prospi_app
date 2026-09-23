package com.nesprosi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Фоновая служба поездки: следит за GPS при заблокированном экране,
 * присылает предупреждение, будит у остановки и проверяет, что человек проснулся.
 */
class TripService : Service(), LocationListener {

    enum class Phase { WAITING_GPS, RIDING, WARNED, ALARM, CHECK }

    /** Почему звонит будильник. */
    enum class Reason { ARRIVED, BACKUP, PASSED, REPEAT }

    data class State(
        val active: Boolean = false,
        val dest: Place? = null,
        val phase: Phase = Phase.WAITING_GPS,
        val reason: Reason = Reason.ARRIVED,
        val lat: Double? = null,
        val lon: Double? = null,
        val distance: Double? = null,
        val etaSec: Double? = null,
        val speed: Double? = null,
        val accuracy: Float? = null,
        val gpsLost: Boolean = false,
        val backupAt: Long? = null,
        /** Когда показан вопрос «Вы проснулись?» — время, после которого будильник зазвонит снова. */
        val checkDeadline: Long? = null,
        val simulated: Boolean = false,
        /** Расстояние в начале поездки и расстояние, на котором разбудим — для полосы прогресса. */
        val startDistance: Double? = null,
        val wakeDistance: Double? = null,
    ) {
        /** Доля пройденного пути до будильника, 0…1. */
        val progress: Float?
            get() {
                val start = startDistance ?: return null
                val d = distance ?: return null
                val wake = wakeDistance ?: 0.0
                if (start <= wake) return 1f
                return ((start - d) / (start - wake)).toFloat().coerceIn(0f, 1f)
            }
    }

    companion object {
        private const val ACTION_START = "com.nesprosi.app.START"
        private const val ACTION_STOP = "com.nesprosi.app.STOP"
        private const val ACTION_DISMISS = "com.nesprosi.app.DISMISS"
        private const val ACTION_AWAKE = "com.nesprosi.app.AWAKE"
        private const val ACTION_BACKUP = "com.nesprosi.app.BACKUP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_SIMULATE = "simulate"

        private const val CH_TRIP = "trip"
        private const val CH_WARN = "warn"
        private const val CH_ALARM = "alarm"
        private const val CH_INFO = "info"
        private const val ID_TRIP = 1
        private const val ID_WARN = 2
        private const val ID_ALARM = 3
        private const val ID_GPS = 4
        private const val ID_BATTERY = 5
        private const val ID_SMS = 6

        private const val CHECK_DELAY_MS = 60_000L
        private const val CHECK_ANSWER_MS = 30_000L
        private const val GPS_LOST_MS = 3 * 60_000L

        var state = State()
            private set
        private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

        fun addListener(l: (State) -> Unit) = listeners.add(l)
        fun removeListener(l: (State) -> Unit) = listeners.remove(l)

        fun start(ctx: Context, place: Place, simulate: Boolean) {
            val intent = Intent(ctx, TripService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_NAME, place.name)
                .putExtra(EXTRA_LAT, place.lat)
                .putExtra(EXTRA_LON, place.lon)
                .putExtra(EXTRA_SIMULATE, simulate)
            ContextCompat.startForegroundService(ctx, intent)
        }

        /** Завершить поездку кнопкой. */
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        /** Будильник выключен удержанием кнопки. */
        fun dismiss(ctx: Context) = send(ctx, ACTION_DISMISS)

        /** Ответ «Да, я не сплю». */
        fun confirmAwake(ctx: Context) = send(ctx, ACTION_AWAKE)

        /** Кнопка «Завершить» для уведомления и виджета. */
        fun stopPendingIntent(ctx: Context): PendingIntent = PendingIntent.getService(
            ctx, 1, Intent(ctx, TripService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun backupFired(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, TripService::class.java).setAction(ACTION_BACKUP))

        private fun send(ctx: Context, action: String) {
            if (state.active) ctx.startService(Intent(ctx, TripService::class.java).setAction(action))
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var lctx: Context // контекст с языком приложения
    private val handler = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val alarmManager by lazy { getSystemService(AlarmManager::class.java) }
    private val tripStore by lazy { getSharedPreferences("active_trip", Context.MODE_PRIVATE) }
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
    }

    private var dest: Place? = null
    private var simulated = false
    private var startedAt = 0L
    private var backupAt: Long? = null
    private var phase = Phase.WAITING_GPS
    private var reason = Reason.ARRIVED
    private var firstAlarmReason: Reason? = null
    private var checkDeadline: Long? = null

    private var lastFix: Location? = null
    private var lastFixAt = 0L
    private var smoothedSpeed: Double? = null
    private var closestDistance = Double.MAX_VALUE
    private var passedFired = false
    private var gpsLost = false
    private var batteryWarned = false
    private var smsStartSent = false
    private var smsArrivedSent = false

    private var startDistance: Double? = null
    private var listeningGps = false
    private var gpsIntervalMs = 0L
    private var lastWidgetUpdate = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val announced = mutableSetOf<Int>()
    private var simRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var volume = 0.25f
    private var savedAlarmVolume: Int? = null
    private var torchOn = false
    private var torchCameraId: String? = null

    private val rampVolume = object : Runnable {
        override fun run() {
            volume = min(1f, volume + 0.05f)
            player?.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, 1000)
        }
    }
    private val flashRunnable = object : Runnable {
        override fun run() {
            setTorch(!torchOn)
            handler.postDelayed(this, 400)
        }
    }
    private val backupRunnable = Runnable { onBackup() }
    private val checkPromptRunnable = Runnable { showCheckPrompt() }
    private val repeatRunnable = Runnable {
        if (phase == Phase.CHECK && checkDeadline != null) startAlarm(Reason.REPEAT)
    }
    private val watchdog = object : Runnable {
        override fun run() {
            checkGps()
            checkBattery()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        lctx = Lang.wrap(this)
        createChannels()
    }

    private fun s(id: Int, vararg args: Any): String = lctx.getString(id, *args)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTrip(intent, redelivered = flags and START_FLAG_REDELIVERY != 0)
            ACTION_STOP -> finishTrip(null)
            ACTION_DISMISS -> onDismiss()
            ACTION_AWAKE -> if (phase == Phase.CHECK) finishTrip(null)
            ACTION_BACKUP -> onBackupIntent()
            else -> if (!state.active) stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        cleanup()
        tts?.shutdown()
        tts = null
        if (state.active) {
            state = State()
            publish()
        }
        super.onDestroy()
    }

    // ---------- Поездка ----------

    private fun startTrip(intent: Intent, redelivered: Boolean) {
        cleanup()
        val place = Place(
            intent.getStringExtra(EXTRA_NAME) ?: s(R.string.dest_fallback),
            intent.getDoubleExtra(EXTRA_LAT, 0.0),
            intent.getDoubleExtra(EXTRA_LON, 0.0),
        )
        dest = place
        simulated = intent.getBooleanExtra(EXTRA_SIMULATE, false)
        resetTripFields()

        val saved = if (redelivered) savedStartedAt(place) else null
        startedAt = saved ?: System.currentTimeMillis()
        backupAt = prefs.backupMinutes.takeIf { it > 0 }?.let { startedAt + it * 60_000L }

        if (!goForeground(tripNotification(s(R.string.notif_waiting_gps)), withLocation = true)) {
            stopSelf()
            return
        }
        saveTrip(place)

        // не даём процессору уснуть, пока идёт поездка (максимум 6 часов)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeProspi:trip")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        if (simulated) startSimulation(place) else startLocationUpdates(2000L)
        initVoice()
        scheduleBackup()
        handler.postDelayed(watchdog, 20_000)
        publishState()
    }

    private fun resetTripFields() {
        phase = Phase.WAITING_GPS
        reason = Reason.ARRIVED
        firstAlarmReason = null
        checkDeadline = null
        lastFix = null
        lastFixAt = System.currentTimeMillis()
        smoothedSpeed = null
        closestDistance = Double.MAX_VALUE
        passedFired = false
        gpsLost = false
        batteryWarned = false
        smsStartSent = false
        smsArrivedSent = false
        startDistance = null
        announced.clear()
    }

    /** Завершение поездки. result == null — определить по тому, был ли будильник. */
    private fun finishTrip(result: String?) {
        val place = dest
        if (place != null && state.active) {
            val r = result ?: when (firstAlarmReason) {
                null -> TripRecord.STOPPED
                Reason.BACKUP -> TripRecord.BACKUP
                else -> TripRecord.ARRIVED
            }
            prefs.addHistory(
                TripRecord(place.name, startedAt, System.currentTimeMillis(), r, simulated, place.lat, place.lon)
            )
        }
        cleanup()
        tts?.shutdown()
        tts = null
        ttsReady = false
        tripStore.edit().clear().apply()
        dest = null
        state = State()
        publish()
        TripWidget.updateAll(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        stopLocationUpdates()
        stopSimulation()
        stopAlarmEffects()
        cancelBackup()
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(checkPromptRunnable)
        handler.removeCallbacks(repeatRunnable)
        listOf(ID_WARN, ID_ALARM, ID_GPS, ID_BATTERY, ID_SMS).forEach { nm.cancel(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Переводит службу в foreground. Сначала пробуем с GPS, если нельзя — как проигрывание звука. */
    private fun goForeground(notification: Notification, withLocation: Boolean): Boolean {
        val types = mutableListOf<Int>()
        if (Build.VERSION.SDK_INT >= 29) {
            if (withLocation) types += ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            types += ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            types += 0
        }
        for (type in types) {
            try {
                ServiceCompat.startForeground(this, ID_TRIP, notification, type)
                return true
            } catch (e: Exception) {
                // пробуем следующий вариант
            }
        }
        return false
    }

    private fun saveTrip(place: Place) {
        tripStore.edit()
            .putString("name", place.name)
            .putString("lat", place.lat.toString())
            .putString("lon", place.lon.toString())
            .putLong("startedAt", startedAt)
            .putBoolean("simulated", simulated)
            .apply()
    }

    private fun savedTrip(): Place? {
        val name = tripStore.getString("name", null) ?: return null
        val lat = tripStore.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lon = tripStore.getString("lon", null)?.toDoubleOrNull() ?: return null
        return Place(name, lat, lon)
    }

    private fun savedStartedAt(place: Place): Long? =
        if (savedTrip() == place) tripStore.getLong("startedAt", 0).takeIf { it > 0 } else null

    // ---------- GPS ----------

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates(intervalMs: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java)
        if (listeningGps) lm.removeUpdates(this)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider in lm.allProviders) {
                runCatching { lm.requestLocationUpdates(provider, intervalMs, 0f, this, Looper.getMainLooper()) }
            }
        }
        listeningGps = true
        gpsIntervalMs = intervalMs
    }

    /** На каком расстоянии от остановки зазвонит будильник (для времени — оценка по скорости). */
    private fun wakeDistance(): Double =
        if (prefs.mode == Prefs.MODE_DIST) prefs.distMeters.toDouble()
        else max(150.0, (smoothedSpeed ?: 8.0) * prefs.minutes * 60)

    /**
     * Экономия батареи: пока до будильника далеко, GPS спрашиваем редко, ближе — чаще.
     * Главная жалоба на такие приложения — разряд батареи.
     */
    private fun gpsIntervalFor(d: Double): Long {
        if (phase == Phase.ALARM || phase == Phase.CHECK) return 2000L
        val left = d - wakeDistance()
        return when {
            left > 8000 -> 20_000L
            left > 3000 -> 8000L
            left > 1000 -> 4000L
            else -> 2000L
        }
    }

    private fun stopLocationUpdates() {
        if (listeningGps) {
            getSystemService(LocationManager::class.java).removeUpdates(this)
            listeningGps = false
        }
    }

    /** Проверка без поездки: «едем» к остановке с 4 км со скоростью 60 м/с. */
    private fun startSimulation(place: Place) {
        val angle = Math.random() * 2 * PI
        var lat = place.lat + cos(angle) * 0.036
        var lon = place.lon + sin(angle) * 0.036 / cos(Math.toRadians(place.lat))
        val v = 60.0
        val r = object : Runnable {
            override fun run() {
                val d = Geo.distance(lat, lon, place.lat, place.lon)
                val f = min(1.0, v / max(d, 1.0))
                lat += (place.lat - lat) * f
                lon += (place.lon - lon) * f
                val fake = Location("sim").apply {
                    latitude = lat
                    longitude = lon
                    accuracy = 5f
                    speed = if (d > 1) v.toFloat() else 0f
                    time = System.currentTimeMillis()
                }
                onLocationChanged(fake)
                handler.postDelayed(this, 1000)
            }
        }
        simRunnable = r
        handler.post(r)
    }

    private fun stopSimulation() {
        simRunnable?.let { handler.removeCallbacks(it) }
        simRunnable = null
    }

    override fun onLocationChanged(loc: Location) {
        val place = dest ?: return
        val last = lastFix
        // грубая точка от вышек не должна перебивать недавнюю точную точку GPS
        if (last != null && loc.accuracy > 100 && last.accuracy <= 100 && loc.time - last.time < 30_000) return

        val d = Geo.distance(loc.latitude, loc.longitude, place.lat, place.lon)
        var sample: Double? = if (loc.hasSpeed()) loc.speed.toDouble() else null
        if (sample == null && last != null && loc.accuracy < 50 && last.accuracy < 50) {
            val dt = (loc.time - last.time) / 1000.0
            if (dt > 0.5) sample = last.distanceTo(loc) / dt
        }
        val sp = sample
        if (sp != null) smoothedSpeed = smoothedSpeed?.let { it * 0.7 + sp * 0.3 } ?: sp
        lastFix = loc
        lastFixAt = System.currentTimeMillis()
        if (gpsLost) {
            gpsLost = false
            nm.cancel(ID_GPS)
        }
        val eta = smoothedSpeed?.takeIf { it > 1.0 }?.let { d / it }

        if (!smsStartSent) {
            smsStartSent = true
            sendSms(s(R.string.sms_start, place.name, Geo.mapLink(loc.latitude, loc.longitude)))
        }
        if (startDistance == null) startDistance = d
        if (!simulated) {
            val interval = gpsIntervalFor(d)
            if (interval != gpsIntervalMs) startLocationUpdates(interval)
        }
        announceDistance(d)

        when (phase) {
            Phase.WAITING_GPS, Phase.RIDING, Phase.WARNED -> {
                if (phase == Phase.WAITING_GPS) phase = Phase.RIDING
                closestDistance = min(closestDistance, d)
                val hit: Boolean
                val warn: Boolean
                if (prefs.mode == Prefs.MODE_DIST) {
                    val r = prefs.distMeters
                    hit = d <= r
                    warn = d <= r * 2.5
                } else {
                    val t = prefs.minutes * 60.0
                    hit = (eta != null && eta <= t) || d <= 150
                    warn = eta != null && eta <= t * 2.5
                }
                when {
                    hit -> startAlarm(Reason.ARRIVED)
                    warn && phase == Phase.RIDING -> sendWarning()
                }
            }
            Phase.ALARM, Phase.CHECK -> {
                // человек выключил будильник, но автобус уехал дальше — будим снова
                closestDistance = min(closestDistance, d)
                if (!passedFired && firstAlarmReason != Reason.BACKUP &&
                    d > closestDistance + 400 && (smoothedSpeed ?: 0.0) > 3.0
                ) {
                    passedFired = true
                    startAlarm(Reason.PASSED)
                }
            }
        }

        state = currentState(loc, d, eta)
        publish()
        updateTripNotification(d, eta)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun currentState(loc: Location? = lastFix, d: Double? = state.distance, eta: Double? = state.etaSec) = State(
        active = true,
        dest = dest,
        phase = phase,
        reason = reason,
        lat = loc?.latitude,
        lon = loc?.longitude,
        distance = d,
        etaSec = eta,
        speed = smoothedSpeed,
        accuracy = loc?.accuracy,
        gpsLost = gpsLost,
        backupAt = backupAt,
        checkDeadline = checkDeadline,
        simulated = simulated,
        startDistance = startDistance,
        wakeDistance = wakeDistance(),
    )

    private fun publishState() {
        state = currentState()
        publish()
    }

    private fun publish() {
        listeners.forEach { it(state) }
        // виджет обновляем не чаще раза в 15 секунд, но смену этапа — сразу
        val now = System.currentTimeMillis()
        if (now - lastWidgetUpdate > 15_000 || state.phase == Phase.ALARM || !state.active) {
            lastWidgetUpdate = now
            TripWidget.updateAll(this)
        }
    }

    // ---------- Голосовые подсказки ----------

    private fun initVoice() {
        if (!prefs.voice || tts != null) return
        tts = TextToSpeech(this) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            // язык приложения; если голоса для него нет (например, кыргызского) — русский, потом английский
            val fallbacks = listOf(Lang.locale(this), java.util.Locale.forLanguageTag("ru"), java.util.Locale.ENGLISH)
                .let { if (Lang.language(this) == "ky") it else listOf(it[0], java.util.Locale.ENGLISH) }
            fallbacks.firstOrNull { engine.setLanguage(it) >= TextToSpeech.LANG_AVAILABLE }
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        // не говорим вслух, если человек просил тишину, а наушников нет
        if ((prefs.vibrationOnly || prefs.headphonesOnly) && !headphonesConnected()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nesprosi")
    }

    /** «До остановки 2 километра», «…1 километр» — только если будильник ещё не близко. */
    private fun announceDistance(d: Double) {
        if (phase != Phase.RIDING && phase != Phase.WAITING_GPS) return
        val start = startDistance ?: return
        for (mark in listOf(5000, 2000, 1000)) {
            if (d <= mark && start > mark + 300 && mark > wakeDistance() * 1.3 && announced.add(mark)) {
                speak(s(R.string.voice_distance, Geo.formatDistance(lctx, mark.toDouble())))
                break
            }
        }
    }

    private fun checkGps() {
        if (simulated || phase == Phase.ALARM || phase == Phase.CHECK || gpsLost) return
        if (System.currentTimeMillis() - lastFixAt < GPS_LOST_MS) return
        gpsLost = true
        val text = backupAt?.let { s(R.string.gps_lost_text, timeText(it)) } ?: s(R.string.gps_lost_text_no_backup)
        nm.notify(ID_GPS, infoNotification(s(R.string.gps_lost_title), text))
        publishState()
    }

    private fun checkBattery() {
        if (batteryWarned) return
        val (level, charging) = batteryLevel(this)
        if (level in 0..10 && !charging) {
            batteryWarned = true
            nm.notify(ID_BATTERY, infoNotification(s(R.string.battery_trip_title, level), s(R.string.battery_trip_text)))
        }
    }

    // ---------- Страховочный таймер ----------

    private fun backupPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this, 10, Intent(this, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scheduleBackup() {
        val at = backupAt ?: return
        val canExact = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (canExact) {
            // AlarmManager сработает, даже если система закроет приложение
            val show = PendingIntent.getActivity(
                this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), backupPendingIntent())
        } else {
            handler.postDelayed(backupRunnable, max(0L, at - System.currentTimeMillis()))
        }
    }

    private fun cancelBackup() {
        handler.removeCallbacks(backupRunnable)
        runCatching { alarmManager.cancel(backupPendingIntent()) }
    }

    private fun onBackupIntent() {
        if (state.active) {
            // служба уже работает; startForegroundService требует снова вызвать startForeground
            goForeground(tripNotification(s(R.string.notif_alarm_ringing)), withLocation = !simulated)
            onBackup()
            return
        }
        // система закрыла приложение — восстанавливаем поездку и будим
        val place = savedTrip()
        if (!goForeground(tripNotification(s(R.string.notif_alarm_ringing)), withLocation = false) || place == null) {
            tripStore.edit().clear().apply()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        dest = place
        simulated = tripStore.getBoolean("simulated", false)
        resetTripFields()
        startedAt = tripStore.getLong("startedAt", System.currentTimeMillis())
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeProspi:trip")
            .apply { acquire(30 * 60 * 1000L) }
        onBackup()
    }

    private fun onBackup() {
        backupAt = null
        if (phase != Phase.ALARM && phase != Phase.CHECK) startAlarm(Reason.BACKUP)
    }

    // ---------- Уведомления ----------

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIP, s(R.string.ch_trip), NotificationManager.IMPORTANCE_LOW).apply {
                description = s(R.string.ch_trip_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, s(R.string.ch_warn), NotificationManager.IMPORTANCE_HIGH).apply {
                description = s(R.string.ch_warn_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, s(R.string.ch_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
                description = s(R.string.ch_alarm_desc)
                setSound(null, null) // звук включает сама служба
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_INFO, s(R.string.ch_info), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = s(R.string.ch_info_desc)
            }
        )
    }

    private fun stopPendingIntent(): PendingIntent = stopPendingIntent(this)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Уведомление поездки. На экране блокировки видны полоса прогресса и обратный отсчёт до прибытия —
     * как «живые» уведомления в Don't Miss the Stop и WakeSignal.
     */
    private fun tripNotification(text: String, progress: Float? = null, arriveAt: Long? = null): Notification {
        val builder = NotificationCompat.Builder(this, CH_TRIP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s(R.string.notif_trip_title, dest?.name ?: s(R.string.dest_fallback)))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openAppIntent())
            .addAction(0, s(R.string.notif_finish), stopPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress != null) {
            builder.setProgress(100, (progress * 100).roundToInt(), false)
            // своя разметка как в макете: крупно расстояние, время, полоса и зона будильника
            val st = state
            if (st.distance != null) {
                val (num, unit) = Geo.splitValueUnit(Geo.formatDistance(lctx, st.distance))
                val eta = st.etaSec?.let { Geo.formatEtaMinutes(lctx, it) }.orEmpty()
                val title = s(R.string.widget_approaching, dest?.name.orEmpty())
                val pct = (progress * 100).roundToInt()
                val small = RemoteViews(packageName, R.layout.notif_trip).apply {
                    setTextViewText(R.id.notifDistance, "$num $unit")
                    setTextViewText(R.id.notifTitle, title)
                    setTextViewText(R.id.notifEta, eta)
                    setProgressBar(R.id.notifProgress, 100, pct, false)
                }
                val big = RemoteViews(packageName, R.layout.notif_trip_big).apply {
                    setTextViewText(R.id.notifTitle, title)
                    setTextViewText(R.id.notifDistance, num)
                    setTextViewText(R.id.notifUnit, s(R.string.unit_remaining, unit))
                    setTextViewText(R.id.notifEta, eta)
                    setProgressBar(R.id.notifProgress, 100, pct, false)
                    setTextViewText(R.id.notifZone, st.wakeDistance?.let { s(R.string.wake_zone, Geo.formatDistance(lctx, it)) }.orEmpty())
                }
                builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomContentView(small)
                    .setCustomBigContentView(big)
            }
        }
        if (arriveAt != null) {
            builder.setWhen(arriveAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        return builder.build()
    }

    private fun updateTripNotification(d: Double, eta: Double?) {
        val notification = when (phase) {
            Phase.ALARM -> tripNotification(s(R.string.notif_alarm_ringing))
            Phase.CHECK -> tripNotification(s(R.string.notif_check_soon))
            else -> {
                val left = Geo.formatDistance(lctx, d)
                val text = if (eta != null) s(R.string.notif_left_eta, left, Geo.formatEta(lctx, eta)) else s(R.string.notif_left, left)
                tripNotification(text, state.progress, eta?.let { System.currentTimeMillis() + (it * 1000).toLong() })
            }
        }
        nm.notify(ID_TRIP, notification)
    }

    private fun infoNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CH_INFO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

    private fun sendWarning() {
        phase = Phase.WARNED
        speak(s(R.string.voice_warn, dest?.name.orEmpty()))
        nm.notify(
            ID_WARN,
            NotificationCompat.Builder(this, CH_WARN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s(R.string.warn_title))
                .setContentText(s(R.string.warn_text, dest?.name.orEmpty()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun timeText(at: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

    // ---------- Будильник ----------

    private fun alarmActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Уведомление, которое открывает экран будильника поверх блокировки. */
    private fun showFullScreen(title: String, text: String) {
        val fullScreen = alarmActivityIntent()
        // обновление уже показанного уведомления не открывает экран заново — поэтому сначала убираем старое
        nm.cancel(ID_ALARM)
        nm.notify(
            ID_ALARM,
            NotificationCompat.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen) // выключить можно только на экране будильника, удержанием
                .build()
        )
        // если приложение сейчас на экране, открываем сразу
        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        }
    }

    private fun startAlarm(why: Reason) {
        handler.removeCallbacks(checkPromptRunnable)
        handler.removeCallbacks(repeatRunnable)
        stopAlarmEffects()
        phase = Phase.ALARM
        reason = why
        checkDeadline = null
        if (firstAlarmReason == null) firstAlarmReason = why
        nm.cancel(ID_WARN)
        if (why == Reason.BACKUP) cancelBackup()

        val place = dest
        val name = place?.name.orEmpty()
        val (title, text) = when (why) {
            Reason.ARRIVED -> s(R.string.alarm_title) to s(R.string.alarm_text, name)
            Reason.BACKUP -> s(R.string.alarm_backup_title) to
                s(R.string.alarm_backup_text, ((System.currentTimeMillis() - startedAt) / 60_000).toInt())
            Reason.PASSED -> s(R.string.alarm_passed_title) to s(R.string.alarm_passed_text, name)
            Reason.REPEAT -> s(R.string.alarm_repeat_title) to s(R.string.alarm_text, name)
        }
        showFullScreen(title, text)
        nm.notify(ID_TRIP, tripNotification(s(R.string.notif_alarm_ringing)))
        startAlarmEffects()

        if (why == Reason.ARRIVED && !smsArrivedSent && place != null) {
            smsArrivedSent = true
            val loc = lastFix
            val link = if (loc != null) Geo.mapLink(loc.latitude, loc.longitude) else Geo.mapLink(place.lat, place.lon)
            sendSms(s(R.string.sms_arrived, name, link))
        }
        publishState()
    }

    /** Будильник выключен удержанием. Если включена проверка — через минуту спросим, проснулся ли человек. */
    private fun onDismiss() {
        if (phase != Phase.ALARM) return
        stopAlarmEffects()
        nm.cancel(ID_ALARM)
        if (!prefs.wakeCheck) {
            finishTrip(null)
            return
        }
        phase = Phase.CHECK
        checkDeadline = null
        handler.postDelayed(checkPromptRunnable, CHECK_DELAY_MS)
        nm.notify(ID_TRIP, tripNotification(s(R.string.notif_check_soon)))
        publishState()
    }

    private fun showCheckPrompt() {
        if (phase != Phase.CHECK) return
        checkDeadline = System.currentTimeMillis() + CHECK_ANSWER_MS
        showFullScreen(s(R.string.check_title), s(R.string.check_text))
        vibrateOnce(longArrayOf(0, 400, 200, 400))
        handler.postDelayed(repeatRunnable, CHECK_ANSWER_MS)
        publishState()
    }

    private fun startAlarmEffects() {
        if (!prefs.vibrationOnly) {
            if (prefs.headphonesOnly) {
                if (headphonesConnected()) playSound(AudioAttributes.USAGE_MEDIA)
            } else {
                raiseAlarmVolume()
                playSound(AudioAttributes.USAGE_ALARM)
            }
        }
        vibrateAlarm()
        if (prefs.flashlight) startFlash()
    }

    private fun stopAlarmEffects() {
        handler.removeCallbacks(rampVolume)
        handler.removeCallbacks(flashRunnable)
        if (torchOn) setTorch(false)
        player?.runCatching { stop(); release() }
        player = null
        tone?.runCatching { stopTone(); release() }
        tone = null
        vibrator.cancel()
        savedAlarmVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        savedAlarmVolume = null
    }

    private fun headphonesConnected(): Boolean {
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= 31) types += AudioDeviceInfo.TYPE_BLE_HEADSET
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    /** Если громкость будильника почти на нуле — поднимаем её, чтобы точно разбудить. */
    private fun raiseAlarmVolume() {
        runCatching {
            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = (maxVol * 0.85).roundToInt()
            if (cur < target) {
                savedAlarmVolume = cur
                audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
            }
        }
    }

    private fun playSound(usage: Int) {
        val uri = prefs.alarmSound?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        // в наушниках начинаем тише, чтобы не оглушить
        volume = if (usage == AudioAttributes.USAGE_MEDIA) 0.1f else 0.25f
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@TripService, uri!!)
                isLooping = true
                prepare()
                setVolume(volume, volume)
                start()
            }
            handler.postDelayed(rampVolume, 1000)
        } catch (e: Exception) {
            player?.release()
            player = null
            if (usage == AudioAttributes.USAGE_ALARM) {
                tone = runCatching {
                    ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
                        .also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
                }.getOrNull()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun vibrateAlarm() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300, 700, 300, 1200, 600), 0))

    private fun vibrateOnce(pattern: LongArray) = vibrate(VibrationEffect.createWaveform(pattern, -1))

    private fun startFlash() {
        val cm = getSystemService(CameraManager::class.java)
        torchCameraId = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        if (torchCameraId != null) handler.post(flashRunnable)
    }

    private fun setTorch(on: Boolean) {
        val id = torchCameraId ?: return
        runCatching { getSystemService(CameraManager::class.java).setTorchMode(id, on) }
        torchOn = on
    }

    // ---------- SMS близким ----------

    private fun sendSms(text: String) {
        val phone = prefs.contactPhone.trim()
        if (simulated || !prefs.smsEnabled || phone.isEmpty()) return
        // Приложение не отправляет SMS само: готовим текст и открываем обычное SMS-приложение по нажатию.
        val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone)))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (sms.resolveActivity(packageManager) == null) return
        val open = PendingIntent.getActivity(
            this, 7, sms, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            ID_SMS,
            NotificationCompat.Builder(this, CH_INFO)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(s(R.string.sms_ready_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}

/** Уровень заряда в процентах и заряжается ли телефон. */
fun batteryLevel(ctx: Context): Pair<Int, Boolean> {
    val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to false
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return (if (level >= 0 && scale > 0) level * 100 / scale else -1) to charging
}
