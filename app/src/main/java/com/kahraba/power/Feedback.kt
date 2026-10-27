package com.kahraba.power

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

/** "Send feedback": opens the email app with the app version and phone details filled in. */
object Feedback {
    /** Where feedback goes. Empty hides every "Send feedback" button. */
    const val EMAIL = ""

    val enabled get() = EMAIL.isNotBlank()

    fun open(activity: Activity) {
        val version = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val scores = CheckHistory.scores(CheckHistory.read(activity))
        val right = scores.sumOf { it.right }
        val total = scores.sumOf { it.total }
        val body = "\n\n\n" +
            "-----\n" +
            "Kahraba or Not $version\n" +
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
            "Score: " + (if (total > 0) "right $right of $total" else "no confirmed checks yet") + "\n"
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, "Kahraba or Not feedback")
            .putExtra(Intent.EXTRA_TEXT, body)
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, "No email app found. Write to $EMAIL", Toast.LENGTH_LONG).show()
        }
    }
}
