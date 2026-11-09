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
        "Kahraba often runs at a higher voltage than the moteur, so the same halogen bulb glows brighter on kahraba: usually 10–25%, depending on the day.",
        // Words and names (1.5.06)
        "“Mazout”, the Lebanese word for diesel, came through French from the Russian “mazut”, a heavy fuel oil.",
        "“Adaad” (counter) is the meter some moteur owners now install, so you pay for what you use instead of a fixed amp plan.",
        "The volt is named after Alessandro Volta, who built the first battery in 1800 from stacked discs of zinc, copper and salty cloth.",
        "The amp is named after André-Marie Ampère, who showed that two wires carrying current push or pull on each other.",
        "The watt is named after James Watt, whose improved steam engine helped start the Industrial Revolution.",
        "The hertz is named after Heinrich Hertz, the first to prove that radio waves exist.",
        "One lux is the light of one lumen spread over one square metre: the unit this app's light readings use.",
        // History
        "The world's first public power station, Edison's Pearl Street Station, opened in New York in 1882 and lit about 400 lamps.",
        "Alternating current (AC) won over direct current because transformers can step its voltage up for long cables and back down for homes.",
        "The Qaraoun dam on the Litani river, finished in 1959, created Lebanon's largest lake and feeds hydroelectric plants downstream.",
        "From 2013 to 2021, two floating power plants on ships, moored off Zouk and Jiyeh, supplied part of Lebanon's electricity.",
        "In October 2021 Lebanon's whole national grid shut down for about a day when its two biggest power plants ran out of fuel.",
        "Most of Europe and the Middle East use 220–240 V at 50 Hz; the Americas mostly use 110–120 V at 60 Hz.",
        // How it works
        "Tungsten has the highest melting point of any metal, 3,422 °C. That's why it's used for the glowing filament in a bulb.",
        "In a halogen bulb, a little halogen gas carries evaporated tungsten back onto the filament, so the bulb lasts longer and stays clear.",
        "Touching a halogen bulb's glass with bare fingers leaves oil that can make a hot spot and shorten its life.",
        "Only about 5% of the power going into a halogen bulb becomes light. The rest is heat, which is why it gets so hot.",
        "A halogen bulb's light rises steeply with voltage: about 5% more volts gives about 18% more light. That's how this app tells kahraba from moteur.",
        "Your eyes can't see a light flickering 100 times a second, but a phone camera sometimes can: the dark bands on photos of some screens and lamps.",
        "The grid keeps its frequency very close to 50 Hz. A moteur drifts a little as its engine speeds up and slows down with the load.",
        "A changeover switch can't connect kahraba and the moteur at the same time, so the light dips for a split second when it swaps between them.",
        "The earth wire in a socket is a safety path: if a fault makes a metal case live, the current goes to earth and trips the breaker instead of going through you.",
        "A breaker trips on too much current; a residual-current device (RCD) trips when current leaks away, even a tiny amount that could hurt someone.",
        "A UPS keeps a battery charged and turns its 12 V DC back into 220 V AC when the power goes off.",
        // Light and the phone
        "A phone's light sensor sits near the top of the screen. It's what dims the screen automatically in a dark room.",
        "A full moon gives about 0.1–0.3 lux. A lit room is a few hundred lux. Direct sunlight can reach 100,000 lux.",
        "A halogen bulb a short distance from the phone can easily give 1,000–5,000 lux: plenty for this app to see small changes.",
        // Numbers
        "A litre of diesel holds about 10 kWh of energy, but a generator turns only about a third of that into electricity.",
        "On a sunny Lebanese day, 1 kW of solar panels makes roughly 4–5 kWh: enough for a fridge for a few days.",
        "A fridge uses about 1–2 kWh a day. It runs in bursts, which is why its hum comes and goes.",
        "Once a room is cool, an inverter air conditioner can run at less than half its full power. An older one switches fully on and off instead.",
        "A static shock from a doorknob can be several thousand volts, but it carries so little current that it only stings.",
        "A lightning bolt can heat the air to about 30,000 °C, several times hotter than the surface of the sun.",
        "Some electric eels can produce more than 800 volts, enough to stun a horse.",
        // What this app has seen
        "When kahraba cuts out, the moteur takes about a minute to start: in this app's recordings, usually 60 to 80 seconds.",
        "At night a calm moteur can keep a halogen light perfectly steady for up to an hour. In the daytime it almost always wobbles.",
        "Measured with a voltmeter, kahraba usually ran at about 231–238 V and the moteur at about 208–227 V.",
        "When the grid is unstable, kahraba can arrive for only a few seconds before the power cuts out and the moteur takes over again.",
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
