package com.karaba.power

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.karaba.power.Prefs.monitoring
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The main screen: one-tap quick check. Tap, lay the phone under a halogen light, and it beeps
 * the answer (4 short beeps = kahraba, 1 long beep = moteur).
 */
class MainActivity : AppCompatActivity(), SensorEventListener {

    private val ui = Handler(Looper.getMainLooper())
    private val beeper by lazy { Beeper(applicationContext) }
    private lateinit var sensors: SensorManager
    private var lightSensor: Sensor? = null

    private lateinit var orb: LinearLayout
    private lateinit var orbIcon: ImageView
    private lateinit var orbTitle: TextView
    private lateinit var orbSub: TextView
    private lateinit var ring: CircularProgressIndicator
    private lateinit var statusLine: TextView
    private lateinit var checkButton: MaterialButton
    private lateinit var historyList: LinearLayout
    private lateinit var monitorDot: View
    private lateinit var monitorSub: TextView

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

        orb = findViewById(R.id.orb)
        orbIcon = findViewById(R.id.orbIcon)
        orbTitle = findViewById(R.id.orbTitle)
        orbSub = findViewById(R.id.orbSub)
        ring = findViewById(R.id.ring)
        statusLine = findViewById(R.id.statusLine)
        checkButton = findViewById(R.id.checkButton)
        historyList = findViewById(R.id.historyList)
        monitorDot = findViewById(R.id.monitorDot)
        monitorSub = findViewById(R.id.monitorSub)

        checkButton.setOnClickListener { if (check == null) startCheck() else stopCheck(null) }
        orb.setOnClickListener { if (check == null) startCheck() }
        findViewById<View>(R.id.monitorCard).setOnClickListener {
            startActivity(Intent(this, MonitorActivity::class.java))
        }
        findViewById<View>(R.id.helpButton).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Keep the always-on monitor running if it was on (e.g. after an update).
        if (monitoring && !MonitorState.running) MonitorService.send(this, MonitorService.ACTION_START)

