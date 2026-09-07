package com.eink.screensaver

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * DOES NOT WORK on the HiBreak — kept only so the attempt is on record.
 *
 * Holds `Settings.System.SCREEN_BRIGHTNESS` at the floor for the duration of a
 * lock cycle, which is the only thing that actually stops the frontlight flash.
 *
 * Why the window attribute is not enough: on every wake `DisplayPowerController`
 * unconditionally restores the user's manual level first — measured at 240 on a
 * HiBreak, straight to `/sys/class/leds/lcd-backlight` — and only ~150-240 ms
 * later recomputes with `LockScreenActivity`'s `screenBrightness = 0.0f` as
 * `reason=override`. That gap is the flash. Nothing about our launch ordering
 * closes it, and the vendor xrz brightness controls do not drive this panel at
 * all (see [EinkCompat]). Lowering the setting itself makes that first
 * unconditional restore write darkness instead.
 *
 * The user's own values are persisted before the change, so a process death
 * cannot strand the device dark — [ScreenSaverService] calls [restore] on start.
 * **Measured result: DisplayPowerController wins.** It latches the manual
 * brightness on the way down, never re-reads the setting during the wake, and
 * then persists its own value straight back — our 0 survived under 500 ms
 * before the setting read 240 again, and the backlight still went to 240. A
 * plain `settings put system screen_brightness 1` from adb with the screen on
 * bounces back the same way, so this is not a permission problem and no
 * earlier call site would help.
 *
 * Needs `WRITE_SETTINGS`, which is optional: without it every call is a no-op
 * and the app behaves exactly as it did before.
 */
object SystemBrightness {

    private const val TAG = "SystemBrightness"

    /** Darkest the setting goes. E-ink is reflective, so this stays readable. */
    private const val DIMMED_LEVEL = 0

    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /**
     * Drop the system brightness to [DIMMED_LEVEL], remembering the user's level
     * and auto-brightness mode. Idempotent: a second call while already dimmed
     * will not overwrite what was saved.
     */
    fun dim(context: Context) {
        if (!canWrite(context)) return
        if (PrefsManager.getSavedBrightness(context) != PrefsManager.NO_SAVED_BRIGHTNESS) return

        try {
            val level = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS
            )
            val mode = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            if (level <= DIMMED_LEVEL) return  // already dark, nothing to restore later

            PrefsManager.setSavedBrightness(context, level, mode)

            // Auto-brightness would recompute from lux and ignore the level we
            // write, so pin the mode to manual for the cycle and put it back on
            // restore.
            if (mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                Settings.System.putInt(
                    context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
            }
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, DIMMED_LEVEL
            )
            Log.d(TAG, "brightness $level (mode $mode) -> $DIMMED_LEVEL")
        } catch (e: Throwable) {
            Log.w(TAG, "dim failed: ${e.message}")
            PrefsManager.clearSavedBrightness(context)
        }
    }

    /** Put back whatever [dim] saved. Idempotent, and safe to call unprompted. */
    fun restore(context: Context) {
        val level = PrefsManager.getSavedBrightness(context)
        if (level == PrefsManager.NO_SAVED_BRIGHTNESS) return
        if (!canWrite(context)) {
            // Permission revoked mid-cycle; the value is unrecoverable by us and
            // keeping it would re-apply a stale level later.
            Log.w(TAG, "cannot restore brightness $level, WRITE_SETTINGS is gone")
            PrefsManager.clearSavedBrightness(context)
            return
        }
        try {
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level
            )
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                PrefsManager.getSavedBrightnessMode(context)
            )
            Log.d(TAG, "brightness restored to $level")
        } catch (e: Throwable) {
            Log.w(TAG, "restore failed: ${e.message}")
        }
        PrefsManager.clearSavedBrightness(context)
    }
}
