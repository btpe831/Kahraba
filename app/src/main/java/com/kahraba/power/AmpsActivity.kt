package com.kahraba.power

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/**
 * Watts to amps: how much of a moteur subscription (5 A, 10 A...) appliances use.
 * Amps = watts ÷ volts. Built in code, in the same card style as Settings.
 */
class AmpsActivity : ThemedActivity() {

    /**
     * [inverterWatts]: for motor appliances, the typical draw of an inverter model, which also
     * starts gently (no surge). Non-inverter motors briefly draw 3-5x when they start.
     */
    private data class Appliance(
        val name: String,
        val watts: Int,
        val inverterWatts: Int? = null,
        val custom: Boolean = false,
    ) {
        val motor get() = inverterWatts != null
    }

    private val presets = listOf(
        "Lighting & small" to listOf(
            Appliance("LED bulb", 10), Appliance("Halogen bulb", 50), Appliance("Phone charger", 10),
            Appliance("Laptop", 65), Appliance("Wi-Fi router", 12), Appliance("TV", 100), Appliance("Fan", 60),
        ),
        "Kitchen" to listOf(
            Appliance("Fridge", 150, inverterWatts = 100), Appliance("Freezer", 200, inverterWatts = 130), Appliance("Kettle", 2000),
            Appliance("Coffee machine", 1200), Appliance("Microwave", 1200), Appliance("Toaster", 900),
            Appliance("Air fryer", 1500), Appliance("Electric oven", 2500), Appliance("Dishwasher", 1800),
        ),
        "Laundry & cleaning" to listOf(
            Appliance("Washing machine (heating water)", 2000, inverterWatts = 2000), Appliance("Clothes dryer", 2500),
            Appliance("Iron", 1200), Appliance("Vacuum cleaner", 1400),
        ),
        "Comfort & water" to listOf(
            Appliance("Electric heater", 2000), Appliance("Water heater (boiler)", 2000),
            Appliance("Air conditioner (12,000 BTU)", 1200, inverterWatts = 900), Appliance("Hair dryer", 1800),
            Appliance("Water pump", 750, inverterWatts = 750),
        ),
    )

    private val counts = HashMap<String, Int>()
    private val dp get() = resources.displayMetrics.density
    private lateinit var body: LinearLayout
    private lateinit var totalText: TextView
    private lateinit var totalSub: TextView
    private lateinit var totalBar: View
    private lateinit var totalTrack: FrameLayout
    private lateinit var convertResult: TextView
    private var convertWatts: Double? = null

    /** Motor appliances are inverter models unless switched off (most new ones are). */
    private fun isInverter(a: Appliance) = a.motor && Prefs.sp(this).getBoolean("amps_inverter_${a.name}", true)

    /** The watts used for an appliance, given whether it's an inverter model. */
    private fun wattsOf(a: Appliance) = if (isInverter(a)) a.inverterWatts!! else a.watts

