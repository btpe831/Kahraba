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
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Watts to amps: how much of a moteur subscription (5 A, 10 A...) appliances use.
 * Amps = watts ÷ volts. People make their own categories and appliances (with wattage), then
 * tap + / − for what they'd run at the same time. Built in code, in the same style as Settings.
 */
class AmpsActivity : ThemedActivity() {

    /** [surge]: an older motor without an inverter, which briefly draws 3–5× when it starts. */
    private data class Item(val name: String, val watts: Int, val surge: Boolean = false, val count: Int = 0)
    private data class Category(val name: String, val items: List<Item>)

    private var categories = mutableListOf<Category>()

    private val dp get() = resources.displayMetrics.density
    private lateinit var body: LinearLayout
    private lateinit var totalText: TextView
    private lateinit var totalSub: TextView
    private lateinit var totalBar: View
    private lateinit var totalTrack: FrameLayout
    private lateinit var convertResult: TextView
    private var convertWatts: Double? = null

    private val subscription get() = Prefs.sp(this).getInt(SUB_KEY, 10)
    private val volts get() = Prefs.sp(this).getInt(VOLT_KEY, 220)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        categories = load()
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
        header.addView(outlineButton("Close") { finish() })
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
                Prefs.sp(this@AmpsActivity).edit().putInt(VOLT_KEY, it).apply(); build(); update()
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
                convertWatts?.let { setText(it.toInt().toString()) }
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

        // WHAT'S ON: the user's own categories and appliances.
        val onHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        onHeader.addView(text("WHAT'S ON", 15f, R.attr.appAccent, bold = true).apply { letterSpacing = 0.14f },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (categories.any { c -> c.items.any { it.count > 0 } }) {
            onHeader.addView(text("Reset counts", 14f, R.attr.appAccent, bold = true).apply {
                setPadding(px(8), px(6), px(4), px(6))
                setOnClickListener {
                    categories = categories.map { c -> c.copy(items = c.items.map { it.copy(count = 0) }) }.toMutableList()
                    save(); build(); update()
                }
            })
        }
        onHeader.setPadding(px(4), px(26), 0, px(2))
        body.addView(onHeader)

        if (categories.isEmpty()) {
            body.addView(card {
                addView(text("Add your own appliances", 18f, R.attr.appText, bold = true))
                addView(text("Make a category (e.g. Kitchen), then add what's in it with the watts from its label. " +
                    "Tap + for everything you'd run at the same time.", 14f, R.attr.appMuted), lp(top = 4))
                addView(primaryButton("+ Add a category") { addCategory() }, lp(top = 14))
                addView(text("Or start from a typical list you can edit", 15f, R.attr.appAccent, bold = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, px(14), 0, px(4))
                    setOnClickListener { categories = examples(); save(); build(); update() }
                }, lp())
            })
        } else {
            body.addView(text("Tap + for everything you'd run at the same time. Tap an appliance to edit it.",
                14f, R.attr.appMuted).apply { setPadding(px(4), 0, px(4), 0) })
            for (i in categories.indices) body.addView(categoryCard(i))
            body.addView(outlineButton("+ Add a category") { addCategory() }, lp(top = 14))
        }

