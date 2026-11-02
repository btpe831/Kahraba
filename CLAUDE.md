# Kahraba or Not: project notes

Android app (Kotlin, no Compose) that tells whether a home in Lebanon is on **kahraba** (EDL, the
state grid) or the **moteur** (neighbourhood diesel generator), using the phone's light sensor under
a halogen/filament bulb, plus the charger for power cuts. Owner: Aubrey Arenas (feedback address in
the app: aubs115@duck.com). Public repo, © 2026 Aubrey Arenas, all rights reserved (see LICENSE).

## Working with the owner (follow these)

- **Confirm before changing anything.** Propose first; build only after a yes.
- **Always confirm the version number before pushing** an app change (pushing a new version
  publishes a release). Small updates go up to 50 before the next jump: 1.3.01, 1.3.02 … 1.3.50, then
  1.4.0, 1.4.01 … 1.4.50, then 1.5.0. Bigger changes: say so and suggest a bigger jump. The version is
  `appVersion` in `gradle.properties` (two digits after the last dot: 1.3.02, not 1.3.2).
- Write for a non-developer, on a phone: short, plain, polished English. Avoid "wrong", "fix it"
  wording in the app (use "correct", "incorrect"). Say "the app" (not "the monitor"); the feature is
  called **Always-on mode** (renamed from "Always-on monitor").
- Times shown in the app use am/pm ("h:mm a", e.g. 8:09 PM); 24-hour only in exported files.
- Never put AI model names in commits or files. Commit trailers come from the session.
- Work on branch `claude/lebanon-power-source-detection-5k9xqd`; `main` is behind (merge only when asked).

## Build, release, tests

- No Android SDK in the cloud session. GitHub Actions (`.github/workflows/build.yml`) runs the unit
  tests and builds a release APK signed with `app/release-key.p12` (encrypted; password in the
  `SIGNING_PASSWORD` secret; cert SHA-256 77:E9:3F:A7…5F:67:76, checked in CI). versionCode = run number.
- A release is published only when `appVersion` changed since the last release tag
  (`.github/release_notes.py`). Release notes = the version's section in
  `app/src/main/assets/whats_new.txt` (also shown in the app's "What's new" window after an update;
  a test checks the current version has one). Fallbacks: `.github/notes/<version>.md` (1.0.0), else
  commit subjects/bodies since the last tag.
- **Release notes: just what's improved, in plain words. No dates, times or recording details.**
- Download link for testers: https://github.com/btpe831/Kahraba/releases/latest
- Detection logic is pure Kotlin (`PowerDetector.kt`, `QuickCheck.kt`, `CheckHistory.kt`,
  `CardOrder.kt`, `Area.kt`, `OldDefaults.kt`) and is tested with JUnit in `app/src/test`. To run them
  locally without the SDK, a plain Kotlin/JVM Gradle project can compile those files plus tiny stubs
  for `android.content.Context`/`SharedPreferences` and `Prefs` (constants + `sp()`).
- Android code (activities, layouts) can only be compiled in CI: read diffs carefully before pushing.

## What the recordings taught us (all in app/src/test/resources, replayed by tests)

- Halogen brightness follows voltage strongly (~V^3.4). Moteur: voltage wobbles as the engine hunts;
  kahraba steadier and usually higher (234 V vs 213-223 V measured), so the bulb is brighter, but by
  very different amounts: 25-30% (26 Oct), 9-14% (28 Oct evening), 7-10% (31 Oct), 20% (29 Oct am).
- A calm moteur can be perfectly steady for 20+ minutes (nights). Steady light proves nothing.
- The moteur creeps brighter in the evening and in small steps on busy afternoons; it reached
  kahraba's level after 8 pm on 28 Oct. Hence rooms use the moteur's *brightest* level and need a
  15% gap, and "No flicker" says "Kahraba, or a calm moteur".
- Sensors: Pixel 2 reports changes of ~5% and up; Pixel 9 (under-display) ~8% and up. Both keep
  reading with the screen off (Pixel 2 confirmed over 43 h).