    private val subscription get() = Prefs.sp(this).getInt(SUB_KEY, 10)
    private val volts get() = Prefs.sp(this).getInt(VOLT_KEY, 220)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val root = FrameLayout(this).apply { setBackgroundColor(color(R.attr.appBg)) }
        val scroll = ScrollView(this).apply { clipToPadding = false; setPadding(0, 0, 0, px(110)) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(16), px(20), px(24))
        }
        scroll.addView(body)
        root.addView(scroll)
        root.addView(totalBarView(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        build()
        update()
    }

    private fun build() {
        body.removeAllViews()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Amps calculator", 30f, R.attr.appText, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Close"
            cornerRadius = px(24)
            strokeColor = android.content.res.ColorStateList.valueOf(color(R.attr.appBorder))
            setTextColor(color(R.attr.appText))
            setOnClickListener { finish() }
        })
        body.addView(header)
        body.addView(text("How much of your moteur subscription do your appliances use?", 15f, R.attr.appMuted), lp(top = 6))

        section("Your subscription")
        body.addView(card {
            addView(segmented(listOf(5, 10, 15, 20, 25, 30).map { "${it}A" to it }, subscription) {
                Prefs.sp(this@AmpsActivity).edit().putInt(SUB_KEY, it).apply(); update()
            })
            addView(text("The amps on your ishtirak (moteur subscription). Above it, the moteur's breaker trips.",
                13f, R.attr.appMuted), lp(top = 10))
        })

        section("Voltage")
        body.addView(card {
            addView(segmented(listOf(210, 220, 230).map { "$it V" to it }, volts) {
                Prefs.sp(this@AmpsActivity).edit().putInt(VOLT_KEY, it).apply(); update()
            })
            addView(text("Lebanon is 220 V. The moteur often runs lower (e.g. 213 V), which means slightly more amps for the same appliance.",
                13f, R.attr.appMuted), lp(top = 10))
        })

        section("Quick convert")
        body.addView(card {
            val row = LinearLayout(this@AmpsActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val input = EditText(this@AmpsActivity).apply {
                hint = "e.g. 1200"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                textSize = 22f
                setTextColor(color(R.attr.appText))
                setHintTextColor(color(R.attr.appMuted))
                background = rounded(color(R.attr.appBg), color(R.attr.appBorder), 16f)
                setPadding(px(16), px(10), px(16), px(10))
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(e: Editable?) {
                        convertWatts = e?.toString()?.replace(',', '.')?.toDoubleOrNull()
                        update()
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("watts", 17f, R.attr.appMuted), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(12) })
            addView(row)
            convertResult = text("Type the watts on the appliance's label.", 20f, R.attr.appText, bold = true)
            addView(convertResult, lp(top = 12))
        })

        section("What's on")
        body.addView(text("Tap + for everything you want to run at the same time.", 14f, R.attr.appMuted).apply {
            setPadding(px(4), 0, px(4), 0)
        })
        for ((group, items) in presets) body.addView(applianceCard(group, items))
        body.addView(applianceCard("Your own", customAppliances(), addButton = true))
        body.addView(text(
            "Inverter models (most new fridges, ACs, washers and pumps) start gently and use less on average. " +
                "Older non-inverter motors (⚡) draw 3–5× more for a second or two when they start, which can trip a small subscription. " +
                "Tap the chip to switch. Wattages are typical; check the label for yours. Amps = watts ÷ volts.",
            13f, R.attr.appMuted,
        ).apply { setPadding(px(4), px(16), px(4), 0) })
    }

    private fun applianceCard(title: String, items: List<Appliance>, addButton: Boolean = false): View = card {
        addView(text(title.uppercase(), 13f, R.attr.appAccent, bold = true).apply { letterSpacing = 0.12f })
        for (a in items) addView(applianceRow(a))
        if (addButton) {
            addView(MaterialButton(this@AmpsActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "+ Add an appliance"
                cornerRadius = px(22)
                strokeColor = android.content.res.ColorStateList.valueOf(color(R.attr.appBorder))
                setTextColor(color(R.attr.appText))
                setOnClickListener { addCustom() }
            }, lp(top = 8))
        }
    }.also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = px(12) }

    private fun applianceRow(a: Appliance): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(10), 0, px(10)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(a.name, 16f, R.attr.appText, bold = true))
        val detail = text("", 13f, R.attr.appMuted)
        fun paintDetail() {
            val w = wattsOf(a)
            detail.text = "$w W · ${amps(w.toDouble())} A" + if (a.motor && !isInverter(a)) " · ⚡ start-up surge" else ""
        }
        paintDetail()
        texts.addView(detail)
        if (a.motor) {
            // Inverter switch: on for most new fridges, ACs, washers and pumps.
            val chip = text("", 12f, R.attr.appAccent, bold = true).apply {
                setPadding(px(10), px(4), px(10), px(4))
            }
            fun paintChip() {
                val on = isInverter(a)
                chip.text = if (on) "✓ Inverter" else "Not inverter"
                chip.setTextColor(color(if (on) R.attr.appOnAccent else R.attr.appMuted))
                chip.background = if (on) rounded(color(R.attr.appAccent), color(R.attr.appAccent), 14f)
                    else rounded(color(R.attr.appBg), color(R.attr.appBorder), 14f)
            }
            paintChip()
            chip.setOnClickListener {
                Prefs.sp(this).edit().putBoolean("amps_inverter_${a.name}", !isInverter(a)).apply()
                paintChip(); paintDetail(); update()
            }
            texts.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(6) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (a.custom) {
            row.setOnLongClickListener {
                MaterialAlertDialogBuilder(this).setMessage("Remove “${a.name}”?")
                    .setPositiveButton("Remove") { _, _ -> removeCustom(a) }
                    .setNegativeButton("Cancel", null).show()
                true
            }
        }
        val count = text("${counts[a.name] ?: 0}", 18f, R.attr.appText, bold = true).apply { gravity = Gravity.CENTER }
        fun step(d: Int) {
            val n = ((counts[a.name] ?: 0) + d).coerceIn(0, 20)
            counts[a.name] = n
            count.text = "$n"
            update()
        }
        row.addView(roundButton("−") { step(-1) })
        row.addView(count, LinearLayout.LayoutParams(px(36), -2))
        row.addView(roundButton("+") { step(1) })
        return row
    }

    private fun roundButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(color(R.attr.appAccent))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.attr.appBg))
            setStroke(px(1), color(R.attr.appBorder))
        }
        layoutParams = LinearLayout.LayoutParams(px(38), px(38))
        setOnClickListener { onClick() }
    }

    // ---- Totals ----

    private fun allAppliances() = presets.flatMap { it.second } + customAppliances()

    private fun amps(watts: Double) = String.format(Locale.US, "%.1f", watts / volts)

    private fun update() {
        val on = allAppliances().filter { (counts[it.name] ?: 0) > 0 }
        val totalW = on.sumOf { (counts[it.name] ?: 0) * wattsOf(it).toDouble() }
        // Biggest start-up kick from a non-inverter motor (about 3x its running draw, briefly).
        val surgeW = on.filter { it.motor && !isInverter(it) }.maxOfOrNull { wattsOf(it) * 2.0 } ?: 0.0
        val totalA = totalW / volts
        val sub = subscription.toDouble()
        totalText.text = "${amps(totalW)} A of ${subscription} A"
        val spare = sub - totalA
        totalSub.text = when {
            totalW == 0.0 -> "Add appliances to see the total"
            spare >= 0 && (totalW + surgeW) / volts > sub ->
                "${totalW.toInt()} W · fits, but a non-inverter motor starting could trip it"
            spare >= 1 -> "${totalW.toInt()} W · ${String.format(Locale.US, "%.1f", spare)} A spare"
            spare >= 0 -> "${totalW.toInt()} W · right at the limit – a motor starting could trip it"
            else -> "${totalW.toInt()} W · over by ${String.format(Locale.US, "%.1f", -spare)} A – the breaker will likely trip"
        }
        val share = (totalA / sub).coerceIn(0.0, 1.0)
        val barColor = ContextCompat.getColor(this, when {
            totalA > sub -> R.color.over_limit
            totalA > sub * 0.85 -> R.color.moteur
            else -> R.color.kahraba
        })
        totalBar.background = GradientDrawable().apply { cornerRadius = 4 * dp; setColor(barColor) }
        totalTrack.post { totalBar.layoutParams = totalBar.layoutParams.apply { width = (totalTrack.width * share).toInt() } }

        convertWatts?.let { w ->
            convertResult.text = "${w.toInt()} W = ${amps(w)} A" +
                "  ·  ${(w / volts / sub * 100).toInt()}% of ${subscription} A"
        } ?: run { if (::convertResult.isInitialized) convertResult.text = "Type the watts on the appliance's label." }
    }

    private fun totalBarView(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(28), px(20), px(20))
            val bg = color(R.attr.appBg)
            val clear = bg and 0x00FFFFFF
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(clear, clear or (0xE6 shl 24), bg))
        }
        val label = text("TOTAL RUNNING", 11f, R.attr.appMuted, bold = true).apply { letterSpacing = 0.12f }
        totalText = text("0.0 A", 24f, R.attr.appText, bold = true)
        totalSub = text("", 13f, R.attr.appMuted)
        totalTrack = FrameLayout(this).apply { background = rounded(color(R.attr.appOutline), color(R.attr.appOutline), 4f) }
        totalBar = View(this)
        totalTrack.addView(totalBar, FrameLayout.LayoutParams(0, -1))
        bar.addView(label)
        bar.addView(totalText)
        bar.addView(totalTrack, LinearLayout.LayoutParams(-1, px(8)).apply { topMargin = px(8) })
        bar.addView(totalSub, lp(top = 6))
        return bar
    }

    // ---- Your own appliances (saved) ----

    private fun customAppliances(): List<Appliance> =
        (Prefs.sp(this).getString(CUSTOM_KEY, "") ?: "").lines().mapNotNull { line ->
            val p = line.split(";")
            val w = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            Appliance(p[0], w, custom = true)
        }

    private fun saveCustom(list: List<Appliance>) {
        Prefs.sp(this).edit().putString(CUSTOM_KEY, list.joinToString("\n") { "${it.name};${it.watts}" }).apply()
    }

    private fun addCustom() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val name = EditText(this).apply { hint = "Name, e.g. Blender"; setSingleLine() }
        val watts = EditText(this).apply { hint = "Watts, e.g. 600"; inputType = InputType.TYPE_CLASS_NUMBER }
        box.addView(name); box.addView(watts)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add an appliance")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val n = name.text.toString().replace(";", " ").replace("\n", " ").trim().take(30)
                val w = watts.text.toString().toIntOrNull()
                if (n.isNotEmpty() && w != null && w > 0 && allAppliances().none { it.name == n }) {
                    saveCustom(customAppliances() + Appliance(n, w, custom = true))
                    build(); update()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeCustom(a: Appliance) {
        saveCustom(customAppliances().filter { it.name != a.name })
        counts.remove(a.name)
        build(); update()
    }

    // ---- Building blocks (same style as Settings) ----

    private fun section(title: String) {
        body.addView(text(title.uppercase(), 15f, R.attr.appAccent, bold = true).apply {
            letterSpacing = 0.14f
            setPadding(px(4), px(26), 0, px(2))
        })
    }

    private fun card(fill: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(16), px(20), px(16))
        background = rounded(color(R.attr.appSurface), color(R.attr.appBorder), 22f)
        layoutParams = lp(top = 8)
        fill()
    }

    private fun segmented(options: List<Pair<String, Int>>, selected: Int, onPick: (Int) -> Unit): View {
        val bar = LinearLayout(this).apply {
            setPadding(px(4), px(4), px(4), px(4))
            background = rounded(color(R.attr.appSurfaceAlt), color(R.attr.appBorder), 26f)
            tag = selected
        }
        fun paint() {
            for (i in 0 until bar.childCount) {
                val tv = bar.getChildAt(i) as TextView
                val on = options[i].second == bar.tag
                tv.setTextColor(color(if (on) R.attr.appOnAccent else R.attr.appMuted))
                tv.background = if (on) rounded(color(R.attr.appAccent), color(R.attr.appAccent), 22f) else null
            }
        }
        for ((label, value) in options) {
            bar.addView(text(label, 15f, R.attr.appText, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, px(10), 0, px(10))
                setOnClickListener { bar.tag = value; paint(); onPick(value) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint()
        return bar
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

    companion object {
        private const val SUB_KEY = "amps_subscription"
        private const val VOLT_KEY = "amps_volts"
        private const val CUSTOM_KEY = "amps_custom"
    }
}
