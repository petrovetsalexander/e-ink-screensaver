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

**After every `adb install -r`, re-enable the accessibility service.** The reinstall kills it, `AccessibilityManagerService` files it under `Crashed services:` and does not rebind — `dumpsys accessibility` then shows `Bound services:{}` with ours still listed under `Enabled services`. The app degrades exactly as designed (`canUseVendorWake()` false → CPU-only wake lock → the activity wakes the screen itself), except that on this device the fallback does not work either: `appops` shows both `SYSTEM_ALERT_WINDOW` and `TURN_SCREEN_ON` rejected for this package, so the screen never comes on and `startActivity` from the service is refused with `Background activity launch blocked … (BAL_BLOCK)`. The symptom is a power press that turns the backlight off and shows nothing at all, with no error in the app's own log — the give-away is `WakeLock acquired (CPU only)` followed by silence from `LockScreenAct`. Toggling the service in Settings fixes it; over adb:

```bash
adb shell settings put secure enabled_accessibility_services '""'
adb shell settings put secure enabled_accessibility_services com.eink.screensaver/com.eink.screensaver.SleepAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

The durable fix, if this gets annoying, is `SYSTEM_ALERT_WINDOW` — a granted overlay permission is a documented BAL exemption and would make the launch work with the accessibility service in any state.

## Project Overview

E-Ink Screensaver is an Android app (Kotlin, minSdk 28, targetSdk/compileSdk 34) that draws an information dashboard — clock, weather, news, sticky notes, currently-reading book — over the lock screen of an e-ink device, then lets the panel go to sleep holding that image at zero power.

Package: `com.eink.screensaver`. Dependencies are only `core-ktx`, `appcompat`, and `material` (RecyclerView arrives transitively through Material). No DI, no coroutines (background work uses `kotlin.concurrent.thread`), no view binding (`findViewById` everywhere), no Room/DataStore — everything persists in one `SharedPreferences` file.

## Architecture

### Screen-off → draw → sleep cycle

`ScreenSaverService` is a `specialUse` foreground service that owns the whole cycle:

1. `ACTION_SCREEN_OFF` arrives → `onScreenOff()` acquires a `SCREEN_DIM_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP` (5 s), waits 200 ms for the screen to actually be on, then `startActivity(LockScreenActivity)` directly.
2. `LockScreenActivity` sets `window.screenBrightness = 0.0f` **before** anything else (so the e-ink frontlight never flashes), `setShowWhenLocked` + `setTurnScreenOn`, and immersive fullscreen.
3. On launch the activity does one full panel refresh — `forceGlobalRefresh(MODE_CLEAN)` on the vendor path, a 100 ms black-then-white decor flash without it — which clears the ghosting left by whatever was on screen before the lock. Every redraw after that is partial; see the refresh schedule below.
4. The wake lock expires, the device sleeps, the e-ink panel retains the image.
5. Two `AlarmManager` alarms wake the service periodically (see below).
6. Unlock is detected by polling `KeyguardManager.isDeviceLocked` every 1000 ms, and only while the screen is on (`SCREEN_ON`/`SCREEN_OFF` start/stop the poll). Broadcast-based detection proved unreliable. On unlock: short vibration, then finish.

**The app does not use `fullScreenIntent`** (hence the old branch name `no_fullScreenIntent`). The service launches the activity from the background directly, relying on the wake lock. Every trace of the earlier approach is gone — the permission, the second notification channel, the cancel calls and the settings gate — so `POST_NOTIFICATIONS` is the only hard requirement, and only because a foreground service cannot run without a notification.


### Incoming calls

The lock screen has no call handling of its own — the dialer's full-screen intent simply launches over it, and `LockScreenActivity` is `showWhenLocked` + `singleTask` on its own `taskAffinity`, so it is stopped, not destroyed: `isActive` stays true, the receivers stay registered, and when the call ends the activity comes back through `onResume`. `wasBackgrounded`, set in `onStop`, promotes that redraw to a `fullRefresh()` — the in-call UI's pixels are still on the panel and no partial waveform clears them.

What the service must not do during a call is anything involving the screen, and three guards say so, all keyed off `isCallInProgress()` (the audio mode: RINGTONE / IN_CALL / IN_COMMUNICATION, chosen over `TelephonyManager.getCallState` because that needs READ_PHONE_STATE from Android 12 and still misses a Telegram call):

- `onScreenOff()` returns early — the proximity sensor does not broadcast SCREEN_OFF, but the power button and the screen timeout do, and drawing a lock screen over a ringing call is wrong every time. The clock alarm is still rescheduled so the cycle resumes afterwards.
- `onClockAlarmFired()` skips the wake and the redraw and only reschedules. Without this the tagged vendor wake fires under the in-call UI and the self-sleep behind it takes the screen off whoever is on the phone.
- The `scheduleSelfSleep()` runnable treats a call started inside its delay as the user being present.

### The two alarms

Both are `PendingIntent.getService` back into `ScreenSaverService`, dispatched by `action` in `onStartCommand`:

- **`ACTION_UPDATE_CLOCK`** (`ALARM_REQUEST_CODE`) — every `update_interval_minutes`. Takes a 3 s screen wake lock, broadcasts `LockScreenActivity.ACTION_UPDATE_DISPLAY`, reschedules itself. Uses `setExactAndAllowWhileIdle` when exact alarms are permitted, `setAndAllowWhileIdle` otherwise.
- **`ACTION_FETCH_DATA`** (`FETCH_REQUEST_CODE`) — interval is the **minimum** of the enabled sources' intervals (weather, news, Bookmate's fixed 60 min). Takes a 20 s `PARTIAL_WAKE_LOCK`, runs all fetches on one raw thread, and each fetcher is additionally gated on its own cache age, so a shared short interval doesn't over-fetch a slow source. Reschedules in `finally`. Also fired eagerly from `onScreenOff` via `triggerDataFetchIfStale`.

Fetched data never travels in the intent — it lands in `SharedPreferences` and the service broadcasts `ACTION_DATA_UPDATED`, which `LockScreenActivity` turns into a redraw. All internal broadcasts are `setPackage(packageName)`-scoped but receivers register with `RECEIVER_EXPORTED` (needed for the system `SCREEN_*`/`USER_PRESENT` actions in the same filter).

### Module system

The lock screen is a vertical stack of five modules — `clock`, `weather`, `news`, `notes` (rendered as sticker cards), `book` — each with an enable flag, its own font size, and a user-defined order (`modules_order`, default `clock,weather,news,notes,book`).

`activity_lockscreen.xml` declares the sections in default order; on every `updateDisplay()` `reorderModules()` removes and re-adds them into `mainContainer` following the saved order, then each module's block decides `VISIBLE`/`GONE` from its toggle plus whether its cache holds usable data. Sticker cards and their rows are built programmatically (`buildStickerView`), as is the reorderable list in `ModuleSettingsActivity`.

**Adding or renaming a module touches all of:** `PrefsManager` (toggle + font-size keys, `DEFAULT_MODULES_ORDER`), a section in `activity_lockscreen.xml`, `LockScreenActivity.getModuleView()` and the corresponding block in `updateDisplay()`, the three `when` blocks in `ModuleSettingsActivity` (`isModuleEnabled` / `setModuleEnabled` / `moduleDisplayName`), a `<activity>` entry in the manifest for its settings screen, and strings in **both** `values/` and `values-ru/`.

The battery indicator is **not** a module: `batteryRow` is an overlay child of the root `FrameLayout`, anchored `top|end` inside the strip that `mainContainer`'s 32dp top padding leaves empty, so the module order and the clock position never move it. `BatteryIndicatorView` draws the gauge on a canvas; `updateBattery()` reads the sticky `ACTION_BATTERY_CHANGED` on each `updateDisplay()`, which is what ties it to the clock's update interval. It has no enable toggle.

Neither is the missed-notification corner: `notificationsRow` is the mirror-image overlay at `top|start`, one glyph and a count each for SMS, Telegram and missed calls, each hidden at zero and the row hidden when all three are. The counting is `NotificationListener`, a `NotificationListenerService` that classifies by posting package and `Notification.CATEGORY_MISSED_CALL` — the default SMS package from `Telephony.Sms.getDefaultSmsPackage` plus a hardcoded fallback set, anything under `org.telegram` plus Telegram X, and `com.android.server.telecom`, which is what posts missed calls on AOSP. Group summaries and ongoing notifications are skipped; a bundled chat is weighted by `EXTRA_MESSAGES.size`, else `Notification.number`, else 1. It writes the three counts into prefs on every post and removal and **does not broadcast** — a notification is not worth a panel refresh, so the number is picked up by the next clock tick. `NotificationListener.snapshot()` recounts from the live service when it is bound and falls back to the prefs cache when it is not. Notification access is a separate user grant (`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`, gated in `SettingsActivity`), and on Android 13+ a sideloaded app hits the same *Allow restricted settings* wall as the accessibility service.

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

### The frontlight flash on lock — root cause

Measured on the HiBreak, not inferred. The frontlight is Android's own `/sys/class/leds/lcd-backlight`, driven by `Settings.System.SCREEN_BRIGHTNESS` (240 on this device, auto-brightness off). It is **not** the xrz `vendor.xrz.global_brightness_level`, which stayed 0 through 182 samples across a full lock cycle. A `logcat -v time` of one cycle:

```
56.301 Brightness [0.0]       reason 'screen_off', previous 'manual'
56.305 write 0 to /sys/class/leds/lcd-backlight/brightness
56.880 Brightness [0.9409449] reason 'manual', previous 'screen_off'
57.026 write 240 to /sys/class/leds/lcd-backlight/brightness   ← the flash
57.265 Brightness [0.0]       reason 'override', previous 'manual'
57.266 write 1 to /sys/class/leds/lcd-backlight/brightness
```

On every wake `DisplayPowerController` restores the user's manual level first and only then recomputes with our window's `screenBrightness = 0.0f` as `reason=override` — 240 ms later. Having the window already added, drawn and focused shortens the gap to ~141 ms but does **not** remove it: the manual restore is unconditional. Reordering our activity launch does not fix this, and neither does any brightness API — see below for what does.

**The fix, and it is not a brightness API.** The stock screensaver never flashes because its wake carries a flag this firmware's `DisplayPowerController` prints as `isWakeUpOnly=true`; while that is set the panel takes its update and the backlight is never written. `dexdump` of `services.jar` shows `PowerManagerService.updateGlobalWakefulnessLocked` setting it from a hardcoded string:

```
mIsWakeUpOnly = (reason == WAKE_REASON_APPLICATION
                 && "com.xrz.screensaver".equals(details))