- Power: every "kahraba cut -> moteur" in the logs lasted 61-78 s (the moteur takes ~1 min to start).
  Switches back to kahraba are either silent (light jumps) or a short blink (10 s on 31 Oct 04:41).
- Fixtures: `recording-2026-10-26.csv`, `calm-moteur-2026-10-26.csv` (first evening);
  `oct28-pixel2/pixel9-side-by-side.csv` (moteur, kahraba 18:32:40-18:50:09, dark, moteur from 19:21:49);
  `oct28-night-pixel2-kahraba-cut-moteur.csv`, `oct29-morning-pixel2-moteur-kahraba.csv` (switch 06:29:43);
  `oct30-night-pixel2-raw-blink-to-kahraba.csv` (moteur; 10 s blink 04:41:41 -> kahraba; 77 s cut 06:58 -> moteur);
  `oct31-day-pixel2-raw-calm-kahraba.csv` (busy moteur ~3150; kahraba 13:00 settling in steps, dead still;
  7.5 min outage 13:22 back as kahraba; 21 min outage 15:00 back as moteur; phone fell 18:01:12).
  `nov01-morning-pixel2-raw-kahraba-cut-moteur.csv` (kahraba 231 V set 08:12:59, ~4,400-4,700 lux dead still;
  1m 13s cut 10:00:12 -> moteur 213 V, ~3,100-3,500 wobbling: kahraba ~40% brighter).
- 31 Oct 13:00-15:00 was only 15-24% above the moteur (vs 40% on 1 Nov), but its wobble pattern (<= 1
  episode per 5 min) matches kahraba, so very likely kahraba. Still: ask for a voltmeter photo at alerts.
  `nov01-evening-pixel2-raw-kahraba-cut-calm-moteur.csv` (from 14:00: busy moteur; kahraba 16:01, ~4,600
  still; cut 18:00:14 for 1m 14s; moteur 222 V, pole light green, ~3,600-4,150 calm: 1.2.04 false kahraba 18:37).
  `nov02-night-pixel2-raw-kahraba-cut-moteur-kahraba.csv` (from 21:00 1 Nov: moteur 223 V; kahraba 01:01 ~3,600
  still; cut 03:30:33 for 1m 14s (heater reset); moteur ~2,600-3,200 wobbling, 227 V at 6 AM = as bright as the
  later kahraba; kahraba 09:59 at 227-228 V under 8 A. Same voltage = same light: only wobble separates them).
- Kahraba is dead still (zero wobble for 20+ min); a daytime moteur wobbles most minutes. In the
  evening a moteur also calms down and creeps brighter, but gradually (30 Oct 20:40-21:45).
- Voltmeter: moteur 208 V (31 Oct, 8 pm) to 223 V; kahraba 231-234 V (1 Nov morning too).
- **Ignore the Pixel 2 Always-on data from the night of 31 Oct - 1 Nov**: it was under an LED (owner only
  wanted power-cut beeps), so its light readings and kahraba/moteur labels mean nothing.
- The 28-30 Oct recording (unattended, 1/s files only) has no ground truth; replays suggested a real
  switch at 29 Oct 16:34 and a likely one at 30 Oct 12:08, and showed 10% was too low a threshold
  (false kahraba at 29 Oct 14:10 from moteur creep).

## Always-on mode rules (PowerDetector)

- Charger off >= 30 s, then back: moteur took over (> 5 min: "power off", then "assuming moteur").
- Charger off < 30 s (a blink): keep history; after 2 min steady, light >= 5% brighter than before
  -> kahraba, >= 5% dimmer -> moteur, else no change (1.1.04).
- Silent switch: when on moteur and the light has been steady 2 min, trace the current level back to
  where it started, skip 45 s of switching, compare with the 3-min level before: rise 12-60% ->
  kahraba (1.1.02 tried 10%: false alerts).
