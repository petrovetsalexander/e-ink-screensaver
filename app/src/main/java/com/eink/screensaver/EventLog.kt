package com.eink.screensaver

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Opt-in trace of the events the lock cycle is made of.
 *
 * Everything here exists because the interesting failures happen while the
 * device is asleep and off the cable: the panel wakes with the frontlight on,
 * or the keyguard prompt never appears, and by the time the phone is plugged in
 * the logcat ring buffer has long since rolled over. So the same lines that go
 * to logcat are also appended to a file the user can share from the settings
 * screen.
 *
 * Off by default and genuinely off: [enabled] is a plain volatile read at every
 * call site, and with it false nothing is opened, formatted or written.
 *
 * The format is one event per line, deliberately close to `logcat -v time` so
 * the two can be read side by side:
 *
 *     09-10 21:14:03.512 [   1234.5s] SVC  SCREEN_OFF           call=false
 *
 * The bracketed number is `elapsedRealtime`, which keeps counting while the
 * device sleeps — the gap between two lines is how long the phone was down.
 */
object EventLog {

    private const val TAG = "EventLog"

    private const val LOG_DIR = "logs"
    private const val CURRENT = "events.log"
    private const val PREVIOUS = "events-prev.log"
    private const val EXPORT_PREFIX = "eink-events-"

    /** Rotation threshold. Two files of this, so a share is bounded at ~1 MB. */
    private const val MAX_BYTES = 512L * 1024L

    // Sources. Short and fixed-width so the column stays readable.
    const val SRC_SERVICE = "SVC"
    const val SRC_LOCK = "LOCK"
    const val SRC_A11Y = "A11Y"
    const val SRC_EINK = "EINK"
    const val SRC_UI = "UI"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var enabled = false

    /** Writes happen off the caller's thread; timestamps are taken at call time. */
    @Volatile
    private var writer: Handler? = null

    private val lineFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * Called from [EinkApp.onCreate], i.e. once per process before anything
     * else runs. Every other entry point can then log without a context.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        enabled = PrefsManager.isEventLogEnabled(context)
        if (enabled) writeSessionHeader("process start")
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(context: Context, on: Boolean) {
        PrefsManager.setEventLogEnabled(context, on)
        if (appContext == null) appContext = context.applicationContext
        if (on && !enabled) {
            enabled = true
            writeSessionHeader("logging enabled by the user")
        } else if (!on && enabled) {
            log(SRC_UI, "LOG_OFF", "logging disabled by the user")
            enabled = false
        }
    }

    // ════════ Writing ════════

    /**
     * One event. [src] is the subsystem, [event] a stable all-caps name — these
     * are grep targets, so they must not drift — and [details] free-form `k=v`
     * pairs.
     *
     * Also mirrored to logcat under the subsystem's own tag, so turning the file
     * log on does not mean losing the live view while on the cable.
     */
    fun log(src: String, event: String, details: String = "") {
        if (!enabled) return
        val wall = System.currentTimeMillis()
        val since = SystemClock.elapsedRealtime()
        Log.d(logcatTag(src), if (details.isEmpty()) event else "$event  $details")
        post {
            append(
                String.format(
                    Locale.US, "%s [%9.1fs] %-4s %-20s %s%n",
                    lineFormat.format(Date(wall)), since / 1000.0, src, event, details
                )
            )
        }
    }

    /** Marks a boundary in the file so separate runs are told apart at a glance. */
    private fun writeSessionHeader(reason: String) {
        val ctx = appContext ?: return
        post {
            append("\n════════ session: " + reason + " ════════\n" + snapshot(ctx) + "\n")
        }
    }

    private fun post(block: () -> Unit) {
        val h = writer ?: synchronized(this) {
            writer ?: Handler(
                HandlerThread("EventLogWriter").apply { start() }.looper
            ).also { writer = it }
        }
        h.post {
            try {
                block()
            } catch (e: Throwable) {
                Log.w(TAG, "log write failed: " + e.message)
            }
        }
    }

    /** Writer thread only. */
    private fun append(line: String) {
        val dir = logDir() ?: return
        val current = File(dir, CURRENT)
        if (current.length() > MAX_BYTES) {
            File(dir, PREVIOUS).delete()
            current.renameTo(File(dir, PREVIOUS))
        }
        current.appendText(line)
    }