```

For an `ACQUIRE_CAUSES_WAKEUP` wake lock the "details" string is the tag, so `ScreenSaverService.WAKE_TAG_NO_BACKLIGHT` names ours `"com.xrz.screensaver"` and gets the stock screensaver's flash-free wake.

**The tag alone is a trap.** The flag stays set for as long as the device is awake, and in that state the panel never properly powers on and the touchscreen ignores input — the keyguard is visible but dead to touch. A first attempt shipped the tag without an exit and made the phone very hard to unlock: `screen_off_timeout` is 10 minutes here, and every power press just handed `SCREEN_OFF` back to the service, which woke the device again with the tag. The device never got a normal wake.

What clears it is an explicit sleep, after which the next wake is an ordinary `WAKE_REASON_POWER_BUTTON` with the backlight restored. The stock app does that with `PowerManager.goToSleep()` at uid 1000; `DEVICE_POWER` is a signature permission, so `SleepAccessibilityService` calls `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` instead — the same thing reachable by an ordinary app, at the price of one accessibility toggle. It reads nothing and consumes no events.

Three interlocks make this safe to run, and they are not optional:

- **The tag is only taken when it can be undone.** `canUseVendorWake()` requires `SleepAccessibilityService.isConnected` at that moment; otherwise the old CPU-only wake lock is used and `LockScreenActivity` turns the screen on itself.
- **A latch.** `SCREEN_OFF` arriving within `SELF_SLEEP_GRACE_MS` of our own sleep is ignored. Answering our own sleep with another wake is the loop that broke the phone.
- **A rate limit and a circuit breaker.** More than `BREAKER_MAX_WAKES` vendor wakes in a minute, a failed lock action, or the device still interactive 1.5 s after one, all set `vendorWakeDisabled` for the life of the process. Every failure mode degrades to the old behaviour rather than to a phone that will not unlock.

Measured over three lock/wake cycles: no `lcd-backlight` write above 1 on any lock, `mWakefulness=Asleep` after each, the latch catching our own `SCREEN_OFF` every time, `isWakeUpOnly=false` on every wake, breaker never tripped. In normal use the user reports no flash at all, including the first lock after an unlock — an earlier lab run did record one write of 240 on a fresh `LockScreenActivity` launch, but that was almost certainly the test harness waking the device itself before the cycle, not the launch.

Two things fell out of getting the device to sleep properly. The panel now really reaches `Asleep` instead of sitting `Awake` until the 10-minute screen timeout, so wireless adb drops at lock (collect logs after reconnecting; the buffer survives). And the side-mounted fingerprint sensor unlocks on a touch again, without a press — while the device was stuck in the vendor's wake-up-only state it was not listening. That is not something this app sets; it is something this app was previously breaking.

**Distribution catch:** Android 13+ blocks accessibility services for sideloaded apps. The system silently reverts `enabled_accessibility_services` until the user opens App info → ⋮ → *Allow restricted settings*. Any install instructions have to say so; over adb the equivalent is `appops set com.eink.screensaver ACCESS_RESTRICTED_SETTINGS allow`.

Two other candidates were measured and ruled out. **`DisplayPolicyManager.setBrightnessLevelForPackage`** — see the vendor-layer section; the call works end to end and the backlight ignores it. **`Settings.System.SCREEN_BRIGHTNESS` via `WRITE_SETTINGS`** — `DisplayPowerController` latches the manual level on the way down, does not re-read the setting during the wake, and persists its own value back over ours within 500 ms; a plain `settings put system screen_brightness 1` from adb bounces back to 240 the same way, so it is neither a permission nor a call-site-timing problem. `DreamService` was considered and rejected: dreams are disabled on this device (`screensaver_enabled=0`, `screensaver_activate_on_sleep=0`), those are `Settings.Secure` and need `WRITE_SECURE_SETTINGS`, and a dream does not start on a power press at all — which is the gesture this app exists for.

Untested lead: `screen_brightness_cold` / `screen_brightness_warm`, which suggest the Bigme frontlight has two channels this device exposes separately.

### Sleep drain — measured, and it is not this app

25 minutes locked and untouched, `dumpsys batterystats` reset at the start:

```
u0a280 (this app)          wake lock com.xrz.screensaver: 5.5s over 6 acquisitions
                           cpu: 1.24s usr + 0.33s krn
                           wakeup alarm ACTION_UPDATE_CLOCK: 4x
                           estimated: 0.000955 mAh

