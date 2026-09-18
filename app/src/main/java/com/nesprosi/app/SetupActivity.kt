package com.nesprosi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.nesprosi.app.Reliability.Item

/**
 * Проверка надёжности: всё, что на Android может помешать будильнику, с кнопкой «Исправить».
 * Показывается при первом запуске и из настроек.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var testBtn: MaterialButton
    private var testPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopTestRunnable = Runnable { stopTest() }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setupRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        list = findViewById(R.id.checkList)
        testBtn = findViewById(R.id.testAlarmBtn)
        findViewById<View>(R.id.setupDone).setOnClickListener { finish() }
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        testBtn.setOnClickListener { if (testPlayer == null) startTest() else stopTest() }
        Prefs(this).setupShown = true

        findViewById<TextView>(R.id.oemText).setText(
            when (Reliability.brand()) {
                Reliability.Brand.SAMSUNG -> R.string.oem_samsung
                Reliability.Brand.XIAOMI -> R.string.oem_xiaomi
                Reliability.Brand.HUAWEI -> R.string.oem_huawei
                Reliability.Brand.OPPO -> R.string.oem_oppo
                Reliability.Brand.OTHER -> R.string.oem_other
            }
        )
        findViewById<View>(R.id.oemBtn).setOnClickListener { openAppSettings() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        stopTest()
        super.onPause()
    }

    private fun render() {
        list.removeAllViews()
        val items = listOf(
            Row(Item.LOCATION, R.drawable.ic_gps, R.string.chk_location, R.string.chk_location_desc),
            Row(Item.LOCATION_SERVICES, R.drawable.ic_location, R.string.chk_location_services, R.string.chk_location_services_desc),
            Row(Item.NOTIFICATIONS, R.drawable.ic_notifications, R.string.chk_notifications, R.string.chk_notifications_desc),
            Row(Item.FULL_SCREEN, R.drawable.ic_lock_clock, R.string.chk_fullscreen, R.string.chk_fullscreen_desc),
            Row(Item.BATTERY, R.drawable.ic_battery, R.string.chk_battery, R.string.chk_battery_desc),
            Row(Item.EXACT_ALARM, R.drawable.ic_alarm_on, R.string.chk_exact, R.string.chk_exact_desc),
            Row(Item.VOLUME, R.drawable.ic_volume, R.string.chk_volume, R.string.chk_volume_desc_short),
        )
        for (r in items) {
            val ok = Reliability.isOk(this, r.item)
            val row = layoutInflater.inflate(R.layout.item_check, list, false)
            row.findViewById<ImageView>(R.id.checkIcon).apply {
                setImageResource(r.icon)
                setColorFilter(color(if (ok) R.color.accent else R.color.warn))
            }
            row.findViewById<View>(R.id.checkIconBg).background.mutate()
                .setTint(color(if (ok) R.color.surface_mid else R.color.warn_container))
            row.findViewById<TextView>(R.id.checkTitle).setText(r.title)
            row.findViewById<TextView>(R.id.checkDesc).setText(if (r.item == Item.VOLUME && !ok) R.string.chk_volume_desc else r.desc)
            row.findViewById<View>(R.id.checkOk).visibility = if (ok) View.VISIBLE else View.GONE
            row.findViewById<Button>(R.id.checkFix).apply {
                visibility = if (ok) View.GONE else View.VISIBLE
                setOnClickListener { fix(r.item) }
            }
            list.addView(row)
        }

        val issues = Reliability.issueCount(this)
        val summary = findViewById<TextView>(R.id.setupSummary)
        val card = findViewById<MaterialCardView>(R.id.summaryCard)
        val text = findViewById<TextView>(R.id.summaryText)
        if (issues == 0) {
            summary.setText(R.string.setup_all_ok)
            text.setText(R.string.setup_all_ok_desc)
            card.setCardBackgroundColor(color(R.color.ok_container))
            summary.setTextColor(color(R.color.on_ok_container))
            text.setTextColor(color(R.color.on_ok_container))
            findViewById<View>(R.id.summaryIconBg).background.mutate().setTint(color(R.color.ok))
            findViewById<ImageView>(R.id.summaryIcon).apply {
                setImageResource(R.drawable.ic_check_circle)
                setColorFilter(0xFFFFFFFF.toInt())
            }
            findViewById<View>(R.id.priorityBadge).visibility = View.GONE
        } else {
            summary.text = resources.getQuantityString(R.plurals.setup_issues, issues, issues)
            text.setText(R.string.setup_intro)
            card.setCardBackgroundColor(color(R.color.warn_container))
            summary.setTextColor(color(R.color.on_warn_container))
            text.setTextColor(color(R.color.on_warn_container))
            findViewById<View>(R.id.summaryIconBg).background.mutate().setTint(color(R.color.amber))
            findViewById<ImageView>(R.id.summaryIcon).apply {
                setImageResource(R.drawable.ic_shield)
                setColorFilter(color(R.color.navy))
            }
            findViewById<View>(R.id.priorityBadge).visibility = View.VISIBLE
        }
    }

    private data class Row(val item: Item, val icon: Int, val title: Int, val desc: Int)

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    @SuppressLint("BatteryLife")
    private fun fix(item: Item) {
        runCatching {
            when (item) {
                Item.LOCATION -> permissions.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
                Item.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) {
                    permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                Item.LOCATION_SERVICES -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                Item.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri()))
                }
                Item.BATTERY -> startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))
                Item.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
                }
                Item.VOLUME -> startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
            }
        }.onFailure { openAppSettings() }
    }

    // ---------- Проверка звука и вибрации ----------

    /** 5 секунд звука будильника и вибрации — как будет в поездке. */
    private fun startTest() {
        val prefs = Prefs(this)
        val uri = prefs.alarmSound?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (!prefs.vibrationOnly && uri != null) {
            testPlayer = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(if (prefs.headphonesOnly) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(this@SetupActivity, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()
        }
        if (testPlayer == null) testPlayer = MediaPlayer() // только вибрация — но кнопка всё равно «Остановить»
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300, 700, 300, 1200), -1))
        testBtn.setText(R.string.test_alarm_stop)
        testBtn.setIconResource(R.drawable.ic_stop_circle)
        handler.postDelayed(stopTestRunnable, 5000)
    }

    private fun stopTest() {
        handler.removeCallbacks(stopTestRunnable)
        testPlayer?.runCatching { stop(); release() }
        testPlayer = null
        vibrator.cancel()
        if (::testBtn.isInitialized) {
            testBtn.setText(R.string.test_alarm)
            testBtn.setIconResource(R.drawable.ic_music)
        }
    }

    private fun packageUri() = Uri.parse("package:$packageName")

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
    }
}
