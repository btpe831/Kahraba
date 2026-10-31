# Kahraba or Not: project notes

Android app (Kotlin, no Compose) that tells whether a home in Lebanon is on **kahraba** (EDL, the
state grid) or the **moteur** (neighbourhood diesel generator), using the phone's light sensor under
a halogen/filament bulb, plus the charger for power cuts. Owner: Aubrey Arenas (feedback address in
the app: aubs115@duck.com). Public repo, © 2026 Aubrey Arenas, all rights reserved (see LICENSE).

## Working with the owner (follow these)

- **Confirm before changing anything.** Propose first; build only after a yes.
- **Always confirm the version number before pushing** an app change (pushing a new version
  publishes a release). Small updates: 1.0.01 … 1.0.10, then 1.1.0, 1.1.01 … Bigger changes: say so
  and suggest a bigger jump. The version is `appVersion` in `gradle.properties`.
- Write for a non-developer, on a phone: short, plain, polished English. Avoid "wrong", "fix it"
  wording in the app (use "correct", "incorrect"). Say "the app" (not "the monitor"); the feature is
  called **Always-on mode** (renamed from "Always-on monitor").
- Never put AI model names in commits or files. Commit trailers come from the session.
- Work on branch `claude/lebanon-power-source-detection-5k9xqd`; `main` is behind (merge only when asked).

## Build, release, tests

- No Android SDK in the cloud session. GitHub Actions (`.github/workflows/build.yml`) runs the unit
  tests and builds a release APK signed with `app/release-key.p12` (encrypted; password in the
  `SIGNING_PASSWORD` secret; cert SHA-256 77:E9:3F:A7…5F:67:76, checked in CI). versionCode = run number.
- A release is published only when `appVersion` changed since the last release tag
  (`.github/release_notes.py`). Notes = commit subjects/bodies since the last tag, minus commits
  touching only `.github/`; `.github/notes/<version>.md` replaces them (used for 1.0.0).
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
  `oct30-night-pixel2-raw-blink-to-kahraba.csv` (moteur; 10 s blink 04:41:41 -> kahraba; 77 s cut 06:58 -> moteur).
- The 28-30 Oct recording (unattended, 1/s files only) has no ground truth; replays suggested a real
  switch at 29 Oct 16:34 and a likely one at 30 Oct 12:08, and showed 10% was too low a threshold
  (false kahraba at 29 Oct 14:10 from moteur creep).

## Always-on mode rules (PowerDetector)

- Charger off >= 30 s, then back: moteur took over (> 5 min: "power off", then "assuming moteur").
- Charger off < 30 s (a blink): keep history; after 2 min steady, light >= 5% brighter than before
  -> kahraba, >= 5% dimmer -> moteur, else no change. (In 1.1.04, held, see below.)
- Silent switch: when on moteur and the light has been steady 2 min, trace the current level back to
  where it started, skip 45 s of switching, compare with the 3-min level before: rise 12-60% ->
  kahraba (1.1.02 tried 10%: false alerts).
- Only works from a known source: if it shows "Not sure yet", tap "It's moteur".

## Status (31 Oct 2026)

- Released: 1.1.03. Held, committed but NOT pushed: 1.1.04 (blink handling above, and "Always-on
  monitor" renamed to "Always-on mode" / "the app"). Push only when the owner says so.
- Shared with testers online. Play Protect asks to scan: testers tap More details > Install anyway.
- Ideas not built (ask first): "Now kahraba / Now moteur" label buttons in the Light meter;
  per-phone sensor calibration; Did you know? notifications (opt-in); voltage smart plug support
  (needs internet permission, breaks the "no internet" promise); remote alerts (MacroDroid + ntfy
  works today without app changes).
