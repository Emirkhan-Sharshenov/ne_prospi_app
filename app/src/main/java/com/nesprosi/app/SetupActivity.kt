package com.nesprosi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nesprosi.app.Reliability.Item

/**
 * Проверка надёжности: всё, что на Android может помешать будильнику, с кнопкой «Исправить».
 * Показывается при первом запуске и из настроек.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.setupRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        list = findViewById(R.id.checkList)
        summary = findViewById(R.id.setupSummary)
        findViewById<View>(R.id.setupDone).setOnClickListener { finish() }
        findViewById<View>(R.id.backBtn).setOnClickListener { finish() }
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

    private fun render() {
        list.removeAllViews()
        val items = listOf(
            Triple(Item.LOCATION, R.string.chk_location, R.string.chk_location_desc),
            Triple(Item.LOCATION_SERVICES, R.string.chk_location_services, R.string.chk_location_services_desc),
            Triple(Item.NOTIFICATIONS, R.string.chk_notifications, R.string.chk_notifications_desc),
            Triple(Item.FULL_SCREEN, R.string.chk_fullscreen, R.string.chk_fullscreen_desc),
            Triple(Item.BATTERY, R.string.chk_battery, R.string.chk_battery_desc),
            Triple(Item.EXACT_ALARM, R.string.chk_exact, R.string.chk_exact_desc),
            Triple(Item.VOLUME, R.string.chk_volume, R.string.chk_volume_desc),
        )
        for ((item, title, desc) in items) {
            val ok = Reliability.isOk(this, item)
            val row = layoutInflater.inflate(R.layout.item_check, list, false)
            row.findViewById<TextView>(R.id.checkIcon).text = if (ok) "✅" else if (item == Item.VOLUME) "🔈" else "⚠️"
            row.findViewById<TextView>(R.id.checkTitle).setText(title)
            row.findViewById<TextView>(R.id.checkDesc).setText(desc)
            val fix = row.findViewById<Button>(R.id.checkFix)
            fix.visibility = if (ok) View.GONE else View.VISIBLE
            fix.setOnClickListener { fix(item) }
            list.addView(row)
        }
        val issues = Reliability.issueCount(this)
        summary.text = if (issues == 0) getString(R.string.setup_all_ok) else getString(R.string.setup_issues, issues)
        summary.setTextColor(ContextCompat.getColor(this, if (issues == 0) R.color.ok else R.color.warn))
    }

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

    private fun packageUri() = Uri.parse("package:$packageName")

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
    }
}
