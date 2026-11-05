package com.kahraba.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kahraba.power.Prefs.monitoring

/** Restarts monitoring, and getting alerts from another phone, after a reboot or an update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.monitoring) {
            runCatching { MonitorService.send(context, MonitorService.ACTION_START) }
        }
        AlertsService.start(context)
    }
}
