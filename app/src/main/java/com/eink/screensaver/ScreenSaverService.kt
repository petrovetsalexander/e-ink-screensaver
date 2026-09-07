package com.eink.screensaver

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.eink.screensaver.data.BookmateFetcher
import com.eink.screensaver.data.ImageCache
import com.eink.screensaver.data.NewsFetcher
import com.eink.screensaver.data.WeatherFetcher

class ScreenSaverService : Service() {

    companion object {
        const val TAG = "ScreenSaverSvc"
        const val CHANNEL_ID = "eink_screensaver_channel"
        const val CHANNEL_LOCKSCREEN_ID = "eink_lockscreen_channel"
        const val NOTIFICATION_ID = 1001
        const val LOCKSCREEN_NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.eink.screensaver.ACTION_STOP"

        /**
         * Wake lock tag that suppresses the frontlight on Bigme's xrz firmware.
         *
         * Not a name — a magic string. The vendor patched
         * `PowerManagerService.updateGlobalWakefulnessLocked` with, in effect:
         *
         *     mIsWakeUpOnly = (reason == WAKE_REASON_APPLICATION
         *                      && "com.xrz.screensaver".equals(details))
         *
         * and `DisplayPowerController` keeps the display at `state=1` while that
         * flag is set: the panel takes its update, the backlight is never
         * written. For a wake caused by an ACQUIRE_CAUSES_WAKEUP wake lock the
         * "details" string is the wake lock's own tag, so this exact tag buys
         * the stock screensaver's flash-free wake. Read out of
         * /system/framework/services.jar with dexdump.
         *
         * **The flag stays set for as long as the device is awake**, and in that
         * state the panel never properly powers on and the touchscreen ignores
         * input. So the tag may only ever be used when we can put the device
         * straight back to sleep — see [canUseVendorWake] and [scheduleSelfSleep].
         * An earlier attempt without that made the phone very hard to unlock.
         */
        private const val WAKE_TAG_NO_BACKLIGHT = "com.xrz.screensaver"

        /** How long after our own sleep an incoming SCREEN_OFF is still ours. */
        private const val SELF_SLEEP_GRACE_MS = 4_000L

        /** Time given to the panel to take the drawing before we sleep again. */
        private const val DRAW_SETTLE_MS = 900L

        /** Floor on how often the vendor wake path may run. */
        private const val MIN_VENDOR_WAKE_INTERVAL_MS = 5_000L

        /** Circuit breaker: more than this many vendor wakes in the window below
         *  means something is looping, and the path is dropped for the session. */
        private const val BREAKER_MAX_WAKES = 6
        private const val BREAKER_WINDOW_MS = 60_000L

        /**
         * Set when the vendor wake path has misbehaved. Sticky for the life of
         * the process: whatever went wrong, the fallback is a usable phone.
         * Read by [LockScreenActivity] too, which must know whether the panel
         * was already woken for it.
         */
        @Volatile
        var vendorWakeDisabled = false
            private set

        /** True when a tagged wake is safe: we can undo it. */
        fun canUseVendorWake(): Boolean =
            EinkCompat.isSupported && !vendorWakeDisabled && SleepAccessibilityService.isConnected

        private const val LAUNCH_DELAY_MS = 200L
        private const val WAKELOCK_TIMEOUT_MS = 5_000L
        private const val ALARM_WAKELOCK_TIMEOUT_MS = 3_000L
        private const val ACTION_UPDATE_CLOCK = "com.eink.screensaver.ACTION_UPDATE_CLOCK"
        private const val ALARM_REQUEST_CODE = 2001

        const val ACTION_DATA_UPDATED = "com.eink.screensaver.ACTION_DATA_UPDATED"
        private const val ACTION_FETCH_DATA = "com.eink.screensaver.ACTION_FETCH_DATA"
        private const val FETCH_REQUEST_CODE = 2002
        private const val FETCH_WAKELOCK_TIMEOUT_MS = 20_000L
        private const val BOOKMATE_INTERVAL_MIN = 60
    }

    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var alarmManager: AlarmManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var fetchWakeLock: PowerManager.WakeLock? = null

