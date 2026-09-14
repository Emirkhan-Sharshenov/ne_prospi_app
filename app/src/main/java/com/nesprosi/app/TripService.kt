package com.nesprosi.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Фоновая служба поездки: следит за GPS, пока экран заблокирован,
 * присылает предупреждение и включает будильник у остановки.
 */
class TripService : Service(), LocationListener {

    enum class Phase { WAITING_GPS, RIDING, WARNED, ALARM }

    data class State(
        val active: Boolean = false,
        val dest: Place? = null,
        val phase: Phase = Phase.WAITING_GPS,
        val lat: Double? = null,
        val lon: Double? = null,
        val distance: Double? = null,
        val etaSec: Double? = null,
        val speed: Double? = null,
        val accuracy: Float? = null,
    )

    companion object {
        private const val ACTION_START = "com.nesprosi.app.START"
        private const val ACTION_STOP = "com.nesprosi.app.STOP"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_SIMULATE = "simulate"

        private const val CH_TRIP = "trip"
        private const val CH_WARN = "warn"
        private const val CH_ALARM = "alarm"
        private const val ID_TRIP = 1
        private const val ID_WARN = 2
        private const val ID_ALARM = 3

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

        fun stop(ctx: Context) {
            if (state.active) ctx.startService(Intent(ctx, TripService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
    }

    private var dest: Place? = null
    private var phase = Phase.WAITING_GPS
    private var lastFix: Location? = null
    private var smoothedSpeed: Double? = null
    private var listeningGps = false
    private var simRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var volume = 0.25f
    private var savedAlarmVolume: Int? = null
    private val rampVolume = object : Runnable {
        override fun run() {
            volume = min(1f, volume + 0.05f)
            player?.setVolume(volume, volume)
            if (volume < 1f) handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTrip(intent)
            ACTION_STOP -> stopTrip()
            else -> if (!state.active) stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        cleanup()
        if (state.active) {
            state = State()
            publish()
        }
        super.onDestroy()
    }

    // ---------- Поездка ----------

    @SuppressLint("MissingPermission")
    private fun startTrip(intent: Intent) {
        cleanup()
        val place = Place(
            intent.getStringExtra(EXTRA_NAME) ?: "Остановка",
            intent.getDoubleExtra(EXTRA_LAT, 0.0),
            intent.getDoubleExtra(EXTRA_LON, 0.0),
        )
        dest = place
        phase = Phase.WAITING_GPS
        lastFix = null
        smoothedSpeed = null

        try {
            ServiceCompat.startForeground(
                this, ID_TRIP, tripNotification("Ищу GPS…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return
        }

        // не даём процессору уснуть, пока идёт поездка (максимум 6 часов)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeProspi:trip")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        if (intent.getBooleanExtra(EXTRA_SIMULATE, false)) {
            startSimulation(place)
        } else {
            val lm = getSystemService(LocationManager::class.java)
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (provider in lm.allProviders) {
                    runCatching { lm.requestLocationUpdates(provider, 2000L, 0f, this, Looper.getMainLooper()) }
                }
            }
            listeningGps = true
        }

        state = State(active = true, dest = place, phase = phase)
        publish()
    }

    private fun stopTrip() {
        cleanup()
        dest = null
        state = State()
        publish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        stopLocationUpdates()
        stopSimulation()
        stopAlarm()
        nm.cancel(ID_WARN)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
                    speed = v.toFloat()
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
        if (phase == Phase.ALARM) return

        val last = lastFix
        // грубая точка от вышек не должна перебивать недавнюю точную точку GPS
        if (last != null && loc.accuracy > 100 && last.accuracy <= 100 && loc.time - last.time < 30_000) return

        val d = Geo.distance(loc.latitude, loc.longitude, place.lat, place.lon)

        var sample: Double? = if (loc.hasSpeed()) loc.speed.toDouble() else null
        if (sample == null && last != null && loc.accuracy < 50 && last.accuracy < 50) {
            val dt = (loc.time - last.time) / 1000.0
            if (dt > 0.5) sample = last.distanceTo(loc) / dt
        }
        val s = sample
        if (s != null) smoothedSpeed = smoothedSpeed?.let { it * 0.7 + s * 0.3 } ?: s
        lastFix = loc
        val eta = smoothedSpeed?.takeIf { it > 1.0 }?.let { d / it }

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

        if (phase == Phase.WAITING_GPS) phase = Phase.RIDING
        when {
            hit -> startAlarm()
            warn && phase == Phase.RIDING -> sendWarning()
        }

        state = State(true, place, phase, loc.latitude, loc.longitude, d, eta, smoothedSpeed, loc.accuracy)
        publish()
        if (phase != Phase.ALARM) {
            val etaText = eta?.let { " · прибытие через ${Geo.formatEta(it)}" } ?: ""
            nm.notify(ID_TRIP, tripNotification("Осталось ${Geo.formatDistance(d)}$etaText"))
        }
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun publish() = listeners.forEach { it(state) }

    // ---------- Уведомления ----------

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_TRIP, "Поездка", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Расстояние до остановки во время поездки"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WARN, "Скоро выходить", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Мягкое предупреждение перед остановкой"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Будильник", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Громкий будильник у остановки"
                setSound(null, null) // звук включает сама служба на громкости будильника
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this, 1, Intent(this, TripService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun tripNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_TRIP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Еду до: ${dest?.name ?: "остановки"}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Завершить", stopPendingIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun sendWarning() {
        phase = Phase.WARNED
        nm.notify(
            ID_WARN,
            NotificationCompat.Builder(this, CH_WARN)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Скоро ваша остановка")
                .setContentText("Приготовьтесь выходить: ${dest?.name}")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build()
        )
    }

    // ---------- Будильник ----------

    private fun startAlarm() {
        phase = Phase.ALARM
        stopLocationUpdates()
        stopSimulation()
        nm.cancel(ID_WARN)

        val alarmIntent = Intent(this, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        val fullScreen = PendingIntent.getActivity(
            this, 2, alarmIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            ID_ALARM,
            NotificationCompat.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏰ Выходите!")
                .setContentText("Вы подъезжаете: ${dest?.name}")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setFullScreenIntent(fullScreen, true) // показывает экран будильника поверх блокировки
                .setContentIntent(fullScreen)
                .addAction(0, "Я проснулся", stopPendingIntent())
                .build()
        )
        nm.notify(ID_TRIP, tripNotification("Будильник звонит"))
        // если приложение сейчас открыто, показываем экран будильника сразу
        runCatching { startActivity(alarmIntent) }

        raiseAlarmVolume()
        playSound()
        vibrate()
    }

    private fun stopAlarm() {
        handler.removeCallbacks(rampVolume)
        player?.runCatching { stop(); release() }
        player = null
        tone?.runCatching { stopTone(); release() }
        tone = null
        vibrator.cancel()
        savedAlarmVolume?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) } }
        savedAlarmVolume = null
        nm.cancel(ID_ALARM)
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

    private fun playSound() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        volume = 0.25f
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM) // звучит даже в беззвучном режиме
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@TripService, uri!!)
                isLooping = true
                prepare()
                setVolume(volume, volume)
                start()
            }
            handler.postDelayed(rampVolume, 1000) // громкость нарастает
        } catch (e: Exception) {
            player?.release()
            player = null
            tone = runCatching {
                ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
                    .also { it.startTone(ToneGenerator.TONE_SUP_RINGTONE) }
            }.getOrNull()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 300, 700, 300, 1200, 600), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
