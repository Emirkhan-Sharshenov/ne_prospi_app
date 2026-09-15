package com.nesprosi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var soundBtn: Button
    private lateinit var smsSwitch: MaterialSwitch
    private lateinit var offlineStatus: TextView
    private lateinit var offlineDownload: Button
    private lateinit var offlineDelete: Button
    private val handler = Handler(Looper.getMainLooper())
    private val pollOffline = object : Runnable {
        override fun run() {
            if (renderOffline()) handler.postDelayed(this, 1000)
        }
    }

    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        // «По умолчанию» в списке — это стандартная мелодия будильника
        prefs.alarmSound = uri?.takeIf { it != RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) }?.toString()
        renderSound()
    }

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            prefs.smsEnabled = false
            smsSwitch.isChecked = false
            Toast.makeText(this, R.string.sms_perm_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        prefs = Prefs(this)
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<View>(R.id.setupBtn).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        setupLanguage()
        setupTheme()
        setupAlarm()
        setupFamily()
        setupOffline()
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollOffline)
    }

    override fun onPause() {
        handler.removeCallbacks(pollOffline)
        super.onPause()
    }

    private fun setupLanguage() {
        val group = findViewById<RadioGroup>(R.id.langGroup)
        group.check(
            when (Lang.current()) {
                "ru" -> R.id.langRu
                "ky" -> R.id.langKy
                else -> R.id.langSystem
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            val locales = when (id) {
                R.id.langRu -> LocaleListCompat.forLanguageTags("ru")
                R.id.langKy -> LocaleListCompat.forLanguageTags("ky")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(locales) // экран перезапустится на новом языке
        }
    }

    private fun setupTheme() {
        val group = findViewById<RadioGroup>(R.id.themeGroup)
        group.check(
            when (prefs.theme) {
                Prefs.THEME_LIGHT -> R.id.themeLight
                Prefs.THEME_DARK -> R.id.themeDark
                else -> R.id.themeSystem
            }
        )
        group.setOnCheckedChangeListener { _, id ->
            prefs.theme = when (id) {
                R.id.themeLight -> Prefs.THEME_LIGHT
                R.id.themeDark -> Prefs.THEME_DARK
                else -> Prefs.THEME_SYSTEM
            }
            prefs.applyTheme()
        }
    }

    private fun setupAlarm() {
        soundBtn = findViewById(R.id.soundBtn)
        soundBtn.setOnClickListener {
            val current = prefs.alarmSound?.let { Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtonePicker.launch(
                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            )
        }
        renderSound()
        bindSwitch(R.id.vibrationOnlySwitch, prefs.vibrationOnly) { prefs.vibrationOnly = it }
        bindSwitch(R.id.flashlightSwitch, prefs.flashlight) { prefs.flashlight = it }
        bindSwitch(R.id.headphonesSwitch, prefs.headphonesOnly) { prefs.headphonesOnly = it }
        bindSwitch(R.id.voiceSwitch, prefs.voice) { prefs.voice = it }
    }

    private fun renderSound() {
        val title = prefs.alarmSound?.let { runCatching { RingtoneManager.getRingtone(this, Uri.parse(it))?.getTitle(this) }.getOrNull() }
        soundBtn.text = getString(R.string.alarm_sound, title ?: getString(R.string.alarm_sound_default))
    }

    private fun setupFamily() {
        val phone = findViewById<EditText>(R.id.phoneInput)
        phone.setText(prefs.contactPhone)
        phone.doAfterTextChanged { prefs.contactPhone = it?.toString().orEmpty() }
        smsSwitch = bindSwitch(R.id.smsSwitch, prefs.smsEnabled) { on ->
            prefs.smsEnabled = on
            if (on && ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                smsPermission.launch(Manifest.permission.SEND_SMS)
            }
        }
    }

    private fun setupOffline() {
        offlineStatus = findViewById(R.id.offlineStatus)
        offlineDownload = findViewById(R.id.offlineDownload)
        offlineDelete = findViewById(R.id.offlineDelete)
        offlineDownload.text = getString(R.string.offline_download, OfflineMap.SIZE_MB)
        offlineDownload.setOnClickListener {
            OfflineMap.startDownload(this)
            handler.removeCallbacks(pollOffline)
            handler.post(pollOffline)
        }
        offlineDelete.setOnClickListener {
            OfflineMap.delete(this)
            renderOffline()
        }
        bindSwitch(R.id.offlineAlwaysSwitch, prefs.offlineAlways) { prefs.offlineAlways = it }
    }

    /** Показывает состояние офлайн-карты. true — идёт загрузка, нужно обновлять дальше. */
    private fun renderOffline(): Boolean {
        val status = OfflineMap.status(this)
        offlineStatus.text = when (status) {
            is OfflineMap.Status.Downloading -> getString(R.string.offline_downloading, status.percent)
            OfflineMap.Status.Ready -> getString(R.string.offline_ready, (OfflineMap.file(this).length() / 1_000_000).toInt())
            OfflineMap.Status.Failed -> getString(R.string.offline_failed)
            OfflineMap.Status.NotDownloaded -> getString(R.string.offline_not_downloaded)
        }
        offlineDownload.visibility = if (status is OfflineMap.Status.NotDownloaded || status == OfflineMap.Status.Failed) View.VISIBLE else View.GONE
        offlineDelete.visibility = if (status == OfflineMap.Status.NotDownloaded || status == OfflineMap.Status.Failed) View.GONE else View.VISIBLE
        return status is OfflineMap.Status.Downloading
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch {
        val sw = findViewById<MaterialSwitch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        return sw
    }
}
