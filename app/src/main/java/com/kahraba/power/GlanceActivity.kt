package com.kahraba.power

import android.graphics.Color
import android.graphics.Typeface
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
 * the screen. Black, dim, one big coloured word ("KAHRABA") and "since 5:00 PM", drifting a little
 * every minute so no pixel stays lit in the same place (OLED screens can keep a ghost image).
 * Shows Always-on mode on this phone, or the other phone's news if this one gets alerts. Tap to close.
 */
class GlanceActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var box: LinearLayout
    private lateinit var word: TextView
    private lateinit var since: TextView
    private lateinit var note: TextView
    private var lastMoveMs = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = BRIGHTNESS }
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
        val dp = resources.displayMetrics.density
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        word = TextView(this).apply {
            textSize = 60f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
        }
        since = TextView(this).apply {
            textSize = 24f
            setTextColor(GREY)
            gravity = Gravity.CENTER
        }
        note = TextView(this).apply {
            textSize = 15f
            setTextColor(DARK_GREY)
            gravity = Gravity.CENTER
        }
        box.addView(word)
        box.addView(since, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt() })
        box.addView(note, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * dp).toInt() })
        root.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // How to leave, for the first few seconds only.
        val hint = TextView(this).apply {
            text = "Tap anywhere to close"
            textSize = 13f
            setTextColor(DARK_GREY)
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (28 * dp).toInt()
        })
        ui.postDelayed({ hint.visibility = View.GONE }, 6000)
        setContentView(root)
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
        word.setTextColor(colorFor(source))
        since.text = when {
            source == Source.UNKNOWN && !MonitorState.running && remote == null -> "Always-on mode isn't running"
            source == Source.UNKNOWN -> "Waiting for a wobble or a power cut"
            else -> "since ${PowerDetector.formatClock(sinceMs)}"
        }
        note.text = from
        note.visibility = if (from.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Moves the words to a new spot near the middle, so the same pixels don't stay lit. */
    private fun drift() {
        val root = box.parent as View
        val maxX = ((root.width - box.width) / 2f * 0.8f).coerceAtLeast(0f)
        val maxY = ((root.height - box.height) / 2f * 0.6f).coerceAtLeast(0f)
        box.animate()
            .translationX(Random.nextFloat() * 2 * maxX - maxX)
            .translationY(Random.nextFloat() * 2 * maxY - maxY)
            .setDuration(1500)
            .start()
    }

    companion object {
        /** Dim: plenty to read in a room, easy on the screen and on the light sensor next to it. */
        private const val BRIGHTNESS = 0.08f
        private const val MOVE_MS = 60_000L
        private const val GREY = 0xFF9CA3AF.toInt()
        private const val DARK_GREY = 0xFF6B7280.toInt()

        /** Lighter than the app's usual colours, so they read well on black. */
        fun colorFor(s: Source): Int = when (s) {
            Source.KAHRABA -> 0xFF34D399.toInt()
            Source.MOTEUR -> 0xFFFBBF24.toInt()
            Source.NO_POWER -> 0xFF94A3B8.toInt()
            Source.POWER_ON -> 0xFF60A5FA.toInt()
            Source.UNKNOWN -> 0xFF94A3B8.toInt()
        }
    }
}
