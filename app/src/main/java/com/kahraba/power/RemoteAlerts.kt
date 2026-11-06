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
    private const val HISTORY_AT = "history_at"
    private const val SHOW_REMOTE = "show_remote"
    private const val PHONE_NAME = "phone_name"
    const val NAME_MAX = 30

    /** The sending phone says it's still there every 15 minutes, about 100 messages a day. */
    const val ALIVE_EVERY_MS = 15 * 60_000L
    /** No word for this long: the other phone may be off, unplugged or offline. */
    const val SILENT_MS = 40 * 60_000L
    /** Alerts older than this (the phone was offline) update the status quietly instead of beeping. */
    const val FRESH_MS = 15 * 60_000L
    /** The switch log goes to the other phones every 6 hours (and when a phone is added), to fill any gaps. */
    const val HISTORY_EVERY_MS = 6 * 3_600_000L
    private const val HISTORY_DAYS_MS = 7 * 86_400_000L
    /** Alerts waiting for the internet are dropped after this long. */
    private const val KEEP_MS = 6 * 3_600_000L

    fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The name other phones see: the one typed in Alerts on other phones, else the phone's own ([systemName]). */
    fun deviceName(ctx: Context): String = customName(ctx).ifEmpty { systemName(ctx) }

    fun customName(ctx: Context): String = sp(ctx).getString(PHONE_NAME, "")?.trim() ?: ""

    fun setCustomName(ctx: Context, name: String) =
        sp(ctx).edit().putString(PHONE_NAME, name.trim().take(NAME_MAX)).apply()

    /**
     * The phone's own name (Settings › About phone), e.g. "Pixel 2" or "Galaxy A54 5G". Pixels keep it
     * in one place, some Samsungs in another; if neither is readable, the maker and model code.
     */
    fun systemName(ctx: Context): String {
        val cr = ctx.contentResolver
        val found = listOf(
            { android.provider.Settings.Global.getString(cr, "device_name") },
            { android.provider.Settings.System.getString(cr, "device_name") },
            { android.provider.Settings.Secure.getString(cr, "bluetooth_name") },
        ).firstNotNullOfOrNull { read -> runCatching(read).getOrNull()?.trim()?.takeIf { it.isNotEmpty() } }
        if (found != null) return found.take(NAME_MAX)
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

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
            // Only the latest copy of the log matters too.
            if (m.kind == RemoteCodec.Kind.HISTORY) {
                val batch = m.part.substringBefore("/")
                queue.removeAll { it.second.kind == RemoteCodec.Kind.HISTORY && it.second.part.substringBefore("/") != batch }
            }
            queue.addLast(System.currentTimeMillis() to m)
            while (queue.size > 300) queue.removeFirst()
        }
        exec.execute { flush(app) }
    }

    fun historyDue(ctx: Context, now: Long = System.currentTimeMillis()) =
        now - sp(ctx).getLong(HISTORY_AT, 0L) >= HISTORY_EVERY_MS

    /**
     * Sends the switch log in parts: all of it ([all], when a phone is added), or the last 7 days
     * and the switch before them (every 6 hours).
     */
    fun sendHistory(ctx: Context, all: Boolean = false) {
        if (!sendOn(ctx) || sendPairing(ctx) == null) return
        val now = System.currentTimeMillis()
        sp(ctx).edit().putLong(HISTORY_AT, now).apply()
        val entries = EventLog.read(ctx).sortedBy { it.timeMs }
        if (entries.isEmpty()) return
        val first = if (all) 0 else
            entries.indexOfFirst { it.timeMs >= now - HISTORY_DAYS_MS }.let { if (it < 0) entries.size - 1 else maxOf(0, it - 1) }
        val parts = RemoteCodec.historyParts(entries.drop(first).map { EventLog.line(it) })
        val batch = now.toString(36)
        val device = deviceName(ctx)
        parts.forEachIndexed { i, lines ->
            send(ctx, RemoteCodec.Message(RemoteCodec.Kind.HISTORY, Source.UNKNOWN, 0L, now, device,
                text = lines.joinToString("\n"), part = "$batch/$i/${parts.size}"))
        }
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
        EventLog.clearRemote(ctx)
    }

    /** Your power shows the other phone's data (true, the default) or this phone's own. */
    fun showRemote(ctx: Context) = sp(ctx).getBoolean(SHOW_REMOTE, true)
    fun setShowRemote(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(SHOW_REMOTE, on).apply()

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