device   time on battery 25m 21s, uptime 3m 5s (12.2%), screen on 20s (7x),
         light idling 15m 13s (60%)
         idle 2.12 mAh vs ~0.06 mAh for every app combined
```

So the device's own idle floor (~5 mA) is 97% of the drain and this app is a rounding error inside the remaining 3%. Top wakers were `wlan0` (86x) and `BTIF_WAKEUP_IRQ` (55x) — radios, not us, and the Wi-Fi count is inflated because the measurement ran over wireless adb.

The original complaint most likely was the stock screensaver: `com.xrz.screensaver` was observed waking the power group out of Dozing roughly once a second, and `com.xrz.standby/…ScreenSaveActivity` launched on every screen-off alongside our activity. That package has since been uninstalled from the test device, and the self-sleep in `scheduleSelfSleep` means the device now actually reaches `Asleep` instead of sitting `Awake` until the 10-minute screen timeout.

Caveats for anyone repeating this: the battery was at 100%, so `charge_counter` never moved and `actual drain` reads 0 — the per-app split is trustworthy, the absolute mAh are not. A clean run needs the battery below ~90% and wireless debugging off, which needs a USB cable that survives bulk transfers.
- The panel takes **partial** updates for the routine redraws and one full clearing pass every three hours. `LockScreenActivity` pins the window to `PARTIAL_REFRESH_MODE` (`EinkCompat.MODE_REGAL` — 16 grey levels, no flash) in `onCreate`, and `refreshDisplay()` is the entry point for every redraw but the first: it calls `updateDisplay()` alone until `FULL_REFRESH_INTERVAL_MS` has elapsed since `lastFullRefreshMs`, then promotes the redraw to `fullRefresh()`. `lastFullRefreshMs` is a companion-object field on elapsed-realtime, so the schedule survives the activity being recreated on each lock but not a process death. Activity creation always calls `fullRefresh()` directly, on the grounds that the panel is still holding another app's pixels. `MODE_DU` is the one-line fallback if REGAL smears on some firmware.
- **Leaving the screen clears the panel.** `clearPanelOnExit()`, called from `finishOnUnlock()` and the `ACTION_FINISH` branch, fires `forceGlobalRefresh(MODE_CLEAN)` twice: once immediately, while our content is still what the panel holds, and once `EXIT_REFRESH_DELAY_MS` (400 ms) later on a handler of its own, by which point the screen behind us has drawn. Without it the ghosting accumulated since the last full refresh stays on the panel under the launcher, because nothing else on the device knows to clear it — that is what "garbage after unlock" was. Vendor path only. Both constants are the tuning knobs: raise the delay if the second pass still catches the transition, drop the immediate pass if two flashes on unlock read as one too many.
- The update interval is a battery/ghosting trade-off, not a UI nicety — each tick costs a wake lock and a panel refresh.

### The vendor layer (`EinkCompat`)

`EinkCompat` is a reflection bridge to Bigme's undocumented xrz framework (`xrz.framework.manager.XrzEinkManager` / `XrzEinkManagerInternal`), mapped in [imedwei/inksdk](https://github.com/imedwei/inksdk)'s `docs/bigme-sdk-reverse-engineered.md` and re-verified here against `Bigme_HiBreak_V1.0_20260306` by pulling `framework.jar` and dexdumping it. The classes ship in `framework.jar/classes5.dex`, which is on BOOTCLASSPATH, so plain `Class.forName` reaches them — `/system/framework/xrz.framework.server.jar` holds only the `xrz.framework.server.*` half and is no use as a classloader fallback. Watch two things the write-up gets wrong for this firmware: `EINK_DEFAULT_MODE` is 0, not 178 (178 is `EINK_NORMAL_MODE`), and the write-up's `app_process` proof runs at shell UID, which is exempt from hidden-API enforcement — only a run inside the app process proves a call works. Check `EinkCompat.isSupported` and keep the portable path alive next to every vendor call.

Two things it buys:

- **Vendor brightness — reachable, and inert.** Both routes into it work from an ordinary app UID and neither touches the panel light on the HiBreak. `setScreenBrightnessLevel(int)` is just `SystemProperties.set("vendor.xrz.global_brightness_level", …)`; the write succeeds (SELinux does not block it — `setFrontlight` proves it by reading the value back), the property simply drives nothing here. `DisplayPolicyManager.setBrightnessLevelForPackage(pkg, level)` was probed with level 100: returned true, `dumpsys xrz_display_policy_service` showed `appBrightnessLevel=100`, the property did read 100 while the lock screen was on top — and `/sys/class/leds/lcd-backlight` still went 0 → 240 → 1 exactly as without it. `EinkCompat.setPackageBrightness` is kept for other models but has no caller. Separately, by the time `ACTION_SCREEN_OFF` reaches `onScreenOff()` the system has already zeroed the xrz property, so `dimFrontlight()` usually hits its `current <= 0` guard anyway. It and `restoreFrontlight()` still save the user's level in prefs (`saved_frontlight_level`, sentinel `PrefsManager.NO_SAVED_FRONTLIGHT`) so a process death can't strand it; restore paths are the unlock poll in `cancelNotificationAndFinish()` (the reliable one), `USER_PRESENT`, `ACTION_STOP`, `onDestroy`.
- **Real panel refresh.** `forceGlobalRefresh(MODE_CLEAN)` replaces the black→white flash on the vendor path, saving two full frames and 100 ms of wake lock per launch. The window is pinned to `MODE_REGAL` for partial updates via `setWindowRefreshMode` — it used to be `MODE_GC16`, which is what made every clock tick flash the whole screen. Other waveform constants (`MODE_DU`, `MODE_A2`, `MODE_GC16`, …) are in `EinkCompat`.

**Hidden-API enforcement blocks all of it by default.** Measured on the device: `Class.forName` on the xrz classes succeeds, but every `getMethod` throws `NoSuchMethodException` — `getScreenBrightnessLevel`, `Window.setRefreshMode` and `forceGlobalRefresh` are all invisible to an ordinary app on Android 14. With `adb shell settings put global hidden_api_policy 1` every one of them resolves and the calls go through. So the vendor path is opt-in per device (that setting, or a bypass such as `org.lsposed.hiddenapibypass`); with enforcement on, `EinkCompat` degrades to no-op and the portable paths carry the app, which is exactly what the logs show.

Verify the device before assuming any of it works: `adb shell service list | grep -iE 'xrz|handwritten'`.

## Gotchas

- The clock-interval SeekBar in `activity_clock_settings.xml` ranges 0–30 and labels 0 as "disabled", but `scheduleClockAlarm()` does not special-case 0 — it would schedule an alarm 0 ms out and spin. Handle 0 explicitly if you touch that path.
- `LockScreenActivity.isActive` is a static guard the service checks before launching; keep it accurate if you add finish paths.
- Cover-image loading and all fetches post results back through `handler.post`, and check the module toggle again on the main thread — the user may have disabled the module while the thread ran.
- `PrefsManager` has a hardcoded default Bookmate user id.