        // First launch: show the guide before anything else.
        if (savedInstanceState == null && !Prefs.sp(this).getBoolean(Prefs.HELP_SEEN, false)) {
            startActivity(Intent(this, HelpActivity::class.java))
        } else if (savedInstanceState == null && Prefs.autoCheck(this)) {
            startCheck()
        }
    }

    override fun onResume() {
        super.onResume()
        if (check == null) showLastResult()
        renderMonitorCard()
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
            showOrb(R.color.idle, R.drawable.ic_bolt, "No sensor", "")
            statusLine.text = "This phone has no light sensor."
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
        checkButton.text = "Cancel"
        ring.setProgressCompat(0, false)
        ring.visibility = View.VISIBLE
        ui.post(sampler)
    }

    /** Sample-and-hold 5 times a second, same as the monitor. */
    private val sampler = object : Runnable {
        override fun run() {
            val q = check ?: return
            val now = System.currentTimeMillis()
            if (now < measureFromMs) {
                val left = (measureFromMs - now + 999) / 1000
                showOrb(R.color.checking, R.drawable.ic_bolt, "$left", "Put the phone down")
                statusLine.text = "Face-up, under a halogen light"
                ring.setProgressCompat(((now - checkStartMs) * 1000 / SETTLE_MS).toInt(), true)
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
            showOrb(R.color.checking, R.drawable.ic_bolt, "Checking", "Don't touch the phone")
            statusLine.text = "${lux?.toInt() ?: "–"} lux" +
                if (steady > 0) "  ·  steady ${steady}s of ${QuickCheck.STEADY_NEEDED_MS / 1000}s" else ""
            ring.setProgressCompat((steady * 1000L / (QuickCheck.STEADY_NEEDED_MS / 1000)).toInt(), true)
            ui.postDelayed(this, 200)
        }
    }

    private fun stopCheck(result: QuickCheck.Result?) {
        ui.removeCallbacks(sampler)
        sensors.unregisterListener(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        check = null
        checkButton.text = "Check now"
        ring.visibility = View.INVISIBLE
        if (result == null) {
            showLastResult()
            return
        }
        when (result.verdict) {
            QuickCheck.Verdict.KAHRABA -> beeper.quickResult(kahraba = true)
            QuickCheck.Verdict.MOTEUR -> beeper.quickResult(kahraba = false)
            QuickCheck.Verdict.CANT_TELL -> {}
        }
        CheckHistory.add(this, result.verdict, result.lux)
        showResult(result.verdict, System.currentTimeMillis(), result.detail)
    }

    override fun onSensorChanged(event: SensorEvent) {
        lux = event.values[0].toDouble()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- Display ----

    private fun showLastResult() {
        renderHistory()
        val last = CheckHistory.read(this).firstOrNull()
        if (last == null) {
            showOrb(R.color.idle, R.drawable.ic_bolt, "Tap to check", "")
            statusLine.text = "No checks yet"
        } else {
            showResult(last.verdict, last.timeMs, null, last.corrected)
        }
    }

    private fun showResult(v: QuickCheck.Verdict, timeMs: Long, detail: String?, corrected: Boolean = false) {
        renderHistory()
        when (v) {
            QuickCheck.Verdict.KAHRABA -> showOrb(R.color.kahraba, R.drawable.ic_bolt, "Kahraba", if (corrected) "Corrected by you" else "Grid power")
            QuickCheck.Verdict.MOTEUR -> showOrb(R.color.moteur, R.drawable.ic_moteur, "Moteur", if (corrected) "Corrected by you" else "Generator")
            QuickCheck.Verdict.CANT_TELL -> showOrb(R.color.idle, R.drawable.ic_bolt, "Can't tell", "Try again")
        }
        val ago = System.currentTimeMillis() - timeMs
        val agoText = if (ago < 60_000) "just now" else "${PowerDetector.formatDuration(ago)} ago"
        statusLine.text = "Checked $agoText  ·  ${PowerDetector.formatClock(timeMs)}" +
            (detail?.takeIf { v == QuickCheck.Verdict.CANT_TELL }?.let { "\n$it" } ?: "")
    }

    private fun showOrb(colorRes: Int, iconRes: Int, title: String, sub: String) {
        (orb.background.mutate() as GradientDrawable).setColor(ContextCompat.getColor(this, colorRes))
        orbIcon.setImageResource(iconRes)
        orbTitle.text = title
        orbSub.text = sub
        orbSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val entries = CheckHistory.read(this).take(5)
        val fmt = SimpleDateFormat("EEE  HH:mm", Locale.US)
        if (entries.isEmpty()) {
            addHistoryRow(R.color.outline, "No checks yet", "")
            return
        }
        for (e in entries) {
            val color = when (e.verdict) {
                QuickCheck.Verdict.KAHRABA -> R.color.kahraba
                QuickCheck.Verdict.MOTEUR -> R.color.moteur
                QuickCheck.Verdict.CANT_TELL -> R.color.idle
            }
            val label = CheckHistory.label(e.verdict) + if (e.corrected) "  (corrected)" else ""
            addHistoryRow(color, label, fmt.format(Date(e.timeMs))) { editEntry(e) }
        }
    }

    /** Tap a recent check to fix it (e.g. it said kahraba but the lamp post was green) or delete it. */
    private fun editEntry(e: CheckHistory.Entry) {
        val when_ = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(e.timeMs))
        val options = arrayOf("It was kahraba", "It was moteur", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle("Check at $when_")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> CheckHistory.correct(this, e.timeMs, QuickCheck.Verdict.KAHRABA)
                    1 -> CheckHistory.correct(this, e.timeMs, QuickCheck.Verdict.MOTEUR)
                    2 -> CheckHistory.delete(this, e.timeMs)
                }
                showLastResult()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addHistoryRow(colorRes: Int, label: String, time: String, onClick: (() -> Unit)? = null) {
        val row = layoutInflater.inflate(R.layout.item_check, historyList, false)
        if (onClick != null) {
            row.setOnClickListener { onClick() }
            row.isClickable = true
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            row.setBackgroundResource(ripple.resourceId)
        }
        (row.findViewById<View>(R.id.dot).background.mutate() as GradientDrawable)
            .setColor(ContextCompat.getColor(this, colorRes))
        row.findViewById<TextView>(R.id.label).text = label
        row.findViewById<TextView>(R.id.time).text = time
        historyList.addView(row)
    }

    private fun renderMonitorCard() {
        val dot = monitorDot.background.mutate() as GradientDrawable
        if (MonitorState.running) {
            val s = MonitorState.source
            dot.setColor(MonitorService.colorFor(s))
            monitorSub.text = if (s == Source.UNKNOWN) {
                "On  ·  not sure yet"
            } else {
                "On  ·  ${EventLog.label(s).lowercase().replaceFirstChar { it.uppercase() }} " +
                    "since ${PowerDetector.formatClock(MonitorState.sinceMs)}"
            }
        } else {
            dot.setColor(ContextCompat.getColor(this, R.color.outline))
            monitorSub.text = "Off  ·  for a phone left under a light"
        }
    }

    companion object {
        private const val SETTLE_MS = 3_000L
    }
}
