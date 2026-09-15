package com.nesprosi.app

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import org.osmdroid.mapsforge.MapsForgeTileProvider
import org.osmdroid.mapsforge.MapsForgeTileSource
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File

/** Офлайн-карта Кыргызстана (векторный файл mapsforge, ~58 МБ). */
object OfflineMap {
    private const val URL = "https://download.mapsforge.org/maps/v5/asia/kyrgyzstan.map"
    const val SIZE_MB = 58
    private var initialized = false

    private fun dir(ctx: Context) = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    fun file(ctx: Context) = File(dir(ctx), "kyrgyzstan.map")
    private fun partFile(ctx: Context) = File(dir(ctx), "kyrgyzstan.map.part")

    fun isReady(ctx: Context) = file(ctx).length() > 1_000_000

    sealed class Status {
        data object NotDownloaded : Status()
        data class Downloading(val percent: Int) : Status()
        data object Ready : Status()
        data object Failed : Status()
    }

    fun startDownload(ctx: Context) {
        partFile(ctx).delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle(ctx.getString(R.string.set_offline))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(partFile(ctx)))
        Prefs(ctx).offlineDownloadId = ctx.getSystemService(DownloadManager::class.java).enqueue(request)
    }

    /** Текущее состояние; заодно переименовывает скачанный файл, когда загрузка закончилась. */
    fun status(ctx: Context): Status {
        val prefs = Prefs(ctx)
        val id = prefs.offlineDownloadId
        if (id >= 0) {
            val dm = ctx.getSystemService(DownloadManager::class.java)
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (state) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            file(ctx).delete()
                            partFile(ctx).renameTo(file(ctx))
                            prefs.offlineDownloadId = -1
                        }
                        DownloadManager.STATUS_FAILED -> {
                            prefs.offlineDownloadId = -1
                            partFile(ctx).delete()
                            return Status.Failed
                        }
                        else -> return Status.Downloading(if (total > 0) (done * 100 / total).toInt() else 0)
                    }
                } else {
                    prefs.offlineDownloadId = -1
                }
            }
        }
        return if (isReady(ctx)) Status.Ready else Status.NotDownloaded
    }

    fun delete(ctx: Context) {
        val prefs = Prefs(ctx)
        if (prefs.offlineDownloadId >= 0) {
            ctx.getSystemService(DownloadManager::class.java).remove(prefs.offlineDownloadId)
            prefs.offlineDownloadId = -1
        }
        file(ctx).delete()
        partFile(ctx).delete()
    }

    fun tileProvider(ctx: Context): MapsForgeTileProvider {
        if (!initialized) {
            MapsForgeTileSource.createInstance(ctx.applicationContext as Application)
            initialized = true
        }
        val source = MapsForgeTileSource.createFromFiles(arrayOf(file(ctx)))
        return MapsForgeTileProvider(SimpleRegisterReceiver(ctx), source, null)
    }
}
