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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.karaba.power.Prefs.monitoring
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The main screen: the NOW card and one-tap quick check, a calendar of checks with day notes,
 * the room, recent checks, and a sticky bar with the current status.
 */
class MainActivity : ThemedActivity(), SensorEventListener {

    private val ui = Handler(Looper.getMainLooper())
    private val beeper by lazy { Beeper(applicationContext) }
    private lateinit var sensors: SensorManager
    private var lightSensor: Sensor? = null
    private lateinit var motion: MotionWatcher

    private lateinit var hero: LinearLayout
    private lateinit var heroRoom: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroIcon: ImageView
    private lateinit var heroSub: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroProgress: LinearProgressIndicator
    private lateinit var checkButton: MaterialButton
    private lateinit var confirmRow: View
    private lateinit var historyList: LinearLayout
    private lateinit var monitorDot: View
    private lateinit var monitorSub: TextView
    private lateinit var spotButton: MaterialButton
    private lateinit var spotInfo: TextView
    private lateinit var dayName: TextView
    private lateinit var dateText: TextView
    private lateinit var clockText: TextView
    private lateinit var calSwitch: MaterialSwitch
    private lateinit var calWeekdays: LinearLayout
    private lateinit var calGrid: LinearLayout
    private lateinit var calLegend: TextView
    private lateinit var barTile: FrameLayout
    private lateinit var barIcon: ImageView
    private lateinit var barLabel: TextView
    private lateinit var barStatus: TextView

    // Running check state (null when idle).
    private var check: QuickCheck? = null
    private var checkStartMs = 0L
    private var measureFromMs = 0L
    private var lux: Double? = null
    private var lastCalendarKey = ""

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        MonitorService.createChannels(this)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
        motion = MotionWatcher(sensors) { ui.post { onPhoneMoved() } }

        hero = findViewById(R.id.hero)
        heroRoom = findViewById(R.id.heroRoom)
        heroTitle = findViewById(R.id.heroTitle)
        heroIcon = findViewById(R.id.heroIcon)
        heroSub = findViewById(R.id.heroSub)
        heroMeta = findViewById(R.id.heroMeta)
        heroProgress = findViewById(R.id.heroProgress)
        checkButton = findViewById(R.id.checkButton)
        confirmRow = findViewById(R.id.confirmRow)
        historyList = findViewById(R.id.historyList)
        monitorDot = findViewById(R.id.monitorDot)
        monitorSub = findViewById(R.id.monitorSub)
        spotButton = findViewById(R.id.spotButton)
        spotInfo = findViewById(R.id.spotInfo)
        dayName = findViewById(R.id.dayName)
        dateText = findViewById(R.id.dateText)
        clockText = findViewById(R.id.clockText)
        calSwitch = findViewById(R.id.calMonitorSwitch)
        calWeekdays = findViewById(R.id.calWeekdays)
        calGrid = findViewById(R.id.calGrid)
        calLegend = findViewById(R.id.calLegend)
        barTile = findViewById(R.id.barTile)
        barIcon = findViewById(R.id.barIcon)
        barLabel = findViewById(R.id.barLabel)
        barStatus = findViewById(R.id.barStatus)

