package com.karaba.power

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.karaba.power.Prefs.monitoring
import java.io.File

/** The secondary mode: always-on monitoring with the phone left under a light. */
class MonitorActivity : ThemedActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var stateBox: LinearLayout
    private lateinit var stateText: TextView
    private lateinit var sinceText: TextView
    private lateinit var detailText: TextView
    private lateinit var messageText: TextView
    private lateinit var startStop: Button
    private lateinit var battery: Button
    private val testBeeper by lazy { Beeper(applicationContext) }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        MonitorService.createChannels(this)

        stateBox = findViewById(R.id.stateBox)
        stateText = findViewById(R.id.stateText)
        sinceText = findViewById(R.id.sinceText)
        detailText = findViewById(R.id.detailText)
        messageText = findViewById(R.id.messageText)
        startStop = findViewById(R.id.startStop)
        battery = findViewById(R.id.battery)

        startStop.setOnClickListener {
            if (MonitorState.running) {
                MonitorService.send(this, MonitorService.ACTION_STOP)
            } else {
                askNotificationPermission()
                monitoring = true
                MonitorService.send(this, MonitorService.ACTION_START)
            }
            ui.postDelayed({ render() }, 300)
        }
        findViewById<Button>(R.id.setKahraba).setOnClickListener { confirmSet(Source.KAHRABA) }
        findViewById<Button>(R.id.setMoteur).setOnClickListener { confirmSet(Source.MOTEUR) }
        findViewById<Button>(R.id.openLog).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<Button>(R.id.testKahraba).setOnClickListener { testBeeper.kahraba(respectQuiet = false) }
        findViewById<Button>(R.id.testMoteur).setOnClickListener { testBeeper.moteur(respectQuiet = false) }
        findViewById<Button>(R.id.silence).setOnClickListener {
            testBeeper.cancel()
            if (MonitorState.running) MonitorService.send(this, MonitorService.ACTION_SILENCE)
        }
        findViewById<Button>(R.id.shareData).setOnClickListener { shareRecordings() }
        findViewById<Button>(R.id.monitorRoomButton).setOnClickListener {
            RoomPicker.pick(this, Prefs.monitorRoom(this)) { room ->
                Prefs.setMonitorRoom(this, room)
                renderRoom()
            }
        }
        battery.setOnClickListener { askBatteryExemption() }

        askNotificationPermission()
        if (monitoring && !MonitorState.running) MonitorService.send(this, MonitorService.ACTION_START)
    }

    override fun onResume() {
        super.onResume()
        renderRoom()
        ui.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        testBeeper.shutdown()
        super.onDestroy()
    }

    private fun render() {
        renderRoom()
        val running = MonitorState.running
        val s = MonitorState.source
        val box = stateBox.background.mutate() as android.graphics.drawable.GradientDrawable
        val label = findViewById<TextView>(R.id.stateLabel)
        when {
            // Off: show no source at all; an old one could be hours out of date.
            !running -> {
                label.text = "Always-on monitor"
                stateText.text = "Off"
                sinceText.text = "Tap Start to track kahraba and moteur"
                box.setColor(ContextCompat.getColor(this, R.color.no_power))
            }
            s == Source.UNKNOWN -> {
                label.text = "Currently on"
                stateText.text = "Not sure yet"
                sinceText.text = "Give it a few minutes under a halogen light,\nor tap \"It's kahraba\" / \"It's moteur\" below"
                box.setColor(MonitorService.colorFor(s))
            }
            else -> {
                label.text = "Currently on"
                stateText.text = EventLog.label(s)
                val since = MonitorState.sinceMs
                sinceText.text = "since ${PowerDetector.formatClock(since)} " +
                    "(${PowerDetector.formatDuration(System.currentTimeMillis() - since)})"
                box.setColor(MonitorService.colorFor(s))
            }
        }
        startStop.text = if (running) "Stop monitoring" else "Start monitoring"

        val thr = Prefs.config(this).flickerThresholdPct
        detailText.text = if (!running) {
            "Monitoring is OFF.\nTap Start, then leave the phone plugged in under a halogen light. " +
                "The screen can be off."
        } else buildString {
            if (!MonitorState.hasLightSensor) append("No light sensor found on this phone!\n")
            append("Light:    ${MonitorState.lux?.let { "${it.toInt()} lx" } ?: "–"}\n")
            append("Wobble:   ${MonitorState.flickerPct?.let { "%.2f%%".format(it) } ?: "–"}  (limit ${"%.2f".format(thr)}%)\n")
            append("Check:    ${MonitorState.lightStatus}\n")
            append("Charger:  ${when (MonitorState.plugged) { true -> "connected"; false -> "NOT connected"; null -> "–" }}\n")
            append("Sensor:   ${MonitorState.sensorUpdatesPerMin} readings/min")
            val calLeft = MonitorState.calibrationEndsMs - System.currentTimeMillis()
            if (calLeft > 0) append("\n\nLearning ${EventLog.label(MonitorState.calibrationSource)}: ${calLeft / 1000}s left – keep the phone still")
        }

        val msg = MonitorState.message
        messageText.visibility = if (msg == null) View.GONE else View.VISIBLE
        messageText.text = msg
        messageText.setOnClickListener { MonitorState.message = null; render() }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun renderRoom() {
        val room = Prefs.monitorRoom(this)
        findViewById<Button>(R.id.monitorRoomButton).text = room.ifEmpty { "None" }
        findViewById<TextView>(R.id.monitorRoomInfo).text = RoomPicker.describe(
            this, room,
            "Optional. Pick the room this phone sits in to share what it learned with quick checks there. " +
                "With learned levels, the monitor can tell kahraba from moteur straight away, even a calm moteur.",
        )
    }

    private fun confirmSet(s: Source) {
        val name = EventLog.label(s).lowercase()
        AlertDialog.Builder(this)
            .setTitle("It's $name?")
            .setMessage(
                "Only confirm if you're sure (lamp post ${if (s == Source.KAHRABA) "OFF" else "GREEN"}).\n\n" +
                    "The app will switch to ${EventLog.label(s)} and spend 2 minutes learning what " +
                    "$name looks like. Keep the phone still under the halogen light."
            )
            .setPositiveButton("Yes, it's $name") { _, _ ->
                monitoring = true
                MonitorService.send(this, MonitorService.ACTION_SET) {
                    it.putExtra(MonitorService.EXTRA_SOURCE, s.name)
                    it.putExtra(MonitorService.EXTRA_CALIBRATE, true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Permissions & sharing ----

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun askBatteryExemption() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun shareRecordings() {
        val files = mutableListOf<File>()
        EventLog.file(this).takeIf { it.exists() }?.let { files += it }
        File(filesDir, "data").listFiles()?.sortedBy { it.name }?.let { files += it }
        if (files.isEmpty()) {
            AlertDialog.Builder(this).setMessage("Nothing recorded yet. Start monitoring first.")
                .setPositiveButton("OK", null).show()
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/csv")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share recordings"))
    }
}
