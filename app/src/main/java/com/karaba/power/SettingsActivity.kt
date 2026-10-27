package com.karaba.power

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Settings, in the same card style as the main screen. Built in code to keep it in one place. */
class SettingsActivity : ThemedActivity() {

    private val dp get() = resources.displayMetrics.density
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val scroll = ScrollView(this).apply { setBackgroundColor(color(R.attr.appBg)) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(40))
        }
        scroll.addView(body)
        setContentView(scroll)
        build()
    }

    private fun build() {
        body.removeAllViews()

        // Header: title and Close.
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Settings", 32f, R.attr.appText, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Close"
            cornerRadius = px(24)
            strokeColor = android.content.res.ColorStateList.valueOf(color(R.attr.appBorder))
            setTextColor(color(R.attr.appText))
            setOnClickListener { finish() }
        })
        body.addView(header)

        // THEME
        section("Theme")
        val current = Prefs.colorTheme(this)
        for (t in Themes.all) body.addView(themeRow(t, t.key == current), lp(top = 10))
        body.addView(text(
            "Your theme colours the whole app. Green always means kahraba and amber always means moteur, in every theme.",
            14f, R.attr.appMuted,
        ).apply { setPadding(px(4), px(14), px(4), 0) })

        // SOUND
        section("Sound")
        body.addView(card {
            addView(text("Beep volume", 18f, R.attr.appText, bold = true))
            val now = Prefs.sp(this@SettingsActivity).getString(Prefs.BEEP_VOLUME, "full") ?: "full"
            addView(segmented(listOf("Low" to "low", "Medium" to "medium", "Full" to "full"), now) {
                Prefs.sp(this@SettingsActivity).edit().putString(Prefs.BEEP_VOLUME, it).apply()
            }, lp(top = 12))
            addView(text("Beeps play on the alarm channel, so they sound even when the phone is on silent.", 14f, R.attr.appMuted), lp(top = 10))
        })

        // QUICK CHECK
        section("Quick check")
        body.addView(card {
            addView(toggle("Check when the app opens", "Start a check straight after opening, so you only need to put the phone down.",
                Prefs.AUTO_CHECK, false))
        })

        // ROOMS
        section("Rooms")
        body.addView(card {
            addView(link("Manage rooms", "Add, rename or delete rooms (e.g. Kitchen, Bathroom).") {
                RoomPicker.pick(this@SettingsActivity, Prefs.currentSpot(this@SettingsActivity)) { room ->
                    Prefs.setCurrentSpot(this@SettingsActivity, room)
                }
            })
        })

        // MONITOR
        section("Always-on monitor")
        body.addView(card {
            addView(toggle("No beeps at night", "Switches are still logged and shown, just without sound.", Prefs.QUIET_ENABLED, false))
            addView(divider())
            addView(timeRow("Quiet from", Prefs.QUIET_START, "23:00"))
            addView(divider())
            addView(timeRow("Quiet until", Prefs.QUIET_END, "07:00"))
        })
        body.addView(card {
            addView(text("Advanced", 18f, R.attr.appText, bold = true))
            addView(numberRow("Flicker limit", "%", Prefs.FLICKER_THRESHOLD, Prefs.DEFAULT_THRESHOLD,
                "Light wobble above this counts as moteur. Set automatically when you teach the monitor."))
            addView(divider())
            addView(numberRow("Minutes before calling kahraba", "min", Prefs.STEADY_MINUTES, 2.0,
                "How long the light must stay brighter and steady."))
            addView(divider())
            addView(numberRow("Minimum light", "lux", Prefs.MIN_LUX, 100.0, "Below this the light is too dim to judge."))
            addView(divider())
            addView(toggle("Moteur from flicker alone", "Switch to moteur when the light flickers, even without a power cut. " +
                "Off by default because kahraba can wobble too.", Prefs.MOTEUR_ON_FLICKER, false))
        }, lp(top = 12))

        // TOOLS
        section("Tools")
        body.addView(card {
            addView(link("Light meter", "Live lux, a chart, and recordings you can save as CSV.") {
                startActivity(Intent(this@SettingsActivity, LightMeterActivity::class.java))
            })
        })

        // ABOUT
        section("About")
        body.addView(card {
            addView(link("How to use Ijit", "Which bulbs work, distance, rooms, solar and UPS, and the disclaimer.") {
                startActivity(Intent(this@SettingsActivity, HelpActivity::class.java))
            })
            addView(divider())
            addView(text("Privacy", 17f, R.attr.appText, bold = true), lp(top = 12))
            addView(text("Everything stays on this phone. Ijit has no internet access; data only leaves if you tap Share.",
                14f, R.attr.appMuted), lp(top = 4))
            val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
            addView(text("Version $version", 14f, R.attr.appMuted), lp(top = 14))
        })
    }

    // ---- Theme rows ----

    private fun themeRow(t: Themes.Theme, selected: Boolean): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(16), px(18), px(16))
            background = rounded(color(if (selected) R.attr.appSurfaceAlt else R.attr.appSurface),
                color(if (selected) R.attr.appAccent else R.attr.appBorder), 22f)
            setOnClickListener {
                if (!selected) {
                    Prefs.setColorTheme(this@SettingsActivity, t.key)
                    AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this@SettingsActivity))
                    recreate()
                }
            }
        }
        // Swatch: the theme's background with its accent dot.
        val swatch = FrameLayout(this).apply { background = rounded(t.bg, 0x33FFFFFF, 14f) }
        swatch.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.dot) }
        }, FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER))
        row.addView(swatch, LinearLayout.LayoutParams(px(54), px(54)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(t.name, 20f, R.attr.appText, bold = true))
        texts.addView(text(t.description, 15f, R.attr.appMuted))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(16) })

        // Radio: an empty ring, or a filled accent circle with a tick.
        val radio = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            if (selected) {
                text = "✓"
                setTextColor(color(R.attr.appOnAccent))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.attr.appAccent)) }
            } else {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(px(2), color(R.attr.appMuted)) }
            }
        }
        row.addView(radio, LinearLayout.LayoutParams(px(28), px(28)))
        return row
    }

    // ---- Building blocks ----

    private fun section(title: String) {
        body.addView(text(title.uppercase(), 15f, R.attr.appAccent, bold = true).apply {
            letterSpacing = 0.14f
            setPadding(px(4), px(30), 0, px(4))
        })
    }

    private fun card(fill: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(18), px(20), px(18))
        background = rounded(color(R.attr.appSurface), color(R.attr.appBorder), 22f)
        layoutParams = lp(top = 10)
        fill()
    }

    /** Pill-shaped choice, like "Low | Medium | Full". */
    private fun segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val bar = LinearLayout(this).apply {
            setPadding(px(4), px(4), px(4), px(4))
            background = rounded(color(R.attr.appSurfaceAlt), color(R.attr.appBorder), 28f)
        }
        fun paint() {
            val chosen = bar.tag as String
            for (i in 0 until bar.childCount) {
                val tv = bar.getChildAt(i) as TextView
                val on = options[i].second == chosen
                tv.setTextColor(color(if (on) R.attr.appOnAccent else R.attr.appMuted))
                tv.background = if (on) rounded(color(R.attr.appAccent), color(R.attr.appAccent), 24f) else null
            }
        }
        bar.tag = selected
        for ((label, value) in options) {
            bar.addView(text(label, 17f, R.attr.appText, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(12), 0, px(12))
                setOnClickListener {
                    bar.tag = value
                    paint()
                    onPick(value)
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint()
        return bar
    }

    private fun toggle(title: String, sub: String, key: String, def: Boolean): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(6), 0, px(6)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 18f, R.attr.appText, bold = true))
        texts.addView(text(sub, 14f, R.attr.appMuted), lp(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(12) })
        row.addView(MaterialSwitch(this).apply {
            isChecked = Prefs.sp(this@SettingsActivity).getBoolean(key, def)
            setOnCheckedChangeListener { _, on -> Prefs.sp(this@SettingsActivity).edit().putBoolean(key, on).apply() }
        })
        return row
    }

    private fun link(title: String, sub: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6), 0, px(6))
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 18f, R.attr.appText, bold = true))
        texts.addView(text(sub, 14f, R.attr.appMuted), lp(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(color(R.attr.appAccent))
        }, LinearLayout.LayoutParams(px(24), px(24)))
        return row
    }

    private fun timeRow(title: String, key: String, def: String): View {
        val value = text(Prefs.sp(this).getString(key, def) ?: def, 18f, R.attr.appAccent, bold = true)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(12))
            setOnClickListener {
                val (h, m) = (Prefs.sp(this@SettingsActivity).getString(key, def) ?: def).split(":").map { it.toIntOrNull() ?: 0 }
                TimePickerDialog(this@SettingsActivity, { _, hh, mm ->
                    val v = "%02d:%02d".format(hh, mm)
                    Prefs.sp(this@SettingsActivity).edit().putString(key, v).apply()
                    value.text = v
                }, h, m, true).show()
            }
        }
        row.addView(text(title, 17f, R.attr.appText), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(value)
        return row
    }

    private fun numberRow(title: String, unit: String, key: String, def: Double, help: String): View {
        fun current() = Prefs.sp(this).getString(key, null)?.replace(',', '.')?.toDoubleOrNull() ?: def
        fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.2f".format(v)
        val value = text("${fmt(current())} $unit", 17f, R.attr.appAccent, bold = true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, px(12), 0, px(12))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(title, 17f, R.attr.appText), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(value)
        row.addView(top)
        row.addView(text(help, 13f, R.attr.appMuted), lp(top = 2))
        row.setOnClickListener {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(fmt(current()))
                setSelection(text.length)
            }
            val box = FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save") { _, _ ->
                    val v = input.text.toString().replace(',', '.').toDoubleOrNull()
                    if (v != null && v > 0) {
                        Prefs.sp(this).edit().putString(key, v.toString()).apply()
                        value.text = "${fmt(v)} $unit"
                    }
                }
                .setNeutralButton("Default") { _, _ ->
                    Prefs.sp(this).edit().remove(key).apply()
                    value.text = "${fmt(def)} $unit"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        return row
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(color(R.attr.appOutline))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1))
    }

    private fun text(s: String, size: Float, colorAttr: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color(colorAttr))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(fill)
        setStroke(px(1), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = px(top) }

    private fun px(v: Int) = (v * dp).toInt()

    private fun color(attr: Int): Int {
        val v = TypedValue()
        theme.resolveAttribute(attr, v, true)
        return v.data
    }
}
