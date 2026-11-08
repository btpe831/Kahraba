package com.kahraba.power

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.random.Random

/**
 * Glance screen (1.5.0): for a phone left on all day, readable across the room without wearing out
 * the screen. Black, with "KAHRABA" and "since 5:00 PM" either on a coloured card (white words, the
 * default since 1.5.02) or as coloured words. It drifts to a new spot every minute so no pixel stays
 * lit in the same place (OLED screens can keep a ghost image), and stays below the top edge, where the
 * light sensor is. Shows Always-on mode on this phone, or the other phone's news if this one gets
 * alerts. Style and brightness: Settings › Always-on mode, or the two buttons shown for a few seconds.
 * Tap anywhere else to close.
 */
class GlanceActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var word: TextView
    private lateinit var since: TextView
    private lateinit var note: TextView
    private lateinit var controls: LinearLayout
    private lateinit var styleButton: TextView
    private lateinit var brightButton: TextView
    private var lastMoveMs = 0L
    private val dp get() = resources.displayMetrics.density

    private val tick = object : Runnable {
        override fun run() {
            render()
            val now = System.currentTimeMillis()
            if (now - lastMoveMs >= MOVE_MS) {
                lastMoveMs = now
                drift()
            }
            ui.postDelayed(this, 1000)
        }
    }
    private val hideControls = Runnable { controls.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyBrightness()
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener { finish() }
        }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        word = TextView(this).apply {
            textSize = 56f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
        }
        since = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
        }
        note = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
        }
        box.addView(word)
        box.addView(since, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (4 * dp).toInt() })
        box.addView(note, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * dp).toInt() })
        root.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // For the first few seconds: style and brightness, and how to leave.
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        styleButton = pill { cycleStyle() }
        brightButton = pill { cycleBrightness() }
        row.addView(styleButton)
        row.addView(brightButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (10 * dp).toInt() })
        controls.addView(row)
        controls.addView(TextView(this).apply {
            text = "Tap anywhere else to close"
            textSize = 13f
            setTextColor(DARK_GREY)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (10 * dp).toInt() })
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (28 * dp).toInt()
        })
        labelButtons()
        setContentView(root)
        showControls()
    }

    override fun onResume() {
        super.onResume()
        lastMoveMs = System.currentTimeMillis()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private fun pill(onTap: () -> Unit) = TextView(this).apply {
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(GREY)
        setPadding((16 * dp).toInt(), (9 * dp).toInt(), (16 * dp).toInt(), (9 * dp).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setStroke((1 * dp).toInt(), DARK_GREY)
        }
        setOnClickListener {
            onTap()
            showControls()
        }
    }

    private fun showControls() {
        controls.visibility = View.VISIBLE
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 6000)
    }

    private fun labelButtons() {
        styleButton.text = if (Prefs.glanceStyle(this) == "word") "Style: Word" else "Style: Card"
        brightButton.text = "☀ " + when (Prefs.glanceBright(this)) { "low" -> "Low"; "full" -> "Full"; else -> "Medium" }
    }

    private fun cycleStyle() {
        val next = if (Prefs.glanceStyle(this) == "word") "card" else "word"
        Prefs.sp(this).edit().putString(Prefs.GLANCE_STYLE, next).apply()
        labelButtons()
        render()
    }

    private fun cycleBrightness() {
        val next = when (Prefs.glanceBright(this)) { "low" -> "medium"; "medium" -> "full"; else -> "low" }
        Prefs.sp(this).edit().putString(Prefs.GLANCE_BRIGHT, next).apply()
        labelButtons()
        applyBrightness()
    }

    private fun applyBrightness() {
        window.attributes = window.attributes.apply { screenBrightness = brightness(Prefs.glanceBright(this@GlanceActivity)) }
    }

    private fun render() {
        val remote = if (MonitorState.running) null else RemoteAlerts.current(this)
        val (source, sinceMs, from) = when {
            MonitorState.running -> Triple(MonitorState.source, MonitorState.sinceMs, "")
            remote != null -> Triple(remote.source, remote.sinceMs, "From ${remote.device}")
            else -> Triple(Source.UNKNOWN, 0L, "")
        }
        word.text = when (source) {
            Source.KAHRABA -> "KAHRABA"
            Source.MOTEUR -> "MOTEUR"
            Source.NO_POWER -> "POWER OFF"
            Source.POWER_ON -> "POWER ON"
            Source.UNKNOWN -> if (MonitorState.running || remote != null) "NOT SURE" else "OFF"
        }
        since.text = when {
            source == Source.UNKNOWN && !MonitorState.running && remote == null -> "Always-on mode isn't running"
            source == Source.UNKNOWN -> "Waiting for a wobble or a power cut"
            else -> "since ${PowerDetector.formatClock(sinceMs)}"
        }
        note.text = from
        note.visibility = if (from.isEmpty()) View.GONE else View.VISIBLE

        if (Prefs.glanceStyle(this) == "word") {
            box.background = null
            box.setPadding(0, 0, 0, 0)
            word.setTextColor(colorFor(source))
            since.setTextColor(GREY)
            note.setTextColor(DARK_GREY)
        } else {
            // The app's own colours (green, orange, grey) behind white words.
            box.background = GradientDrawable().apply {
                cornerRadius = 28 * dp
                setColor(MonitorService.colorFor(source))
            }
            box.setPadding((28 * dp).toInt(), (26 * dp).toInt(), (28 * dp).toInt(), (26 * dp).toInt())
            word.setTextColor(Color.WHITE)
            since.setTextColor(0xE6FFFFFF.toInt())
            note.setTextColor(0xCCFFFFFF.toInt())
        }
    }

    /**
     * Moves the words to a new spot, so the same pixels don't stay lit. Sideways anywhere, but only
     * a little above the middle: the light sensor is at the top edge.
     */
    private fun drift() {
        val root = box.parent as View
        val maxX = ((root.width - box.width) / 2f * 0.8f).coerceAtLeast(0f)
        val maxY = ((root.height - box.height) / 2f * 0.6f).coerceAtLeast(0f)
        box.animate()
            .translationX(Random.nextFloat() * 2 * maxX - maxX)
            .translationY(Random.nextFloat() * maxY * 1.2f - maxY * 0.2f)
            .setDuration(1500)
            .start()
    }

    companion object {
        private const val MOVE_MS = 60_000L
        private const val GREY = 0xFF9CA3AF.toInt()
        private const val DARK_GREY = 0xFF6B7280.toInt()

        /** Low is dim (the 1.5.0 level), Medium about a third, Full the phone's maximum. */
        fun brightness(level: String): Float = when (level) {
            "low" -> 0.08f
            "full" -> 1f
            else -> 0.35f
        }

        /** Lighter than the app's usual colours, so coloured words read well on black. */
        fun colorFor(s: Source): Int = when (s) {
            Source.KAHRABA -> 0xFF34D399.toInt()
            Source.MOTEUR -> 0xFFFBBF24.toInt()
            Source.NO_POWER -> 0xFF94A3B8.toInt()
            Source.POWER_ON -> 0xFF60A5FA.toInt()
            Source.UNKNOWN -> 0xFF94A3B8.toInt()
        }
    }
}
