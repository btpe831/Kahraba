package com.kahraba.power

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gets alerts from another phone (1.5.0): keeps a connection open to the other phone's private
 * ntfy.sh topic, opens each message with the key from its code, and shows it here with the usual
 * beeps. Runs in the background like Always-on mode, and warns if the other phone goes quiet.
 */
class AlertsService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var beeper: Beeper
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null
    private var worker: Thread? = null
    private var lastAlertId = ALERT_ID_FIRST
    private val history = RemoteCodec.HistoryCollector()

    private val watch = object : Runnable {
        override fun run() {
            checkSilence()
            updateStatus()
            main.postDelayed(this, 60_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MonitorService.createChannels(this)
        // Foreground first: Android requires it soon after every start, even one that stops at once.
        if (!goForeground() || RemoteAlerts.getPairing(this) == null) {
            stopSelf()
            return
        }
        beeper = Beeper(this)
        // Like ntfy's own "instant delivery": the phone must stay able to receive.
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kahraba:alerts")
            .apply { acquire() }
        running = true
        worker = Thread({ loop() }, "kahraba-alerts").apply { start() }
        main.post(watch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running || !goForeground() || RemoteAlerts.getPairing(this) == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // A new code: reconnect to it.
        if (intent?.action == ACTION_RECONNECT) runCatching { conn?.disconnect() }
        updateStatus()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { conn?.disconnect() }
        worker?.interrupt()
        if (::beeper.isInitialized) beeper.shutdown()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun loop() {
        var wait = 5_000L
        while (running) {
            val p = RemoteAlerts.getPairing(this) ?: break
            val heardAny = runCatching { listen(p) }.getOrDefault(false)
            if (!running) break
            wait = if (heardAny) 5_000L else (wait * 2).coerceAtMost(60_000L)
            try {
                Thread.sleep(wait)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /** One connection: reads messages until it drops. True if it connected. */
    private fun listen(p: RemoteCodec.Pairing): Boolean {
        val since = RemoteAlerts.lastId(this) ?: "2h"
        val c = URL("${RemoteCodec.SERVER}/${p.topic}/json?since=$since").openConnection() as HttpURLConnection
        conn = c
        var connected = false
        try {
            c.connectTimeout = 20_000
            // ntfy.sh sends a keepalive every 45 s: no data for 2 minutes means the connection is dead.
            c.readTimeout = 120_000
            c.inputStream.bufferedReader().use { r ->
                while (running) {
                    val line = r.readLine() ?: break
                    connected = true
                    if (line.isNotBlank()) handle(p, line)
                }
            }
        } finally {
            runCatching { c.disconnect() }
            conn = null
        }
        return connected
    }

    private fun handle(p: RemoteCodec.Pairing, line: String) {
        val j = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (j.optString("event") != "message") return
        // Another code was scanned meanwhile: ignore the old one's messages.
        if (RemoteAlerts.getPairing(this)?.topic != p.topic) return
        RemoteAlerts.setLastId(this, j.optString("id"))
        val m = RemoteCodec.open(p.key, j.optString("message"))?.let(RemoteCodec::decode) ?: return
        // The other phone's switch log, for the pattern card, history and log on this phone.
        if (m.log.isNotEmpty()) EventLog.mergeRemote(this, listOf(m.log))
        if (m.kind == RemoteCodec.Kind.HISTORY) {
            history.add(m)?.let { EventLog.mergeRemote(this, it) }
            return
        }
        val newer = RemoteAlerts.saveHeard(this, m)
        val fresh = System.currentTimeMillis() - m.timeMs < RemoteAlerts.FRESH_MS
        main.post {
            if (newer && fresh && m.kind == RemoteCodec.Kind.ALERT) alert(m)
            updateStatus()
        }
    }

    private fun alert(m: RemoteCodec.Message) {
        if (RemoteAlerts.beepHere(this)) when (m.beep) {
            RemoteCodec.BEEP_KAHRABA -> beeper.kahraba()
            RemoteCodec.BEEP_MOTEUR -> beeper.moteur()
            RemoteCodec.BEEP_OFF -> beeper.powerOff()
            RemoteCodec.BEEP_BACK -> beeper.powerBack()
            RemoteCodec.BEEP_MOVED -> beeper.phoneMoved()
        }
        val text = m.text + "\nFrom ${m.device}" + if (m.room.isNotEmpty()) " (${m.room})" else ""
        val n = NotificationCompat.Builder(this, MonitorService.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(m.title)
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(MonitorService.colorFor(m.source))
            .setAutoCancel(true)
            .build()
        notifications().notify(nextAlertId(), n)
    }

    /** Once, when the other phone has gone quiet; it's said again only after it's been heard from. */
    private fun checkSilence() {
        val h = RemoteAlerts.heard(this) ?: return
        val now = System.currentTimeMillis()
        if (h.kind == RemoteCodec.Kind.STOPPED || now - h.heardMs < RemoteAlerts.SILENT_MS || RemoteAlerts.warned(this)) return
        RemoteAlerts.setWarned(this)
        val text = "Last heard at ${PowerDetector.formatClock(h.heardMs)}. It may be off, unplugged, or without internet " +
            "(the router may be off in a power cut). Alerts start again as soon as it's back."
        val n = NotificationCompat.Builder(this, MonitorService.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("No news from ${h.device}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        notifications().notify(nextAlertId(), n)
    }

    private fun nextAlertId(): Int {
        lastAlertId = ALERT_ID_FIRST + (lastAlertId - ALERT_ID_FIRST + 1) % ALERT_ID_COUNT
        return lastAlertId
    }

    private fun notifications() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun openApp() = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusNotification(): Notification {
        val h = RemoteAlerts.current(this)
        val title = when {
            h == null -> "Getting alerts from your other phone"
            h.source == Source.UNKNOWN -> "${h.device} isn't sure yet"
            else -> "${RemoteAlerts.word(h.source)} since ${PowerDetector.formatClock(h.sinceMs)}"
        }
        return NotificationCompat.Builder(this, MonitorService.CHANNEL_STATUS)
            .setSmallIcon(MonitorService.iconFor(h?.source ?: Source.UNKNOWN))
            .setContentTitle(title)
            .setContentText(RemoteAlerts.statusLine(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(MonitorService.colorFor(h?.source ?: Source.UNKNOWN))
            .setContentIntent(openApp())
            .build()
    }

    private fun updateStatus() {
        if (running) notifications().notify(STATUS_ID, statusNotification())
    }

    private fun goForeground(): Boolean = runCatching {
        val n = statusNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, n)
        }
    }.isSuccess

    companion object {
        const val ACTION_RECONNECT = "com.kahraba.power.ALERTS_RECONNECT"
        private const val STATUS_ID = 2
        private const val ALERT_ID_FIRST = 1100
        private const val ALERT_ID_COUNT = 50

        /** Starts (or reconnects) when this phone gets alerts from another; harmless otherwise. */
        fun start(ctx: Context, reconnect: Boolean = false) {
            if (RemoteAlerts.getPairing(ctx) == null) return
            val i = Intent(ctx, AlertsService::class.java)
            if (reconnect) i.action = ACTION_RECONNECT
            runCatching { ContextCompat.startForegroundService(ctx, i) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, AlertsService::class.java)) }
        }
    }
}
