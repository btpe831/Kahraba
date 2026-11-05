package com.kahraba.power

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Alerts on other phones (1.5.0). The phone running Always-on mode sends; phones that scanned its
 * code get the alerts ([AlertsService]). Kept in its own preferences file, out of backups: a backup
 * may be shared, and the code is what lets a phone read the alerts.
 */
object RemoteAlerts {
    private const val FILE = "remote_alerts"
    private const val SEND_ON = "send_on"
    private const val SEND_CODE = "send_code"
    private const val GET_CODE = "get_code"
    private const val GET_BEEP = "get_beep"
    private const val LAST_ID = "last_id"
    private const val HEARD_MS = "heard_ms"
    private const val HEARD_KIND = "heard_kind"
    private const val HEARD_SOURCE = "heard_source"
    private const val HEARD_SINCE = "heard_since"
    private const val HEARD_DEVICE = "heard_device"
    private const val HEARD_ROOM = "heard_room"
    private const val WARNED = "warned_silent"

    /** The sending phone says it's still there every 15 minutes, about 100 messages a day. */
    const val ALIVE_EVERY_MS = 15 * 60_000L
    /** No word for this long: the other phone may be off, unplugged or offline. */
    const val SILENT_MS = 40 * 60_000L
    /** Alerts older than this (the phone was offline) update the status quietly instead of beeping. */
    const val FRESH_MS = 15 * 60_000L
    /** Alerts waiting for the internet are dropped after this long. */
    private const val KEEP_MS = 6 * 3_600_000L

    fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun deviceName(ctx: Context): String =
        runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    // ---- Sending ----

