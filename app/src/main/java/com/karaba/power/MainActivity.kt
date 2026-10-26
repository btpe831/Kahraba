package com.karaba.power

import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.karaba.power.Prefs.monitoring

/**
 * The main screen: one-tap quick check. Tap, lay the phone under a halogen light, and it beeps
 * the answer (4 short beeps = kahraba, 1 long beep = moteur).
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private val ui = Handler(Looper.getMainLooper())
    private val beeper by lazy { Beeper(applicationContext) }
    private lateinit var sensors: SensorManager
    private var lightSensor: Sensor? = null

    private lateinit var resultBox: LinearLayout
    private lateinit var resultLabel: TextView
    private lateinit var resultText: TextView
    private lateinit var resultSub: TextView
    private lateinit var checkButton: Button
    private lateinit var historyText: TextView
    private lateinit var monitorButton: Button

    // Running check state (null when idle).
    private var check: QuickCheck? = null
    private var checkStartMs = 0L
    private var measureFromMs = 0L
    private var lux: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        MonitorService.createChannels(this)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)

        resultBox = findViewById(R.id.resultBox)
        resultLabel = findViewById(R.id.resultLabel)
        resultText = findViewById(R.id.resultText)
        resultSub = findViewById(R.id.resultSub)
        checkButton = findViewById(R.id.checkButton)
        historyText = findViewById(R.id.historyText)
        monitorButton = findViewById(R.id.monitorButton)

        checkButton.setOnClickListener { if (check == null) startCheck() else stopCheck(null) }
        monitorButton.setOnClickListener { startActivity(Intent(this, MonitorActivity::class.java)) }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Keep the always-on monitor running if it was on (e.g. after an update).
        if (monitoring && !MonitorState.running) MonitorService.send(this, MonitorService.ACTION_START)

        if (savedInstanceState == null && Prefs.autoCheck(this)) startCheck()
    }

    override fun onResume() {
        super.onResume()
        if (check == null) showLastResult()
        monitorButton.text = if (MonitorState.running) {
            "Always-on monitor: ON (${EventLog.label(MonitorState.source)})"
        } else {
            "Always-on monitor: OFF"
        }
    }

    override fun onStop() {
        super.onStop()
        if (check != null) stopCheck(null)
    }

    override fun onDestroy() {
        beeper.shutdown()
        super.onDestroy()
    }

    // ---- The check ----

    private fun startCheck() {
        val sensor = lightSensor
        if (sensor == null) {
            show("No light sensor", "This phone has no light sensor.", Source.UNKNOWN)
            return
        }
        beeper.cancel()
        val cfg = Prefs.config(this)
        check = QuickCheck(cfg.flickerThresholdPct, cfg.minLux)
        lux = null
        checkStartMs = System.currentTimeMillis()
        // A short pause first, so the hand putting the phone down isn't read as a wobble.
        measureFromMs = checkStartMs + SETTLE_MS
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        checkButton.text = "CANCEL"
        ui.post(sampler)
    }

    /** Sample-and-hold 5 times a second, same as the monitor. */
    private val sampler = object : Runnable {
        override fun run() {
            val q = check ?: return
            val now = System.currentTimeMillis()
            if (now < measureFromMs) {
                val left = (measureFromMs - now + 999) / 1000
                show("Put the phone down", "Starting in $left…", Source.UNKNOWN, label = "Get ready")
                ui.postDelayed(this, 200)
                return
            }
            val result = lux?.let { q.add(now, it) }
                ?: if (now - measureFromMs >= QuickCheck.MAX_MS) {
                    QuickCheck.Result(QuickCheck.Verdict.CANT_TELL, "No light readings – is a light on?")
                } else null
            if (result != null) {
                stopCheck(result)
                return
            }
            val steady = q.steadySeconds
            show(
                "Checking…",
                "${lux?.toInt() ?: "–"} lx" +
                    if (steady > 0) " · steady ${steady}s of ${QuickCheck.STEADY_NEEDED_MS / 1000}s" else "",
                Source.UNKNOWN,
                label = "Don't touch the phone",
            )
            ui.postDelayed(this, 200)
        }
    }

    private fun stopCheck(result: QuickCheck.Result?) {
        ui.removeCallbacks(sampler)
        sensors.unregisterListener(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        check = null
        checkButton.text = "CHECK NOW"
        if (result == null) {
            showLastResult()
            return
        }
        when (result.verdict) {
            QuickCheck.Verdict.KAHRABA -> beeper.quickResult(kahraba = true)
            QuickCheck.Verdict.MOTEUR -> beeper.quickResult(kahraba = false)
            QuickCheck.Verdict.CANT_TELL -> {}
        }
        CheckHistory.add(this, result.verdict)
        showResult(result.verdict, System.currentTimeMillis(), result.detail)
    }

    override fun onSensorChanged(event: SensorEvent) {
        lux = event.values[0].toDouble()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- Display ----

    private fun showLastResult() {
        historyText.text = CheckHistory.formatted(this)
        val last = CheckHistory.read(this).firstOrNull()
        if (last == null) {
            show("—", "Tap Check now to find out", Source.UNKNOWN)
        } else {
            showResult(last.verdict, last.timeMs, null)
        }
    }

    private fun showResult(v: QuickCheck.Verdict, timeMs: Long, detail: String?) {
        historyText.text = CheckHistory.formatted(this)
        val src = when (v) {
            QuickCheck.Verdict.KAHRABA -> Source.KAHRABA
            QuickCheck.Verdict.MOTEUR -> Source.MOTEUR
            QuickCheck.Verdict.CANT_TELL -> Source.UNKNOWN
        }
        val ago = PowerDetector.formatDuration(System.currentTimeMillis() - timeMs)
        val sub = "Checked at ${PowerDetector.formatClock(timeMs)} ($ago ago)" + (detail?.let { "\n$it" } ?: "")
        show(CheckHistory.label(v), sub, src)
    }

    private fun show(big: String, small: String, color: Source, label: String = "Last check") {
        resultLabel.text = label
        resultText.text = big
        resultSub.text = small
        resultBox.setBackgroundColor(MonitorService.colorFor(color))
    }

    companion object {
        private const val SETTLE_MS = 3_000L
    }
}
