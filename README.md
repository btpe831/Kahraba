# Kahraba or Not

An Android app for homes in Lebanon: is the house on **kahraba** (EDL, the state grid) or the
**moteur** (the neighbourhood generator)? The switch back to kahraba is often silent. This app
tells you, using your phone's light sensor and a halogen bulb.

**[Download the latest version](https://github.com/btpe831/Kahraba/releases/latest)** (the `.apk` file under Assets)

## Install

1. On the Android phone, open the link above and tap `KahrabaOrNot-x.y.z.apk`.
2. Open the file. Android warns about apps from outside the Play Store; allow
   "Install unknown apps" for your browser if asked.
3. Updates: install the newer `.apk` the same way. Your data stays.

## What you need

- An Android phone with a light sensor (almost all have one).
- A **halogen** or old-style **filament** bulb. LED bulbs smooth out the power and hide the
  difference, so they don't work.

## How it works

A halogen bulb's brightness follows the voltage. On the moteur, the voltage wobbles as the
engine speeds up and slows down, and so does the light. Kahraba is steadier and usually a bit
higher, so the same bulb glows steady and usually 10–25% brighter (it varies from day to day).

- **Quick check:** lay the phone face-up under the bulb. A wobble means moteur. To say "kahraba"
  for sure, the app learns how bright a room is on each source (two checks to set up).
- **Always-on mode:** for a spare phone left on its charger under a light. A power cut means
  the moteur took over; a jump in brightness means kahraba is back. It beeps on each switch.
- **Accuracy:** the app keeps score of the results you confirm, so you can see how far to trust
  it in your home.
- Also: a light meter with CSV export, an amps calculator for your moteur subscription, a
  calendar with notes, backup and restore.

## Privacy

The app has **no internet permission**: nothing you do in it leaves your phone. Backups are files
you save yourself.

## Is this the real app?

Every release is signed with the same key. Its certificate SHA-256 is:

```
77:E9:3F:A7:E7:C9:4C:4E:53:04:DA:05:50:BC:84:44:26:94:97:7A:96:50:E5:2D:92:6B:AB:F0:FE:5F:67:76
```

## Disclaimer

The app makes an educated guess from the light. It may be inaccurate. Don't rely on it for anything
where an incorrect result is dangerous or costly.

Created by Aubrey Arenas.

© 2026 Aubrey Arenas. All rights reserved. The code is public so you can see what the app does;
see [LICENSE](LICENSE) for what that allows.
