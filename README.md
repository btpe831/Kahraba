# Ijit

An Android app (repo: Karaba) that answers "ijit el kahraba?": is the house on **kahraba** (EDL grid) or the **moteur**
(neighbourhood generator), and logs every switch.

## How it works

- **Power cuts:** the phone sits on its charger. A short loss of charging is a power cut, and in
  Lebanon a cut means the moteur took over.
- **Silent switch back to kahraba:** halogen bulbs wobble a lot on the moteur and stay steady on
  kahraba. The light sensor watches for that. After the light has been steady for a while
  (2 minutes by default), the app says kahraba is back.

## Quick check (main screen)

Tap **Check now**, lay the phone face-up under a halogen light and don't touch it. It beeps the
answer straight away: **4 short beeps = kahraba**, **1 long beep = moteur**. Moteur is usually
found within a few seconds; kahraba needs 25 seconds of steady light.

## Always-on monitor (secondary)

Alerts:

- Kahraba back: 4 quick beeps, pause, 4 beeps, pause, 4 beeps, played twice.
- Moteur took over: a 3-second beep, repeated after 1 and 2 minutes.
- Alerts play on the alarm channel, so they sound when the phone is on silent.
  Night silence can be turned on in Settings.

## Features

- Always-on monitoring in the background (screen can be off).
- "It's kahraba" / "It's moteur" buttons: tell the app when you're sure (e.g. from the lamp post)
  and it learns what each source looks like over 2 minutes.
- Switch log with how long each state lasted.
- Per-second light recordings (last 7 days) that can be shared for tuning.

## Limits

- Needs a halogen or old-style filament bulb. Most LED and energy-saving bulbs hide the flicker.
- Doesn't work if the lights run through a solar inverter or UPS: they output smooth power whatever
  feeds them, so every check would say kahraba.

## Setup

1. Install the APK from the latest release on GitHub.
2. Plug the phone into a wall socket (not the UPS) and lay it face-up under a halogen light.
3. Tap **Start monitoring** and **Allow running in background**.

## Build

GitHub Actions builds and tests the app on every push and publishes the APK as a release.
Locally: `./gradlew testDebugUnitTest assembleDebug`.
