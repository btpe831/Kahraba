package com.kahraba.power

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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kahraba.power.Prefs.monitoring
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
        setupHeader("Always-on monitor")
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
        val label = findViewById<TextView>(R.id.stateLabel)
        when {
            // Off: show no source at all; an old one could be hours out of date.
            !running -> {
                label.text = "Monitor"
                stateText.text = "Off"
                sinceText.text = "Tap Start to track kahraba and moteur"
                paintState(ContextCompat.getColor(this, R.color.no_power))
            }
            s == Source.UNKNOWN -> {
                label.text = "Now"
                stateText.text = "Not sure yet"
                sinceText.text = "Give it a few minutes under a halogen light,\nor tap \"It's kahraba\" / \"It's moteur\" below"
                paintState(MonitorService.colorFor(s))
            }
            else -> {
                label.text = "Now"
                stateText.text = EventLog.label(s)
                val since = MonitorState.sinceMs
                sinceText.text = "since ${PowerDetector.formatClock(since)} " +
                    "(${PowerDetector.formatDuration(System.currentTimeMillis() - since)})"
                paintState(MonitorService.colorFor(s))
            }
        }
        startStop.text = if (running) "Stop monitoring" else "Start monitoring"

        val thr = Prefs.config(this).flickerThresholdPct
        val stats = listOf(
            "Light" to (if (running) MonitorState.lux?.let { "${it.toInt()} lx" } ?: "–" else "–"),
            "Wobble" to (if (running) MonitorState.flickerPct?.let { "%.1f%%".format(it) } ?: "–" else "–"),
            "Charger" to (if (running) when (MonitorState.plugged) { true -> "On"; false -> "Off"; null -> "–" } else "–"),
            "Readings" to (if (running) "${MonitorState.sensorUpdatesPerMin}/min" else "–"),
        )
        val labels = listOf(R.id.monLabel1, R.id.monLabel2, R.id.monLabel3, R.id.monLabel4)
        val values = listOf(R.id.monValue1, R.id.monValue2, R.id.monValue3, R.id.monValue4)
        for ((i, st) in stats.withIndex()) {
            findViewById<TextView>(labels[i]).text = st.first
            findViewById<TextView>(values[i]).text = st.second
        }
        detailText.text = if (!running) {
            "Tap Start, then leave the phone plugged into a wall socket under a halogen light. The screen can be off."
        } else buildString {
            if (!MonitorState.hasLightSensor) append("This phone has no light sensor, so only power cuts can be seen.\n")
            append(MonitorState.lightStatus)
            append("\nA wobble above ${"%.1f".format(thr)}% counts as moteur. The charger going off means a power cut.")
            val calLeft = MonitorState.calibrationEndsMs - System.currentTimeMillis()
            if (calLeft > 0) append("\n\nLearning ${EventLog.label(MonitorState.calibrationSource).lowercase()}: ${calLeft / 1000}s left. Keep the phone still.")
        }

        val msg = MonitorState.message
        messageText.visibility = if (msg == null) View.GONE else View.VISIBLE
        messageText.text = msg
        messageText.setOnClickListener { MonitorState.message = null; render() }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battery.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    /** The status card: a soft gradient in the status colour, like NOW on the main screen. */
    private fun paintState(c: Int) {
        val dp = resources.displayMetrics.density
        fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(x: Int, sh: Int) = (x shr sh) and 0xFF
            fun m(sh: Int) = (ch(a, sh) * (1 - f) + ch(b, sh) * f).toInt()
            return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
        }
        stateBox.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(mix(c, 0xFFFFFFFF.toInt(), 0.18f), c, mix(c, 0xFF000000.toInt(), 0.35f)),
        ).apply { cornerRadius = 28 * dp }
    }

    private fun renderRoom() {
        val room = Prefs.monitorRoom(this)
        findViewById<Button>(R.id.monitorRoomButton).text = room.ifEmpty { "Choose" } + "  ▾"
        findViewById<TextView>(R.id.monitorRoomInfo).text = RoomPicker.describe(
            this, room,
            "Optional: the room this phone is in. Once that room is learned, " +
                "the monitor can tell kahraba from a steady moteur.",
        )
    }

    private fun confirmSet(s: Source) {
        val name = EventLog.label(s).lowercase()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("It's $name?")
            .setMessage(
                "Only confirm if you know for sure.\n\n" +
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
        launchSafely {
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
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setMessage("Nothing recorded yet. Start monitoring first.")
                .setPositiveButton("OK", null).show()
            return
        }
        val uris = ArrayList(files.map { FileProvider.getUriForFile(this, "$packageName.files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/csv")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launchSafely { startActivity(Intent.createChooser(send, "Share recordings")) }
    }
}
