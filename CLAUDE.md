# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug        # Build debug APK
./gradlew assembleRelease      # Build release APK
./gradlew installDebug         # Build and install on connected device
```

No tests or lint configurations exist in this project.

## Project Overview

E-Ink Screensaver is an Android app (Kotlin, minSdk 28, targetSdk 34) that displays a clock on the lock screen, optimized for e-ink displays. The UI is in Russian.

Package: `com.eink.screensaver`

## Architecture

The app uses a foreground service + fullScreenIntent pattern to display a clock over the lock screen. This is the only Google-approved way to launch an Activity from background on Android 10+.

**Core flow:**
1. `ScreenSaverService` (foreground service) listens for `SCREEN_OFF`
2. On screen off: acquires a 5-second WakeLock, posts a notification with `fullScreenIntent`
3. System launches `LockScreenActivity` over the lock screen
4. Activity sets brightness to 0 (so e-ink refreshes without frontlight flash), draws the clock
5. WakeLock expires, screen sleeps, e-ink retains the clock image
6. Unlock detection: polls `KeyguardManager.isDeviceLocked` every 300ms (broadcast-based detection is unreliable)

**Key classes (all in `app/src/main/java/com/eink/screensaver/`):**

- `ScreenSaverService` — Foreground service. Registers `SCREEN_OFF`/`SCREEN_ON`/`USER_PRESENT` receivers. Posts fullScreenIntent notifications to launch the lock screen activity. Manages WakeLock lifecycle.
- `LockScreenActivity` — Full-screen activity shown over lock screen. Renders clock with anti-ghosting offset (random ±40px shifts). Blocks all touch input; unlock only via fingerprint sensor. Polls for unlock state.
- `MainActivity` — Settings UI. Toggle service on/off, configure update interval (1/2/5 min), brightness, date display. Handles runtime permission requests (notifications, full-screen intent on Android 14+).
- `PrefsManager` — SharedPreferences wrapper (enabled state, update interval, brightness, show date).
- `BootReceiver` — Starts the service on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` if enabled in prefs. Also provides static `startService()`/`stopService()` helpers used by MainActivity.

## E-Ink Specific Considerations

- White background + black text for maximum e-ink contrast
- Clock position shifts randomly on each update to prevent ghosting/burn-in
- Brightness set to 0.0f to avoid frontlight flash during e-ink refresh
- Configurable update interval to reduce e-ink refreshes and save battery
- WakeLock is brief (5s) — just enough for e-ink to complete its refresh cycle

## Permissions

The app requires: `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`. On Android 14+, `USE_FULL_SCREEN_INTENT` requires explicit user grant through system settings.