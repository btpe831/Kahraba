package com.kahraba.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kahraba.power.Prefs.monitoring

/** Restarts monitoring after the phone reboots or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.monitoring) {
            runCatching { MonitorService.send(context, MonitorService.ACTION_START) }
        }
    }
}
