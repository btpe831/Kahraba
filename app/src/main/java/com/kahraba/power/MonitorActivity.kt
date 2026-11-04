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
import com.google.android.material.materialswitch.MaterialSwitch
import com.kahraba.power.Prefs.monitoring
import java.io.File

/** Which range the light graph shows (remembered). */
private const val GRAPH_RANGE = "graph_range_ms"

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
        setupHeader("Always-on mode")
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
                ui.postDelayed({ render() }, 300)
            } else {
                chooseLightThenStart()
            }
        }
        findViewById<MaterialSwitch>(R.id.cutsOnlySwitch).setOnCheckedChangeListener { _, on ->
            if (on != Prefs.powerCutsOnly(this)) {
                Prefs.setMonitorLight(this, if (on) Prefs.LIGHT_NONE else Prefs.LIGHT_HALOGEN)
                render()
            }
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
        findViewById<Button>(R.id.monitorLearned).setOnClickListener { showTeachings() }
        findViewById<Button>(R.id.monitorRoomButton).setOnClickListener {
            RoomPicker.pick(this, Prefs.monitorRoom(this)) { room ->
                Prefs.setMonitorRoom(this, room)
                renderRoom()
            }
        }
        battery.setOnClickListener { askBatteryExemption() }
        setUpGraph()

        askNotificationPermission()
        if (monitoring && !MonitorState.running) MonitorService.send(this, MonitorService.ACTION_START)
    }

    override fun onResume() {
        super.onResume()
        renderRoom()
        loadGraph(force = true)
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

    /**
     * Asks what light the phone is under, then starts. Only a halogen or filament bulb shows
     * kahraba or moteur; with anything else the app logs power cuts only.
     */
    private fun chooseLightThenStart() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val muted = android.util.TypedValue().also { theme.resolveAttribute(R.attr.appMuted, it, true) }.data
        val options = listOf(
            Prefs.LIGHT_HALOGEN to "Halogen or filament bulb",
            Prefs.LIGHT_LED to "LED",
            Prefs.LIGHT_OTHER to "Other light",
            Prefs.LIGHT_NONE to "Power cuts only",
        )
        val note = TextView(this).apply {
            text = "This light can't show kahraba or moteur, so the app will log power cuts only."
            setTextColor(muted)
            textSize = 14f
            setPadding(0, px(8), 0, 0)
        }
        val group = android.widget.RadioGroup(this)
        options.forEach { (key, label) ->
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                tag = key
                text = label
                textSize = 16f
                isChecked = key == Prefs.monitorLight(this@MonitorActivity)
            })
        }
        fun chosen(): String = group.findViewById<View>(group.checkedRadioButtonId)?.tag as? String ?: Prefs.LIGHT_HALOGEN
        fun showNote() {
            val c = chosen()
            note.visibility = if (c == Prefs.LIGHT_LED || c == Prefs.LIGHT_OTHER) View.VISIBLE else View.GONE
        }
        group.setOnCheckedChangeListener { _, _ -> showNote() }
        showNote()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), 0)
            addView(group)
            addView(note)
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Choose an option")
            .setView(box)
            .setPositiveButton("Start") { _, _ ->
                Prefs.setMonitorLight(this, chosen())
                askNotificationPermission()
                monitoring = true
                MonitorService.send(this, MonitorService.ACTION_START)
                ui.postDelayed({ render() }, 300)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render() {
        renderRoom()
        val cutsOnly = Prefs.powerCutsOnly(this)
        findViewById<MaterialSwitch>(R.id.cutsOnlySwitch).let { if (it.isChecked != cutsOnly) it.isChecked = cutsOnly }
        findViewById<View>(R.id.roomCard).visibility = if (cutsOnly) View.GONE else View.VISIBLE
        findViewById<View>(R.id.graphBox).visibility = if (cutsOnly) View.GONE else View.VISIBLE
        if (!cutsOnly) {
            loadGraph(force = false)
            // Live every second for 30 seconds and 10 minutes; the longer views change slowly.
            val now = System.currentTimeMillis()
            if (graphRangeMs <= 10 * 60_000L || now - graphDrawnMs >= 10_000) drawGraph()
        }
        findViewById<View>(R.id.teachCard).visibility = if (cutsOnly) View.GONE else View.VISIBLE
        val running = MonitorState.running
        val s = MonitorState.source
        val label = findViewById<TextView>(R.id.stateLabel)
        when {
            // Off: show no source at all; an old one could be hours out of date.
            !running -> {
                label.text = "Always-on"
                stateText.text = "Off"
                sinceText.text = if (cutsOnly) "Tap Start to log power cuts" else "Tap Start to track kahraba and moteur"
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
        startStop.text = if (running) "Stop Always-on mode" else "Start Always-on mode"

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
            "Tap Start, then leave the phone plugged into a wall socket under a halogen light. The screen can be off.\n\n" +
                "It uses only the light sensor and the charger. No camera, microphone or internet."
        } else buildString {
            if (!MonitorState.hasLightSensor) append("This phone has no light sensor, so only power cuts can be seen.\n")
            append(MonitorState.lightStatus)
            if (cutsOnly) append("\nThe charger going off means a power cut.")
            else append("\nA wobble above ${"%.1f".format(thr)}% counts as moteur. The charger going off means a power cut.")
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

    // ---- Light graph: what Always-on mode saved, plus the last few minutes live ----

    private val graphRanges = listOf(
        "30 sec" to 30_000L,
        "1 min" to 60_000L,
        "3 min" to 3 * 60_000L,
        "5 min" to 5 * 60_000L,
        "10 min" to 10 * 60_000L,
        "1 hour" to 3_600_000L,
        "6 hours" to 6 * 3_600_000L,
        "24 hours" to 24 * 3_600_000L,
    )
    private var graphRangeMs = 3_600_000L
    private var savedReadings: List<LightHistory.Reading> = emptyList()
    private var savedStatus: List<LightHistory.Status> = emptyList()
    private var graphLoadedFrom = Long.MAX_VALUE
    private var graphLoadedAt = 0L
    private var graphLoading = false
    private var graphDrawnMs = 0L
    /** The switch log, for colouring the graph from when each source really came on. */
    private var switches: List<Pair<Long, Source>> = emptyList()
    private var switchesReadMs = 0L

    private fun setUpGraph() {
        graphRangeMs = Prefs.sp(this).getLong(GRAPH_RANGE, 3_600_000L)
            .takeIf { r -> graphRanges.any { it.second == r } } ?: 3_600_000L
        // Two rows of four: the short live views, then the longer ones.
        val box = findViewById<LinearLayout>(R.id.graphRanges)
        box.orientation = LinearLayout.VERTICAL
        val dp = resources.displayMetrics.density
        var row = LinearLayout(this)
        for ((i, range) in graphRanges.withIndex()) {
            if (i % 4 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = (6 * dp).toInt()
                })
            }
            val tab = TextView(this).apply {
                text = range.first
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    graphRangeMs = range.second
                    Prefs.sp(this@MonitorActivity).edit().putLong(GRAPH_RANGE, range.second).apply()
                    paintRanges()
                    loadGraph(force = false)
                    drawGraph()
                }
            }
            rangeTabs += tab
            row.addView(tab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i % 4 > 0) marginStart = (6 * dp).toInt()
            })
        }
        paintRanges()

        // Colour key, and what the graph shows.
        val key = android.text.SpannableStringBuilder()
        listOf(Source.KAHRABA to "Kahraba", Source.MOTEUR to "Moteur", Source.NO_POWER to "Power off").forEachIndexed { i, (src, name) ->
            if (i > 0) key.append("     ")
            val start = key.length
            key.append("■")
            key.setSpan(android.text.style.ForegroundColorSpan(MonitorService.colorFor(src)), start, key.length, 0)
            key.append(" ").append(name)
        }
        findViewById<TextView>(R.id.graphLegend).text = key
    }

    private val rangeTabs = ArrayList<TextView>()

    private fun paintRanges() {
        val dp = resources.displayMetrics.density
        fun attr(a: Int) = android.util.TypedValue().also { theme.resolveAttribute(a, it, true) }.data
        for ((i, tv) in rangeTabs.withIndex()) {
            val on = graphRanges[i].second == graphRangeMs
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 999 * dp
                if (on) setColor(attr(R.attr.appAccent)) else setStroke((1 * dp).toInt(), attr(R.attr.appOutline))
            }
            tv.setTextColor(attr(if (on) R.attr.appBg else R.attr.appMuted))
            tv.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tv.isSelected = on
        }
    }

    /** Reads the saved files (in the background) when the range needs more, or every 5 minutes. */
    private fun loadGraph(force: Boolean) {
        val now = System.currentTimeMillis()
        val from = now - graphRangeMs - 30 * 60_000L // a little before, for the light in force at the start
        if (graphLoading) return
        if (!force && from >= graphLoadedFrom && now - graphLoadedAt < 5 * 60_000L) return
        graphLoading = true
        val dir = File(filesDir, "data")
        Thread {
            val dayFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val days = LinkedHashSet<String>()
            var t = from
            while (t < now) { days += dayFmt.format(java.util.Date(t)); t += 6 * 3_600_000L }
            days += dayFmt.format(java.util.Date(now))
            val fromText = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(from))
            val midnights = HashMap<String, Long?>()
            val timeOf = { text: String ->
                val mid = midnights.getOrPut(text.substring(0, 10)) { runCatching { dayFmt.parse(text.substring(0, 10))?.time }.getOrNull() }
                val h = text.substring(11, 13).toIntOrNull()
                val m = text.substring(14, 16).toIntOrNull()
                val s = text.substring(17, 19).toIntOrNull()
                if (mid == null || h == null || m == null || s == null) null else mid + ((h * 60 + m) * 60 + s) * 1000L
            }
            val readings = ArrayList<LightHistory.Reading>()
            val statuses = ArrayList<LightHistory.Status>()
            for (d in days) {
                runCatching {
                    File(dir, "raw-$d.csv").takeIf { it.exists() }?.useLines { lines ->
                        lines.forEach { l -> LightHistory.parseReading(l)?.takeIf { it.timeMs >= from }?.let { readings += it } }
                    }
                }
                runCatching {
                    File(dir, "light-$d.csv").takeIf { it.exists() }?.useLines { lines ->
                        lines.forEach { l ->
                            if (l.length >= 19 && l.substring(0, 19) >= fromText) LightHistory.parseStatus(l, timeOf)?.let { statuses += it }
                        }
                    }
                }
            }
            readings.sortBy { it.timeMs }
            statuses.sortBy { it.timeMs }
            ui.post {
                savedReadings = readings
                savedStatus = statuses
                graphLoadedFrom = from
                graphLoadedAt = now
                graphLoading = false
                drawGraph()
            }
        }.start()
    }

    /** 30_000 -> "30s ago", 180_000 -> "3 min ago", 90_000 -> "1m 30s ago". */
    private fun agoText(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}s ago"
            sec % 60 == 0L -> "${sec / 60} min ago"
            else -> "${sec / 60}m ${sec % 60}s ago"
        }
    }

    private fun drawGraph() {
        val chart = findViewById<HistoryChart>(R.id.graphChart)
        val now = System.currentTimeMillis()
        val from = now - graphRangeMs
        val (liveR, liveS) = MonitorState.recent()
        val readings = LightHistory.merge(savedReadings, liveR) { it.timeMs }
        if (now - switchesReadMs > 10_000) {
            switches = EventLog.read(this).sortedBy { it.timeMs }.map { it.timeMs to it.source }
            switchesReadMs = now
        }
        val statuses = LightHistory.relabel(LightHistory.merge(savedStatus, liveS) { it.timeMs }, switches)
        val labels = if (graphRangeMs <= 10 * 60_000L) {
            listOf(agoText(graphRangeMs), agoText(graphRangeMs / 2), "now")
        } else {
            listOf(
                PowerDetector.formatClock(from),
                PowerDetector.formatClock(from + graphRangeMs / 3),
                PowerDetector.formatClock(from + graphRangeMs * 2 / 3),
                "now",
            )
        }
        chart.emptyText = if (graphLoading && savedStatus.isEmpty()) "Loading…" else "Nothing recorded in this time."
        chart.set(LightHistory.slices(readings, statuses, from, now, chart.sliceCount()), labels)
        graphDrawnMs = now
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
                "the app can tell kahraba from a steady moteur.",
        )
    }

    /** What "It's kahraba / It's moteur" taught here, newest first; tap one to remove it. */
    private fun showTeachings() {
        val taught = CheckHistory.read(this).filter { it.fromMonitor && it.lux != null }
        if (taught.isEmpty()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Nothing taught yet")
                .setMessage("When you tap “It's kahraba” or “It's moteur”, the light level it learns shows here.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val fmt = java.text.SimpleDateFormat("EEE d MMM, h:mm a", java.util.Locale.US)
        val items = taught.map { e ->
            "${CheckHistory.label(e.verdict)} · ${"%,d".format(java.util.Locale.US, Math.round(e.lux!!))} lux · " +
                e.spot.ifEmpty { "no room" } + "\n" + fmt.format(java.util.Date(e.timeMs))
        }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("What it learned")
            .setItems(items) { _, i ->
                val e = taught[i]
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Remove this?")
                    .setMessage(
                        "${CheckHistory.label(e.verdict)}, ${"%,d".format(java.util.Locale.US, Math.round(e.lux!!))} lux, " +
                            "taught for “${e.spot.ifEmpty { "no room" }}” on ${fmt.format(java.util.Date(e.timeMs))}.\n\n" +
                            "The app forgets just this one. Everything else it learned stays.",
                    )
                    .setPositiveButton("Remove") { _, _ ->
                        // Always-on mode picks this up by itself (it watches the saved settings).
                        CheckHistory.delete(this, e.timeMs)
                        renderRoom()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Close", null)
            .show()
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
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setMessage("No recordings yet. Start Always-on mode to begin recording.")
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