    /** On by default: nothing is sent until a phone is added (the code is made then). */
    fun sendOn(ctx: Context) = sp(ctx).getBoolean(SEND_ON, true)
    fun setSendOn(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(SEND_ON, on).apply()

    fun sendPairing(ctx: Context): RemoteCodec.Pairing? = sp(ctx).getString(SEND_CODE, null)?.let(RemoteCodec::parse)

    fun sendPairingOrNew(ctx: Context): RemoteCodec.Pairing = sendPairing(ctx) ?: newSendPairing(ctx)

    /** A new code: phones added before stop getting alerts until they scan it. */
    fun newSendPairing(ctx: Context): RemoteCodec.Pairing =
        RemoteCodec.newPairing().also { sp(ctx).edit().putString(SEND_CODE, it.code).apply() }

    private val exec = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<Pair<Long, RemoteCodec.Message>>()

    /** Sends [m] to the added phones; if the internet is down, it waits and goes later ([retry]). */
    fun send(ctx: Context, m: RemoteCodec.Message) {
        if (!sendOn(ctx) || sendPairing(ctx) == null) return
        val app = ctx.applicationContext
        synchronized(queue) {
            // Only the latest "still here" matters.
            if (m.kind == RemoteCodec.Kind.ALIVE) queue.removeAll { it.second.kind == RemoteCodec.Kind.ALIVE }
            queue.addLast(System.currentTimeMillis() to m)
            while (queue.size > 50) queue.removeFirst()
        }
        exec.execute { flush(app) }
    }

    fun waiting(): Boolean = synchronized(queue) { queue.isNotEmpty() }

    fun retry(ctx: Context) {
        if (waiting()) {
            val app = ctx.applicationContext
            exec.execute { flush(app) }
        }
    }

    private fun flush(ctx: Context) {
        while (true) {
            val item = synchronized(queue) { queue.firstOrNull() } ?: return
            val p = sendPairing(ctx)
            if (p == null || !sendOn(ctx) || System.currentTimeMillis() - item.first > KEEP_MS) {
                synchronized(queue) { queue.remove(item) }
                continue
            }
            if (!post(p, RemoteCodec.seal(p.key, RemoteCodec.encode(item.second)))) return
            synchronized(queue) { queue.remove(item) }
        }
    }

    private fun post(p: RemoteCodec.Pairing, body: String): Boolean = runCatching {
        val c = URL("${RemoteCodec.SERVER}/${p.topic}").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "text/plain")
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    // ---- Getting alerts ----

    fun getPairing(ctx: Context): RemoteCodec.Pairing? = sp(ctx).getString(GET_CODE, null)?.let(RemoteCodec::parse)

    /** Starts listening to another phone's code, or stops ([p] null). Forgets what was heard before. */
    fun setGetPairing(ctx: Context, p: RemoteCodec.Pairing?) {
        sp(ctx).edit()
            .apply { if (p == null) remove(GET_CODE) else putString(GET_CODE, p.code) }
            .remove(LAST_ID).remove(HEARD_MS).remove(HEARD_KIND).remove(HEARD_SOURCE).remove(HEARD_SINCE)
            .remove(HEARD_DEVICE).remove(HEARD_ROOM).remove(WARNED)
            .apply()
    }

    fun beepHere(ctx: Context) = sp(ctx).getBoolean(GET_BEEP, true)
    fun setBeepHere(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(GET_BEEP, on).apply()

    fun lastId(ctx: Context): String? = sp(ctx).getString(LAST_ID, null)
    fun setLastId(ctx: Context, id: String) = sp(ctx).edit().putString(LAST_ID, id).apply()

    class Heard(
        val kind: RemoteCodec.Kind,
        val source: Source,
        val sinceMs: Long,
        val heardMs: Long,
        val device: String,
        val room: String,
    )

    fun heard(ctx: Context): Heard? {
        val sp = sp(ctx)
        val ms = sp.getLong(HEARD_MS, 0L).takeIf { it > 0 } ?: return null
        return Heard(
            runCatching { RemoteCodec.Kind.valueOf(sp.getString(HEARD_KIND, "")!!) }.getOrDefault(RemoteCodec.Kind.ALIVE),
            runCatching { Source.valueOf(sp.getString(HEARD_SOURCE, "")!!) }.getOrDefault(Source.UNKNOWN),
            sp.getLong(HEARD_SINCE, ms), ms, sp.getString(HEARD_DEVICE, "") ?: "", sp.getString(HEARD_ROOM, "") ?: "",
        )
    }

    /** Saves what the other phone says; false if it's older than what's known (a repeat after reconnecting). */
    fun saveHeard(ctx: Context, m: RemoteCodec.Message): Boolean {
        if (m.timeMs < sp(ctx).getLong(HEARD_MS, 0L)) return false
        sp(ctx).edit()
            .putLong(HEARD_MS, m.timeMs).putString(HEARD_KIND, m.kind.name).putString(HEARD_SOURCE, m.source.name)
            .putLong(HEARD_SINCE, m.sinceMs).putString(HEARD_DEVICE, m.device).putString(HEARD_ROOM, m.room)
            .putBoolean(WARNED, false)
            .apply()
        return true
    }

    /** What the other phone says now, if it was heard from recently and Always-on mode runs there. */
    fun current(ctx: Context, now: Long = System.currentTimeMillis()): Heard? {
        if (getPairing(ctx) == null) return null
        return heard(ctx)?.takeIf { now - it.heardMs < SILENT_MS && it.kind != RemoteCodec.Kind.STOPPED }
    }

    fun warned(ctx: Context) = sp(ctx).getBoolean(WARNED, false)
    fun setWarned(ctx: Context) = sp(ctx).edit().putBoolean(WARNED, true).apply()

    fun word(s: Source) = when (s) {
        Source.KAHRABA -> "Kahraba"
        Source.MOTEUR -> "Moteur"
        Source.NO_POWER -> "Power off"
        Source.POWER_ON -> "Power on"
        Source.UNKNOWN -> "Not sure yet"
    }

    /** "Kahraba since 6:01 AM", or what's known about the other phone, for the screens. */
    fun statusLine(ctx: Context, now: Long = System.currentTimeMillis()): String {
        val h = heard(ctx) ?: return "Waiting for your other phone. It sends news at least every 15 minutes while Always-on mode runs."
        val where = h.device + if (h.room.isNotEmpty()) " (${h.room})" else ""
        val what = when {
            h.kind == RemoteCodec.Kind.STOPPED -> "Always-on mode was stopped on $where"
            h.source == Source.UNKNOWN -> "$where isn't sure yet"
            else -> "${word(h.source)} since ${PowerDetector.formatClock(h.sinceMs)}, from $where"
        }
        return "$what. Last heard ${RemoteCodec.ago(now - h.heardMs)}."
    }
}
