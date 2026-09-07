package com.eink.screensaver

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Window
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * Reflection bridge to Bigme's undocumented xrz framework, verified against
 * HiBreak firmware Bigme_HiBreak_V1.0_20260306 (Android 14, SDK 34).
 *
 * What is actually on the device (`dexdump` of the pulled framework.jar):
 *
 *  - `xrz.framework.manager.*` lives in `framework.jar/classes5.dex`, which IS
 *    on BOOTCLASSPATH, so a plain `Class.forName` reaches it. Note that
 *    `/system/framework/xrz.framework.server.jar` holds only the *server* half
 *    (`xrz.framework.server.*`) — there is no point pointing a PathClassLoader
 *    at it, so there is no classloader fallback here.
 *  - `XrzEinkManager.setRefreshModeByWindow(Window, int)` is a one-line wrapper
 *    around a vendor-added **`Window.setRefreshMode(int)`**. That is in-process
 *    with nothing to gate it, so [setWindowRefreshMode] calls Window directly.
 *  - `XrzEinkManager.forceGlobalRefresh(int)` delegates to a JNI method.
 *  - `XrzEinkManagerInternal.setScreenBrightnessLevel(int)` is nothing but
 *    `SystemProperties.set("vendor.xrz.global_brightness_level", …)`. That
 *    property is `vendor_xrz_prop` and the device runs SELinux **Enforcing**,
 *    so the write is very likely refused for a third-party app — see
 *    [setFrontlight], which verifies by reading the value back.
 *
 * All of it sits behind Android's hidden-API restriction: measured on the
 * device, `Class.forName` resolves the xrz classes but every `getMethod` throws
 * `NoSuchMethodException`. (Write-ups that "prove" these calls with an
 * `app_process` probe prove nothing — shell UID is exempt from the restriction,
 * an app is not.) [liftHiddenApiRestriction] clears it for our own process at
 * class-init time, which is what lets this ship to other users instead of
 * asking each of them to set `hidden_api_policy` over adb.
 *
 * Every entry point is still guarded, and every caller keeps its portable path:
 * the bypass is undocumented territory stacked on undocumented territory.
 */
object EinkCompat {

    private const val TAG = "EinkCompat"

    private const val CLASS_MANAGER = "xrz.framework.manager.XrzEinkManager"
    private const val CLASS_INTERNAL = "xrz.framework.manager.XrzEinkManagerInternal"

    // xrz.framework.manager.EinkRefreshMode, read off this firmware. Note
    // EINK_DEFAULT_MODE is 0 here, not the 178 some write-ups quote — 178 is
    // EINK_NORMAL_MODE, which is what ro.vendor.xrz.default_refresh_mode holds.
    const val MODE_DU = 2
    const val MODE_GC16 = 4
    const val MODE_A2 = 16
    const val MODE_CLEAN = 176
    const val MODE_DEFAULT = 0
    const val MODE_NORMAL = 178
    const val MODE_FAST = 179
    const val MODE_REGAL = 180

    init {
        liftHiddenApiRestriction()
    }

    private val managerClass: Class<*>? by lazy { loadClass(CLASS_MANAGER) }
    private val internalClass: Class<*>? by lazy { loadClass(CLASS_INTERNAL) }

    private val getBrightness: Method? by lazy { method(internalClass, "getScreenBrightnessLevel") }
    private val setBrightness: Method? by lazy {
        method(internalClass, "setScreenBrightnessLevel", Int::class.javaPrimitiveType)
    }
    private val globalRefresh: Method? by lazy {
        method(managerClass, "forceGlobalRefresh", Int::class.javaPrimitiveType)
    }
    private val windowSetRefreshMode: Method? by lazy {
        method(Window::class.java, "setRefreshMode", Int::class.javaPrimitiveType)
    }

    /** True when the vendor framework classes resolved in this process. */
    val isSupported: Boolean by lazy {
        val ok = internalClass != null && managerClass != null
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

    /**
     * Under the hood this is a `vendor.xrz.*` SystemProperties write, which
     * SELinux may refuse without throwing anything useful, so read the value
     * back and report whether it actually took.
     */
    fun setFrontlight(level: Int): Boolean = try {
        val m = setBrightness
        if (m == null) false else {
            m.invoke(null, level)
            val readBack = getFrontlight()
            if (readBack == level) {
                Log.d(TAG, "frontlight -> $level")
                true
            } else {
                Log.w(TAG, "frontlight write refused: asked $level, still $readBack")
                false
            }
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
     *
     * Goes straight to the vendor-added `Window.setRefreshMode(int)` rather than
     * through `XrzEinkManager`, which only wraps that same call — one less object
     * and one less class to resolve.
     */
    fun setWindowRefreshMode(window: Window, mode: Int): Boolean = try {
        val m = windowSetRefreshMode
        if (m == null) false else {
            m.invoke(window, mode)
            true
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Window.setRefreshMode($mode) failed: ${e.message}")
        false
    }

    // ════════ Reflection plumbing ════════

    /**
     * Exempt the two signature prefixes we reflect on from the hidden-API
     * restriction, for this process only. Runs once at class init, before any
     * lookup — an exemption added after a failed lookup would not help, since
     * the [Method] handles are cached in the lazies above.
     *
     * Deliberately narrow: exempting `"L"` would open the whole non-SDK surface
     * of the framework to us, and we want exactly two things out of it.
     */
    private fun liftHiddenApiRestriction() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return  // no restriction before 28
        try {
            HiddenApiBypass.addHiddenApiExemptions("Lxrz/framework/", "Landroid/view/Window;")
        } catch (e: Throwable) {
            // Leaves the vendor path unreachable; every caller falls back.
            Log.w(TAG, "hidden-API exemption failed: ${e.message}")
        }
    }

    /** The xrz classes ship inside framework.jar, which is on BOOTCLASSPATH. */
    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name)
    } catch (e: Throwable) {
        Log.d(TAG, "$name unavailable: ${e.message}")
        null
    }

    private fun method(cls: Class<*>?, name: String, vararg args: Class<*>?): Method? = try {
        cls?.getMethod(name, *args)
    } catch (e: Throwable) {
        Log.d(TAG, "$name not found: ${e.message}")
        null
    }
}
