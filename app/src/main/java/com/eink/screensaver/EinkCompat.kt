package com.eink.screensaver

import android.content.Context
import android.util.Log
import android.view.Window
import dalvik.system.PathClassLoader
import java.lang.reflect.Method

/**
 * Reflection bridge to Bigme's undocumented xrz framework
 * (`xrz.framework.manager.*`, present on xrz-based firmware).
 *
 * Two things this buys us that the public Android API cannot:
 *
 *  1. **Hardware frontlight control.** [setFrontlight] drives the panel light
 *     directly, so the service can kill it in `onScreenOff()` *before* any
 *     window of ours exists. `WindowManager.LayoutParams.screenBrightness`
 *     only applies once the window is added, which is too late to stop the
 *     system's dim level from flashing the light on wake.
 *  2. **A real global panel refresh.** [forceGlobalRefresh] asks the HAL for
 *     one clearing waveform pass instead of the black -> white flash, which
 *     costs two extra full frames and 100 ms of wake lock on every redraw.
 *
 * Every entry point is guarded: if the classes are missing (any non-xrz
 * device) or a call throws, the method returns false and the caller falls
 * back to the portable path. Probe with [isSupported] first.
 *
 * The API is undocumented and can disappear on an OTA, so nothing here is
 * allowed to be load-bearing.
 */
object EinkCompat {

    private const val TAG = "EinkCompat"

    private const val CLASS_MANAGER = "xrz.framework.manager.XrzEinkManager"
    private const val CLASS_INTERNAL = "xrz.framework.manager.XrzEinkManagerInternal"
    private const val FRAMEWORK_JAR = "/system/framework/xrz.framework.server.jar"

    // xrz.framework.manager.EinkRefreshMode constants.
    const val MODE_DU = 2
    const val MODE_GC16 = 4
    const val MODE_A2 = 16
    const val MODE_CLEAN = 176
    const val MODE_DEFAULT = 178
    const val MODE_FAST = 179
    const val MODE_REGAL = 180

    private val managerClass: Class<*>? by lazy { loadClass(CLASS_MANAGER) }
    private val internalClass: Class<*>? by lazy { loadClass(CLASS_INTERNAL) }

    private val getBrightness: Method? by lazy { method(internalClass, "getScreenBrightnessLevel") }
    private val setBrightness: Method? by lazy {
        method(internalClass, "setScreenBrightnessLevel", Int::class.javaPrimitiveType)
    }
    private val globalRefresh: Method? by lazy {
        method(managerClass, "forceGlobalRefresh", Int::class.javaPrimitiveType)
    }
    private val refreshModeByWindow: Method? by lazy {
        method(managerClass, "setRefreshModeByWindow", Window::class.java, Int::class.javaPrimitiveType)
    }

    /** True when the vendor framework answered at least the frontlight calls. */
    val isSupported: Boolean by lazy {
        val ok = getBrightness != null && setBrightness != null
        Log.d(TAG, "xrz framework ${if (ok) "available" else "not available"} on this device")
        ok
    }

    // ════════ Frontlight ════════

    /**
     * Current hardware frontlight level, or null if unavailable. The scale is
     * firmware-defined; we only ever pass 0 or a value read back from here, so
     * the range does not matter to us.
     */
    fun getFrontlight(): Int? = try {
        getBrightness?.invoke(null) as? Int
    } catch (e: Throwable) {
        Log.w(TAG, "getScreenBrightnessLevel failed: ${e.message}")
        null
    }

    fun setFrontlight(level: Int): Boolean = try {
        val m = setBrightness
        if (m == null) false else {
            m.invoke(null, level)
            Log.d(TAG, "frontlight -> $level")
            true
        }
    } catch (e: Throwable) {
        Log.w(TAG, "setScreenBrightnessLevel($level) failed: ${e.message}")
        false
    }

    /**
     * Drop the frontlight to 0, remembering the user's level in prefs so it
     * survives a process death. Idempotent — calling it while already dimmed
     * will not overwrite the saved level.
     */
    fun dimFrontlight(context: Context) {
        if (!isSupported) return
        if (PrefsManager.getSavedFrontlight(context) != PrefsManager.NO_SAVED_FRONTLIGHT) return
        val current = getFrontlight() ?: return
        if (current <= 0) return  // user already had the light off, nothing to restore later
        PrefsManager.setSavedFrontlight(context, current)
        if (!setFrontlight(0)) {
            PrefsManager.setSavedFrontlight(context, PrefsManager.NO_SAVED_FRONTLIGHT)
        }
    }

    /** Put the frontlight back to whatever [dimFrontlight] saved. Idempotent. */
    fun restoreFrontlight(context: Context) {
        if (!isSupported) return
        val saved = PrefsManager.getSavedFrontlight(context)
        if (saved == PrefsManager.NO_SAVED_FRONTLIGHT) return
        setFrontlight(saved)
        PrefsManager.setSavedFrontlight(context, PrefsManager.NO_SAVED_FRONTLIGHT)
    }

    // ════════ Panel refresh ════════

    /** One clearing waveform pass over the whole panel. Replaces the flash. */
    fun forceGlobalRefresh(mode: Int = MODE_CLEAN): Boolean = try {
        val m = globalRefresh
        if (m == null) false else {
            m.invoke(null, mode)
            true
        }
    } catch (e: Throwable) {
        Log.w(TAG, "forceGlobalRefresh($mode) failed: ${e.message}")
        false
    }

    /**
     * Pin a window to a waveform mode. [MODE_GC16] gives the lock screen a full
     * flashing refresh on every update, which is what we want anyway.
     */
    fun setWindowRefreshMode(context: Context, window: Window, mode: Int): Boolean = try {
        val cls = managerClass
        val m = refreshModeByWindow
        if (cls == null || m == null) false else {
            val instance = cls.getConstructor(Context::class.java).newInstance(context)
            m.invoke(instance, window, mode)
            true
        }
    } catch (e: Throwable) {
        Log.w(TAG, "setRefreshModeByWindow($mode) failed: ${e.message}")
        false
    }

    // ════════ Reflection plumbing ════════

    /**
     * The xrz classes are injected into the boot classloader at runtime even
     * though the jar is on neither BOOTCLASSPATH nor SYSTEMSERVERCLASSPATH, so
     * a plain Class.forName normally works. The PathClassLoader fallback is
     * there in case a firmware build stops doing that.
     */
    private fun loadClass(name: String): Class<*>? {
        try {
            return Class.forName(name)
        } catch (e: Throwable) {
            Log.d(TAG, "$name not on the boot classpath, trying $FRAMEWORK_JAR")
        }
        return try {
            Class.forName(name, false, PathClassLoader(FRAMEWORK_JAR, EinkCompat::class.java.classLoader))
        } catch (e: Throwable) {
            Log.d(TAG, "$name unavailable: ${e.message}")
            null
        }
    }

    private fun method(cls: Class<*>?, name: String, vararg args: Class<*>?): Method? = try {
        cls?.getMethod(name, *args)
    } catch (e: Throwable) {
        Log.d(TAG, "$name not found: ${e.message}")
        null
    }
}
