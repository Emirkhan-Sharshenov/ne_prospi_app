package com.nesprosi.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Что на телефоне может помешать будильнику. Показывается на экране «Проверка надёжности». */
object Reliability {
    enum class Item { LOCATION, LOCATION_SERVICES, NOTIFICATIONS, FULL_SCREEN, BATTERY, EXACT_ALARM, VOLUME }

    fun isOk(ctx: Context, item: Item): Boolean = when (item) {
        Item.LOCATION ->
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        Item.LOCATION_SERVICES ->
            Build.VERSION.SDK_INT < 28 || ctx.getSystemService(LocationManager::class.java).isLocationEnabled
        Item.NOTIFICATIONS ->
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        Item.FULL_SCREEN ->
            Build.VERSION.SDK_INT < 34 || ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        Item.BATTERY ->
            ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        Item.EXACT_ALARM ->
            Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        Item.VOLUME ->
            ctx.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) > 0
    }

    /** Сколько важных пунктов не в порядке (громкость не считаем — её поднимет сама служба). */
    fun issueCount(ctx: Context): Int =
        Item.entries.filter { it != Item.VOLUME }.count { !isOk(ctx, it) }

    enum class Brand { SAMSUNG, XIAOMI, HUAWEI, OPPO, OTHER }

    fun brand(): Brand {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            "samsung" in m -> Brand.SAMSUNG
            listOf("xiaomi", "redmi", "poco").any { it in m } -> Brand.XIAOMI
            listOf("huawei", "honor").any { it in m } -> Brand.HUAWEI
            listOf("oppo", "realme", "oneplus", "vivo").any { it in m } -> Brand.OPPO
            else -> Brand.OTHER
        }
    }
}
