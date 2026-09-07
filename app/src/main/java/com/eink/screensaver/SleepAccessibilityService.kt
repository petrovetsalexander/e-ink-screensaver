package com.eink.screensaver

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Exists for exactly one call: [sleepNow].
 *
 * `ScreenSaverService` wakes the panel with a wake lock tagged
 * `com.xrz.screensaver`, which makes the vendor's PowerManagerService skip the
 * frontlight (see WAKE_TAG_NO_BACKLIGHT). The catch is that the flag behind
 * that stays set for as long as the device is awake, and in that state the
 * panel never properly powers on and the touchscreen ignores input — the device
 * has to be put back to sleep for the next wake to be a normal one. Measured:
 * an explicit sleep does clear it, and the following power press comes back as
 * WAKE_REASON_POWER_BUTTON with the backlight restored.
 *
 * The stock screensaver does this with `PowerManager.goToSleep()`, which needs
 * the signature permission DEVICE_POWER. `GLOBAL_ACTION_LOCK_SCREEN` is the
 * same thing reachable by a normal app, at the price of the user enabling one
 * accessibility toggle.
 *
 * No events are consumed and no window content is read; the service is a handle
 * for the global action and nothing else.
 */
class SleepAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SleepA11y"

        @Volatile
        private var instance: SleepAccessibilityService? = null

        /**
         * Whether the action is available *right now*. `ScreenSaverService`
         * checks this before every tagged wake: without a working way back to
         * sleep the tag must not be used at all.
         */
        val isConnected: Boolean
            get() = instance != null

        /** Lock the screen, i.e. put the device back to sleep. */
        fun sleepNow(): Boolean {
            val service = instance
            if (service == null) {
                Log.w(TAG, "sleepNow with no connected service")
                return false
            }
            return try {
                val ok = service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                Log.d(TAG, "GLOBAL_ACTION_LOCK_SCREEN -> $ok")
                ok
            } catch (e: Throwable) {
                Log.w(TAG, "GLOBAL_ACTION_LOCK_SCREEN failed: ${e.message}")
                false
            }
        }

        /**
         * Whether the user has switched us on in Settings. [isConnected] is the
         * one that matters at runtime — this is for the settings screen, which
         * has to report something before the service has ever bound.
         */
        fun isEnabledInSettings(context: Context): Boolean = try {
            val expected = ComponentName(context, SleepAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        } catch (e: Throwable) {
            Log.w(TAG, "could not read enabled services: ${e.message}")
            false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.d(TAG, "unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