    private fun logDir(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, LOG_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    // ════════ State snapshot ════════

    /**
     * Everything about the device that decides which path the lock cycle takes,
     * read at the moment of the call. It sits at the top of every session and of
     * every export: most of the questions a trace raises — was the accessibility
     * service actually bound, is the vendor path still on — are answered here
     * rather than by guesswork.
     */
    fun snapshot(context: Context): String = buildString {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Throwable) {
            "?"
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        appendLine("app           : " + version + " (" + context.packageName + ")")
        appendLine("device        : " + Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.DEVICE)
        appendLine("firmware      : " + Build.DISPLAY)
        appendLine("android       : " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
        appendLine("service on    : " + PrefsManager.isEnabled(context))
        appendLine("clock every   : " + PrefsManager.getUpdateIntervalMinutes(context) + " min")
        appendLine(
            "eink vendor   : supported=" + EinkCompat.isSupported +
                " disabled=" + ScreenSaverService.vendorWakeDisabled +
                " canWake=" + ScreenSaverService.canUseVendorWake()
        )
        appendLine(
            "a11y          : enabled=" + SleepAccessibilityService.isEnabledInSettings(context) +
                " bound=" + SleepAccessibilityService.isConnected
        )
        appendLine("notif access  : " + NotificationListener.isEnabledInSettings(context))
        appendLine("overlay       : " + Settings.canDrawOverlays(context))
        appendLine("interactive   : " + power.isInteractive)
        appendLine(
            "keyguard      : locked=" + keyguard.isKeyguardLocked +
                " secure=" + keyguard.isKeyguardSecure
        )
        appendLine("lock activity : active=" + LockScreenActivity.isActive)
        appendLine(
            "frontlight    : vendor=" + EinkCompat.getFrontlight() +
                " saved=" + PrefsManager.getSavedFrontlight(context) +
                " system=" + systemSetting(context, Settings.System.SCREEN_BRIGHTNESS, false)
        )
        appendLine(
            "screen timeout: " + systemSetting(context, Settings.System.SCREEN_OFF_TIMEOUT, false) + " ms"
        )
        appendLine("hidden api    : " + systemSetting(context, "hidden_api_policy", true))
        appendLine("battery       : " + batteryLine(context))
        appendLine(
            "taken at      : " + lineFormat.format(Date()) +
                " [" + SystemClock.elapsedRealtime() / 1000 + "s uptime]"
        )
    }

    private fun systemSetting(context: Context, key: String, global: Boolean): String = try {
        val value = if (global) {
            Settings.Global.getString(context.contentResolver, key)
        } else {
            Settings.System.getString(context.contentResolver, key)
        }
        value ?: "unset"
    } catch (e: Throwable) {
        "?"
    }

    private fun batteryLine(context: Context): String = try {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        level.toString() + "% plugged=" + (plugged != 0)
    } catch (e: Throwable) {
        "?"
    }

    // ════════ Export ════════

    /** Bytes currently on disk, for the settings screen. */
    fun sizeBytes(): Long {
        val dir = logDir() ?: return 0L
        return File(dir, CURRENT).length() + File(dir, PREVIOUS).length()
    }

    /**
     * Builds the file that gets shared: a fresh state snapshot, then the whole
     * trace oldest-first. Plain text, because the point is that it can be read
     * in any chat window and grepped afterwards.
     *
     * Returns null when there is nothing to share. The previous export is
     * deleted first — the log directory is not an archive.
     */
    fun export(context: Context): File? {
        val dir = logDir() ?: return null
        flush()
        val current = File(dir, CURRENT)
        val previous = File(dir, PREVIOUS)
        if (!current.exists() && !previous.exists()) return null

        dir.listFiles { f -> f.name.startsWith(EXPORT_PREFIX) }?.forEach { it.delete() }
        val out = File(dir, EXPORT_PREFIX + fileStampFormat.format(Date()) + ".txt")
        out.bufferedWriter().use { w ->
            w.write("════════ E-Ink Screensaver event log ════════\n")
            w.write(snapshot(context))
            w.write("\n════════ events (oldest first) ════════\n")
            if (previous.exists()) w.write(previous.readText())
            if (current.exists()) w.write(current.readText())
        }
        return out
    }

    fun clear() {
        post {
            logDir()?.listFiles()?.forEach { it.delete() }
        }
        if (enabled) writeSessionHeader("log cleared")
    }

    /** Blocks the caller until the queue has drained. Only used before an export. */
    private fun flush() {
        val h = writer ?: return
        val done = CountDownLatch(1)
        h.post { done.countDown() }
        try {
            done.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun logcatTag(src: String): String = when (src) {
        SRC_SERVICE -> ScreenSaverService.TAG
        SRC_LOCK -> LockScreenActivity.TAG
        SRC_EINK -> "EinkCompat"
        SRC_A11Y -> "SleepA11y"
        else -> TAG
    }
}
