package com.kahraba.power

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
import com.kahraba.power.Prefs.monitoring
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
    private lateinit var heroChart: LuxChart

    // The light during the latest check (5 readings a second), for the graph on the NOW card.
    private val chartT = LongArray(CHART_MAX)
    private val chartV = DoubleArray(CHART_MAX)
    private var chartN = 0
    /** The check the graph belongs to (its time), so it only shows with that result. */
    private var chartCheckMs = 0L
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
        heroChart = findViewById(R.id.heroChart)
        heroChart.windowSeconds = 30
        heroChart.useColors(0xFFFFFFFF.toInt(), 0x40FFFFFF, 0xD9FFFFFF.toInt())
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
        findViewById<View>(R.id.toolMonitor).setOnClickListener { startActivity(Intent(this, MonitorActivity::class.java)) }
        findViewById<View>(R.id.toolMeter).setOnClickListener { startActivity(Intent(this, LightMeterActivity::class.java)) }
        findViewById<View>(R.id.toolAmps).setOnClickListener { startActivity(Intent(this, AmpsActivity::class.java)) }
        setUpHistoryTabs()
        findViewById<TextView>(R.id.appVersion).text = "v${Updates.version(this)}"
        // The bottom bar's status fades out while the NOW card (which shows the same) is on screen.
        findViewById<android.widget.ScrollView>(R.id.scroll).setOnScrollChangeListener { _, _, _, _, _ -> renderBarStatus(animate = true) }
        findViewById<View>(R.id.factsCard).setOnClickListener { Facts.next(this); renderFact() }
        findViewById<View>(R.id.updateCheck).setOnClickListener { Updates.open(this); renderUpdate() }
        findViewById<View>(R.id.updateLater).setOnClickListener { Updates.snooze(this, Updates.LATER_DAYS); renderUpdate() }
        findViewById<View>(R.id.setupHide).setOnClickListener {
            Prefs.sp(this).edit().putBoolean(SETUP_CARD, false).apply()
            android.widget.Toast.makeText(this, "You can turn it back on in Settings › Cards", android.widget.Toast.LENGTH_LONG).show()
            renderSetup()
        }
        findViewById<View>(R.id.feedbackButton).apply {
            visibility = if (Feedback.enabled) View.VISIBLE else View.GONE
            setOnClickListener { Feedback.open(this@MainActivity) }
        }
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
        // Press and hold a card, then drag it up or down: save the new order.
        findViewById<ReorderLayout>(R.id.cardStack).onReordered = { views ->
            val keyOf = cardIds.entries.associate { (key, id) -> id to key }
            Prefs.setCardOrder(this, views.mapNotNull { keyOf[it.id] })
        }
        findViewById<TextView>(R.id.definition).text = android.text.Html.fromHtml(
            "<b>Kahraba</b> (كهرباء) means electricity. In Lebanon, it usually means power from the state (EDL), " +
                "as opposed to the <b>moteur</b>, the neighbourhood generator.",
            android.text.Html.FROM_HTML_MODE_COMPACT,
        )

        // Keep the always-on monitor running if it was on (e.g. after an update).
        if (monitoring && !MonitorState.running) MonitorService.send(this, MonitorService.ACTION_START)

        // Back closes the tour if it's showing.
        onBackPressedDispatcher.addCallback(this, tourBack)

        // First launch shows the welcome and tour (from onResume) instead of an automatic check.
        if (savedInstanceState == null && Tour.welcomeSeen(this) && Tour.tourSeen(this) && Prefs.autoCheck(this)) {
            startCheck()
        }
    }

    override fun onResume() {
        super.onResume()
        // The light sensor runs while the screen is open: for checks and the Light wobble card.
        lightSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        refreshAll()
        ui.post(ticker)
        showIntroIfNew()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
        sensors.unregisterListener(this)
    }

    override fun onStop() {
        super.onStop()
        if (check != null) stopCheck(null)
    }

    override fun onDestroy() {
        beeper.shutdown()
        super.onDestroy()
    }

    /** Clock, sticky bar, monitor and wobble cards refresh every second; history cards every 30 s. */
    private var ticks = 0
    private val ticker = object : Runnable {
        override fun run() {
            renderClock()
            renderBar()
            renderBarStatus(animate = true)
            renderMonitorCard()
            renderWobble()
            if (++ticks % 30 == 0) renderHistoryCards()
            ui.postDelayed(this, 1000)
        }
    }

    // ---- First-run welcome and tour ----

    private var tour: TourOverlay? = null
    private val tourBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { tour?.finish() }
    }

    /** New here: the welcome screens first, then a tour of this screen. */
    private fun showIntroIfNew() {
        if (check != null || tour != null) return
        if (!Tour.welcomeSeen(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        } else if (!Tour.tourSeen(this)) {
            // After the screen has been laid out, so the highlights land in the right place.
            findViewById<View>(R.id.scroll).post { startTour() }
        }
    }

    private fun startTour() {
        if (tour != null || isFinishing) return
        val scroll = findViewById<android.widget.ScrollView>(R.id.scroll)
        val stack = findViewById<LinearLayout>(R.id.cardStack)
        val firstCard = { (0 until stack.childCount).map { stack.getChildAt(it) }.firstOrNull { it.visibility == View.VISIBLE } }
        /** The scroll position that puts the view about a third of the way down the screen. */
        fun scrollFor(v: View?): Int {
            v ?: return scroll.scrollY
            val r = android.graphics.Rect()
            v.getDrawingRect(r)
            // Gives the view's position within the scrolled content (the scroll offset isn't included).
            scroll.offsetDescendantRectToMyCoords(v, r)
            return (r.top - scroll.height / 3).coerceAtLeast(0)
        }
        val steps = listOf(
            TourOverlay.Step(
                "Check now",
                "Tap it, then lay the phone face-up under a halogen light. It beeps the answer: " +
                    "1 long beep = moteur, 4 short = kahraba.",
                { checkButton }, { scrollFor(checkButton) },
            ),
            TourOverlay.Step(
                "Your room",
                "Choose the room you're in. After one check on each source there, the app can say kahraba for sure.",
                { spotButton }, { scrollFor(spotButton) },
            ),
            TourOverlay.Step(
                "Your cards",
                "Press and hold any card, then drag it up or down to move it. Hide cards in Settings.",
                firstCard, { scrollFor(firstCard()) },
            ),
            // The bottom bar is always on screen: no scrolling.
            TourOverlay.Step(
                "All your checks",
                "Tap a check to correct it. Corrections help the app learn and keep its accuracy score up to date.",
                { findViewById(R.id.barHistory) },
            ),
            TourOverlay.Step(
                "Settings and help",
                "Sound, rooms, your area, backup, and the full guide.",
                { findViewById(R.id.barSettings) },
            ),
        )
        val overlay = TourOverlay(this, steps, scroll) {
            tour = null
            tourBack.isEnabled = false
            Tour.setTourSeen(this)
            scroll.smoothScrollTo(0, 0)
        }
        tour = overlay
        tourBack.isEnabled = true
        overlay.start(findViewById(android.R.id.content))
    }

    private fun refreshAll() {
        if (check == null) showLastResult()
        renderSpot()
        renderHistory()
        renderClock()
        renderCalendar()
        renderBar()
        renderMonitorCard()
        renderHistoryCards()
        renderWobble()
        renderFact()
        applyCardSettings()
        renderSetup()
        renderAccuracy()
        renderUpdate()
        // The definition and the "lay the phone…" hint are for the first few checks only.
        val firstChecks = CheckHistory.read(this).count { !it.fromMonitor } < INTRO_CHECKS
        findViewById<View>(R.id.definition).visibility = if (firstChecks) View.VISIBLE else View.GONE
        findViewById<View>(R.id.checkHint).visibility = if (firstChecks) View.VISIBLE else View.GONE
        // After layout, so the NOW card's position is known.
        findViewById<View>(R.id.scroll).post { renderBarStatus(animate = false) }
    }

    // ---- History card: Today · 7 days · Calendar ----

    private val historyTabs = listOf("today" to "Today", "week" to "7 days", "calendar" to "Calendar")

    private fun setUpHistoryTabs() {
        val bar = findViewById<LinearLayout>(R.id.historyTabs)
        bar.setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        bar.background = GradientDrawable().apply {
            cornerRadius = 24 * dp
            setColor(themeColor(R.attr.appSurfaceAlt))
            setStroke((1 * dp).toInt(), themeColor(R.attr.appBorder))
        }
        for ((key, label) in historyTabs) {
            bar.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, (9 * dp).toInt(), 0, (9 * dp).toInt())
                setOnClickListener {
                    Prefs.sp(this@MainActivity).edit().putString(HISTORY_TAB, key).apply()
                    renderHistoryTab()
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        renderHistoryTab()
    }

    private fun renderHistoryTab() {
        val tab = Prefs.sp(this).getString(HISTORY_TAB, "today")
        val bar = findViewById<LinearLayout>(R.id.historyTabs)
        for ((i, pair) in historyTabs.withIndex()) {
            val on = pair.first == tab
            (bar.getChildAt(i) as TextView).apply {
                setTextColor(themeColor(if (on) R.attr.appOnAccent else R.attr.appMuted))
                background = if (on) GradientDrawable().apply { cornerRadius = 20 * dp; setColor(themeColor(R.attr.appAccent)) } else null
            }
        }
        findViewById<View>(R.id.historyToday).visibility = if (tab == "today") View.VISIBLE else View.GONE
        findViewById<View>(R.id.historyWeek).visibility = if (tab == "week") View.VISIBLE else View.GONE
        findViewById<View>(R.id.historyCalendar).visibility = if (tab == "calendar") View.VISIBLE else View.GONE
    }

    /**
     * The bottom bar shows the status only when it adds something: while the monitor runs (the NOW
     * card shows checks), or once the NOW card has scrolled out of sight.
     */
    private fun renderBarStatus(animate: Boolean) {
        val scroll = findViewById<android.widget.ScrollView>(R.id.scroll)
        val r = android.graphics.Rect()
        hero.getDrawingRect(r)
        scroll.offsetDescendantRectToMyCoords(hero, r)
        val nowOnScreen = r.bottom > scroll.scrollY + 24 * dp
        val show = MonitorState.running || !nowOnScreen
        if (show == barStatusShown) return
        barStatusShown = show
        val target = if (show) 1f else 0f
        for (v in listOf(barTile, findViewById<View>(R.id.barText))) {
            v.animate().cancel()
            if (animate) v.animate().alpha(target).setDuration(180).start() else v.alpha = target
        }
    }

    private var barStatusShown: Boolean? = null

    /** UPDATE: shown a month after this version was installed, until checked or put off. */
    private fun renderUpdate() {
        val due = Updates.reminderDue(this)
        findViewById<View>(R.id.updateCard).visibility = if (due) View.VISIBLE else View.GONE
        if (due) findViewById<TextView>(R.id.updateText).text =
            "You've had version ${Updates.version(this)} for a month. There may be a newer one with fixes."
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
        // Keep the last reading: the sensor is already running and only reports changes.
        checkStartMs = System.currentTimeMillis()
        // A short pause first, so the hand putting the phone down isn't read as a wobble or a move.
        measureFromMs = checkStartMs + SETTLE_MS
        confirmRow.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Fixed screen brightness: the light sensor sits under the screen, so auto-brightness
        // changing it mid-check could nudge the reading.
        window.attributes = window.attributes.apply { screenBrightness = LightMeterActivity.LOCKED_BRIGHTNESS }
        motion.start()
        checkButton.text = "Cancel"
        heroProgress.setProgressCompat(0, false)
        heroProgress.visibility = View.VISIBLE
        chartN = 0
        chartCheckMs = 0L
        heroChart.endLabel = "now"
        heroChart.setData(chartT, chartV, 0)
        heroChart.visibility = View.VISIBLE
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
            lux?.let { addChartPoint(now, it) }
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
        showHero(R.color.idle, R.drawable.ic_bolt, "Check stopped", "The phone was moved", "Place it down and try again")
    }

    private fun stopCheck(result: QuickCheck.Result?) {
        ui.removeCallbacks(sampler)
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
        // Keep the graph with this result.
        chartCheckMs = CheckHistory.read(this).first().timeMs
        heroChart.endLabel = "end"
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
        liveReadings.addLast(System.currentTimeMillis() to event.values[0].toDouble())
        if (System.currentTimeMillis() >= measureFromMs) check?.addRaw(System.currentTimeMillis(), event.values[0].toDouble())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- NOW card ----

    private fun addChartPoint(t: Long, v: Double) {
        if (chartN == CHART_MAX) {
            // Full: drop the oldest half (the graph only shows the last 30 seconds).
            val keep = CHART_MAX / 2
            System.arraycopy(chartT, CHART_MAX - keep, chartT, 0, keep)
            System.arraycopy(chartV, CHART_MAX - keep, chartV, 0, keep)
            chartN = keep
        }
        chartT[chartN] = t
        chartV[chartN] = v
        chartN++
        heroChart.setData(chartT, chartV, chartN)
    }

    private fun showLastResult() {
        val last = CheckHistory.read(this).firstOrNull { !it.fromMonitor }
        // The graph shows only with the check it was recorded for (not after a cancelled check).
        heroChart.visibility =
            if (check == null && last != null && last.timeMs == chartCheckMs && chartN > 1) View.VISIBLE else View.GONE
        if (last == null) {
            showHero(R.color.idle, R.drawable.ic_bolt, "Kahraba or not?", "Tap to check", "Lay the phone under a halogen light")
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
            QuickCheck.Verdict.NO_FLICKER -> showHero(R.color.idle, R.drawable.ic_bolt, "No flicker", "Kahraba, or a calm moteur", meta)
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
        v.findViewById<TextView>(R.id.sheetDate).text = "Tap a check to correct or delete it"
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

    /** The compact room picker above the Check button: the room and a one-line status. */
    private fun renderSpot() {
        val room = Prefs.currentSpot(this)
        spotButton.text = (room.ifEmpty { "Choose" }) + "  ▾"
        val (k, m) = CheckHistory.spotLevels(this, room)
        spotInfo.text = when {
            room.isEmpty() -> "No room chosen yet"
            k != null && m != null && k > m * QuickCheck.LEVEL_GAP -> "Set up and ready ✓"
            k != null && m != null -> "Kahraba and moteur look alike here"
            k == null && m == null -> "Not set up yet"
            k == null -> "Almost ready: needs a kahraba check"
            else -> "Almost ready: needs a moteur check"
        }
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

    /** Tap a check to correct it (e.g. it said kahraba but it was moteur) or delete it. */
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
                barLabel.text = "Kahraba or Not"
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

    // ---- Phase 2 cards ----

    /** DID YOU KNOW?: a fact about kahraba, a new one each day; tap for another. */
    private fun renderFact() {
        val (fact, n) = Facts.current(this)
        findViewById<TextView>(R.id.factText).text = fact
        findViewById<TextView>(R.id.factFooter).text = "Tap for another  ·  $n of ${Facts.all.size}"
    }

    /** Shows, hides and orders the cards as chosen in Settings > Cards > Arrange cards. */
    /** Card setting keys and their views. */
    private val cardIds = mapOf(
        "card_room" to R.id.roomCard, "card_advice" to R.id.adviceCard, "card_history" to R.id.historyCard,
        "card_recent" to R.id.recentCard, "card_accuracy" to R.id.accuracyCard, "card_tools" to R.id.toolsCard,
        "card_facts" to R.id.factsCard, "card_wobble" to R.id.wobbleCard,
    )

    private fun applyCardSettings() {
        val ids = cardIds
        for ((key, id) in ids) findViewById<View>(id).visibility = if (Prefs.cardOn(this, key)) View.VISIBLE else View.GONE
        // History stays hidden until there is something to show: a check, or the monitor's log.
        val hasHistory = CheckHistory.read(this).any { !it.fromMonitor } || EventLog.read(this).isNotEmpty()
        if (!hasHistory) findViewById<View>(R.id.historyCard).visibility = View.GONE

        // The saved order (from dragging a card, or Arrange cards); only moves views when it changed.
        val stack = findViewById<LinearLayout>(R.id.cardStack)
        val wanted = Prefs.cardOrder(this).mapNotNull { key -> ids[key]?.let { findViewById<View>(it) } }
        val now = (0 until stack.childCount).map { stack.getChildAt(it) }
        if (wanted.size == now.size && wanted != now) {
            stack.removeAllViews()
            for (v in wanted) stack.addView(v)
        }
    }

    /**
     * SET UP A ROOM: three steps to teach the current room (pick a room, one moteur check,
     * one confirmed kahraba check). Shown until the room is learned, or hidden by the user.
     */
    private fun renderSetup() {
        val card = findViewById<View>(R.id.setupCard)
        val room = Prefs.currentSpot(this)
        val (k, m) = CheckHistory.spotLevels(this, room)
        val learned = room.isNotEmpty() && k != null && m != null && k > m * QuickCheck.LEVEL_GAP
        card.visibility = if (Prefs.cardOn(this, SETUP_CARD) && !learned) View.VISIBLE else View.GONE
        if (card.visibility != View.VISIBLE) return

        val steps = findViewById<LinearLayout>(R.id.setupSteps)
        steps.removeAllViews()
        val pickRoom = {
            RoomPicker.pick(this, room) { picked ->
                Prefs.setCurrentSpot(this, picked)
                refreshAll()
            }
        }
        steps.addView(setupStep(1, room.isNotEmpty(),
            if (room.isEmpty()) "Choose the room you're in. Tap here." else "Room: $room",
            if (check == null) pickRoom else null))
        steps.addView(setupStep(2, m != null,
            "On moteur, do a check here. If it doesn't say Moteur, tap It was moteur."))
        steps.addView(setupStep(3, k != null,
            "On kahraba, do a check here, then tap It was kahraba."))
        findViewById<TextView>(R.id.setupTip).text = if (k != null && m != null) {
            "Kahraba and moteur look alike in this room. Try a spot closer to a halogen bulb, or another room."
        } else {
            "Only confirm when you know for sure. Keep the phone in the same place each time, with daylight out of the way."
        }
    }

    private fun setupStep(n: Int, done: Boolean, text: String, onClick: (() -> Unit)? = null): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val accent = themeColor(R.attr.appAccent)
        row.addView(TextView(this).apply {
            this.text = if (done) "✓" else "$n"
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (done) themeColor(R.attr.appOnAccent) else accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (done) setColor(accent) else setStroke((1.5f * dp).toInt(), accent)
            }
        }, LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt()))
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(themeColor(if (done) R.attr.appMuted else R.attr.appText))
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (onClick != null) {
            row.setOnClickListener { onClick() }
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            row.setBackgroundResource(ripple.resourceId)
        }
        return row
    }

    /** ACCURACY: how often each result was correct, from the checks the user confirmed. */
    private fun renderAccuracy() {
        val scores = CheckHistory.scores(CheckHistory.read(this))
        val right = scores.sumOf { it.right }
        val total = scores.sumOf { it.total }
        val list = findViewById<LinearLayout>(R.id.accuracyList)
        list.removeAllViews()
        val head = findViewById<TextView>(R.id.accuracyTotal)
        val note = findViewById<TextView>(R.id.accuracyNote)
        if (total == 0) {
            head.text = "No confirmed checks yet"
            note.text = "After a check, tap It was kahraba or It was moteur when you know for sure. The app keeps score here."
            return
        }
        head.text = "$right of $total correct (${right * 100 / total}%)"
        for (sc in scores.filter { it.total > 0 }) {
            val (label, value) = when (sc.said) {
                QuickCheck.Verdict.MOTEUR -> "Moteur results" to "${sc.right} of ${sc.total} correct"
                QuickCheck.Verdict.KAHRABA -> "Kahraba results" to "${sc.right} of ${sc.total} correct"
                else -> "No-flicker results" to "${sc.right} of ${sc.total} were kahraba"
            }
            list.addView(row(colorFor(sc.said), label, value))
        }
        note.text = (if (total < 10) "The score becomes reliable after about 10 confirmed checks. " else "") +
            "Counts checks you confirmed. Confirm correct results too, not only mistakes, so the score stays accurate."
    }

    private fun renderHistoryCards() {
        val now = System.currentTimeMillis()
        val spans = DayData.monitorSpans(this, now)
        val checks = CheckHistory.read(this).filter { !it.fromMonitor }
        renderTimeline(now, spans, checks)
        renderWeek(now, spans, checks)
        renderStats(now, spans, checks)
        renderAdvice(now)
    }

    /** LAST 12 HOURS: one column per hour, from the monitor where it ran, else from checks. */
    private fun renderTimeline(now: Long, spans: List<DayData.Span>, checks: List<CheckHistory.Entry>) {
        val row = findViewById<LinearLayout>(R.id.timelineRow)
        row.removeAllViews()
        val hourStart = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val label = SimpleDateFormat("h a", Locale.US)
        var anyData = false
        for (h in 11 downTo 0) {
            val from = hourStart - h * 3_600_000L
            val to = if (h == 0) now else from + 3_600_000L
            val k = DayData.msOf(spans, Source.KAHRABA, from, to)
            val m = DayData.msOf(spans, Source.MOTEUR, from, to)
            val inHour = checks.filter { it.timeMs in from until to }
            var icon = 0
            var color = R.color.idle
            var text = "–"
            if (k + m >= 5 * 60_000) {
                val kahrabaMost = k >= m
                icon = if (kahrabaMost) R.drawable.ic_bolt else R.drawable.ic_moteur
                color = if (kahrabaMost) R.color.kahraba else R.color.moteur
                text = if (k > 0) "${k / 60_000}m" else "0m"
            } else if (inHour.isNotEmpty()) {
                val last = inHour.maxBy { it.timeMs }
                icon = if (last.verdict == QuickCheck.Verdict.MOTEUR) R.drawable.ic_moteur else R.drawable.ic_bolt
                color = colorFor(last.verdict)
                text = "check"
            }
            if (icon != 0) anyData = true
            row.addView(timelineColumn(if (h == 0) "Now" else label.format(Date(from)), icon, color, text))
        }
        val scroll = findViewById<android.widget.HorizontalScrollView>(R.id.timelineScroll)
        scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) }

        val lastSwitch = EventLog.read(this).lastOrNull { it.source == Source.KAHRABA || it.source == Source.MOTEUR }
        findViewById<TextView>(R.id.timelineNote).text = when {
            lastSwitch != null && MonitorState.running ->
                "${EventLog.label(lastSwitch.source).lowercase().replaceFirstChar { it.uppercase() }} since ${PowerDetector.formatClock(lastSwitch.timeMs)}"
            anyData -> "Minutes are kahraba time recorded by the always-on monitor; other hours show your checks."
            else -> "No data yet. Do a check, or turn on the always-on monitor."
        }
    }

    private fun timelineColumn(hour: String, icon: Int, color: Int, text: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        col.addView(TextView(this).apply {
            this.text = hour
            textSize = 14f
            setTextColor(themeColor(if (hour == "Now") R.attr.appText else R.attr.appMuted))
            if (hour == "Now") setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(ImageView(this).apply {
            if (icon != 0) {
                setImageResource(icon)
                setColorFilter(ContextCompat.getColor(this@MainActivity, color))
            } else {
                setImageResource(R.drawable.dot)
                setColorFilter(themeColor(R.attr.appOutline))
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }
        }, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { topMargin = (8 * dp).toInt() })
        col.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(themeColor(R.attr.appText))
            setPadding(0, (8 * dp).toInt(), 0, 0)
        })
        col.layoutParams = LinearLayout.LayoutParams((60 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        return col
    }

    /** 7 DAYS: kahraba hours per day (monitor), or kahraba share of checks when there's no monitor data. */
    private fun renderWeek(now: Long, spans: List<DayData.Span>, checks: List<CheckHistory.Entry>) {
        val list = findViewById<LinearLayout>(R.id.weekList)
        list.removeAllViews()
        val today = DayData.startOfDay(now)
        val dayName = SimpleDateFormat("EEE", Locale.US)
        var usedMonitor = false
        for (d in 0..6) {
            val from = today - d * 86_400_000L
            val dayStart = DayData.startOfDay(from + 3_600_000L) // safe across clock changes
            val to = if (d == 0) now else dayStart + 86_400_000L
            val k = DayData.msOf(spans, Source.KAHRABA, dayStart, to)
            val m = DayData.msOf(spans, Source.MOTEUR, dayStart, to)
            val dayChecks = checks.filter { it.timeMs in dayStart until to }
            val name = if (d == 0) "Today" else dayName.format(Date(dayStart))
            if (k + m > 0) {
                usedMonitor = true
                list.addView(weekRow(name, R.drawable.ic_bolt, R.color.kahraba, DayData.hours(k), k.toFloat() / (to - dayStart)))
            } else if (dayChecks.isNotEmpty()) {
                val kc = dayChecks.count { it.verdict == QuickCheck.Verdict.KAHRABA || it.verdict == QuickCheck.Verdict.NO_FLICKER }
                val last = dayChecks.maxBy { it.timeMs }
                list.addView(weekRow(name, if (last.verdict == QuickCheck.Verdict.MOTEUR) R.drawable.ic_moteur else R.drawable.ic_bolt,
                    colorFor(last.verdict), "$kc/${dayChecks.size} checks", kc.toFloat() / dayChecks.size))
            } else {
                list.addView(weekRow(name, 0, R.color.idle, "no data", 0f))
            }
        }
        findViewById<TextView>(R.id.weekNote).text = if (usedMonitor) {
            "Bars show the share of each day on kahraba, from the always-on monitor."
        } else {
            "Bars show how many checks were kahraba (or no flicker). The always-on monitor gives exact hours."
        }
    }

    private fun weekRow(name: String, icon: Int, color: Int, value: String, share: Float): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }
        row.addView(TextView(this).apply {
            text = name
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(R.attr.appText))
        }, LinearLayout.LayoutParams((70 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(ImageView(this).apply {
            if (icon != 0) {
                setImageResource(icon)
                setColorFilter(ContextCompat.getColor(this@MainActivity, color))
            }
        }, LinearLayout.LayoutParams((20 * dp).toInt(), (20 * dp).toInt()))
        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(themeColor(R.attr.appMuted))
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        }, LinearLayout.LayoutParams((104 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        // Bar: a track with the kahraba share filled.
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = 4 * dp; setColor(themeColor(R.attr.appOutline)) }
        }
        val fill = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ContextCompat.getColor(this@MainActivity, R.color.kahraba), lighten(ContextCompat.getColor(this@MainActivity, R.color.kahraba), 0.35f)),
            ).apply { cornerRadius = 4 * dp }
        }
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        row.addView(track, LinearLayout.LayoutParams(0, (7 * dp).toInt(), 1f))
        track.post {
            fill.layoutParams = fill.layoutParams.apply { width = (track.width * share.coerceIn(0f, 1f)).toInt() }
        }
        return row
    }

    /** KAHRABA TODAY: hours, switches, last switch and longest stretch (monitor), or check counts. */
    private fun renderStats(now: Long, spans: List<DayData.Span>, checks: List<CheckHistory.Entry>) {
        val dayStart = DayData.startOfDay(now)
        val k = DayData.msOf(spans, Source.KAHRABA, dayStart, now)
        val m = DayData.msOf(spans, Source.MOTEUR, dayStart, now)
        val labels = listOf(R.id.statLabel1, R.id.statLabel2, R.id.statLabel3, R.id.statLabel4).map { findViewById<TextView>(it) }
        val values = listOf(R.id.statValue1, R.id.statValue2, R.id.statValue3, R.id.statValue4).map { findViewById<TextView>(it) }
        val note = findViewById<TextView>(R.id.statNote)
        if (k + m > 0) {
            val events = EventLog.read(this).filter { it.timeMs >= dayStart && (it.source == Source.KAHRABA || it.source == Source.MOTEUR) }
            val last = events.lastOrNull()
            val longest = spans.filter { it.source == Source.KAHRABA }
                .maxOfOrNull { maxOf(0L, minOf(it.end, now) - maxOf(it.start, dayStart)) } ?: 0L
            val pairs = listOf(
                "Kahraba" to DayData.hours(k),
                "Moteur" to DayData.hours(m),
                "Switches" to "${events.size}",
                "Longest kahraba" to DayData.hours(longest),
            )
            for (i in 0..3) { labels[i].text = pairs[i].first; values[i].text = pairs[i].second }
            note.text = if (last != null) {
                "Last switch: ${EventLog.label(last.source).lowercase()} at ${PowerDetector.formatClock(last.timeMs)}"
            } else "From the always-on monitor."
        } else {
            val today = checks.filter { it.timeMs >= dayStart }
            val kc = today.count { it.verdict == QuickCheck.Verdict.KAHRABA }
            val mc = today.count { it.verdict == QuickCheck.Verdict.MOTEUR }
            val lastK = checks.firstOrNull { it.verdict == QuickCheck.Verdict.KAHRABA }
            val pairs = listOf(
                "Checks today" to "${today.size}",
                "Kahraba" to "$kc",
                "Moteur" to "$mc",
                "Last kahraba" to (lastK?.let { PowerDetector.formatClock(it.timeMs) } ?: "–"),
            )
            for (i in 0..3) { labels[i].text = pairs[i].first; values[i].text = pairs[i].second }
            note.text = "Turn on the always-on monitor for exact kahraba hours and switch times."
        }
    }

    /** WHAT TO RUN: simple advice for the current source. */
    private fun renderAdvice(now: Long) {
        val icon = findViewById<ImageView>(R.id.adviceIcon)
        val title = findViewById<TextView>(R.id.adviceTitle)
        val sub = findViewById<TextView>(R.id.adviceSub)
        // The monitor if it's running, otherwise a check from the last 30 minutes.
        val recent = CheckHistory.read(this).firstOrNull { !it.fromMonitor }?.takeIf { now - it.timeMs < 30 * 60_000 }
        val source: Source
        val since: String
        if (MonitorState.running && MonitorState.source != Source.UNKNOWN) {
            source = MonitorState.source
            since = "since ${PowerDetector.formatClock(MonitorState.sinceMs)}"
        } else if (recent != null && recent.verdict != QuickCheck.Verdict.CANT_TELL) {
            source = when (recent.verdict) {
                QuickCheck.Verdict.MOTEUR -> Source.MOTEUR
                QuickCheck.Verdict.KAHRABA -> Source.KAHRABA
                else -> Source.UNKNOWN
            }
            since = "checked ${PowerDetector.formatClock(recent.timeMs)}"
        } else {
            source = Source.UNKNOWN
            since = ""
        }
        val noFlicker = recent?.verdict == QuickCheck.Verdict.NO_FLICKER && !MonitorState.running
        when {
            source == Source.KAHRABA -> {
                icon.setImageResource(R.drawable.ic_bolt)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.kahraba))
                title.text = "Run the big things"
                sub.text = "Kahraba $since. Good time for the washing machine, water heater, oven, iron or water pump."
            }
            source == Source.MOTEUR -> {
                icon.setImageResource(R.drawable.ic_moteur)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.moteur))
                title.text = "Keep it light"
                sub.text = "Moteur $since. Stay under your amps: avoid the heater, kettle and washing machine at the same time."
            }
            noFlicker -> {
                icon.setImageResource(R.drawable.ic_bolt)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.idle))
                title.text = "Not sure yet"
                sub.text = "No wobble $since, but a calm moteur looks the same. Confirm before running several big appliances."
            }
            else -> {
                icon.setImageResource(R.drawable.ic_bolt)
                icon.setColorFilter(ContextCompat.getColor(this, R.color.idle))
                title.text = "Check first"
                sub.text = "Do a quick check to see if it's kahraba before running big appliances."
            }
        }
    }

    // Live readings for the Light wobble card (last few seconds).
    private val liveReadings = ArrayDeque<Pair<Long, Double>>()

    /** LIGHT WOBBLE: live lux and how much it wobbles now, as the app judges it. */
    private fun renderWobble() {
        val now = System.currentTimeMillis()
        lux?.let { liveReadings.addLast(now to it) } // hold the last value between sensor updates
        while (liveReadings.isNotEmpty() && liveReadings.first().first < now - PowerDetector.FLICKER_WINDOW_MS) liveReadings.removeFirst()
        val values = liveReadings.map { it.second }
        val med = PowerDetector.median(values)
        val luxView = findViewById<TextView>(R.id.wobbleLux)
        val title = findViewById<TextView>(R.id.wobbleTitle)
        val sub = findViewById<TextView>(R.id.wobbleSub)
        val note = findViewById<TextView>(R.id.wobbleNote)
        val minLux = Prefs.config(this).minLux
        luxView.text = med?.let { it.toInt().toString() } ?: "–"
        val wobble = if (med != null && med > 0 && values.size >= 3) (values.max() - values.min()) / med * 100 else null
        val (name, detail) = when {
            med == null -> "No reading" to "Waiting for the light sensor"
            med < minLux -> "Too dim" to "Needs at least ${minLux.toInt()} lux to judge"
            wobble == null || wobble < Prefs.config(this).flickerThresholdPct -> "Steady" to "Kahraba, or a calm moteur"
            wobble < 8 -> "Slight wobble" to "Moteur sign"
            wobble < 20 -> "Busy" to "Moteur, wobbling"
            else -> "Very busy" to "Moteur, or something moved"
        }
        title.text = name
        sub.text = detail + (wobble?.let { " · %.0f%%".format(it) } ?: "")
        note.text = "Live from the light sensor while this screen is open. A wobble means moteur; steady light can be either."

        val gauge = findViewById<LinearLayout>(R.id.wobbleGauge)
        val segments = 12
        if (gauge.childCount != segments) {
            gauge.removeAllViews()
            for (i in 0 until segments) gauge.addView(View(this),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginEnd = if (i < segments - 1) (4 * dp).toInt() else 0
                })
        }
        // 0–30% wobble across 12 segments; steady is one green segment.
        val filled = when {
            med == null || med < minLux -> 0
            wobble == null || wobble < Prefs.config(this).flickerThresholdPct -> 1
            else -> (wobble / 2.5).toInt().coerceIn(2, segments)
        }
        val on = ContextCompat.getColor(this, if (filled <= 1) R.color.kahraba else R.color.moteur)
        for (i in 0 until segments) {
            gauge.getChildAt(i).background = GradientDrawable().apply {
                cornerRadius = 4 * dp
                setColor(if (i < filled) on else themeColor(R.attr.appOutline))
            }
        }
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
        /** 2 minutes of readings at 5 a second; plenty for a check. */
        private const val CHART_MAX = 600
        private const val CAL_MONITOR = "calendar_monitor"
        private const val SETUP_CARD = "card_setup"
        private const val HISTORY_TAB = "history_tab"
        /** The definition and hint show until this many checks have been done. */
        private const val INTRO_CHECKS = 3
    }
}
