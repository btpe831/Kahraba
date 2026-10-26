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

class MainActivity : AppCompatActivity() {

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
        setContentView(R.layout.activity_main)
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
        findViewById<Button>(R.id.quickCheck).setOnClickListener { chooseQuickCheckLength() }
        findViewById<Button>(R.id.setKahraba).setOnClickListener { confirmSet(Source.KAHRABA) }
        findViewById<Button>(R.id.setMoteur).setOnClickListener { confirmSet(Source.MOTEUR) }
        findViewById<Button>(R.id.openLog).setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        findViewById<Button>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.testKahraba).setOnClickListener { testBeeper.kahraba(respectQuiet = false) }
        findViewById<Button>(R.id.testMoteur).setOnClickListener { testBeeper.moteur(respectQuiet = false) }
        findViewById<Button>(R.id.silence).setOnClickListener {
            testBeeper.cancel()
            if (MonitorState.running) MonitorService.send(this, MonitorService.ACTION_SILENCE)
        }
        findViewById<Button>(R.id.shareData).setOnClickListener { shareRecordings() }
        battery.setOnClickListener { askBatteryExemption() }

        askNotificationPermission()
        if (monitoring && !MonitorState.running) MonitorService.send(this, MonitorService.ACTION_START)
    }

    override fun onResume() {
        super.onResume()
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
        val running = MonitorState.running
        val s = if (running) MonitorState.source else Prefs.savedSource(this)
        stateText.text = EventLog.label(s)
        stateBox.setBackgroundColor(MonitorService.colorFor(s))
        val since = if (running) MonitorState.sinceMs else Prefs.savedSince(this)
        sinceText.text = if (s == Source.UNKNOWN) {
            "Tap \"It's kahraba\" or \"It's moteur\" below if you know"
        } else {
            "since ${PowerDetector.formatClock(since)} (${PowerDetector.formatDuration(System.currentTimeMillis() - since)})"
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

    // ---- Quick check ----

    private fun chooseQuickCheckLength() {
        val def = Prefs.quickSeconds(this)
        val options = listOf(def, 30, 60).distinct()
        val labels = options.map {
            when {
                it < 30 -> "$it seconds (fast, less sure)"
                it < 60 -> "$it seconds"
                else -> "$it seconds (most reliable)"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Quick check")
            .setItems(labels) { _, i -> runQuickCheck(options[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runQuickCheck(seconds: Int) {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensor == null) {
            AlertDialog.Builder(this).setMessage("This phone has no light sensor.").setPositiveButton("OK", null).show()
            return
        }
        val cfg = Prefs.config(this)
        val check = QuickCheck(cfg.flickerThresholdPct, cfg.minLux)
        var lux: Double? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { lux = e.values[0].toDouble() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val start = System.currentTimeMillis()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Checking…")
            .setMessage("Lay the phone face-up under a halogen light and don't touch it.")
            .setCancelable(false)
            .setNegativeButton("Cancel", null)
            .create()

        // Sample-and-hold 5 times a second, same as the monitor.
        val sampler = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                lux?.let { check.add(now, it) }
                val left = seconds - (now - start) / 1000
                dialog.setMessage(
                    "Lay the phone face-up under a halogen light and don't touch it.\n\n" +
                        "${left.coerceAtLeast(0)} s left · ${lux?.toInt() ?: "–"} lx"
                )
                if (now - start >= seconds * 1000L) {
                    finish()
                } else {
                    ui.postDelayed(this, 200)
                }
            }

            fun finish() {
                sm.unregisterListener(listener)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog.dismiss()
                showQuickResult(check.result())
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                ui.removeCallbacks(sampler)
                sm.unregisterListener(listener)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialog.dismiss()
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        dialog.show()
        ui.postDelayed(sampler, 500) // let the first reading arrive
    }

    private fun showQuickResult(r: QuickCheck.Result) {
        val title = when (r.verdict) {
            QuickCheck.Verdict.MOTEUR -> "Looks like MOTEUR"
            QuickCheck.Verdict.KAHRABA -> "Looks like KAHRABA"
            QuickCheck.Verdict.CANT_TELL -> "Can't tell"
        }
        val body = buildString {
            if (r.confidence.isNotEmpty()) append("Confidence: ${r.confidence}\n\n")
            append(r.detail)
        }
        val b = AlertDialog.Builder(this).setTitle(title).setMessage(body).setNegativeButton("Close", null)
        val src = when (r.verdict) {
            QuickCheck.Verdict.MOTEUR -> Source.MOTEUR
            QuickCheck.Verdict.KAHRABA -> Source.KAHRABA
            QuickCheck.Verdict.CANT_TELL -> null
        }
        if (src != null) {
            b.setPositiveButton("Use this") { _, _ ->
                monitoring = true
                MonitorService.send(this, MonitorService.ACTION_SET) {
                    it.putExtra(MonitorService.EXTRA_SOURCE, src.name)
                }
            }
        }
        b.show()
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