        body.addView(text(
            "Wattages are on each appliance's label or manual. Most new fridges, ACs, washers and pumps are inverter " +
                "models: they start gently. Older motors without an inverter (⚡) draw 3–5× more for a second or two when " +
                "they start, which can trip a small subscription. Amps = watts ÷ volts.",
            13f, R.attr.appMuted,
        ).apply { setPadding(px(4), px(18), px(4), 0) })
    }

    // ---- Category cards ----

    private fun categoryCard(ci: Int): View {
        val c = categories[ci]
        val card = card {
            val head = LinearLayout(this@AmpsActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(text(c.name.uppercase(), 13f, R.attr.appAccent, bold = true).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(text("Edit", 14f, R.attr.appAccent, bold = true).apply {
                setPadding(px(10), px(4), 0, px(4))
                setOnClickListener { editCategory(ci) }
            })
            addView(head)
            if (c.items.isEmpty()) {
                addView(text("Nothing here yet.", 14f, R.attr.appMuted), lp(top = 8))
            }
            for (ii in c.items.indices) addView(itemRow(ci, ii))
            addView(outlineButton("+ Add an appliance") { editItem(ci, null) }, lp(top = 8))
        }
        (card.layoutParams as LinearLayout.LayoutParams).topMargin = px(12)
        return card
    }

    private fun itemRow(ci: Int, ii: Int): View {
        val item = categories[ci].items[ii]
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(10), 0, px(10))
            setOnClickListener { editItem(ci, ii) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(item.name, 16f, R.attr.appText, bold = true))
        texts.addView(text("${item.watts} W · ${amps(item.watts.toDouble())} A" +
            if (item.surge) " · ⚡ start-up surge" else "", 13f, R.attr.appMuted))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val count = text("${item.count}", 18f, R.attr.appText, bold = true).apply { gravity = Gravity.CENTER }
        fun step(d: Int) {
            val n = (categories[ci].items[ii].count + d).coerceIn(0, 20)
            setItem(ci, ii, categories[ci].items[ii].copy(count = n))
            count.text = "$n"
            save(); update()
        }
        row.addView(roundButton("−") { step(-1) })
        row.addView(count, LinearLayout.LayoutParams(px(36), -2))
        row.addView(roundButton("+") { step(1) })
        return row
    }

    private fun setItem(ci: Int, ii: Int, item: Item) {
        val items = categories[ci].items.toMutableList()
        items[ii] = item
        categories[ci] = categories[ci].copy(items = items)
    }

    // ---- Editing ----

    private fun addCategory() {
        askText("New category", "e.g. Kitchen", "") { name ->
            categories.add(Category(name, emptyList()))
            save(); build(); update()
        }
    }

    private fun editCategory(ci: Int) {
        val c = categories[ci]
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setItems(arrayOf("Rename", "Move up", "Move down", "Delete")) { _, which ->
                when (which) {
                    0 -> askText("Rename category", "e.g. Kitchen", c.name) { name ->
                        categories[ci] = c.copy(name = name); save(); build(); update()
                    }
                    1 -> if (ci > 0) { categories.add(ci - 1, categories.removeAt(ci)); save(); build(); update() }
                    2 -> if (ci < categories.size - 1) { categories.add(ci + 1, categories.removeAt(ci)); save(); build(); update() }
                    3 -> MaterialAlertDialogBuilder(this)
                        .setMessage("Delete “${c.name}” and its ${c.items.size} appliances?")
                        .setPositiveButton("Delete") { _, _ -> categories.removeAt(ci); save(); build(); update() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Add (ii = null) or edit an appliance: name, watts and the start-up surge switch. */
    private fun editItem(ci: Int, ii: Int?) {
        val old = ii?.let { categories[ci].items[it] }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        val name = EditText(this).apply { hint = "Name, e.g. Coffee machine"; setSingleLine(); setText(old?.name ?: "") }
        val watts = EditText(this).apply {
            hint = "Watts, e.g. 1200"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(old?.watts?.toString() ?: "")
        }
        val surgeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, 0) }
        val surgeTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        surgeTexts.addView(text("Older motor, no inverter", 16f, R.attr.appText, bold = true))
        surgeTexts.addView(text("Fridges, ACs, pumps that draw a big kick when they start", 13f, R.attr.appMuted))
        surgeRow.addView(surgeTexts, LinearLayout.LayoutParams(0, -2, 1f))
        val surge = MaterialSwitch(this).apply { isChecked = old?.surge ?: false }
        surgeRow.addView(surge)
        box.addView(name); box.addView(watts); box.addView(surgeRow)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (old == null) "Add an appliance" else "Edit appliance")
            .setView(box)
            .setPositiveButton(if (old == null) "Add" else "Save") { _, _ ->
                val n = clean(name.text.toString())
                val w = watts.text.toString().toIntOrNull()
                if (n.isNotEmpty() && w != null && w > 0) {
                    val item = Item(n, w, surge.isChecked, old?.count ?: 0)
                    if (ii == null) {
                        categories[ci] = categories[ci].copy(items = categories[ci].items + item)
                    } else {
                        setItem(ci, ii, item)
                    }
                    save(); build(); update()
                }
            }
            .setNegativeButton("Cancel", null)
        if (ii != null) {
            dialog.setNeutralButton("Delete") { _, _ ->
                categories[ci] = categories[ci].copy(items = categories[ci].items.filterIndexed { i, _ -> i != ii })
                save(); build(); update()
            }
        }
        dialog.show()
    }

    private fun askText(title: String, hint: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply { this.hint = hint; setSingleLine(); setText(initial); setSelection(text.length) }
        val box = FrameLayout(this).apply { setPadding(px(20), px(8), px(20), 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> clean(input.text.toString()).takeIf { it.isNotEmpty() }?.let(onDone) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clean(s: String) = s.replace("\n", " ").trim().take(40)

    // ---- Saving (on this phone; included in backups) ----

    private fun load(): MutableList<Category> {
        val json = Prefs.sp(this).getString(CATEGORIES_KEY, null)
        if (json != null) {
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    val items = c.getJSONArray("items")
                    Category(c.getString("name"), (0 until items.length()).map { j ->
                        val o = items.getJSONObject(j)
                        Item(o.getString("name"), o.getInt("watts"), o.optBoolean("surge"), o.optInt("count"))
                    })
                }.toMutableList()
            }.getOrDefault(mutableListOf())
        }
        // First time: keep appliances added in the previous version of the calculator.
        val old = (Prefs.sp(this).getString(OLD_CUSTOM_KEY, "") ?: "").lines().mapNotNull { line ->
            val p = line.split(";")
            p.getOrNull(1)?.toIntOrNull()?.let { Item(p[0], it) }
        }
        return if (old.isEmpty()) mutableListOf() else mutableListOf(Category("My appliances", old))
    }

    private fun save() {
        val arr = JSONArray()
        for (c in categories) {
            val items = JSONArray()
            for (item in c.items) {
                items.put(JSONObject().put("name", item.name).put("watts", item.watts).put("surge", item.surge).put("count", item.count))
            }
            arr.put(JSONObject().put("name", c.name).put("items", items))
        }
        Prefs.sp(this).edit().putString(CATEGORIES_KEY, arr.toString()).apply()
    }

    /** A typical starting list, all editable. New appliances are assumed to be inverter models. */
    private fun examples() = mutableListOf(
        Category("Kitchen", listOf(
            Item("Fridge", 100), Item("Kettle", 2000), Item("Coffee machine", 1200), Item("Microwave", 1200),
            Item("Air fryer", 1500), Item("Electric oven", 2500),
        )),
        Category("Laundry", listOf(Item("Washing machine (heating water)", 2000), Item("Iron", 1200))),
        Category("Comfort & water", listOf(
            Item("Electric heater", 2000), Item("Water heater (boiler)", 2000), Item("Air conditioner (12,000 BTU)", 900),
            Item("Water pump", 750), Item("Hair dryer", 1800),
        )),
        Category("Lights & small", listOf(
            Item("LED bulb", 10), Item("TV", 100), Item("Wi-Fi router", 12), Item("Laptop", 65), Item("Phone charger", 10),
        )),
    )

    // ---- Totals ----

    private fun amps(watts: Double) = String.format(Locale.US, "%.1f", watts / volts)

    private fun update() {
        val on = categories.flatMap { it.items }.filter { it.count > 0 }
        val totalW = on.sumOf { it.count * it.watts.toDouble() }
        // Biggest start-up kick from an older motor (about 3× its running draw, briefly).
        val surgeW = on.filter { it.surge }.maxOfOrNull { it.watts * 2.0 } ?: 0.0
        val totalA = totalW / volts
        val sub = subscription.toDouble()
        totalText.text = "${amps(totalW)} A of $subscription A"
        val spare = sub - totalA
        totalSub.text = when {
            totalW == 0.0 -> "Tap + on appliances to see the total"
            spare >= 0 && (totalW + surgeW) / volts > sub ->
                "${totalW.toInt()} W · fits, but an older motor starting could trip it"
            spare >= 1 -> "${totalW.toInt()} W · ${String.format(Locale.US, "%.1f", spare)} A spare"
            spare >= 0 -> "${totalW.toInt()} W · right at the limit"
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

        if (::convertResult.isInitialized) {
            convertResult.text = convertWatts?.let { w ->
                "${w.toInt()} W = ${amps(w)} A  ·  ${(w / volts / sub * 100).toInt()}% of $subscription A"
            } ?: "Type the watts on the appliance's label."
        }
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

    private fun outlineButton(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            cornerRadius = px(22)
            strokeColor = android.content.res.ColorStateList.valueOf(color(R.attr.appBorder))
            setTextColor(color(R.attr.appText))
            setOnClickListener { onClick() }
        }

    private fun primaryButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        cornerRadius = px(24)
        backgroundTintList = android.content.res.ColorStateList.valueOf(color(R.attr.appAccent))
        setTextColor(color(R.attr.appOnAccent))
        setOnClickListener { onClick() }
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
        private const val CATEGORIES_KEY = "amps_categories"
        private const val OLD_CUSTOM_KEY = "amps_custom"
    }
}
