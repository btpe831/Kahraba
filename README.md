# Karaba

An Android app that tells you whether the house is on **kahraba** (EDL grid) or the **moteur**
(neighbourhood generator), and logs every switch.

## How it works

- **Power cuts:** the phone sits on its charger. A short loss of charging is a power cut, and in
  Lebanon a cut means the moteur took over.
- **Silent switch back to kahraba:** halogen bulbs wobble slightly on the moteur and stay steady on
  kahraba. The light sensor watches for that. After the light has been steady for a while
  (2 minutes by default), the app says kahraba is back.

## Alerts

- Kahraba back: 4 quick beeps, pause, 4 beeps, pause, 4 beeps, played twice.
- Moteur took over: a 3-second beep, repeated after 1 and 2 minutes.
- Alerts play on the alarm channel, so they sound when the phone is on silent.
  Night silence can be turned on in Settings.

## Features

- Always-on monitoring in the background (screen can be off).
- Quick check: lay the phone under a halogen light for a one-off verdict. It stops as soon as it is
  sure: moteur within a few seconds, kahraba after 25 seconds of steady light.
- "It's kahraba" / "It's moteur" buttons: tell the app when you're sure (e.g. from the lamp post)
  and it learns what each source looks like over 2 minutes.
- Switch log with how long each state lasted.
- Per-second light recordings (last 7 days) that can be shared for tuning.

## Setup

1. Install the APK from the latest release on GitHub.
2. Plug the phone into a wall socket (not the UPS) and lay it face-up under a halogen light.
3. Tap **Start monitoring** and **Allow running in background**.

## Build

GitHub Actions builds and tests the app on every push and publishes the APK as a release.
Locally: `./gradlew testDebugUnitTest assembleDebug`.
