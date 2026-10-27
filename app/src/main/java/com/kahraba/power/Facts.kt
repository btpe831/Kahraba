package com.kahraba.power

import android.content.Context
import kotlin.random.Random

/**
 * "Did you know?" facts about kahraba, built into the app. One a day (in a shuffled order that
 * repeats once all have been shown); tapping the card moves to the next one.
 */
object Facts {
    val all = listOf(
        // Words
        "“Kahraba” comes from the Persian kahrubā, “straw-attractor”: the old name for amber, which picks up bits of straw when you rub it.",
        "The English word “electricity” also comes from amber: the Greek ēlektron. Two languages, one stone.",
        "“Moteur” is French for engine. In Lebanon it means the whole neighbourhood generator, a leftover of French influence.",
        "“Ishtirak” means subscription: the monthly moteur plan, sold in amps (5 A, 10 A…).",
        "“Kahraba el dawlé” means the state's electricity, to tell it apart from the moteur.",
        // History
        "Beirut had electric trams and street lighting in the early 1900s, run by a Belgian company.",
        "Électricité du Liban (EDL), the state electricity company, was founded in 1964.",
        "Neighbourhood generators spread during the civil war (1975–1990), and they never left.",
        "During the 2021–2022 crisis, many areas got only an hour or two of kahraba a day.",
        "Since 2021, rooftop solar panels have spread fast across Lebanon as homes looked for alternatives to the moteur.",
        "Many buildings have a changeover switch near the entrance that swaps the whole building between kahraba and moteur.",
        // How it works
        "Lebanon runs on 220 volts at 50 hertz: the current changes direction 100 times every second.",
        "A halogen bulb is a tungsten filament glowing at around 2,500–3,000 °C. Its brightness follows the voltage, which is why this app works.",
        "A halogen bulb exaggerates voltage changes: about 10% less voltage makes it roughly 30% dimmer.",
        "LED bulbs have a small circuit inside that smooths the power, so they hide the moteur's wobble.",
        "The moteur's flicker comes from its engine speeding up and slowing down as neighbours switch things on and off.",
        "A generator's frequency depends on its engine speed: a typical 50 Hz generator spins at 1,500 rpm.",
        "The moteur's breaker trips when your home draws more amps than your subscription. That's the click, and then darkness.",
        // Handy numbers
        "At 220 V, 1 amp is about 220 watts. A 5 A subscription is about 1,100 W in total.",
        "A 2,000 W kettle draws about 9 amps: more than a whole 5 A subscription on its own.",
        "A 2,000 W water heater (boiler) draws about 9 A, which leaves little room on a 10 A subscription.",
        "A phone charger uses about 10 W. A 5 A subscription could charge about 100 phones at once.",
        "Running a 1,000 W heater for one hour uses 1 kWh: the unit on your electricity bill.",
        "Older motors (fridges, ACs, pumps) can draw 3–5× their normal power for a moment as they start.",
        "Inverter appliances speed their motors up gently instead of switching hard on, so they avoid big start-up kicks.",
        "On a clear day at noon, sunlight delivers about 1,000 W on every square metre: one reason rooftop solar works so well here.",
        "Kahraba often runs at a higher voltage than the moteur, so the same halogen bulb glows about 25% brighter on kahraba.",
    )

    private val order: List<Int> by lazy { all.indices.shuffled(Random(1964)) }

    /** Today's fact (plus however many times the card was tapped today) and its position. */
    fun current(ctx: Context): Pair<String, Int> {
        val day = (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86_400_000L
        val sp = Prefs.sp(ctx)
        val offset = if (sp.getLong(DAY_KEY, -1) == day) sp.getInt(OFFSET_KEY, 0) else 0
        val i = ((day + offset) % all.size).toInt()
        return all[order[i]] to i + 1
    }

    /** Show the next fact (until tomorrow). */
    fun next(ctx: Context) {
        val day = (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86_400_000L
        val sp = Prefs.sp(ctx)
        val offset = if (sp.getLong(DAY_KEY, -1) == day) sp.getInt(OFFSET_KEY, 0) else 0
        sp.edit().putLong(DAY_KEY, day).putInt(OFFSET_KEY, offset + 1).apply()
    }

    private const val DAY_KEY = "fact_day"
    private const val OFFSET_KEY = "fact_offset"
}