    // ── Interlock for the vendor wake path ──
    /** SCREEN_OFF arriving before this is the one we caused ourselves. */
    private var selfSleepUntilMs = 0L
    private var lastVendorWakeMs = 0L
    private var breakerWindowStartMs = 0L
    private var breakerWakeCount = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "══ SCREEN_OFF ══")
                    onScreenOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "══ SCREEN_ON ══")
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "══ USER_PRESENT ══")
                    cancelClockAlarm()
                    cancelDataFetchAlarm()
                    cancelLockscreenNotification()
                    releaseWakeLock()
                    EinkCompat.restoreFrontlight(this@ScreenSaverService)
                }
            }
        }
    }

    // ════════ Lifecycle ════════

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(POWER_SERVICE) as PowerManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        notificationManager = getSystemService(NotificationManager::class.java)
        alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildPersistentNotification())
        registerScreenReceiver()

        // If the process died while the frontlight was dimmed, the panel light is
        // still off. Put it back before anything else.
        EinkCompat.restoreFrontlight(this)

        if (!powerManager.isInteractive) {
            Log.d(TAG, "Service started with screen OFF → trigger lockscreen")
            onScreenOff()
        }

        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelClockAlarm()
                cancelDataFetchAlarm()
                cancelLockscreenNotification()
                sendBroadcast(Intent(LockScreenActivity.ACTION_FINISH).setPackage(packageName))
                releaseWakeLock()
                EinkCompat.restoreFrontlight(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CLOCK -> {
                Log.d(TAG, "AlarmManager → update clock")
                onClockAlarmFired()
                return START_STICKY
            }
            ACTION_FETCH_DATA -> {
                Log.d(TAG, "AlarmManager → fetch data")
                onDataFetchAlarmFired()
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelClockAlarm()
        cancelDataFetchAlarm()
        cancelLockscreenNotification()
        releaseWakeLock()
        releaseFetchWakeLock()
        unregisterScreenReceiver()
        EinkCompat.restoreFrontlight(this)
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ════════ SCREEN_OFF → WakeLock → FullScreen Notification ════════

    @SuppressLint("WakelockTimeout")
    private fun onScreenOff() {
        // The latch. When we put the device to sleep ourselves the system hands
        // us the resulting SCREEN_OFF like any other, and answering it with
        // another wake is an endless loop — that is exactly what made the phone
        // unusable the first time this was tried.
        if (SystemClock.elapsedRealtime() < selfSleepUntilMs) {
            Log.d(TAG, "SCREEN_OFF is our own sleep → ignoring")
            return
        }

        // Before the isActive guard: the screen goes off on every cycle, whether or
        // not the activity survived the last one. Largely vestigial on the HiBreak —
        // the system zeroes the xrz level before this broadcast reaches us, so the
        // call usually short-circuits — but other Bigme models may wire that level
        // up.
        EinkCompat.dimFrontlight(this)

        val vendorWake = acquireScreenWakeLock()

        if (LockScreenActivity.isActive) {
            Log.d(TAG, "LockScreenActivity already active, skipping launch")
            if (vendorWake) scheduleSelfSleep()
            return
        }

        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        // Small delay lets the system finish going to sleep before we launch;
        // the activity then turns the screen back on itself.
        handler.postDelayed({
            startActivity(intent)
        }, LAUNCH_DELAY_MS)

        if (vendorWake) scheduleSelfSleep()

        scheduleClockAlarm()
        triggerDataFetchIfStale()
    }

    fun cancelLockscreenNotification() {
        notificationManager.cancel(LOCKSCREEN_NOTIFICATION_ID)
    }

    // ════════ Vendor wake path + its interlocks ════════

    /**
     * Take the wake lock that will turn the panel on, and report whether it was
     * the vendor one. The tagged wake is only taken when it can be undone and
     * when neither the rate limit nor the circuit breaker objects; otherwise
     * this is the old CPU-only lock and the activity wakes the display itself.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireScreenWakeLock(): Boolean {
        releaseWakeLock()
        val useVendor = canUseVendorWake() && rateLimitAllowsVendorWake()
        wakeLock = if (useVendor) {
            @Suppress("DEPRECATION")
            powerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                WAKE_TAG_NO_BACKLIGHT
            )
        } else {
            // CPU-only: keeps this service alive long enough to launch the activity
            // and let it draw, but deliberately does NOT touch the display. The
            // activity's own setTurnScreenOn(true) wakes the display instead.
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EinkScreensaver:ScreenOn"
            )
        }
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        Log.d(TAG, "WakeLock acquired (${if (useVendor) "vendor, no-backlight tag" else "CPU only"})")
        return useVendor
    }

    /**
     * Floor on how often the vendor path may fire, plus a breaker that drops it
     * for good if it fires implausibly often. Neither should ever trigger in
     * normal use; they exist so that a bug here degrades into the old behaviour
     * rather than into a phone that will not unlock.
     */
    private fun rateLimitAllowsVendorWake(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVendorWakeMs < MIN_VENDOR_WAKE_INTERVAL_MS) {
            Log.d(TAG, "vendor wake rate-limited")
            return false
        }
        if (now - breakerWindowStartMs > BREAKER_WINDOW_MS) {
            breakerWindowStartMs = now
            breakerWakeCount = 0
        }
        breakerWakeCount++
        if (breakerWakeCount > BREAKER_MAX_WAKES) {
            Log.w(TAG, "$breakerWakeCount vendor wakes in a minute → disabling the vendor path")
            vendorWakeDisabled = true
            return false
        }
        lastVendorWakeMs = now
        return true
    }

    /**
     * Put the device back to sleep once the panel has taken the drawing. This is
     * what clears the vendor's wake-up-only flag; without it the panel stays
     * half-awake and the touchscreen ignores input.
     */
    private fun scheduleSelfSleep() {
        handler.postDelayed({
            // The user may have woken the phone in the meantime — never sleep on them.
            if (!LockScreenActivity.isActive || !keyguardManager.isDeviceLocked) {
                Log.d(TAG, "self-sleep skipped, the user is here")
                return@postDelayed
            }
            selfSleepUntilMs = SystemClock.elapsedRealtime() + SELF_SLEEP_GRACE_MS
            releaseWakeLock()
            if (!SleepAccessibilityService.sleepNow()) {
                Log.w(TAG, "could not sleep → disabling the vendor path")
                vendorWakeDisabled = true
                selfSleepUntilMs = 0L
                return@postDelayed
            }
            // Trust nothing: confirm the device actually went down, or stop using
            // a wake we cannot undo.
            handler.postDelayed({
                if (powerManager.isInteractive) {
                    Log.w(TAG, "still awake after the lock action → disabling the vendor path")
                    vendorWakeDisabled = true
                }
            }, 1_500L)
        }, DRAW_SETTLE_MS)
    }

    // ════════ WakeLock ════════

    private fun acquireWakeLock() {
        releaseWakeLock()
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EinkScreensaver:DrawClock"
        )
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
        Log.d(TAG, "WakeLock acquired (${WAKELOCK_TIMEOUT_MS}ms timeout)")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun releaseFetchWakeLock() {
        fetchWakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Fetch WakeLock released")
            }
        }
        fetchWakeLock = null
    }

    // ════════ AlarmManager for periodic clock updates ════════

    private fun getAlarmPendingIntent(): PendingIntent {
        val intent = Intent(this, ScreenSaverService::class.java).apply {
            action = ACTION_UPDATE_CLOCK
        }
        return PendingIntent.getService(
            this, ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleClockAlarm() {
        val intervalMs = PrefsManager.getUpdateIntervalMinutes(this) * 60_000L
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        val pi = getAlarmPendingIntent()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            Log.d(TAG, "Clock alarm scheduled (inexact) in ${intervalMs / 1000}s")
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            Log.d(TAG, "Clock alarm scheduled (exact) in ${intervalMs / 1000}s")
        }
    }

    private fun cancelClockAlarm() {
        alarmManager.cancel(getAlarmPendingIntent())
        Log.d(TAG, "Clock alarm cancelled")
    }

    @SuppressLint("WakelockTimeout")
    private fun onClockAlarmFired() {
        releaseWakeLock()
        val useVendor = canUseVendorWake() && rateLimitAllowsVendorWake()
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            if (useVendor) WAKE_TAG_NO_BACKLIGHT else "EinkScreensaver:AlarmUpdate"
        )
        wakeLock?.acquire(ALARM_WAKELOCK_TIMEOUT_MS)

        // Send broadcast to active lockscreen
        sendBroadcast(
            Intent(LockScreenActivity.ACTION_UPDATE_DISPLAY)
                .setPackage(packageName)
        )

        // This path matters most for the flash: it fires every update interval
        // for as long as the device stays locked.
        if (useVendor) scheduleSelfSleep()

        scheduleClockAlarm()
    }

    // ════════ Data Fetch Alarm ════════

    private fun getDataFetchPendingIntent(): PendingIntent {
        val intent = Intent(this, ScreenSaverService::class.java).apply {
            action = ACTION_FETCH_DATA
        }
        return PendingIntent.getService(
            this, FETCH_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleDataFetchAlarm() {
        val intervals = mutableListOf<Int>()
        if (PrefsManager.isWeatherEnabled(this)) {
            intervals.add(PrefsManager.getWeatherIntervalMin(this))
        }
        if (PrefsManager.isNewsEnabled(this)) {
            intervals.add(PrefsManager.getNewsIntervalMin(this))
        }
        if (PrefsManager.isBookmateEnabled(this)) {
            intervals.add(BOOKMATE_INTERVAL_MIN)
        }
        if (intervals.isEmpty()) return

        val minInterval = intervals.min()
        val intervalMs = minInterval * 60_000L
        val triggerAt = SystemClock.elapsedRealtime() + intervalMs
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, getDataFetchPendingIntent()
        )
        Log.d(TAG, "Data fetch alarm scheduled in ${minInterval}min")
    }

    private fun cancelDataFetchAlarm() {
        alarmManager.cancel(getDataFetchPendingIntent())
        Log.d(TAG, "Data fetch alarm cancelled")
    }

    private fun triggerDataFetchIfStale() {
        val ctx = this
        val hasAnyData = PrefsManager.isWeatherEnabled(ctx) ||
                PrefsManager.isNewsEnabled(ctx) ||
                PrefsManager.isBookmateEnabled(ctx)
        if (!hasAnyData) return

        onDataFetchAlarmFired()
    }

    private fun onDataFetchAlarmFired() {
        releaseFetchWakeLock()
        fetchWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EinkScreensaver:DataFetch"
        )
        fetchWakeLock?.acquire(FETCH_WAKELOCK_TIMEOUT_MS)

        val ctx = applicationContext
        kotlin.concurrent.thread {
            try {
                val now = System.currentTimeMillis()

                // Weather
                if (PrefsManager.isWeatherEnabled(ctx)) {
                    val interval = PrefsManager.getWeatherIntervalMin(ctx) * 60_000L
                    if (now - PrefsManager.getWeatherCacheTimeMs(ctx) > interval) {
                        val data = WeatherFetcher.fetch(
                            PrefsManager.getWeatherCity(ctx),
                            PrefsManager.getWeatherApiKey(ctx)
                        )
                        if (data != null) {
                            PrefsManager.setWeatherCache(ctx, data.toJson())
                            Log.d(TAG, "Weather cache updated")
                        }
                    }
                }

                // News
                if (PrefsManager.isNewsEnabled(ctx)) {
                    val interval = PrefsManager.getNewsIntervalMin(ctx) * 60_000L
                    if (now - PrefsManager.getNewsCacheTimeMs(ctx) > interval) {
                        val downloadCount = PrefsManager.getNewsDownloadCount(ctx)
                        val bodyTags = PrefsManager.getNewsBodyTagList(ctx)
                        val items = NewsFetcher.fetch(PrefsManager.getNewsRssUrl(ctx), downloadCount, bodyTags)
                        if (items != null) {
                            PrefsManager.setNewsCache(ctx, NewsFetcher.itemsToJson(items))
                            Log.d(TAG, "News cache updated")
                        }
                    }
                }

                // Bookmate
                if (PrefsManager.isBookmateEnabled(ctx)) {
                    val interval = BOOKMATE_INTERVAL_MIN * 60_000L
                    if (now - PrefsManager.getBookmateCacheTimeMs(ctx) > interval) {
                        val book = BookmateFetcher.fetch(PrefsManager.getBookmateUserId(ctx))
                        if (book != null) {
                            PrefsManager.setBookmateCache(ctx, book.toJson())
                            if (book.coverUrl.isNotBlank()) {
                                ImageCache.downloadAndCache(ctx, book.coverUrl)
                            }
                            Log.d(TAG, "Bookmate cache updated")
                        }
                    }
                }
                sendBroadcast(Intent(ACTION_DATA_UPDATED).setPackage(packageName))
                Log.d(TAG, "Data updated → broadcast sent")
            } catch (e: Exception) {
                Log.w(TAG, "Data fetch error: ${e.message}")
            } finally {
                releaseFetchWakeLock()
                scheduleDataFetchAlarm()
            }
        }
    }

    // ════════ Receivers ════════

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun unregisterScreenReceiver() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }

    // ════════ Notification channels ════════

    private fun createNotificationChannels() {
        val mainChannel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_screensaver), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_screensaver_desc)
            setShowBadge(false)
        }

        val lockChannel = NotificationChannel(
            CHANNEL_LOCKSCREEN_ID, getString(R.string.channel_lockscreen), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_lockscreen_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannel(mainChannel)
        notificationManager.createNotificationChannel(lockChannel)
    }

    private fun buildPersistentNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenSaverService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stopIntent).build())
            .setOngoing(true)
            .build()
    }
}
