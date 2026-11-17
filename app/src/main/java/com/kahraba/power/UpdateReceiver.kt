package com.kahraba.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * Hears back from Android's installer after an in-app update (1.5.15). On Android 12 and later the
 * app can update itself without asking; when Android still wants a "yes" (Android 11, or the first
 * time), it sends its confirmation screen here, and it's shown. When it succeeds, the app restarts
 * as the new version.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Toast.makeText(context, "The update didn't install. ${why.take(120)}".trim(), Toast.LENGTH_LONG).show()
            }
        }
    }
}
