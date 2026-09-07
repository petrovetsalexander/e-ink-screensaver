# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug      # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # unsigned APK → app/build/outputs/apk/release/
./gradlew installDebug       # build + install on the connected device
```

**Build with JDK 21.** `JAVA_HOME` on this machine points at JDK 25, which AGP 8.9 does not support; Android Studio uses its own JBR, so this only bites on the command line:

```bash
JAVA_HOME="$HOME/.jdks/jbr-21.0.11" ./gradlew assembleDebug
```

The toolchain is pinned to the Android 14 target: Gradle 8.11.1 (via the wrapper in the repo root), AGP 8.9.2, Kotlin 2.1.20, Java 17 bytecode, `compileSdk`/`targetSdk` 34, `minSdk` 28. A system Gradle 9.3.1 is on PATH — ignore it, the wrapper is the supported entry point.

Versions live in `gradle/libs.versions.toml`; `app/build.gradle.kts` references them through `libs.*` aliases, so bump versions there, not in the module file. **The androidx versions are capped by `compileSdk = 34`** — core-ktx 1.15+, appcompat 1.7.1+, and activity 1.10+ all require compileSdk 35 and fail the build outright. Raising them means moving off the Android 14 compile target first.

No tests, no lint configuration, no CI — there is no "run a single test" command, and `lint.abortOnError` is off. Verification is manual: install on a device and watch logcat.

```bash
adb logcat -s ScreenSaverSvc LockScreenAct EinkCompat NetworkFetcher WeatherFetcher NewsFetcher BookmateFetcher ImageCache
```

## Project Overview

E-Ink Screensaver is an Android app (Kotlin, minSdk 28, targetSdk/compileSdk 34) that draws an information dashboard — clock, weather, news, sticky notes, currently-reading book — over the lock screen of an e-ink device, then lets the panel go to sleep holding that image at zero power.

Package: `com.eink.screensaver`. Dependencies are only `core-ktx`, `appcompat`, and `material` (RecyclerView arrives transitively through Material). No DI, no coroutines (background work uses `kotlin.concurrent.thread`), no view binding (`findViewById` everywhere), no Room/DataStore — everything persists in one `SharedPreferences` file.

## Architecture

### Screen-off → draw → sleep cycle

`ScreenSaverService` is a `specialUse` foreground service that owns the whole cycle:

1. `ACTION_SCREEN_OFF` arrives → `onScreenOff()` acquires a `SCREEN_DIM_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP` (5 s), waits 200 ms for the screen to actually be on, then `startActivity(LockScreenActivity)` directly.
2. `LockScreenActivity` sets `window.screenBrightness = 0.0f` **before** anything else (so the e-ink frontlight never flashes), `setShowWhenLocked` + `setTurnScreenOn`, and immersive fullscreen.
3. The activity flashes the decor view black for 100 ms, then white, then draws — this forces a full GC16 panel refresh that clears ghosting, replacing the older random-offset anti-ghosting trick.
4. The wake lock expires, the device sleeps, the e-ink panel retains the image.
5. Two `AlarmManager` alarms wake the service periodically (see below).
6. Unlock is detected by polling `KeyguardManager.isDeviceLocked` every 1000 ms, and only while the screen is on (`SCREEN_ON`/`SCREEN_OFF` start/stop the poll). Broadcast-based detection proved unreliable. On unlock: short vibration, then finish.

**This branch deliberately does not use `fullScreenIntent`** (see branch name `no_fullScreenIntent`). The service launches the activity from the background directly, relying on the wake lock. Residue of the old approach still exists and is intentionally kept: the `USE_FULL_SCREEN_INTENT` permission, the `CHANNEL_LOCKSCREEN_ID` channel, `LOCKSCREEN_NOTIFICATION_ID` cancel calls, and the full-screen-intent permission gate in `SettingsActivity`. Only the *posting* side is gone.

### The two alarms

Both are `PendingIntent.getService` back into `ScreenSaverService`, dispatched by `action` in `onStartCommand`:

- **`ACTION_UPDATE_CLOCK`** (`ALARM_REQUEST_CODE`) — every `update_interval_minutes`. Takes a 3 s screen wake lock, broadcasts `LockScreenActivity.ACTION_UPDATE_DISPLAY`, reschedules itself. Uses `setExactAndAllowWhileIdle` when exact alarms are permitted, `setAndAllowWhileIdle` otherwise.
- **`ACTION_FETCH_DATA`** (`FETCH_REQUEST_CODE`) — interval is the **minimum** of the enabled sources' intervals (weather, news, Bookmate's fixed 60 min). Takes a 20 s `PARTIAL_WAKE_LOCK`, runs all fetches on one raw thread, and each fetcher is additionally gated on its own cache age, so a shared short interval doesn't over-fetch a slow source. Reschedules in `finally`. Also fired eagerly from `onScreenOff` via `triggerDataFetchIfStale`.

Fetched data never travels in the intent — it lands in `SharedPreferences` and the service broadcasts `ACTION_DATA_UPDATED`, which `LockScreenActivity` turns into a redraw. All internal broadcasts are `setPackage(packageName)`-scoped but receivers register with `RECEIVER_EXPORTED` (needed for the system `SCREEN_*`/`USER_PRESENT` actions in the same filter).

### Module system

The lock screen is a vertical stack of five modules — `clock`, `weather`, `news`, `notes` (rendered as sticker cards), `book` — each with an enable flag, its own font size, and a user-defined order (`modules_order`, default `clock,weather,news,notes,book`).

`activity_lockscreen.xml` declares the sections in default order; on every `updateDisplay()` `reorderModules()` removes and re-adds them into `mainContainer` following the saved order, then each module's block decides `VISIBLE`/`GONE` from its toggle plus whether its cache holds usable data. Sticker cards and their rows are built programmatically (`buildStickerView`), as is the reorderable list in `ModuleSettingsActivity`.

**Adding or renaming a module touches all of:** `PrefsManager` (toggle + font-size keys, `DEFAULT_MODULES_ORDER`), a section in `activity_lockscreen.xml`, `LockScreenActivity.getModuleView()` and the corresponding block in `updateDisplay()`, the three `when` blocks in `ModuleSettingsActivity` (`isModuleEnabled` / `setModuleEnabled` / `moduleDisplayName`), a `<activity>` entry in the manifest for its settings screen, and strings in **both** `values/` and `values-ru/`.

### Data layer (`data/`)

Plain objects, no framework. `NetworkFetcher.fetchString` is the shared `HttpURLConnection` GET (10 s connect / 15 s read, `EinkScreensaver/1.0` UA) and returns `null` on any failure — every fetcher propagates that null rather than throwing.

- `WeatherFetcher` → OpenWeatherMap current + forecast (`lang=ru`, metric); needs a user-supplied city and API key. `WeatherData` lives in the same file.
- `NewsFetcher` → RSS via `XmlPullParser`; body text is taken from the first matching tag in the user-configurable `news_body_tags` priority list (default `rbc_news:full-text,description`, matching both qualified and local names), then HTML-stripped. `itemsFromJson` still accepts the legacy plain-string-array cache format.
- `BookmateFetcher` → Bookmate v5 API, most recent book for a user id.
- `ImageCache` → book covers as PNGs in `filesDir/img_cache`, filename is the URL hash; presence of the file is the whole cache policy (never invalidated).

Each fetcher's result is serialized to JSON by hand (`org.json`) and stored in prefs alongside a `*_cache_time_ms` timestamp; the lock screen only ever reads the cache, never the network.

### Settings UI

- `MainActivity` (launcher) is **not** the settings screen — it is the stickers/notes editor: create/rename/delete stickers, add/complete/delete notes inside the selected one, toggle per-sticker visibility, plus a manual sync button that runs all fetchers on demand. Stickers are a `JSONArray` in prefs (`notes_stickers_json`), with a one-time migration from the older flat `notes_json`.
- `settings/SettingsActivity` — service on/off, permission gates, language, entry to modules, about/how-it-works dialogs.
- `settings/ModuleSettingsActivity` — drag-to-reorder (`ItemTouchHelper`) + enable checkboxes, and navigation into the five per-module screens.
- `settings/{Clock,Weather,News,Notes,Book}SettingsActivity` — one screen per module. All of them follow the same shape: read prefs into widgets in `onCreate`, write back on change (`if (fromUser)` for SeekBars). There is no save button and no validation layer.

`PrefsManager` is a stateless `object` — every accessor takes a `Context`. It is the single source of truth; nothing else calls `getSharedPreferences`. Note the "enabled" concepts are distinct: `isEnabled` (service running), `isBlockXEnabled` (module shown), and `isWeatherEnabled`/`isNewsEnabled`/`isBookmateEnabled` (source is *configured*, derived from whether its credentials/URL are set).

### Localization

Default strings are English (`values/`), with a Russian translation (`values-ru/`) and `locales_config.xml`. Language is switched at runtime through `AppCompatDelegate.setApplicationLocales` in `SettingsActivity`, with `AppLocalesMetadataHolderService` in the manifest providing pre-Android 13 storage. Add every new string to both files. The date line on the lock screen is hardcoded to `Locale("ru")`; times use `Locale.getDefault()`.

## E-Ink Specific Considerations

- Pure black on pure white, no gradients or shadows; `section_border` is a hairline rectangle.
- Brightness is pinned to `0.0f` by the activity (there is no brightness preference any more).
- Every redraw begins with the black→white flash for a full panel refresh; keep this in mind before adding partial-update paths.
- The update interval is a battery/ghosting trade-off, not a UI nicety — each tick costs a wake lock and a panel refresh.

### The vendor layer (`EinkCompat`)

`EinkCompat` is a reflection bridge to Bigme's undocumented xrz framework (`xrz.framework.manager.XrzEinkManager` / `XrzEinkManagerInternal`), mapped in [imedwei/inksdk](https://github.com/imedwei/inksdk)'s `docs/bigme-sdk-reverse-engineered.md`. The classes are reachable from an ordinary app UID with no permission; `EinkCompat` falls back to a `PathClassLoader` over `/system/framework/xrz.framework.server.jar`, then to no-op. Check `EinkCompat.isSupported` and keep the portable path alive next to every vendor call — an OTA can remove this API without warning.

Two things it buys:

- **Hardware frontlight.** `dimFrontlight()` / `restoreFrontlight()` drive the panel light directly, so `ScreenSaverService.onScreenOff()` can kill it *before* any window of ours exists — `window.screenBrightness = 0.0f` only applies once `LockScreenActivity`'s window is added, which is too late to stop the system's dim level from flashing the light. The user's level is saved in prefs (`saved_frontlight_level`, sentinel `PrefsManager.NO_SAVED_FRONTLIGHT`) so a process death can't strand the light at 0; the service restores it in `onCreate`. Restore paths: the unlock poll in `cancelNotificationAndFinish()` (the reliable one), `USER_PRESENT`, `ACTION_STOP`, `onDestroy`.
- **Real panel refresh.** `forceGlobalRefresh(MODE_CLEAN)` replaces the black→white flash on the vendor path, saving two full frames and 100 ms of wake lock per launch. The window is also pinned to `MODE_GC16` via `setWindowRefreshMode`. Waveform constants (`MODE_DU`, `MODE_A2`, `MODE_REGAL`, …) are in `EinkCompat` if a partial-update path is ever wanted.

Verify the device before assuming any of it works: `adb shell service list | grep -iE 'xrz|handwritten'`.

## Gotchas

- The clock-interval SeekBar in `activity_clock_settings.xml` ranges 0–30 and labels 0 as "disabled", but `scheduleClockAlarm()` does not special-case 0 — it would schedule an alarm 0 ms out and spin. Handle 0 explicitly if you touch that path.
- `LockScreenActivity.isActive` is a static guard the service checks before launching; keep it accurate if you add finish paths.
- Cover-image loading and all fetches post results back through `handler.post`, and check the module toggle again on the main thread — the user may have disabled the module while the thread ran.
- `PrefsManager` has a hardcoded default Bookmate user id.
