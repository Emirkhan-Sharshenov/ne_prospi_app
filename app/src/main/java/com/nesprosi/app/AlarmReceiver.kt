package com.nesprosi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Срабатывание страховочного таймера через AlarmManager — даже если система закрыла приложение. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { TripService.backupFired(context) }
    }
}