        checkButton.setOnClickListener { if (check == null) startCheck() else stopCheck(null) }
        hero.setOnClickListener { if (check == null) startCheck() }
        findViewById<View>(R.id.confirmKahraba).setOnClickListener { confirmLatest(QuickCheck.Verdict.KAHRABA) }
        findViewById<View>(R.id.confirmMoteur).setOnClickListener { confirmLatest(QuickCheck.Verdict.MOTEUR) }
        findViewById<View>(R.id.monitorCard).setOnClickListener { startActivity(Intent(this, MonitorActivity::class.java)) }
        findViewById<View>(R.id.meterCard).setOnClickListener { startActivity(Intent(this, LightMeterActivity::class.java)) }
        findViewById<View>(R.id.footerNote).setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }
        findViewById<View>(R.id.barSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<View>(R.id.barHistory).setOnClickListener { showAllChecks() }
        spotButton.setOnClickListener {
            if (check == null) {
                RoomPicker.pick(this, Prefs.currentSpot(this)) { room ->
                    Prefs.setCurrentSpot(this, room)
                    refreshAll()
                }
            }
        }
        calSwitch.isChecked = Prefs.sp(this).getBoolean(CAL_MONITOR, false)
        calSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.sp(this).edit().putBoolean(CAL_MONITOR, on).apply()
            renderCalendar()
        }
        styleBottomBar()

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
        refreshAll()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    override fun onStop() {
        super.onStop()
        if (check != null) stopCheck(null)
    }

    override fun onDestroy() {
        beeper.shutdown()
        super.onDestroy()
    }

    /** Clock, sticky bar and monitor card refresh every second. */
    private val ticker = object : Runnable {
        override fun run() {
            renderClock()
            renderBar()
            renderMonitorCard()
            ui.postDelayed(this, 1000)
        }
    }

    private fun refreshAll() {
        if (check == null) showLastResult()
        renderSpot()
        renderHistory()
        renderClock()
        renderCalendar()
        renderBar()
        renderMonitorCard()
    }

    // ---- The check ----

    private fun startCheck() {
        val sensor = lightSensor
        if (sensor == null) {
            showHero(R.color.idle, R.drawable.ic_bolt, "No sensor", "This phone has no light sensor", "")
            return
        }
        beeper.cancel()
        val cfg = Prefs.config(this)
        val (k, m) = CheckHistory.spotLevels(this, Prefs.currentSpot(this))
        check = QuickCheck(cfg.flickerThresholdPct, cfg.minLux, k, m)
        lux = null
        checkStartMs = System.currentTimeMillis()
        // A short pause first, so the hand putting the phone down isn't read as a wobble or a move.
        measureFromMs = checkStartMs + SETTLE_MS
        confirmRow.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Fixed screen brightness: the light sensor sits under the screen, so auto-brightness
        // changing it mid-check could nudge the reading.
        window.attributes = window.attributes.apply { screenBrightness = LightMeterActivity.LOCKED_BRIGHTNESS }
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        motion.start()
        checkButton.text = "Cancel"
        heroProgress.setProgressCompat(0, false)
        heroProgress.visibility = View.VISIBLE
        ui.post(sampler)
    }

    /** Sample-and-hold 5 times a second, same as the monitor. */
    private val sampler = object : Runnable {
        override fun run() {
            val q = check ?: return
            val now = System.currentTimeMillis()
            if (now < measureFromMs) {
                val left = (measureFromMs - now + 999) / 1000
                showHero(R.color.checking, R.drawable.ic_bolt, "$left", "Put the phone down", "Face-up · under a halogen light")
                heroProgress.setProgressCompat(((now - checkStartMs) * 1000 / SETTLE_MS).toInt(), true)
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
            val needS = q.steadyNeededMs / 1000
            showHero(
                R.color.checking, R.drawable.ic_bolt, "Checking", "Don't touch the phone",
                "${lux?.toInt() ?: "–"} lux" + if (steady > 0) " · steady ${steady}s of ${needS}s" else "",
            )
            heroProgress.setProgressCompat((steady * 1000L / needS).toInt().coerceAtMost(1000), true)
            ui.postDelayed(this, 200)
        }
    }

    /** The phone was picked up or moved during a check: stop, save nothing. */
    private fun onPhoneMoved() {
        if (check == null || System.currentTimeMillis() < measureFromMs) return
        stopCheck(null)
        beeper.cancel()
        showHero(R.color.idle, R.drawable.ic_bolt, "Moved", "The phone moved", "Put it down and try again")
    }

    private fun stopCheck(result: QuickCheck.Result?) {
        ui.removeCallbacks(sampler)
        sensors.unregisterListener(this)
        motion.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        check = null
        checkButton.text = "Check now"
        heroProgress.visibility = View.GONE
        if (result == null) {
            showLastResult()
            return
        }
        when (result.verdict) {
            QuickCheck.Verdict.KAHRABA -> beeper.quickResult(kahraba = true)
            QuickCheck.Verdict.MOTEUR -> beeper.quickResult(kahraba = false)
            QuickCheck.Verdict.NO_FLICKER -> beeper.quickNoFlicker()
            QuickCheck.Verdict.CANT_TELL -> {}
        }
        CheckHistory.add(this, result.verdict, result.lux, spot = Prefs.currentSpot(this), byLevel = result.byLevel)
        refreshAll()
        showResult(CheckHistory.read(this).first(), result.detail)
        // Ask to confirm, so the room can be learned (not for "can't tell": no usable reading).
        confirmRow.visibility = if (result.verdict != QuickCheck.Verdict.CANT_TELL) View.VISIBLE else View.GONE
        val room = Prefs.currentSpot(this)
        findViewById<TextView>(R.id.confirmPrompt).text =
            if (room.isEmpty()) "Know for sure? Confirm it:" else "Know for sure? Confirm it to teach “$room”:"
    }

    override fun onSensorChanged(event: SensorEvent) {
        lux = event.values[0].toDouble()
        if (System.currentTimeMillis() >= measureFromMs) check?.addRaw(System.currentTimeMillis(), event.values[0].toDouble())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- NOW card ----

    private fun showLastResult() {
        val last = CheckHistory.read(this).firstOrNull { !it.fromMonitor }
        if (last == null) {
            showHero(R.color.idle, R.drawable.ic_bolt, "Ijit?", "Tap to check", "Kahraba or moteur")
        } else {
            showResult(last, null)
        }
    }

    private fun showResult(e: CheckHistory.Entry, detail: String?) {
        val ago = System.currentTimeMillis() - e.timeMs
        val agoText = if (ago < 60_000) "just now" else "${PowerDetector.formatDuration(ago)} ago"
        val meta = "Checked ${PowerDetector.formatClock(e.timeMs)} · $agoText" +
            (e.lux?.let { " · ${it.toInt()} lux" } ?: "")
        when (e.verdict) {
            QuickCheck.Verdict.KAHRABA -> showHero(R.color.kahraba, R.drawable.ic_bolt, "Kahraba",
                if (e.corrected) "Confirmed by you" else "Grid power", meta)
            QuickCheck.Verdict.MOTEUR -> showHero(R.color.moteur, R.drawable.ic_moteur, "Moteur",
                if (e.corrected) "Confirmed by you" else "Generator", meta)
            QuickCheck.Verdict.NO_FLICKER -> showHero(R.color.idle, R.drawable.ic_bolt, "No flicker", "Probably kahraba", meta)
            QuickCheck.Verdict.CANT_TELL -> showHero(R.color.idle, R.drawable.ic_bolt, "Can't tell",
                detail?.substringBefore(".")?.take(40) ?: "Try again", meta)
        }
    }

    /** The big card: a soft gradient in the status colour, like a sky photo. */
    private fun showHero(colorRes: Int, iconRes: Int, title: String, sub: String, meta: String) {
        val c = ContextCompat.getColor(this, colorRes)
        hero.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(lighten(c, 0.18f), c, darken(c, 0.35f)),
        ).apply {
            cornerRadius = 28 * dp
            setStroke((1 * dp).toInt(), themeColor(R.attr.appBorder))
        }
        val room = Prefs.currentSpot(this)
        heroRoom.text = room.ifEmpty { "No room" }
        heroTitle.text = title
        heroIcon.setImageResource(iconRes)
        heroSub.text = sub
        heroMeta.text = meta
        heroMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- TODAY: clock and calendar ----

    private fun renderClock() {
        val now = Date()
        dayName.text = SimpleDateFormat("EEEE", Locale.US).format(now)
        dateText.text = SimpleDateFormat("MMMM d, yyyy", Locale.US).format(now)
        clockText.text = SimpleDateFormat("h:mm a", Locale.US).format(now)
        // Redraw the calendar when the day changes.
        if (DayData.key(now.time) != lastCalendarKey) renderCalendar()
    }

    private fun renderCalendar() {
        val today = Calendar.getInstance()
        lastCalendarKey = DayData.key(today)
        val showMonitor = calSwitch.isChecked
        val checks = DayData.checksByDay(this)
        val kahraba = if (showMonitor) DayData.kahrabaByDay(this) else emptyMap()
        val notes = DayData.daysWithNotes(this)
        val todayDow = today.get(Calendar.DAY_OF_WEEK)

        // Weekday letters, today's in the accent colour.
        calWeekdays.removeAllViews()
        val letters = listOf("S", "M", "T", "W", "T", "F", "S")
        for (i in 0..6) {
            calWeekdays.addView(TextView(this).apply {
                text = letters[i]
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(themeColor(if (i + 1 == todayDow) R.attr.appAccent else R.attr.appMuted))
                if (i + 1 == todayDow) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        calGrid.removeAllViews()
        val first = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = first.get(Calendar.DAY_OF_WEEK) - 1
        val days = today.getActualMaximum(Calendar.DAY_OF_MONTH)
        var day = 1 - offset
        while (day <= days) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0..6) {
                val inMonth = day in 1..days
                val cell = (first.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, if (inMonth) day else 1) }
                val key = DayData.key(cell)
                row.addView(
                    dayCell(
                        if (inMonth) day else null, key, col + 1 == todayDow, day == today.get(Calendar.DAY_OF_MONTH),
                        checks[key], kahraba[key], key in notes, showMonitor,
                    ),
                    LinearLayout.LayoutParams(0, (78 * dp).toInt(), 1f).apply {
                        setMargins((2 * dp).toInt(), (3 * dp).toInt(), (2 * dp).toInt(), (3 * dp).toInt())
                    },
                )
                day++
            }
            calGrid.addView(row)
        }
        calLegend.text = if (showMonitor) {
            if (kahraba.isEmpty()) "No monitor data yet. Turn on the always-on monitor to record kahraba hours."
            else "Hours of kahraba recorded by the always-on monitor."
        } else {
            "Each day shows its last quick check and how many you did."
        }
    }

    private fun dayCell(
        day: Int?, key: String, todayColumn: Boolean, isToday: Boolean,
        checks: List<CheckHistory.Entry>?, kahrabaMs: Long?, hasNote: Boolean, showMonitor: Boolean,
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        }
        if (todayColumn) {
            cell.background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(themeColor(R.attr.appSurfaceAlt))
            }
        }
        if (day == null) return cell

        val number = TextView(this).apply {
            text = "$day"
            gravity = Gravity.CENTER
            textSize = 17f
            if (isToday) {
                setTextColor(themeColor(R.attr.appOnAccent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(themeColor(R.attr.appAccent))
                }
            } else {
                setTextColor(themeColor(R.attr.appText))
            }
        }
        val numberBox = FrameLayout(this)
        numberBox.addView(number, FrameLayout.LayoutParams((34 * dp).toInt(), (34 * dp).toInt(), Gravity.CENTER))
        if (hasNote) {
            numberBox.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE5484D.toInt()) }
            }, FrameLayout.LayoutParams((7 * dp).toInt(), (7 * dp).toInt(), Gravity.TOP or Gravity.END))
        }
        cell.addView(numberBox, LinearLayout.LayoutParams((40 * dp).toInt(), (34 * dp).toInt()))

        var icon = 0
        var color = 0
        var label = ""
        if (showMonitor && kahrabaMs != null && kahrabaMs >= 60_000) {
            icon = R.drawable.ic_bolt
            color = R.color.kahraba
            label = DayData.shortHours(kahrabaMs)
        } else if (!showMonitor && !checks.isNullOrEmpty()) {
            val last = checks.maxBy { it.timeMs }
            icon = if (last.verdict == QuickCheck.Verdict.MOTEUR) R.drawable.ic_moteur else R.drawable.ic_bolt
            color = colorFor(last.verdict)
            label = if (checks.size == 1) "1 check" else "${checks.size} checks"
        }
        if (icon != 0) {
            cell.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(ContextCompat.getColor(this@MainActivity, color))
            }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { topMargin = (2 * dp).toInt() })
            cell.addView(TextView(this).apply {
                text = label
                textSize = 10f
                setTextColor(themeColor(R.attr.appMuted))
                maxLines = 1
            })
        }
        cell.setOnClickListener { showDay(key) }
        return cell
    }

    /** The day sheet: that day's checks and monitor switches, and a note. */
    private fun showDay(key: String) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(key) ?: return
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_day, null)
        v.findViewById<TextView>(R.id.sheetTitle).text = SimpleDateFormat("EEEE", Locale.US).format(date)
        v.findViewById<TextView>(R.id.sheetDate).text = SimpleDateFormat("MMMM d, yyyy", Locale.US).format(date)
        val content = v.findViewById<LinearLayout>(R.id.sheetContent)
        val note = v.findViewById<EditText>(R.id.sheetNote)
        note.setText(DayData.note(this, key))

        val checks = DayData.checksByDay(this)[key].orEmpty().sortedBy { it.timeMs }
        if (checks.isNotEmpty()) {
            content.addView(sectionLabel("Quick checks"))
            for (e in checks) {
                val label = CheckHistory.label(e.verdict) + (if (e.corrected) "  ✓" else "") +
                    if (e.spot.isNotEmpty()) "  · ${e.spot}" else ""
                content.addView(row(colorFor(e.verdict), label, PowerDetector.formatClock(e.timeMs)))
            }
        }
        val events = DayData.monitorEvents(this, key)
        val kahrabaMs = DayData.kahrabaByDay(this)[key]
        if (events.isNotEmpty() || kahrabaMs != null) {
            content.addView(sectionLabel("Always-on monitor" + (kahrabaMs?.let { " · ${DayData.hours(it)} kahraba" } ?: "")))
            for (e in events) {
                val c = when (e.source) {
                    Source.KAHRABA -> R.color.kahraba
                    Source.MOTEUR -> R.color.moteur
                    else -> R.color.idle
                }
                content.addView(row(c, "${EventLog.label(e.source).lowercase().replaceFirstChar { it.uppercase() }} – ${e.reason}",
                    PowerDetector.formatClock(e.timeMs)))
            }
        }
        if (checks.isEmpty() && events.isEmpty()) content.addView(sectionLabel("No checks this day"))
        content.addView(sectionLabel("Note"))

        v.findViewById<View>(R.id.sheetDone).setOnClickListener { sheet.dismiss() }
        sheet.setOnDismissListener {
            DayData.setNote(this, key, note.text.toString())
            renderCalendar()
        }
        sheet.setContentView(v)
        sheet.show()
    }

    /** Every saved check, newest first, from the list button in the bottom bar. */
    private fun showAllChecks() {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_day, null)
        v.findViewById<TextView>(R.id.sheetTitle).text = "All checks"
        v.findViewById<TextView>(R.id.sheetDate).text = "Tap one to fix or delete it"
        v.findViewById<View>(R.id.sheetNote).visibility = View.GONE
        val content = v.findViewById<LinearLayout>(R.id.sheetContent)
        val fmt = SimpleDateFormat("EEE d MMM  HH:mm", Locale.US)
        val entries = CheckHistory.read(this).filter { !it.fromMonitor }
        if (entries.isEmpty()) content.addView(sectionLabel("No checks yet"))
        for (e in entries) {
            val label = CheckHistory.label(e.verdict) + (if (e.corrected) "  ✓" else "") +
                if (e.spot.isNotEmpty()) "  · ${e.spot}" else ""
            content.addView(row(colorFor(e.verdict), label, fmt.format(Date(e.timeMs))) {
                sheet.dismiss()
                editEntry(e)
            })
        }
        v.findViewById<View>(R.id.sheetDone).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(v)
        sheet.show()
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        textSize = 13f
        letterSpacing = 0.14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(themeColor(R.attr.appAccent))
        setPadding(0, (20 * dp).toInt(), 0, (6 * dp).toInt())
    }

    // ---- Room, recent checks, monitor ----

    private fun renderSpot() {
        val room = Prefs.currentSpot(this)
        spotButton.text = room.ifEmpty { "None" }
        spotInfo.text = RoomPicker.describe(
            this, room,
            "Optional. Choose the room you're checking in, and always put the phone in the same place under " +
                "the same light. Once the app has learned that room, it can tell kahraba and moteur apart, " +
                "even when the moteur is steady.",
        )
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val entries = CheckHistory.read(this).filter { !it.fromMonitor }.take(5)
        val fmt = SimpleDateFormat("EEE  HH:mm", Locale.US)
        if (entries.isEmpty()) {
            historyList.addView(row(R.color.idle, "No checks yet", ""))
            return
        }
        for (e in entries) {
            val label = CheckHistory.label(e.verdict) + (if (e.corrected) "  ✓" else "") +
                if (e.spot.isNotEmpty()) "  · ${e.spot}" else ""
            historyList.addView(row(colorFor(e.verdict), label, fmt.format(Date(e.timeMs))) { editEntry(e) })
        }
    }

    private fun colorFor(v: QuickCheck.Verdict) = when (v) {
        QuickCheck.Verdict.KAHRABA -> R.color.kahraba
        QuickCheck.Verdict.MOTEUR -> R.color.moteur
        else -> R.color.idle
    }

    private fun row(colorRes: Int, label: String, time: String, onClick: (() -> Unit)? = null): View {
        val row = layoutInflater.inflate(R.layout.item_check, historyList, false)
        if (onClick != null) {
            row.setOnClickListener { onClick() }
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            row.setBackgroundResource(ripple.resourceId)
        }
        (row.findViewById<View>(R.id.dot).background.mutate() as GradientDrawable)
            .setColor(ContextCompat.getColor(this, colorRes))
        row.findViewById<TextView>(R.id.label).text = label
        row.findViewById<TextView>(R.id.time).text = time
        return row
    }

    /** "It was kahraba / moteur" under the latest result. */
    private fun confirmLatest(v: QuickCheck.Verdict) {
        val last = CheckHistory.read(this).firstOrNull { !it.fromMonitor } ?: return
        CheckHistory.correct(this, last.timeMs, v)
        confirmRow.visibility = View.GONE
        refreshAll()
    }

    /** Tap a check to fix it (e.g. it said kahraba but the lamp post was green) or delete it. */
    private fun editEntry(e: CheckHistory.Entry) {
        val whenText = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(e.timeMs))
        MaterialAlertDialogBuilder(this)
            .setTitle("Check at $whenText")
            .setItems(arrayOf("It was kahraba", "It was moteur", "Delete")) { _, i ->
                when (i) {
                    0 -> CheckHistory.correct(this, e.timeMs, QuickCheck.Verdict.KAHRABA)
                    1 -> CheckHistory.correct(this, e.timeMs, QuickCheck.Verdict.MOTEUR)
                    2 -> CheckHistory.delete(this, e.timeMs)
                }
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderMonitorCard() {
        val dot = monitorDot.background.mutate() as GradientDrawable
        if (MonitorState.running) {
            val s = MonitorState.source
            dot.setColor(MonitorService.colorFor(s))
            monitorSub.text = if (s == Source.UNKNOWN) "On · not sure yet" else
                "On · ${EventLog.label(s).lowercase().replaceFirstChar { it.uppercase() }} since ${PowerDetector.formatClock(MonitorState.sinceMs)}"
        } else {
            dot.setColor(themeColor(R.attr.appOutline))
            monitorSub.text = "Off · for a phone left under a light"
        }
    }

    // ---- Sticky bottom bar ----

    private fun styleBottomBar() {
        val bg = themeColor(R.attr.appBg)
        val clear = bg and 0x00FFFFFF
        findViewById<View>(R.id.bottomBar).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(clear, clear or (0xE6 shl 24), bg),
        )
    }

    /** Monitor status when it's running, otherwise the last quick check. */
    private fun renderBar() {
        val color: Int
        val icon: Int
        if (MonitorState.running) {
            val s = MonitorState.source
            color = MonitorService.colorFor(s)
            icon = if (s == Source.MOTEUR) R.drawable.ic_moteur else R.drawable.ic_bolt
            val room = Prefs.monitorRoom(this)
            barLabel.text = "Monitor" + if (room.isNotEmpty()) " · $room" else ""
            barStatus.text = if (s == Source.UNKNOWN) "Not sure yet" else
                "${EventLog.label(s).lowercase().replaceFirstChar { it.uppercase() }} · ${PowerDetector.formatClock(MonitorState.sinceMs)}"
        } else {
            val last = CheckHistory.read(this).firstOrNull { !it.fromMonitor }
            if (last == null) {
                color = ContextCompat.getColor(this, R.color.idle)
                icon = R.drawable.ic_bolt
                barLabel.text = "Ijit"
                barStatus.text = "Tap Check now"
            } else {
                color = ContextCompat.getColor(this, colorFor(last.verdict))
                icon = if (last.verdict == QuickCheck.Verdict.MOTEUR) R.drawable.ic_moteur else R.drawable.ic_bolt
                barLabel.text = "Last check" + if (last.spot.isNotEmpty()) " · ${last.spot}" else ""
                barStatus.text = "${CheckHistory.label(last.verdict)} · ${PowerDetector.formatClock(last.timeMs)}"
            }
        }
        barTile.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(lighten(color, 0.15f), darken(color, 0.25f)),
        ).apply { cornerRadius = 16 * dp }
        barIcon.setImageResource(icon)
    }

    // ---- Helpers ----

    private fun themeColor(attr: Int): Int {
        val v = TypedValue()
        theme.resolveAttribute(attr, v, true)
        return v.data
    }

    private fun lighten(c: Int, f: Float) = mix(c, 0xFFFFFFFF.toInt(), f)
    private fun darken(c: Int, f: Float) = mix(c, 0xFF000000.toInt(), f)

    private fun mix(a: Int, b: Int, f: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun m(s: Int) = (ch(a, s) * (1 - f) + ch(b, s) * f).toInt()
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    companion object {
        private const val SETTLE_MS = 3_000L
        private const val CAL_MONITOR = "calendar_monitor"
    }
}
