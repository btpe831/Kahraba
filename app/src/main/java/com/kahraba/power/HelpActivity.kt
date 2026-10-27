package com.kahraba.power

import android.os.Bundle
import android.text.Html
import android.widget.LinearLayout
import android.widget.TextView

/** How to use the app, which lights work, what breaks it, and the disclaimer. */
class HelpActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        setupHeader("How to use the app")
        val container = findViewById<LinearLayout>(R.id.helpContainer)
        for ((title, body) in SECTIONS) {
            val card = layoutInflater.inflate(R.layout.item_help_section, container, false)
            card.findViewById<TextView>(R.id.sectionTitle).text = title
            card.findViewById<TextView>(R.id.sectionBody).text =
                Html.fromHtml(body.trimIndent().replace("\n", "<br>"), Html.FROM_HTML_MODE_COMPACT)
            container.addView(card)
        }
        Prefs.sp(this).edit().putBoolean(Prefs.HELP_SEEN, true).apply()
    }


    companion object {
        private val SECTIONS = listOf(
            "Quick start" to """
                1. Turn on a <b>halogen</b> or old-style <b>filament</b> bulb.
                2. Tap <b>Check now</b>.
                3. Within 3 seconds, lay the phone <b>face-up</b> right under the light and let go.
                4. Wait for the beep:
                &nbsp;&nbsp;• <b>1 long beep = Moteur</b> (usually within about 10 seconds)
                &nbsp;&nbsp;• <b>4 short beeps = Kahraba</b> – only in a learned room (see “Rooms”)
                &nbsp;&nbsp;• <b>2 short beeps = No flicker</b> (after 25 seconds) – probably kahraba, but not certain
                Don't touch the phone or lean over it until it beeps. If the phone is moved, the check stops – just put it down and try again.
            """,

            "Rooms: how to get a sure “Kahraba”" to """
                On its own, a check can prove <b>Moteur</b> (wobble) but not Kahraba, because a calm moteur can be steady too. Rooms fix that.
                Kahraba's higher voltage makes a halogen about 25% brighter. Once the app knows how bright a room is on each source, it can tell them apart even when the light is steady.
                <b>Set up a room</b> (the <b>Set up a room</b> card on the main screen walks you through it):
                1. Tap the room button above <b>Check now</b> → <b>Add a room</b> and name it (Kitchen, Bathroom…).
                2. Always check from the <b>same place</b> in that room, under the <b>same light</b> (a bit of tape helps).
                3. Do a check when you <b>know for sure</b> it's kahraba and tap <b>It was kahraba</b>.
                4. Do one when you know it's moteur and tap <b>It was moteur</b>. (A check that sees a wobble there counts too.)
                After that, checks in that room can say <b>Kahraba</b> (4 short beeps) or catch a calm <b>Moteur</b>, usually within about 15 seconds.
                <b>Tips:</b>
                • Each room learns on its own. Pick the right room before each check.
                • If the brightness doesn't match the room, you get “No flicker” instead of a guess.
                • Keep the other lights in the room as they were when you set it up, and avoid daylight.
                • Moved the lamp or changed the bulb? Tap the room button → Rename or delete → delete it, and set it up again.
                • The always-on monitor can use the same rooms, so teaching a room once helps both.
                • Rooms are learned per phone: each phone's light sensor reads differently.
                • The <b>How right is it?</b> card keeps score of the results you confirm, so you can see how much to trust the app in your home.
            """,

            "How it works" to """
                The moteur's voltage is never quite steady; it rises and falls as neighbours switch things on and off. A halogen bulb's brightness follows the voltage, so on the moteur the light <b>wobbles</b> slightly, often too little for your eyes to notice.
                Kahraba is much steadier, so the light stays <b>flat</b>. Kahraba's voltage is usually higher too, so the same bulb shines about <b>25% brighter</b>.
                Your phone's light sensor watches for that wobble:
                &nbsp;&nbsp;• A wobble that repeats or keeps going → <b>Moteur</b>. A single dip doesn't count: switching on a kettle or heater dips the voltage on kahraba too.
                &nbsp;&nbsp;• 25 seconds with no wobble → <b>No flicker</b> (probably kahraba)
                <b>Why “No flicker” isn't a sure Kahraba:</b> when few people are using it (often at night), the moteur can stay perfectly steady for many minutes. The phone's light sensor also only notices changes bigger than about 8%. So a wobble proves moteur, but a steady light can be either. To be sure, use a learned room (see “Rooms”) or a voltage meter (kahraba usually reads higher, e.g. 234 V vs 213 V).
            """,

            "Which lights work" to """
                <b>✓ Halogen</b> – best. Spotlights, desk lamps, capsule bulbs (G4, G9, GU10, MR16), halogen floodlights.
                <b>✓ Old filament (incandescent) bulbs</b> – work just as well.
                <b>✗ LED bulbs</b> – most have a circuit inside that keeps the light steady, which hides the wobble. The app will wrongly say Kahraba. Some cheap LEDs do wobble, but don't rely on it.
                <b>✗ Bulbs marked “100W equivalent”</b> – these are LEDs, not 100W bulbs.
                <b>✗ Energy-saving bulbs (CFL, the twisted “neon” type)</b> – have electronics inside like LEDs; usually hide the wobble.
                <b>? Fluorescent tubes (“neon”)</b> – old tubes that flicker or hum when switching on may work; modern ones usually don't.
                <b>✗ Dimmed lights</b> – dimmer switches add their own flicker. Use full brightness.
                <b>Not sure about a light?</b> Test it once while you know for sure you're on the moteur. If the check says <b>Moteur</b>, that light works. If it says Kahraba, don't use it.
            """,

            "Distance and light level" to """
                During a check the screen shows the light level in <b>lux</b>. That number matters more than the distance:
                &nbsp;&nbsp;• <b>300 lux or more</b> – best
                &nbsp;&nbsp;• <b>100–300 lux</b> – usually OK
                &nbsp;&nbsp;• <b>Under 100 lux</b> – too dim; the app stops and says “Too dim”
                As a rough guide:
                &nbsp;&nbsp;• Halogen desk lamp or spotlight: <b>30 cm to 1 m</b> away
                &nbsp;&nbsp;• 100W halogen bulb: within about <b>1–1.5 m</b>
                &nbsp;&nbsp;• Ceiling light or chandelier 3 m up: usually <b>too far</b>
                Halogen bulbs get very hot. Keep the phone at least <b>30 cm</b> from the bulb.
            """,

            "Tips for a reliable check" to """
                • The light sensor is at the <b>top of the screen</b>, near the camera. Point that part at the bulb.
                • Lay the phone <b>flat and still</b>; don't hold it.
                • Keep shadows away: no hands, heads or people walking past.
                • Use <b>one light source</b>. Daylight from a window, a TV or LED lamps mixed in will dilute the wobble. Checks work best in the evening or with curtains closed.
                • Any phone case is fine as long as it doesn't cover the top of the screen.
                • If you get “Can't tell”, move closer to the bulb and try again.
            """,

            "When the app won't work" to """
                <b>Solar inverters and battery systems.</b> If your lights run through an inverter (Deye, Growatt, etc.), the inverter makes its own smooth power whatever feeds it. Every check would say Kahraba. Use your inverter's screen or app instead: kahraba usually shows a steadier frequency (close to 50.0 Hz) and often a higher voltage than the moteur.
                <b>UPS.</b> Same problem: a light plugged into a UPS gets smoothed power. Use a light on the normal house wiring.
                <b>Voltage stabilisers.</b> A whole-house stabiliser (regulator) may smooth out the wobble. If checks always say Kahraba even when you know it's the moteur, this may be why.
                <b>The wrong bulbs.</b> See “Which lights work”.
            """,

            "Your results" to """
                <b>Recent checks</b> shows your last checks; the list button in the bottom bar shows all of them. <b>Tap one to fix it</b> if you know it was wrong, or to delete it. Checks you confirmed or fixed are marked ✓.
                <b>How right is it?</b> counts how often each result was right, among the checks you confirmed.
                <b>Move cards:</b> press and hold any card below the Check button, then drag it up or down. Hide cards in Settings › Cards.
                <b>Can't tell</b> means the light was too dim, the phone was moved, or there was no light reading. Nothing is guessed.
                Tip: a wrong <b>Moteur</b> result is rare (kahraba doesn't wobble), but a shadow or moving the phone can cause one. “No flicker” on the moteur happens when the moteur is calm, or the light can't show the wobble (LED, too dim, inverter, UPS).
            """,

            "Always-on monitor (optional)" to """
                For a spare phone that stays in one place. Open it from the card at the bottom of the main screen.
                • Plug the phone into a <b>wall socket</b> (not a UPS) and leave it face-up under a halogen light. The screen can be off.
                • A power cut is seen through the charger and means the moteur took over.
                • It says Kahraba is back when the light gets clearly <b>brighter</b> (no blackout) and stays steady for 2 minutes – kahraba's higher voltage makes the bulb brighter. Steady light alone is not enough, because a calm moteur is steady too.
                • Under <b>Room</b>, pick the room the phone is in, then tap “It's kahraba” and “It's moteur” once each (when you're sure) so it learns how bright that room is on each. If quick checks already taught that room, it knows straight away. Then it can tell kahraba from moteur even when the light is steady. Keep the phone in the exact same place.
                • Keep other lights the same: switching a lamp on can look like the brightness change.
                • Every switch is logged with its time, and it beeps on each change. Night silence and beep volume are in Settings.
                • When the monitor is off it shows “Off”. After you start it, it shows “Not sure yet” until it sees a wobble or a power cut (moteur), matches a learned level, or you tap “It's kahraba” / “It's moteur”.
                • Tap “It's kahraba” or “It's moteur” when you're sure, so it learns your setup.
                • Unplugging the phone yourself looks like a power cut.
                • If the phone is picked up or moved, it stops judging the light until the phone has been still for 10 seconds (power cuts are still noticed). A move is never mistaken for kahraba coming back.
            """,

            "Privacy" to """
                Everything stays on this phone. Kahraba or Not has <b>no internet access</b>. Nothing is sent anywhere unless you tap Share or Send feedback yourself. Your area (Settings › Your area) is optional, chosen by you rather than from GPS, and only included in feedback emails. Uninstalling the app deletes all its data.
            """,

            "Disclaimer" to """
                Kahraba or Not gives an <b>estimate</b> based on how a light bulb behaves. It can be wrong, and results depend on your bulbs, wiring, generator and phone. It is provided as-is, without any guarantee of accuracy.
                • <b>Never use this app for safety decisions.</b> It cannot tell you whether wires are live or safe to touch. Always treat wiring as live, and leave electrical work to a qualified electrician.
                • Don't rely on it for medical equipment or anything where a wrong answer could cause harm or damage.
                • Halogen bulbs get very hot. Keep the phone and your hands away from them.
                • Kahraba or Not is not affiliated with Électricité du Liban (EDL) or any generator provider.
                Use at your own risk.
            """,
        )
    }
}