- Wobble stops (1.2.0): the moteur was busy (>= 10 wobbling seconds in a minute; its level = median
  of the 10 min before), then 5 min of completely still light >= 10% brighter -> kahraba. The still
  stretch must start within 5 min of the last busy moment, or after a power cut/outage since then.
- Phone moved (1.2.0): motion, then the charger off within 10 s -> not a cut: "Phone moved – please
  check" alert; carries on when plugged back in. Power back after an outage: alert with
  "It's kahraba" / "It's moteur" buttons.
- All beeps at 3000 Hz (kitchen timer); rhythm tells them apart.
- Power cuts only (1.2.03, `Prefs.POWER_CUTS_ONLY`; switch on the screen). Starting asks "Choose an option"
  (1.2.04, `Prefs.monitorLight`): Halogen or filament bulb / LED / Other light / Power cuts only; all but
  halogen turn power cuts only on. Light ignored, nothing learned, kahraba/moteur buttons hidden. States POWER_ON / NO_POWER; logs
  "Power went off" and "Power back after X" (blinks too); 1 long beep off, quick beeps back.
- Kahraba confirmation (1.2.05): every light rule only *proposes* kahraba; the alert comes after 5 more
  minutes unless the light wobbles 3+ separate times (episodes after >= 5 calm s). Kahraba never had more than
  1 episode in 5 min; moteur 3+, even calm (1 Nov 18:37, 222 V). The transition is dated when kahraba came on
  (level start / power back / blink), so the NOW card and alert show "Kahraba since 4:01 PM".
- Learned-level rule ("Light matches your kahraba level") only from "Not sure yet", never moteur -> kahraba
  (1.2.05): it called the bright 222 V evening moteur kahraba (1 Nov 18:37; `EveningMoteurTest`).
- Only works from a known source: if it shows "Not sure yet", tap "It's moteur".

## Kahraba pattern card (1.3.0, `KahrabaPattern.kt`, `card_pattern`)

- From the switch log: 7 day bars (kahraba/moteur/no power; blank = not running), typical kahraba length
  (median of the app's own kahraba calls ended by a cut), gap between kahraba starts (only with Always-on
  running throughout), next estimate = last start + gap ("around", or a window if gaps differ > 2 h; hidden
  when passed). Needs 3 of each. "Set by you" starts don't count; a kahraba corrected by "Set by you" is dropped.
- Overview (1.3.01): `KahrabaPattern.today/week` write plain sentences (kahraba time and when, power cuts,
  how long Always-on ran). "Always-on mode stopped" time is not a span (blank bars).
- Seen so far: kahraba in ~2-2.5 h blocks, back ~8-9 h after the last start, cuts often at round times.

## Status (1 Nov 2026)

- Released: 1.3.02 (pattern card colour key; default card order: advice, pattern, recent, history, tools,
  accuracy, wobble, facts; privacy line: only light sensor and charger). 1.3.01: today and week overview.
- Mockup (not built): Shazam-style calm home, https://claude.ai/artifact/PZfEztQWriu3wSmfZLeVRs (one big
  Check button tinted by state, "Always-on: active/inactive" chip, swipe up for Your power, gear for Tools;
  power off greys the button). Would be 1.4.0; ask before building. 1.3.0: Kahraba pattern card. 1.2.05: kahraba confirmed for 5 min; level rule only from Not sure yet. 1.2.04: "Choose an option" light picker. 1.2.03: power cuts only. 1.2.02: am/pm times. 1.2.01: "What's new" window. 1.2.0: wobble-stops rule, phone-moved alert, power-back
  buttons, timer-pitch beeps.
- Shared with testers online. Play Protect asks to scan: testers tap More details > Install anyway.
- Ideas not built (ask first): "Now kahraba / Now moteur" label buttons in the Light meter;
  per-phone sensor calibration; Did you know? notifications (opt-in); voltage smart plug support
  (needs internet permission, breaks the "no internet" promise); remote alerts (MacroDroid + ntfy
  works today without app changes).
