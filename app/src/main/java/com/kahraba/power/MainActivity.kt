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
        // Before the update card reads the version, which would hide that this one is new.
        whatsNew = WhatsNew.pending(this)
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

        setUpHome()

        // Back closes the tour if it's showing (added last, so it goes first).
        onBackPressedDispatcher.addCallback(this, tourBack)

        // First launch shows the welcome and tour (from onResume) instead of an automatic check.
        if (savedInstanceState == null && Tour.welcomeSeen(this) && Tour.tourSeen(this) && Prefs.autoCheck(this)) {
            checkFromHome = true
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
        // Getting alerts from another phone: make sure it's listening (e.g. after Android stopped it).
        AlertsService.start(this)
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
            renderHome()
            if (++ticks % 30 == 0) {
                renderHistoryCards()
                renderPattern()
                if (peekOpen) renderPeek()
            }
            ui.postDelayed(this, 1000)
        }
    }

    // ---- First-run welcome and tour ----

    private var tour: TourOverlay? = null
    private val tourBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { tour?.finish() }
    }

    /** What's new since the version used before this update; shown once. */
    private var whatsNew: List<WhatsNew.Section> = emptyList()

    /** New here: the welcome screens first, then a tour of this screen. Just updated: what's new. */
    private fun showIntroIfNew() {
        if (check != null || tour != null) return
        if (!Tour.welcomeSeen(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        } else if (!Tour.tourSeen(this)) {
            // After the screen has been laid out, so the highlights land in the right place.
            showPower(false)
            home.post { startTour() }
        } else if (whatsNew.isNotEmpty()) {
            val sections = whatsNew
            whatsNew = emptyList()
            MaterialAlertDialogBuilder(this)
                .setTitle(WhatsNew.title(sections))
                .setMessage(WhatsNew.message(sections))
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    private fun startTour() {
        if (tour != null || isFinishing) return
        val steps = listOf(
            TourOverlay.Step(
                "Check",
                "Tap the button, then lay the phone face-up under a halogen light. It beeps the answer: " +
                    "1 long beep = moteur, 4 short = kahraba.",
                { homeButton },
            ),
            TourOverlay.Step(
                "Your room",
                "Choose the room you're in. After one check on each source there, the app can say kahraba for sure.",
                { findViewById(R.id.homeRoom) },
            ),
            TourOverlay.Step(
                "Always-on mode",
                "For a spare phone left on its charger under a light: it beeps when kahraba comes or goes. " +
                    "Tap here, or press and hold the button.",
                { findViewById(R.id.homeChip) },
            ),
            TourOverlay.Step(
                "Your power",
                "Swipe up or tap here for Today at a glance. Swipe up again for the kahraba pattern, history and all your checks.",
                { findViewById(R.id.homeHandle) },
            ),
            TourOverlay.Step(
                "Menu",
                "Always-on mode, Alerts on other phones, Tools (the Light meter and Amps calculator), all your checks, settings and help.",
                { findViewById(R.id.homeSettings) },
            ),
        )
        val overlay = TourOverlay(this, steps, null) {
            tour = null
            tourBack.isEnabled = false
            Tour.setTourSeen(this)
        }
        tour = overlay
        tourBack.isEnabled = true
        overlay.start(findViewById(android.R.id.content))
    }

    // ---- Home (1.4.0): one big button; everything else is in Your power ----

    private lateinit var home: View
    private lateinit var homeButton: View
    /** The live graph under the button while a check runs, and the whole check's graph with its result. */
    private lateinit var homeChart: LuxChart
    private lateinit var resultChart: LuxChart
    private var homeSource = Source.UNKNOWN
    private var homeColor = 0
    private var homeTop = 0
    private var homeBottom = 0
    private var homePulse: android.animation.ObjectAnimator? = null
    private var ringPulse: android.animation.ObjectAnimator? = null
    /** Today at a glance: a short swipe up on home shows it; swipe again for all of Your power. */
    private lateinit var peek: LinearLayout
    private lateinit var peekBody: LinearLayout
    private var peekOpen = false
    /** Back from Today returns home. */
    private val peekBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showPeek(false) }
    }
    /** "Showing: Pixel 2 ▾" at the top of Your power, when this phone gets alerts from another. */
    private var powerShowing: TextView? = null
    /** The running check was started from the home screen, so its result shows there. */
    private var checkFromHome = false

    /** Back from Your power returns home. */
    private val powerBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showPower(false) }
    }
    /** Back from a check result returns home. */
    private val resultBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { hideResult() }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setUpHome() {
        home = findViewById(R.id.home)
        homeButton = findViewById(R.id.homeButton)
        homeChart = findViewById<LuxChart>(R.id.homeChart).apply {
            windowSeconds = 30
            useColors(0xFFFFFFFF.toInt(), 0x40FFFFFF, 0xD9FFFFFF.toInt())
        }
        resultChart = findViewById<LuxChart>(R.id.resultChart).apply {
            endLabel = "end"
            useColors(0xFFFFFFFF.toInt(), 0x40FFFFFF, 0xD9FFFFFF.toInt())
        }
        homeButton.setOnClickListener {
            when {
                peekOpen -> showPeek(false)
                check != null -> stopCheck(null)
                homeSource == Source.NO_POWER -> toast("The power is off. Nothing to check right now.")
                else -> {
                    checkFromHome = true
                    startCheck()
                }
            }
        }
        homeButton.setOnLongClickListener {
            if (check == null) openAlwaysOn()
            true
        }
        findViewById<View>(R.id.homeChip).setOnClickListener {
            if (!MonitorState.running && RemoteAlerts.getPairing(this) != null) SettingsActivity.openRemote(this) else openAlwaysOn()
        }
        findViewById<View>(R.id.homeHandle).setOnClickListener { showPeek(true) }
        findViewById<View>(R.id.homeSettings).setOnClickListener { showTools() }
        findViewById<TextView>(R.id.homeRoom).apply {
            setOnClickListener { if (check == null) pickRoom() }
            // A small white pin before the room name.
            val pin = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin)?.mutate()
            pin?.setTint(0xFFFFFFFF.toInt())
            pin?.setBounds(0, 0, (16 * dp).toInt(), (16 * dp).toInt())
            setCompoundDrawablesRelative(pin, null, null, null)
        }
        setUpPowerSheet()
        setUpPeek()
        // Drag up anywhere on home and Today follows the finger; keep going for Your power. Drag Your power
        // down (from the top of its list) to close it. A tap outside Today closes it.
        (home as DragFrame).sheet = sheetDrag
        findViewById<PullScrollView>(R.id.scroll).sheet = sheetDrag
        home.setOnClickListener { if (peekOpen) showPeek(false) }
        findViewById<View>(R.id.resultDone).setOnClickListener { hideResult() }
        onBackPressedDispatcher.addCallback(this, peekBack)
        onBackPressedDispatcher.addCallback(this, powerBack)
        onBackPressedDispatcher.addCallback(this, resultBack)
    }

    private fun toast(text: String) = android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()

    private fun openAlwaysOn() = startActivity(Intent(this, MonitorActivity::class.java))

    /** Slides the Your power sheet up over home, or back down to home. */
    private fun showPower(show: Boolean) {
        if (!::home.isInitialized || !::peek.isInitialized) return
        if (show) {
            if (peekH <= 0f) measurePeek()
            pageReady = false
            settleTo(fullP(), 300)
        } else {
            closePower(260)
        }
    }

    /**
     * Your power slides straight down to home, from wherever the finger left it: it doesn't stop at
     * Today on the way (that showed Today flashing behind it).
     */
    private fun closePower(ms: Long, open: Boolean = false) {
        settle?.cancel()
        val page = findViewById<android.widget.ScrollView>(R.id.scroll)
        val h = home.height.toFloat()
        peek.visibility = View.INVISIBLE
        settle = android.animation.ValueAnimator.ofFloat(page.translationY, if (open) 0f else h).apply {
            duration = ms
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                page.translationY = it.animatedValue as Float
                blendBars(page.translationY)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    if (open) sheetP = fullP() else place(0f)
                    settled()
                }
            })
            start()
        }
    }

    // ---- Panels that follow the finger (1.5.0) ----
    // How far up the panels are, in pixels: 0 = home, peekH = Today open, peekH + home's height = Your power.

    private var sheetP = 0f
    private var dragFrom = 0f
    private var peekH = 0f
    /** Your power was refreshed for this trip up. */
    private var pageReady = false
    private var settle: android.animation.ValueAnimator? = null
    /** This drag started on an open Your power: it only goes down to home (or back up). */
    private var closing = false

    private fun fullP() = peekH + home.height.toFloat().coerceAtLeast(1f)

    /** Today's height with its latest content. */
    private fun measurePeek() {
        renderPeek()
        peek.measure(
            View.MeasureSpec.makeMeasureSpec(home.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        peekH = peek.measuredHeight.toFloat()
    }

    /** Puts Today and Your power where [p] says. */
    private fun place(p: Float) {
        val full = fullP()
        sheetP = p.coerceIn(0f, full)
        peek.visibility = if (sheetP > 0f && sheetP < full) View.VISIBLE else View.INVISIBLE
        peek.translationY = peekH - minOf(sheetP, peekH)
        val page = findViewById<android.widget.ScrollView>(R.id.scroll)
        if (sheetP > peekH) {
            if (!pageReady) {
                pageReady = true
                refreshAll()
                page.scrollTo(0, 0)
            }
            page.visibility = View.VISIBLE
            page.translationY = full - sheetP
            blendBars(page.translationY)
        } else {
            page.visibility = View.GONE
            page.translationY = home.height.toFloat()
            blendBars(page.translationY)
        }
        findViewById<View>(R.id.homeHandle).alpha = 1f - (sheetP / peekH.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    private fun settleTo(target: Float, ms: Long = 240) {
        settle?.cancel()
        settle = android.animation.ValueAnimator.ofFloat(sheetP, target).apply {
            duration = ms
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { place(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = settled()
            })
            start()
        }
    }

    /** Back, and the bar colours, follow where the panels came to rest. */
    private fun settled() {
        val atFull = sheetP >= fullP() - 1f
        val atPeek = !atFull && sheetP > 0f && sheetP >= peekH - 1f
        powerBack.isEnabled = atFull
        peekOpen = atPeek
        peekBack.isEnabled = atPeek
        if (sheetP <= 0f) pageReady = false
        colorSystemBars()
    }

    private val sheetDrag = object : SheetDrag {
        override fun dragStart(): Boolean {
            if (check != null || tour != null || findViewById<View>(R.id.homeResult).visibility == View.VISIBLE) return false
            settle?.cancel()
            closing = powerBack.isEnabled
            if (closing) return true
            // Fresh content (and height) for Today when it's closed or open, not mid-way; and Your power
            // refreshed now, so the drag doesn't pause when it comes into view.
            if (sheetP <= 0f || peekOpen) {
                measurePeek()
                if (peekOpen) sheetP = peekH
            }
            if (!pageReady) {
                pageReady = true
                refreshAll()
                findViewById<android.widget.ScrollView>(R.id.scroll).scrollTo(0, 0)
            }
            dragFrom = sheetP
            return true
        }

        override fun dragBy(dy: Float) {
            if (closing) {
                val page = findViewById<android.widget.ScrollView>(R.id.scroll)
                page.translationY = dy.coerceIn(0f, home.height.toFloat())
                blendBars(page.translationY)
            } else {
                place(dragFrom - dy)
            }
        }

        override fun dragEnd(velocityY: Float) {
            val fling = 500 * dp
            if (closing) {
                // Pulled down a quarter of the way, or flicked down: close. Otherwise back up.
                val down = findViewById<android.widget.ScrollView>(R.id.scroll).translationY
                val close = velocityY > fling || (velocityY > -fling && down > home.height / 4f)
                closePower(220, open = !close)
                return
            }
            val full = fullP()
            val stops = listOf(0f, peekH, full)
            val target = when {
                velocityY < -fling -> stops.firstOrNull { it > sheetP + 1f } ?: full
                velocityY > fling -> stops.lastOrNull { it < sheetP - 1f } ?: 0f
                else -> stops.minByOrNull { kotlin.math.abs(it - sheetP) } ?: 0f
            }
            settleTo(target)
        }
    }

    /**
     * 1.4.01: the old main screen is gone. Its scroll view is now the Your power sheet: a header
     * with a close button, then the cards (the kahraba pattern, recent checks, accuracy by default).
     * Home sits underneath; the sheet slides up over it.
     */
    private fun setUpPowerSheet() {
        val page = findViewById<android.widget.ScrollView>(R.id.scroll)
        val root = home.parent as android.view.ViewGroup
        root.removeView(home)
        root.addView(home, 0)
        page.setBackgroundColor(themeColor(R.attr.appBg))
        page.visibility = View.GONE
        page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, (32 * dp).toInt())
        findViewById<View>(R.id.bottomBar).visibility = View.GONE

        val inner = page.getChildAt(0) as LinearLayout
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, (4 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = "Your power"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(R.attr.appText))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "×"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(themeColor(R.attr.appText))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.circle_button)
            contentDescription = "Close"
            setOnClickListener { showPower(false) }
        }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
        inner.addView(header, 0)
        powerShowing = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(R.attr.appText))
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 999f
                setColor(themeColor(R.attr.appSurface))
                setStroke((1 * dp).toInt(), themeColor(R.attr.appBorder))
            }
            setOnClickListener { pickShowing() }
        }
        inner.addView(powerShowing, 1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = (4 * dp).toInt()
            topMargin = (6 * dp).toInt()
            bottomMargin = (4 * dp).toInt()
        })
        renderShowing()
        hideOldMain()
    }

    // ---- Today (the peek over home) ----

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setUpPeek() {
        peek = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(themeColor(R.attr.appSurface))
                cornerRadii = FloatArray(8) { i -> if (i < 4) 26 * dp else 0f }
            }
            elevation = 16 * dp
            isClickable = true
            visibility = View.INVISIBLE
        }
        // The grab handle: swipe Today up for Your power, or down to close it.
        peek.addView(View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 3 * dp
                setColor(themeColor(R.attr.appBorder))
            }
        }, LinearLayout.LayoutParams((40 * dp).toInt(), (5 * dp).toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = (10 * dp).toInt()
        })
        peekBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        peek.addView(peekBody)
        peek.addView(com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            text = "See all in Your power"
            cornerRadius = (24 * dp).toInt()
            setTextColor(themeColor(R.attr.appText))
            strokeColor = android.content.res.ColorStateList.valueOf(themeColor(R.attr.appBorder))
            setOnClickListener { showPower(true) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (14 * dp).toInt()
        })
        (home as android.widget.FrameLayout).addView(peek, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))
    }

    /** Slides Today up from the bottom of home, or back down. */
    private fun showPeek(show: Boolean) {
        if (!::peek.isInitialized) return
        if (show) {
            if (check != null) return
            measurePeek()
            settleTo(peekH, 260)
        } else {
            settleTo(0f, 220)
        }
    }

    /** Today: the day's bar, what happened in words, the last few switches, and the next kahraba. */
    private fun renderPeek() {
        val now = System.currentTimeMillis()
        val entries = EventLog.shown(this)
        val running = EventLog.shownLive(this)
        val dayStart = DayData.startOfDay(now)
        val dayEnd = java.util.Calendar.getInstance().apply {
            timeInMillis = dayStart; add(java.util.Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val b = peekBody
        b.removeAllViews()
        fun line(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) =
            b.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(themeColor(color))
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (top * dp).toInt()
            })

        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "Today"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(themeColor(R.attr.appText))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = SimpleDateFormat("EEE d MMM", Locale.US).format(Date(now)) +
                if (EventLog.showsRemote(this@MainActivity)) " · from ${otherPhoneName()}" else ""
            textSize = 13f
            setTextColor(themeColor(R.attr.appMuted))
        })
        b.addView(head)

        if (entries.isEmpty()) {
            line("Nothing recorded yet. Turn on Always-on mode on a phone left under a light, " +
                "or get alerts from one (Menu › Alerts on other phones).", 15f, R.attr.appMuted, top = 8)
            return
        }
        // The day's bar (no day label: it's today), with the hours under it.
        val bar = patternRow("", dayStart, dayEnd, KahrabaPattern.spans(entries, now, running)) as LinearLayout
        bar.getChildAt(0).visibility = View.GONE
        b.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dp).toInt()
        })
        val hours = LinearLayout(this)
        listOf("12 AM", "6 AM", "12 PM", "6 PM", "").forEachIndexed { i, h ->
            hours.addView(TextView(this).apply {
                text = h
                textSize = 11f
                setTextColor(themeColor(R.attr.appMuted))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (i == 4) 0.01f else 1f))
        }
        b.addView(hours)
        line(KahrabaPattern.today(entries, now, running, dayStart), 15f, R.attr.appText, top = 8)

        // The next kahraba, or how long this one usually lasts.
        val s = KahrabaPattern.summary(entries, now, running)
        fun around(ms: Long) = PowerDetector.formatClock((ms + 150_000) / 300_000 * 300_000)
        val next = when {
            s.onSinceMs != null && s.typicalMs != null -> "Kahraba usually lasts ${DayData.hours(s.typicalMs)}, so until about ${around(s.onSinceMs + s.typicalMs)}."
            s.nextFromMs != null && s.nextToMs != null ->
                if (s.nextFromMs == s.nextToMs) "Next kahraba: around ${around(s.nextFromMs)}."
                else "Next kahraba: between ${around(s.nextFromMs)} and ${around(s.nextToMs)}."
            else -> null
        }
        next?.let { line(it, 15f, R.attr.appText, bold = true, top = 8) }

        // The last few switches today, newest first.
        // Each with how long it lasted (until the next change), or "so far" for the one on now.
        val sorted = entries.sortedBy { it.timeMs }
        val today = sorted.withIndex().filter { it.value.timeMs >= dayStart && it.value.source != Source.UNKNOWN }
            .sortedByDescending { it.value.timeMs }.take(4).reversed() // the last 4, in time order
        if (today.isNotEmpty()) {
            line("TODAY'S TIMELINE", 12f, R.attr.appMuted, bold = true, top = 12)
            timelineRows(b, today, sorted, running, now)
        }
    }

    /**
     * Timeline rows ("5:00 PM  ● Kahraba  (42m so far)  ⌁"), for Today and a day's details: each with
     * how long it lasted (until the next change in [sorted]), or "so far" for the one on now, and a
     * small grey crossed bolt when it began with a power cut (explained under the list).
     */
    private fun timelineRows(into: LinearLayout, rows: List<IndexedValue<EventLog.Entry>>, sorted: List<EventLog.Entry>,
                             running: Boolean, now: Long, dayStart: Long? = null) {
        val dayName = SimpleDateFormat("EEE", Locale.US)
        for ((i, e) in rows) {
            val next = sorted.getOrNull(i + 1)
            val lasted = when {
                next != null -> "  (${KahrabaPattern.hours(next.timeMs - e.timeMs)})"
                running -> "  (${KahrabaPattern.hours(now - e.timeMs)} so far)"
                else -> ""
            }
            val cut = isAfterCut(e)
            val t = android.text.SpannableString(
                // Before the day (what was on at midnight): "11:30 PM Sun".
                "${PowerDetector.formatClock(e.timeMs)}${if (dayStart != null && e.timeMs < dayStart) " " + dayName.format(Date(e.timeMs)) else ""}" +
                    "   ●  ${RemoteAlerts.word(e.source)}$lasted" + if (cut) "  \uFFFC" else "",
            )
            val dot = t.indexOf('●')
            t.setSpan(android.text.style.ForegroundColorSpan(MonitorService.colorFor(e.source)), dot, dot + 1, 0)
            into.addView(TextView(this).apply {
                textSize = 15f
                if (cut) t.setSpan(android.text.style.ImageSpan(cutMark(textSize.toInt()), android.text.style.ImageSpan.ALIGN_BASELINE),
                    t.length - 1, t.length, 0)
                text = t
                setTextColor(themeColor(R.attr.appText))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (2 * dp).toInt()
            })
        }
        if (rows.any { isAfterCut(it.value) }) {
            val key = android.text.SpannableString("\uFFFC  after a power cut")
            into.addView(TextView(this).apply {
                textSize = 12f
                key.setSpan(android.text.style.ImageSpan(cutMark(textSize.toInt()), android.text.style.ImageSpan.ALIGN_BASELINE), 0, 1, 0)
                text = key
                setTextColor(themeColor(R.attr.appMuted))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * dp).toInt()
            })
        }
    }

    /**
     * A day of the Kahraba pattern, tapped (1.5.04): when kahraba was on that day, then its whole
     * timeline. From the shown log (this phone's, or the other phone's).
     */
    private fun showPatternDay(dayStart: Long, dayEnd: Long) {
        val now = System.currentTimeMillis()
        val entries = EventLog.shown(this)
        val live = EventLog.shownLive(this) && dayEnd > now
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_day, null)
        v.findViewById<TextView>(R.id.sheetTitle).text = SimpleDateFormat("EEEE", Locale.US).format(Date(dayStart))
        v.findViewById<TextView>(R.id.sheetDate).text = SimpleDateFormat("d MMMM", Locale.US).format(Date(dayStart)) +
            if (EventLog.showsRemote(this)) " · from ${otherPhoneName()}" else ""
        v.findViewById<View>(R.id.sheetNote).visibility = View.GONE
        val content = v.findViewById<LinearLayout>(R.id.sheetContent)
        fun line(text: CharSequence, size: Float, color: Int, bold: Boolean = false, top: Int = 0) =
            content.addView(TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(themeColor(color))
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (top * dp).toInt()
            })

        val running = EventLog.shownLive(this)
        // The day's total counts only its own hours; each period is shown whole, with the other day's
        // name where it starts before midnight or runs past it (1.5.05).
        val inDay = KahrabaPattern.kahrabaPeriods(entries, dayStart, dayEnd, now, running)
        val periods = KahrabaPattern.kahrabaPeriodsTouching(entries, dayStart, dayEnd, now, running)
        val ran = KahrabaPattern.recordedMs(entries, dayStart, dayEnd, now, running)
        val dayName = SimpleDateFormat("EEEE", Locale.US).format(Date(dayStart))
        val shortDay = SimpleDateFormat("EEE", Locale.US)
        fun at(ms: Long) = PowerDetector.formatClock(ms) +
            if (ms < dayStart || ms >= dayEnd) " " + shortDay.format(Date(ms)) else ""
        when {
            ran < 60_000 && periods.isEmpty() -> line("Nothing recorded this day: Always-on mode wasn't running.", 15f, R.attr.appMuted)
            periods.isEmpty() -> line("No kahraba on $dayName.", 17f, R.attr.appText, bold = true)
            else -> {
                line("Kahraba on $dayName: ${KahrabaPattern.hours(inDay.sumOf { it.end - it.start })}", 17f, R.attr.appText, bold = true)
                for (p in periods) {
                    val onNow = running && p.end >= now - 1_000
                    val t = android.text.SpannableString(
                        if (onNow) "●  Since ${at(p.start)}  (${KahrabaPattern.hours(now - p.start)} so far)"
                        else "●  ${at(p.start)} – ${at(p.end)}  (${KahrabaPattern.hours(p.end - p.start)})",
                    )
                    t.setSpan(android.text.style.ForegroundColorSpan(MonitorService.colorFor(Source.KAHRABA)), 0, 1, 0)
                    line(t, 15f, R.attr.appText, top = 4)
                }
            }
        }
        val sorted = entries.sortedBy { it.timeMs }
        // The day's switches, starting with whatever was on at midnight (unless Always-on was stopped).
        val before = sorted.withIndex().lastOrNull { it.value.timeMs < dayStart }?.takeIf { it.value.source != Source.UNKNOWN }
        val rows = listOfNotNull(before) +
            sorted.withIndex().filter { it.value.timeMs in dayStart until dayEnd && it.value.source != Source.UNKNOWN }
        if (rows.isNotEmpty()) {
            line("TIMELINE", 12f, R.attr.appMuted, bold = true, top = 16)
            timelineRows(content, rows, sorted, live, now, dayStart)
        }
        v.findViewById<View>(R.id.sheetDone).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(v)
        sheet.show()
    }

    /** The switch began with the power going off: a cut the moteur took over, power back after an outage, or a blink. */
    private fun isAfterCut(e: EventLog.Entry) =
        e.reason.startsWith("Power cut") || e.reason.startsWith("Power back") || e.reason.startsWith("Power blinked")

    /** The small grey crossed bolt that marks a power cut, [size] pixels square. */
    private fun cutMark(size: Int) = ContextCompat.getDrawable(this, R.drawable.ic_power_off)!!.mutate().apply {
        setTint(themeColor(R.attr.appMuted))
        setBounds(0, 0, size, size)
    }

    /** Whose data Your power shows: the phone running Always-on mode that sends alerts here, or this one. */
    private fun renderShowing() {
        val v = powerShowing ?: return
        if (RemoteAlerts.getPairing(this) == null) {
            v.visibility = View.GONE
            return
        }
        v.visibility = View.VISIBLE
        v.text = "Showing: " + (if (EventLog.showsRemote(this)) otherPhoneName() else "This phone") + "  ▾"
    }

    private fun otherPhoneName() = RemoteAlerts.heard(this)?.device?.takeIf { it.isNotEmpty() } ?: "Your other phone"

    private fun pickShowing() {
        val remote = EventLog.showsRemote(this)
        val items = arrayOf(
            otherPhoneName() + " (sends you alerts)" + if (remote) "  ✓" else "",
            "This phone" + if (!remote) "  ✓" else "",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Show whose data?")
            .setItems(items) { _, i ->
                RemoteAlerts.setShowRemote(this, i == 0)
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The old main screen's own parts, which Your power doesn't show. */
    private fun hideOldMain() {
        if (!::home.isInitialized) return
        for (v in listOf(
            findViewById<View>(R.id.appVersion).parent as View,
            findViewById<View>(R.id.definition),
            hero.parent as View,
            findViewById<View>(R.id.roomCard),
            checkButton,
            confirmRow,
            findViewById<View>(R.id.checkHint),
            findViewById<View>(R.id.setupCard),
        )) v.visibility = View.GONE
    }

    /** MENU (top left of home): Always-on mode, the tools, rooms, all checks, then settings and help. */
    /** The menu, or its Tools page ([toolsPage]: Light meter and Amps calculator, with Back to menu). */
    private fun showTools(toolsPage: Boolean = false) {
        val sheet = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_day, null)
        // The app's name, smaller, with the version under it; or Tools with a way back.
        v.findViewById<TextView>(R.id.sheetTitle).apply {
            text = if (toolsPage) "Tools" else "Kahraba or Not?"
            textSize = 20f
        }
        v.findViewById<TextView>(R.id.sheetDate).apply {
            text = if (toolsPage) "‹ Back to menu" else "Version ${Updates.version(this@MainActivity)}"
            textSize = 13f
            if (toolsPage) setOnClickListener {
                sheet.dismiss()
                showTools()
            }
        }
        v.findViewById<View>(R.id.sheetNote).visibility = View.GONE
        val content = v.findViewById<LinearLayout>(R.id.sheetContent)
        fun item(icon: Int, title: String, sub: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                val ripple = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setOnClickListener {
                    sheet.dismiss()
                    action()
                }
            }
            row.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(themeColor(R.attr.appAccent))
                setPadding((9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt(), (9 * dp).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 12 * dp
                    setColor(themeColor(R.attr.appMuted) and 0x00FFFFFF or 0x1F000000)
                }
            }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(themeColor(R.attr.appText))
            })
            texts.addView(TextView(this).apply {
                text = sub
                textSize = 13f
                setTextColor(themeColor(R.attr.appMuted))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row)
        }
        if (toolsPage) {
            item(R.drawable.ic_chart, "Light meter", "Live light and wobble, recordings") {
                startActivity(Intent(this, LightMeterActivity::class.java))
            }
            item(R.drawable.ic_moteur, "Amps calculator", "What you can run on your moteur amps") {
                startActivity(Intent(this, AmpsActivity::class.java))
            }
            v.findViewById<View>(R.id.sheetDone).setOnClickListener { sheet.dismiss() }
            sheet.setContentView(v)
            sheet.show()
            return
        }
        item(R.drawable.ic_bolt, "Always-on mode", if (MonitorState.running) "Active" else "Inactive") { openAlwaysOn() }
        item(R.drawable.ic_chart, "Glance screen", "A dim screen with one big word, to read across the room") {
            startActivity(Intent(this, GlanceActivity::class.java))
        }
        item(R.drawable.ic_bolt, "Alerts on other phones",
            if (RemoteAlerts.getPairing(this) != null) "Getting alerts from your other phone" else "Get the alerts on another phone") {
            SettingsActivity.openRemote(this)
        }
        item(R.drawable.ic_tools, "Tools", "Light meter and Amps calculator") { showTools(toolsPage = true) }
        item(R.drawable.ic_pin, "Rooms", Prefs.currentSpot(this).ifEmpty { "Choose the room you're in" }) { pickRoom() }
        item(R.drawable.ic_list, "All checks", "Correct or delete a check") { showAllChecks() }
        // Settings and help after a thin line, set apart from the tools.
        content.addView(View(this).apply { setBackgroundColor(themeColor(R.attr.appBorder)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt().coerceAtLeast(1)).apply {
                topMargin = (8 * dp).toInt()
                bottomMargin = (8 * dp).toInt()
            })
        item(R.drawable.ic_settings, "Settings", "Sound, cards, backup and more") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        item(R.drawable.ic_update, "Check for updates", "Opens the download page") { Updates.open(this) }
        item(R.drawable.ic_help, "Help", "How it works, and tips") { startActivity(Intent(this, HelpActivity::class.java)) }
        if (Feedback.enabled) item(R.drawable.ic_feedback, "Send feedback", "Tell us what to improve") { Feedback.open(this) }
        v.findViewById<View>(R.id.sheetDone).setOnClickListener { sheet.dismiss() }
        sheet.setContentView(v)
        sheet.show()
    }

    /** Tools › Rooms: choose the room, then how to set it up if it isn't learned yet. */
    private fun pickRoom() {
        RoomPicker.pick(this, Prefs.currentSpot(this)) { room ->
            Prefs.setCurrentSpot(this, room)
            refreshAll()
            if (room.isEmpty()) return@pick
            val (k, m) = CheckHistory.spotLevels(this, room)
            if (k != null && m != null && k > m * QuickCheck.LEVEL_GAP) return@pick
            MaterialAlertDialogBuilder(this)
                .setTitle("Set up “$room”")
                .setMessage(
                    "So the app can say kahraba for sure in this room:\n\n" +
                        "1. On moteur, do a check here. If it doesn't say Moteur, tap Incorrect.\n" +
                        "2. On kahraba, do a check here, then tap Correct.\n\n" +
                        "Keep the phone in the same place each time, with daylight out of the way.",
                )
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    /**
     * While Your power slides, the bars blend between its background and home's colour, so they don't
     * jump at the end. [pageTy] is how far down the page is (0: fully open). The colour is all home's
     * once the page is 30% of the way down.
     */
    private fun blendBars(pageTy: Float) {
        if (!::home.isInitialized) return
        val f = (pageTy / (home.height * 0.3f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val bg = themeColor(R.attr.appBg)
        window.statusBarColor = androidx.core.graphics.ColorUtils.blendARGB(bg, homeTop, f)
        window.navigationBarColor = androidx.core.graphics.ColorUtils.blendARGB(bg, homeBottom, f)
        val light = f < 0.5f && !Prefs.isDark(this)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** The status and navigation bars take the home colour on the home screen. */
    private fun colorSystemBars() {
        val onHome = !powerBack.isEnabled
        val bg = themeColor(R.attr.appBg)
        window.statusBarColor = if (onHome) homeTop else bg
        window.navigationBarColor = if (onHome) homeBottom else bg
        val light = !onHome && !Prefs.isDark(this)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** The home screen: what's on now, from Always-on mode or a check in the last 30 minutes. */
    private fun renderHome() {
        if (!::home.isInitialized) return
        val now = System.currentTimeMillis()
        val recent = CheckHistory.read(this).firstOrNull { !it.fromMonitor }?.takeIf { now - it.timeMs < 30 * 60_000 }
        var source = Source.UNKNOWN
        var since = ""
        var noFlicker = false
        // Alerts from another phone running Always-on mode, when this one isn't.
        val remote = if (MonitorState.running) null else RemoteAlerts.current(this, now)
        if (MonitorState.running && MonitorState.source != Source.UNKNOWN) {
            source = MonitorState.source
            since = "since ${PowerDetector.formatClock(MonitorState.sinceMs)}"
        } else if (remote != null && remote.source != Source.UNKNOWN) {
            source = remote.source
            since = "since ${PowerDetector.formatClock(remote.sinceMs)}"
        } else if (recent != null) {
            when (recent.verdict) {
                QuickCheck.Verdict.KAHRABA -> source = Source.KAHRABA
                QuickCheck.Verdict.MOTEUR -> source = Source.MOTEUR
                QuickCheck.Verdict.NO_FLICKER -> noFlicker = true
                QuickCheck.Verdict.CANT_TELL -> {}
            }
            if (source != Source.UNKNOWN || noFlicker) since = "checked ${PowerDetector.formatClock(recent.timeMs)}"
        }
        homeSource = source

        findViewById<TextView>(R.id.homeWord).text = when (source) {
            Source.KAHRABA -> "Kahraba"
            Source.MOTEUR -> "Moteur"
            Source.NO_POWER -> "Power off"
            Source.POWER_ON -> "Power on"
            Source.UNKNOWN -> if (noFlicker) "Not sure" else "Kahraba or not?"
        }
        findViewById<TextView>(R.id.homeSince).text = since.ifEmpty { "Tap the button to check" }

        val pattern = KahrabaPattern.summary(EventLog.shown(this), now, EventLog.shownLive(this))
        val nextKahraba = pattern.nextFromMs?.let { from ->
            fun around(ms: Long) = PowerDetector.formatClock((ms + 150_000) / 300_000 * 300_000)
            val to = pattern.nextToMs ?: from
            if (from == to) "Next kahraba: around ${around(from)}" else "Next kahraba: ${around(from)} to ${around(to)}"
        }
        findViewById<TextView>(R.id.homeNext).text = when (source) {
            Source.KAHRABA -> if (pattern.typicalMs != null && pattern.onSinceMs != null) {
                "Usually lasts about ${DayData.hours(pattern.typicalMs)}"
            } else "Good time for the big appliances"
            Source.MOTEUR -> nextKahraba ?: "Keep it light: stay under your amps"
            Source.NO_POWER -> "Kahraba and moteur are both off"
            Source.POWER_ON -> ""
            Source.UNKNOWN -> if (noFlicker) "No wobble: kahraba, or a calm moteur" else nextKahraba ?: ""
        }
        findViewById<View>(R.id.homeNext).visibility =
            if (findViewById<TextView>(R.id.homeNext).text.isNullOrEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.homeChip).text = when {
            MonitorState.running -> "Always-on: active"
            RemoteAlerts.getPairing(this) != null -> RemoteAlerts.heard(this)?.let {
                if (now - it.heardMs < RemoteAlerts.SILENT_MS && it.kind != RemoteCodec.Kind.STOPPED) {
                    "From ${it.device} · ${RemoteCodec.ago(now - it.heardMs)}"
                } else "No news from ${it.device}"
            } ?: "Waiting for your other phone"
            else -> "Always-on: inactive"
        }
        findViewById<TextView>(R.id.homeRoom).apply {
            val room = Prefs.currentSpot(this@MainActivity)
            text = room.ifEmpty { "Choose room" } + "  ▾"
            contentDescription = if (room.isEmpty()) "Choose the room you're in" else "Room: $room. Tap to change."
        }

        val color = if (source == Source.UNKNOWN) ContextCompat.getColor(this, R.color.idle) else MonitorService.colorFor(source)
        if (color != homeColor) {
            homeColor = color
            home.background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(lighten(color, 0.22f), color, darken(color, 0.38f))
                setGradientCenter(0.5f, 0.42f)
                gradientRadius = resources.displayMetrics.heightPixels * 0.75f
            }
            homeTop = darken(color, 0.08f)
            homeBottom = darken(color, 0.3f)
            colorSystemBars()
        }
        findViewById<ImageView>(R.id.homeButtonIcon).apply {
            setImageResource(if (source == Source.MOTEUR) R.drawable.ic_moteur else R.drawable.ic_bolt)
            setColorFilter(color)
        }
        findViewById<TextView>(R.id.homeButtonCap).setTextColor(color)
        homeButton.alpha = if (source == Source.NO_POWER && check == null) 0.45f else 1f
        if (check == null) {
            findViewById<TextView>(R.id.homeButtonCap).text = if (source == Source.NO_POWER) "OFF" else "CHECK"
            findViewById<TextView>(R.id.homeHint).text = if (source == Source.NO_POWER) {
                "Nothing to check until the power's back"
            } else "Tap to check · hold for Always-on mode"
        }

        // A soft ring around the button while Always-on mode is active.
        val ring = findViewById<View>(R.id.homeRing)
        if (MonitorState.running) {
            ring.visibility = View.VISIBLE
            if (ringPulse == null) {
                ringPulse = android.animation.ObjectAnimator.ofFloat(ring, View.ALPHA, 1f, 0.25f).apply {
                    duration = 1400
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            ringPulse?.cancel()
            ringPulse = null
            ring.visibility = View.INVISIBLE
        }
    }

    /** The button pulses gently while a check runs. */
    private fun homeChecking(on: Boolean) {
        if (!::home.isInitialized) return
        homePulse?.cancel()
        homeButton.scaleX = 1f
        homeButton.scaleY = 1f
        homePulse = null
        // While checking: the live reading and graph take the place of the chip and next-kahraba line.
        findViewById<View>(R.id.homeChip).visibility = if (on) View.GONE else View.VISIBLE
        findViewById<View>(R.id.homeLive).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homeChartBox).visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            findViewById<View>(R.id.homeNext).visibility = View.GONE
            findViewById<TextView>(R.id.homeLive).text = lux?.let { "${thousands(it)} lux" } ?: "Waiting for the light sensor…"
            homeChart.setData(chartT, chartV, 0)
            findViewById<TextView>(R.id.homeButtonCap).text = "CHECKING"
            homePulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(
                homeButton,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f),
            ).apply {
                duration = 800
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        } else {
            renderHome()
        }
    }

    /** 3412.6 -> "3,413". */
    private fun thousands(v: Double) = "%,d".format(Locale.US, Math.round(v))

    /** A check's result, full screen in its colour, with Correct / Incorrect. */
    private fun showHomeResult(r: QuickCheck.Result, wobbles: Int, maxSwing: Double) {
        val color = ContextCompat.getColor(this, colorFor(r.verdict))
        // The numbers behind the result: light level, wobbles, and the graph of the whole check.
        val lux = r.lux
        findViewById<View>(R.id.resultStats).visibility = if (lux == null) View.GONE else View.VISIBLE
        if (lux != null) {
            findViewById<TextView>(R.id.resultLight).text = thousands(lux)
            findViewById<TextView>(R.id.resultLightSub).text = "lux"
            val (w, wSub) = when {
                r.verdict == QuickCheck.Verdict.CANT_TELL -> "–" to "couldn't judge"
                wobbles == 0 -> "None" to "steady light"
                else -> (if (wobbles == 1) "1 time" else "$wobbles times") to "up to ${"%.0f".format(maxSwing)}%"
            }
            findViewById<TextView>(R.id.resultWobble).text = w
            findViewById<TextView>(R.id.resultWobbleSub).text = wSub
        }
        val hasChart = chartN > 1
        findViewById<View>(R.id.resultChartBox).visibility = if (hasChart) View.VISIBLE else View.GONE
        if (hasChart) {
            resultChart.windowSeconds = ((chartT[chartN - 1] - chartT[0] + 999) / 1000).toInt().coerceIn(10, 60)
            resultChart.setData(chartT, chartV, chartN)
        }
        val box = findViewById<View>(R.id.homeResult)
        box.setBackgroundColor(color)
        findViewById<TextView>(R.id.resultWord).text = when (r.verdict) {
            QuickCheck.Verdict.KAHRABA -> "Kahraba"
            QuickCheck.Verdict.MOTEUR -> "Moteur"
            QuickCheck.Verdict.NO_FLICKER -> "No flicker"
            QuickCheck.Verdict.CANT_TELL -> "Can't tell"
        }
        findViewById<TextView>(R.id.resultAdvice).text = when (r.verdict) {
            QuickCheck.Verdict.KAHRABA -> "Good time for the washing machine, water heater, oven or iron."
            QuickCheck.Verdict.MOTEUR -> "Keep it light. Stay under your amps: avoid the heater, kettle and washing machine at the same time."
            QuickCheck.Verdict.NO_FLICKER -> "Kahraba, or a calm moteur. Confirm before running several big appliances."
            QuickCheck.Verdict.CANT_TELL -> "Try again with the phone face-up under a halogen light."
        }
        findViewById<TextView>(R.id.resultDetail).text = r.detail
        val yes = findViewById<TextView>(R.id.resultYes)
        val no = findViewById<TextView>(R.id.resultNo)
        val canConfirm = r.verdict != QuickCheck.Verdict.CANT_TELL
        findViewById<View>(R.id.resultPrompt).visibility = if (canConfirm) View.VISIBLE else View.GONE
        findViewById<View>(R.id.resultButtons).visibility = if (canConfirm) View.VISIBLE else View.GONE
        yes.setTextColor(color)
        // Each button: its text and what it says the source really was.
        val choices: Pair<Pair<String, QuickCheck.Verdict>, Pair<String, QuickCheck.Verdict>> = when (r.verdict) {
            QuickCheck.Verdict.KAHRABA -> ("Correct" to QuickCheck.Verdict.KAHRABA) to ("Incorrect" to QuickCheck.Verdict.MOTEUR)
            QuickCheck.Verdict.MOTEUR -> ("Correct" to QuickCheck.Verdict.MOTEUR) to ("Incorrect" to QuickCheck.Verdict.KAHRABA)
            else -> ("It was kahraba" to QuickCheck.Verdict.KAHRABA) to ("It was moteur" to QuickCheck.Verdict.MOTEUR)
        }
        yes.text = choices.first.first
        no.text = choices.second.first
        yes.setOnClickListener { answer(choices.first.second) }
        no.setOnClickListener { answer(choices.second.second) }
        box.alpha = 0f
        box.visibility = View.VISIBLE
        box.animate().alpha(1f).setDuration(220).start()
        resultBack.isEnabled = true
        window.statusBarColor = color
        window.navigationBarColor = color
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun answer(v: QuickCheck.Verdict) {
        confirmLatest(v)
        hideResult()
        toast("Saved. Thanks, that helps the app learn this room.")
    }

    private fun hideResult() {
        resultBack.isEnabled = false
        findViewById<View>(R.id.homeResult).visibility = View.GONE
        colorSystemBars()
        renderHome()
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
        renderPattern()
        renderWobble()
        renderFact()
        applyCardSettings()
        renderShowing()
        renderSetup()
        renderAccuracy()
        renderUpdate()
        renderHome()
        // The definition and the "lay the phone…" hint are for the first few checks only.
        val firstChecks = CheckHistory.read(this).count { !it.fromMonitor } < INTRO_CHECKS
        findViewById<View>(R.id.definition).visibility = if (firstChecks) View.VISIBLE else View.GONE
        findViewById<View>(R.id.checkHint).visibility = if (firstChecks) View.VISIBLE else View.GONE
        // After layout, so the NOW card's position is known.
        findViewById<View>(R.id.scroll).post { renderBarStatus(animate = false) }
        hideOldMain()
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
        homeChecking(true)
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
            if (checkFromHome) {
                findViewById<TextView>(R.id.homeLive).text =
                    (lux?.let { "${thousands(it)} lux" } ?: "– lux") + if (steady > 0) " · steady ${steady}s of ${needS}s" else ""
            }
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
        val fromHome = checkFromHome && !powerBack.isEnabled
        stopCheck(null)
        beeper.cancel()
        showHero(R.color.idle, R.drawable.ic_bolt, "Check stopped", "The phone was moved", "Place it down and try again")
        if (fromHome) toast("Check stopped: the phone was moved. Place it down and try again.")
    }

    private fun stopCheck(result: QuickCheck.Result?) {
        ui.removeCallbacks(sampler)
        motion.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        val wobbles = check?.wobbles ?: 0
        val maxSwing = check?.maxSwing ?: 0.0
        check = null
        checkButton.text = "Check now"
        heroProgress.visibility = View.GONE
        val fromHome = checkFromHome
        checkFromHome = false
        homeChecking(false)
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
        hideOldMain()
        if (fromHome && !powerBack.isEnabled) showHomeResult(result, wobbles, maxSwing)
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
        if (::homeChart.isInitialized) homeChart.setData(chartT, chartV, chartN)
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
        if (check != null && ::home.isInitialized) {
            findViewById<TextView>(R.id.homeHint).text = if (title.isNotEmpty() && title.all { it.isDigit() }) {
                "Put the phone down, face-up · $title"
            } else "Checking the light… keep the phone still. Tap to cancel."
        }
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
            if (kahraba.isEmpty()) "No always-on data yet. Turn on Always-on mode to record kahraba hours."
            else "Hours of kahraba recorded by Always-on mode."
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
            content.addView(sectionLabel("Always-on mode" + (kahrabaMs?.let { " · ${DayData.hours(it)} kahraba" } ?: "")))
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
        val fmt = SimpleDateFormat("EEE d MMM  h:mm a", Locale.US)
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

    // ---- Kahraba pattern ----

    /**
     * KAHRABA PATTERN: the last 7 days as bars (when kahraba was on, from Always-on mode's log),
     * how long it usually lasts, and when it may come next. An estimate, not a schedule.
     */
    private fun renderPattern() {
        val now = System.currentTimeMillis()
        val entries = EventLog.shown(this)
        val running = EventLog.shownLive(this)
        val s = KahrabaPattern.summary(entries, now, running)
        val title = findViewById<TextView>(R.id.patternTitle)
        val sub = findViewById<TextView>(R.id.patternSub)
        fun around(ms: Long) = PowerDetector.formatClock((ms + 150_000) / 300_000 * 300_000)
        val lasts = s.typicalMs?.let { "about ${DayData.hours(it)}" }
        val every = s.gapMs?.let { "about every ${DayData.hours(it)}" }
        when {
            s.onSinceMs != null -> {
                title.text = "Kahraba now, since ${PowerDetector.formatClock(s.onSinceMs)}"
                sub.text = if (s.typicalMs != null) {
                    "It usually lasts $lasts, so until about ${around(s.onSinceMs + s.typicalMs)}."
                } else ""
            }
            s.nextFromMs != null && s.nextToMs != null -> {
                title.text = if (s.nextFromMs == s.nextToMs) "Next kahraba: around ${around(s.nextFromMs)}"
                else "Next kahraba: between ${around(s.nextFromMs)} and ${around(s.nextToMs)}"
                sub.text = "It comes back $every" + (lasts?.let { " and lasts $it." } ?: ".")
            }
            else -> {
                title.text = if (lasts != null) "Kahraba usually lasts $lasts" else "Learning when kahraba comes"
                sub.text = when {
                    every != null -> "It comes back $every. Not sure when it comes next."
                    else -> "Keep Always-on mode running to learn when it comes back " +
                        "(${minOf(s.gaps, KahrabaPattern.MIN_SAMPLES)} of ${KahrabaPattern.MIN_SAMPLES} so far)."
                }
            }
        }
        sub.visibility = if (sub.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Midnight of today and the 6 days before, today first.
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = DayData.startOfDay(now) }
        val dayStarts = (0 until 7).map { i ->
            if (i > 0) cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
            cal.timeInMillis
        }

        // Today and the week in plain words.
        fun para(label: String, text: String) = "<b>$label</b> " + android.text.TextUtils.htmlEncode(text)
        findViewById<TextView>(R.id.patternOverview).text = android.text.Html.fromHtml(
            para("Today:", KahrabaPattern.today(entries, now, running, dayStarts[0])) + "<br><br>" +
                para("Last 7 days:", KahrabaPattern.week(entries, now, running, dayStarts)),
            android.text.Html.FROM_HTML_MODE_LEGACY,
        )

        // One bar per day, oldest first: kahraba, moteur and power off over 24 hours.
        val spans = KahrabaPattern.spans(entries, now, running)
        val days = findViewById<LinearLayout>(R.id.patternDays)
        days.removeAllViews()
        val dayName = SimpleDateFormat("EEE", Locale.US)
        // Oldest at the top, today at the bottom (Mon, Tue … Today).
        for (i in dayStarts.indices.reversed()) {
            val dayStart = dayStarts[i]
            val dayEnd = if (i == 0) java.util.Calendar.getInstance().apply {
                timeInMillis = dayStart; add(java.util.Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis else dayStarts[i - 1]
            days.addView(patternRow(if (i == 0) "Today" else dayName.format(Date(dayStart)), dayStart, dayEnd, spans))
        }
        renderPatternLegend()
        findViewById<TextView>(R.id.patternNote).text = "An estimate from what the app saw, not an official schedule."
    }

    /** The bars' background: where Always-on mode wasn't running. */
    private fun patternTrackColor() = themeColor(R.attr.appMuted) and 0x00FFFFFF or 0x26000000

    /** Colour swatches for the bars, two per line. */
    private fun renderPatternLegend() {
        val legend = findViewById<LinearLayout>(R.id.patternLegend)
        if (legend.childCount > 0) return
        val items = listOf(
            MonitorService.colorFor(Source.KAHRABA) to "Kahraba",
            MonitorService.colorFor(Source.MOTEUR) to "Moteur",
            MonitorService.colorFor(Source.NO_POWER) to "Power off",
            patternTrackColor() to "Not running",
        )
        for (pair in items.chunked(2)) {
            val line = LinearLayout(this).apply { setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt()) }
            for ((color, label) in pair) {
                val item = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                item.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = 4 * dp
                        setColor(color)
                    }
                }, LinearLayout.LayoutParams((16 * dp).toInt(), (16 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
                item.addView(TextView(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(themeColor(R.attr.appText))
                })
                line.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            legend.addView(line)
        }
    }

    private fun patternRow(label: String, dayStart: Long, dayEnd: Long, spans: List<KahrabaPattern.Span>): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
            // Tap a day for when kahraba was on, and its timeline (1.5.04).
            val ripple = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { showPatternDay(dayStart, dayEnd) }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(themeColor(R.attr.appMuted))
        }, LinearLayout.LayoutParams((48 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        val bar = LinearLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 7 * dp
                setColor(patternTrackColor())
            }
            clipToOutline = true
        }
        // Pieces of the day in order; null where nothing was recorded.
        var t = dayStart
        fun piece(to: Long, color: Int?) {
            if (to <= t) return
            bar.addView(View(this).apply { if (color != null) setBackgroundColor(color) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (to - t).toFloat()))
            t = to
        }
        for (sp in spans.sortedBy { it.start }) {
            if (sp.end <= dayStart || sp.start >= dayEnd) continue
            piece(maxOf(sp.start, dayStart), null)
            piece(minOf(sp.end, dayEnd), MonitorService.colorFor(sp.source))
        }
        piece(dayEnd, null)
        row.addView(bar, LinearLayout.LayoutParams(0, (14 * dp).toInt(), 1f))
        return row
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
        val fmt = SimpleDateFormat("EEE  h:mm a", Locale.US)
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
        val whenText = SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(e.timeMs))
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
            barLabel.text = "Always-on" + if (room.isNotEmpty()) " · $room" else ""
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
        "card_advice" to R.id.adviceCard, "card_history" to R.id.historyCard,
        "card_pattern" to R.id.patternCard,
        "card_recent" to R.id.recentCard, "card_accuracy" to R.id.accuracyCard, "card_tools" to R.id.toolsCard,
        "card_facts" to R.id.factsCard, "card_wobble" to R.id.wobbleCard,
    )

    private fun applyCardSettings() {
        val ids = cardIds
        for ((key, id) in ids) findViewById<View>(id).visibility = if (Prefs.cardOn(this, key)) View.VISIBLE else View.GONE
        // History stays hidden until there is something to show: a check, or the monitor's log.
        val hasHistory = CheckHistory.read(this).any { !it.fromMonitor } || EventLog.shown(this).isNotEmpty()
        if (!hasHistory) findViewById<View>(R.id.historyCard).visibility = View.GONE
        // Kahraba pattern: only once Always-on mode has recorded something.
        if (EventLog.shown(this).isEmpty()) findViewById<View>(R.id.patternCard).visibility = View.GONE

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

        val lastSwitch = EventLog.shown(this).lastOrNull { it.source == Source.KAHRABA || it.source == Source.MOTEUR }
        findViewById<TextView>(R.id.timelineNote).text = when {
            lastSwitch != null && EventLog.shownLive(this) ->
                "${EventLog.label(lastSwitch.source).lowercase().replaceFirstChar { it.uppercase() }} since ${PowerDetector.formatClock(lastSwitch.timeMs)}"
            anyData -> "Minutes are kahraba time recorded by Always-on mode; other hours show your checks."
            else -> "No data yet. Do a check, or turn on Always-on mode."
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
        // Oldest at the top, today at the bottom.
        for (d in 6 downTo 0) {
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
            "Bars show the share of each day on kahraba, from Always-on mode."
        } else {
            "Bars show how many checks were kahraba (or no flicker). Always-on mode gives exact hours."
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
            val events = EventLog.shown(this).filter { it.timeMs >= dayStart && (it.source == Source.KAHRABA || it.source == Source.MOTEUR) }
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
            } else "From Always-on mode."
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
            note.text = "Turn on Always-on mode for exact kahraba hours and switch times."
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
