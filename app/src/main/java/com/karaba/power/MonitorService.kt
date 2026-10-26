package com.karaba.power

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.karaba.power.Prefs.monitoring
import kotlin.math.ceil

/**
 * Runs in the background (screen can be off): reads the light sensor and the charger,
 * feeds the detector, logs every switch and plays the alerts.
 */
class MonitorService : Service(), SensorEventListener {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sensors: SensorManager
    private lateinit var detector: PowerDetector
    private lateinit var beeper: Beeper
    private lateinit var data: DataLogger
    private var wakeLock: PowerManager.WakeLock? = null

    private var latestLux: Double? = null
    private var plugged: Boolean? = null
    private val sensorEvents = ArrayDeque<Long>()
    private var lastNotifyMs = 0L
    private var lastDataSecond = 0L
    private var lastCalEval = 0L
    private var calibration: MutableList<Double>? = null
    private val calibrationLevels = mutableListOf<Double>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.post { detector.config = Prefs.config(this) }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            doTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        goForeground()

        thread = HandlerThread("karaba-monitor").apply { start() }
        handler = Handler(thread.looper)
        beeper = Beeper(this)
        data = DataLogger(this)
        detector = PowerDetector(Prefs.config(this), Prefs.savedSource(this), Prefs.savedSince(this))

        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        val light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        MonitorState.hasLightSensor = light != null
        if (light != null) sensors.registerListener(this, light, SensorManager.SENSOR_DELAY_FASTEST, handler)

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefListener)

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "karaba:monitor")
            .apply { acquire() }

        MonitorState.running = true
        MonitorState.source = detector.source
        MonitorState.sinceMs = detector.sinceMs
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                monitoring = false
                // Forget the source: once off, it can't know what happens next.
                val now = System.currentTimeMillis()
                EventLog.append(this, Transition(now, MonitorState.source, Source.UNKNOWN, "Monitoring stopped"))
                Prefs.saveSource(this, Source.UNKNOWN, now)
                MonitorState.source = Source.UNKNOWN
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET -> {
                val s = runCatching { Source.valueOf(intent.getStringExtra(EXTRA_SOURCE)!!) }.getOrNull()
                val calibrate = intent.getBooleanExtra(EXTRA_CALIBRATE, false)
                if (s != null) handler.post { setManual(s, calibrate) }
            }
            ACTION_TEST_KAHRABA -> beeper.kahraba(respectQuiet = false)
            ACTION_TEST_MOTEUR -> beeper.moteur(respectQuiet = false)
            ACTION_SILENCE -> beeper.cancel()
        }
        monitoring = true
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { sensors.unregisterListener(this) }
        runCatching { unregisterReceiver(batteryReceiver) }
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        beeper.shutdown()
        handler.post { data.close() }
        thread.quitSafely()
        runCatching { wakeLock?.release() }
        MonitorState.running = false
        MonitorState.calibrationEndsMs = 0
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        latestLux = event.values[0].toDouble()
        detector.addReading(System.currentTimeMillis(), event.values[0].toDouble())
        sensorEvents.addLast(System.currentTimeMillis())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun doTick() {
        val now = System.currentTimeMillis()
        for (t in detector.tick(now, latestLux, plugged)) onTransition(t)

        while (sensorEvents.isNotEmpty() && sensorEvents.first() < now - 60_000) sensorEvents.removeFirst()

        // Once a second: data file, calibration.
        if (detector.lastEvalMs != lastDataSecond) {
            lastDataSecond = detector.lastEvalMs
            data.write(now, detector.lightLevel, detector.flickerPct, plugged, detector.source, detector.lightStatus)
            calibration?.let { cal ->
                detector.flickerPct?.let { cal += it }
                detector.lightLevel?.takeIf { detector.flickerPct != null }?.let { calibrationLevels += it }
            }
        }
        if (calibration != null && now >= MonitorState.calibrationEndsMs) finishCalibration()

        MonitorState.source = detector.source
        MonitorState.sinceMs = detector.sinceMs
        MonitorState.lux = detector.lightLevel ?: latestLux
        MonitorState.flickerPct = detector.flickerPct
        MonitorState.lightStatus = detector.lightStatus
        MonitorState.plugged = plugged
        MonitorState.sensorUpdatesPerMin = sensorEvents.size

        if (now - lastNotifyMs > 15_000) {
            lastNotifyMs = now
            data.flush()
            updateStatusNotification()
        }
    }

    private fun onTransition(t: Transition) {
        EventLog.append(this, t)
        Prefs.saveSource(this, t.to, t.timeMs)
        MonitorState.source = t.to
        MonitorState.sinceMs = t.timeMs
        updateStatusNotification()
        if (t.manual) return

        when (t.to) {
            Source.KAHRABA -> beeper.kahraba()
            Source.MOTEUR -> beeper.moteur()
            else -> beeper.cancel()
        }
        val title = when (t.to) {
            Source.KAHRABA -> "Kahraba is back"
            Source.MOTEUR -> "Moteur took over"
            Source.NO_POWER -> "Power is off"
            Source.UNKNOWN -> "Power source unknown"
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText("${PowerDetector.formatClock(t.timeMs)} – ${t.reason}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${PowerDetector.formatClock(t.timeMs)} – ${t.reason}"))
            .setContentIntent(openApp())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notifications().notify(ALERT_ID, n)
    }

    private fun setManual(s: Source, calibrate: Boolean) {
        val t = detector.setManual(System.currentTimeMillis(), s)
        onTransition(t)
        beeper.cancel()
        if (calibrate && (s == Source.KAHRABA || s == Source.MOTEUR)) {
            calibration = mutableListOf()
            calibrationLevels.clear()
            MonitorState.calibrationSource = s
            MonitorState.calibrationEndsMs = System.currentTimeMillis() + CALIBRATION_MS
            MonitorState.message = null
        }
    }

    private fun finishCalibration() {
        val values = calibration ?: return
        calibration = null
        MonitorState.calibrationEndsMs = 0
        val s = MonitorState.calibrationSource
        if (values.size < 60) {
            MonitorState.message = "Calibration failed: the light was too dark or kept changing. " +
                "Keep the phone still under a halogen light and try again."
            return
        }
        val sorted = values.sorted()
        // Kahraba: near-top wobble (its noise ceiling). Moteur: its typical strong wobble.
        val pct = if (s == Source.KAHRABA) 0.99 else 0.90
        val noise = sorted[minOf(sorted.size - 1, ceil(sorted.size * pct).toInt() - 1)]
        val thr = Prefs.saveCalibration(this, s, noise)
        val level = PowerDetector.median(calibrationLevels)
        if (level != null) Prefs.saveLevel(this, s, level)
        detector.config = Prefs.config(this)
        MonitorState.message = "Learned ${EventLog.label(s).lowercase()} at this spot: " +
            "${level?.toInt() ?: "–"} lux, wobble ${"%.1f".format(noise)}%. " +
            "Keep the phone in this exact spot so the levels stay comparable."
    }

    // ---- Notifications ----

    private fun notifications() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MonitorActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusNotification(): Notification {
        val s = MonitorState.source
        val text = buildString {
            append("Since ${PowerDetector.formatClock(MonitorState.sinceMs)}")
            MonitorState.lux?.let { append(" · ${it.toInt()} lx") }
            MonitorState.flickerPct?.let { append(" · wobble ${"%.1f".format(it)}%") }
        }
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(if (s == Source.UNKNOWN) "Not sure yet" else "Now: ${EventLog.label(s)}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(colorFor(s))
            .setColorized(true)
            .setContentIntent(openApp())
            .build()
    }

    private fun updateStatusNotification() {
        notifications().notify(STATUS_ID, statusNotification())
    }

    private fun goForeground() {
        val n = statusNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, n)
        }
    }

    companion object {
        const val ACTION_START = "com.karaba.power.START"
        const val ACTION_STOP = "com.karaba.power.STOP"
        const val ACTION_SET = "com.karaba.power.SET"
        const val ACTION_TEST_KAHRABA = "com.karaba.power.TEST_KAHRABA"
        const val ACTION_TEST_MOTEUR = "com.karaba.power.TEST_MOTEUR"
        const val ACTION_SILENCE = "com.karaba.power.SILENCE"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CALIBRATE = "calibrate"

        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ALERTS = "alerts"
        private const val STATUS_ID = 1
        private const val ALERT_ID = 2
        private const val TICK_MS = 200L
        const val CALIBRATION_MS = 120_000L

        fun colorFor(s: Source): Int = when (s) {
            // Same as R.color.kahraba / moteur / no_power / idle.
            Source.KAHRABA -> 0xFF059669.toInt()
            Source.MOTEUR -> 0xFFD97706.toInt()
            Source.NO_POWER -> 0xFF475569.toInt()
            Source.UNKNOWN -> 0xFF64748B.toInt()
        }

        fun send(ctx: Context, action: String, extras: (Intent) -> Unit = {}) {
            val i = Intent(ctx, MonitorService::class.java).setAction(action)
            extras(i)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Current power source", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "Switch alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null) // the app plays its own beeps
                    enableVibration(true)
                }
            )
        }
    }
}
