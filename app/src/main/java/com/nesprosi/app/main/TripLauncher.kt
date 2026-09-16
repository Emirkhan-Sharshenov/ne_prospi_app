package com.nesprosi.app.main

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.nesprosi.app.Place
import com.nesprosi.app.R
import com.nesprosi.app.TripService
import com.nesprosi.app.batteryLevel

/**
 * Запуск поездки: разрешения → геолокация включена → заряд → будильник поверх блокировки → старт.
 * Создаётся как поле активности, чтобы успеть зарегистрировать запрос разрешений.
 */
class TripLauncher(
    private val activity: AppCompatActivity,
    private val onLocationGranted: () -> Unit,
    private val onStarted: () -> Unit,
) {
    private var pending: Pair<Place, Boolean>? = null

    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) {
            onLocationGranted()
            pending?.let { continueStart() }
        } else {
            pending = null
            AlertDialog.Builder(activity)
                .setTitle(R.string.perm_location_title)
                .setMessage(R.string.perm_location_msg)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun packageUri() = Uri.parse("package:${activity.packageName}")

    fun hasLocation() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Только разрешение на местоположение (кнопка «моё местоположение»). */
    fun requestLocation() {
        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun start(place: Place, simulate: Boolean) {
        pending = place to simulate
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        val missing = perms.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) continueStart() else permissions.launch(missing.toTypedArray())
    }

    private fun continueStart(batteryChecked: Boolean = false) {
        val (place, simulate) = pending ?: return
        if (!hasLocation()) return

        val lm = activity.getSystemService(LocationManager::class.java)
        if (!simulate && Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.location_off_title)
                .setMessage(R.string.location_off_msg)
                .setPositiveButton(R.string.enable) { _, _ -> activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val (level, charging) = batteryLevel(activity)
        if (!batteryChecked && level in 0 until 15 && !charging) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.battery_low_title, level))
                .setMessage(R.string.battery_low_msg)
                .setPositiveButton(R.string.start_anyway_2) { _, _ -> continueStart(batteryChecked = true) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(activity, R.string.notif_denied, Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= 34 && !activity.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.fsi_title)
                .setMessage(R.string.fsi_msg)
                .setPositiveButton(R.string.allow) { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri()))
                }
                .setNegativeButton(R.string.start_anyway) { _, _ -> launch(place, simulate) }
                .show()
            return
        }
        launch(place, simulate)
    }

    private fun launch(place: Place, simulate: Boolean) {
        pending = null
        TripService.start(activity, place, simulate)
        onStarted()
    }
}
