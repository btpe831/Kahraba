package com.kahraba.power

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A light meter: live lux, a chart, and recordings saved as CSV (time_s, clock, lux), like the
 * Physics Toolbox recordings used to tune the app. Screen brightness is locked while it's open.
 */
class LightMeterActivity : ThemedActivity(), SensorEventListener {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private lateinit var chart: LuxChart
    private lateinit var luxValue: TextView
    private lateinit var luxStats: TextView
    private lateinit var recStatus: TextView
    private lateinit var recButton: MaterialButton

    // Live readings (last 10 minutes) for the chart and stats.
    private var liveT = LongArray(8192)
    private var liveV = DoubleArray(8192)
    private var liveN = 0

    // The current or last recording.
    private val recT = ArrayList<Long>()
    private val recV = ArrayList<Double>()
    private var recording = false
    /** Paused: the readout, chart and recording all hold still until resumed. */
    private var paused = false
    private lateinit var pauseButton: MaterialButton
    private var recStartMs = 0L
    private var recEndMs = 0L

    private var latest: Double? = null

    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) {
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(csv().toByteArray()) } }
                .onSuccess { toast("Saved") }
                .onFailure { toast("Couldn't save the file") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_light_meter)
        setupHeader("Light meter")
        chart = findViewById(R.id.chart)
        luxValue = findViewById(R.id.luxValue)
        luxStats = findViewById(R.id.luxStats)
        recStatus = findViewById(R.id.recStatus)
        recButton = findViewById(R.id.recButton)

        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (light == null) {
            luxStats.text = "This phone has no light sensor."
        } else {
            sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_FASTEST)
        }

        // Keep the screen on at a fixed brightness so auto-brightness doesn't change the reading.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = LOCKED_BRIGHTNESS }

        buildRangeRow()
        recButton.setOnClickListener { if (recording) stopRecording() else startRecording() }
        findViewById<MaterialButton>(R.id.saveButton).setOnClickListener {
            if (recT.isEmpty()) toast("Record something first") else launchSafely { createCsv.launch(fileName()) }
        }
        findViewById<MaterialButton>(R.id.shareButton).setOnClickListener { share() }
        pauseButton = findViewById(R.id.pauseButton)
        pauseButton.setOnClickListener { setPaused(!paused) }
        findViewById<MaterialButton>(R.id.restartButton).setOnClickListener { restart() }
        renderRecording()
        ui.post(sampler)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        sensors.unregisterListener(this)
        super.onDestroy()
    }


    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values[0].toDouble()
        latest = v
        add(System.currentTimeMillis(), v)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** 10 readings a second (holding the last value), like the Physics Toolbox recordings. */
    private val sampler = object : Runnable {
        override fun run() {
            latest?.let { add(System.currentTimeMillis(), it) }
            render()
            ui.postDelayed(this, 100)
        }
    }

    private fun add(t: Long, v: Double) {
        if (paused) return
        if (liveN == liveT.size) {
            // Drop the oldest half once full (keeps ~10+ minutes at 10 readings a second).
            val half = liveN / 2
            System.arraycopy(liveT, half, liveT, 0, liveN - half)
            System.arraycopy(liveV, half, liveV, 0, liveN - half)
            liveN -= half
        }
        liveT[liveN] = t
        liveV[liveN] = v
        liveN++
        if (recording) {
            recT += t
            recV += v
        }
    }

    private var lastRender = 0L

    private fun render() {
        val now = System.currentTimeMillis()
        if (paused || now - lastRender < 250) return
        lastRender = now
        chart.setData(liveT, liveV, liveN)
        val v = latest
        luxValue.text = v?.let { if (it >= 100) it.toInt().toString() else "%.1f".format(it) } ?: "–"
        // Stats over the last minute, and wobble over the last 4 seconds (as the app judges it).
        fun window(ms: Long): List<Double> {
            val out = ArrayList<Double>()
            var i = liveN - 1
            while (i >= 0 && liveT[i] >= now - ms) { out += liveV[i]; i-- }
            return out
        }
        val minute = window(60_000)
        val four = window(PowerDetector.FLICKER_WINDOW_MS)
        val med = PowerDetector.median(four)
        val wobble = if (med != null && med > 0) (four.max() - four.min()) / med * 100 else null
        luxStats.text = if (minute.isEmpty()) "Waiting for readings…" else
            "Last minute: min ${minute.min().toInt()} · avg ${minute.average().toInt()} · max ${minute.max().toInt()} lux\n" +
                "Wobble now: ${wobble?.let { "%.1f%%".format(it) } ?: "–"}" +
                if (med != null && med < Prefs.config(this).minLux) "  ·  too dim to judge" else ""
        if (recording) renderRecording()
    }

    // ---- Recording ----

    private fun setPaused(p: Boolean) {
        paused = p
        pauseButton.text = if (p) "Resume" else "Pause"
        if (p) luxStats.text = "Paused. Tap Resume to carry on" + if (recording) " recording." else "."
        renderRecording()
    }

    /** Clears the chart and, if recording, starts the recording again from zero. */
    private fun restart() {
        liveN = 0
        recT.clear(); recV.clear()
        recStartMs = System.currentTimeMillis()
        recEndMs = recStartMs
        chart.setData(liveT, liveV, 0)
        setPaused(false)
        lastRender = 0
        render()
    }

    private fun startRecording() {
        setPaused(false)
        recT.clear(); recV.clear()
        recStartMs = System.currentTimeMillis()
        recording = true
        recButton.text = "Stop recording"
        renderRecording()
    }

    private fun stopRecording() {
        recording = false
        recEndMs = System.currentTimeMillis()
        recButton.text = "Start recording"
        renderRecording()
    }

    private fun renderRecording() {
        recStatus.text = when {
            recording && paused -> "Paused · ${recT.size} readings so far"
            recording -> "Recording ${PowerDetector.formatDuration(System.currentTimeMillis() - recStartMs)} · ${recT.size} readings"
            recT.isNotEmpty() -> "Recorded ${PowerDetector.formatDuration(recEndMs - recStartMs)} " +
                "(${recT.size} readings). Save or share it."
            else -> "Not recording"
        }
    }

    private fun fileName(): String =
        "ijit-light-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(recStartMs)) + ".csv"

    /** time_s (from the start), clock time and lux, one reading per line. */
    private fun csv(): String {
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder("time_s,clock,lux\n")
        val start = recT.firstOrNull() ?: recStartMs
        for (i in recT.indices) {
            sb.append("%.3f".format(Locale.US, (recT[i] - start) / 1000.0)).append(',')
                .append(clock.format(Date(recT[i]))).append(',')
                .append("%.2f".format(Locale.US, recV[i])).append('\n')
        }
        return sb.toString()
    }

    private fun share() {
        if (recT.isEmpty()) {
            toast("Record something first")
            return
        }
        val dir = File(filesDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName()).apply { writeText(csv()) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        launchSafely {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Share recording",
            ))
        }
    }

    // ---- Chart range ----

    private fun buildRangeRow() {
        val row = findViewById<LinearLayout>(R.id.rangeRow)
        row.background = rounded(color(R.attr.appSurfaceAlt), color(R.attr.appBorder), 24f)
        row.setPadding(px(4), px(4), px(4), px(4))
        val options = listOf("30s" to 30, "1 min" to 60, "5 min" to 300, "10 min" to 600)
        fun paint() {
            for (i in 0 until row.childCount) {
                val tv = row.getChildAt(i) as TextView
                val on = options[i].second == chart.windowSeconds
                tv.setTextColor(color(if (on) R.attr.appOnAccent else R.attr.appMuted))
                tv.background = if (on) rounded(color(R.attr.appAccent), color(R.attr.appAccent), 20f) else null
            }
        }
        for ((label, secs) in options) {
            row.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, px(10), 0, px(10))
                setOnClickListener { chart.windowSeconds = secs; paint() }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint()
    }

    private fun rounded(fill: Int, stroke: Int, r: Float) = GradientDrawable().apply {
        cornerRadius = r * resources.displayMetrics.density
        setColor(fill)
        setStroke(px(1), stroke)
    }

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun color(attr: Int): Int {
        val v = TypedValue()
        theme.resolveAttribute(attr, v, true)
        return v.data
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** Fixed screen brightness while measuring (0–1). */
        const val LOCKED_BRIGHTNESS = 0.25f
    }
}
