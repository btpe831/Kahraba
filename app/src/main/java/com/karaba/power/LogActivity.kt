package com.karaba.power

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {
    private lateinit var text: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        text = findViewById(R.id.logText)
        findViewById<Button>(R.id.shareLog).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Karaba switch log")
                .putExtra(Intent.EXTRA_TEXT, EventLog.formatted(this))
            startActivity(Intent.createChooser(send, "Share log"))
        }
        findViewById<Button>(R.id.clearLog).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Delete the whole switch log?")
                .setPositiveButton("Delete") { _, _ ->
                    EventLog.clear(this)
                    text.text = EventLog.formatted(this)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        text.text = EventLog.formatted(this)
    }
}
