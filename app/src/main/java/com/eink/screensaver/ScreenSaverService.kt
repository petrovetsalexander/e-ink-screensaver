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

class ScreenSaverService : Service() {

    companion object {
        const val TAG = "ScreenSaverSvc"
        const val CHANNEL_ID = "eink_screensaver_channel"
        const val CHANNEL_LOCKSCREEN_ID = "eink_lockscreen_channel"
        const val NOTIFICATION_ID = 1001
        const val LOCKSCREEN_NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.eink.screensaver.ACTION_STOP"

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
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ════════ SCREEN_OFF → WakeLock → FullScreen Notification ════════

    private fun onScreenOff() {
        if (LockScreenActivity.isActive) {
            Log.d(TAG, "LockScreenActivity already active, skipping fullScreenIntent")
            return
        }

        acquireWakeLock()

        handler.postDelayed({
            postFullScreenNotification()
            scheduleClockAlarm()
        }, LAUNCH_DELAY_MS)

        triggerDataFetchIfStale()
    }

    private fun postFullScreenNotification() {
        // Cancel previous notification so the system treats this as a new fullScreenIntent
        notificationManager.cancel(LOCKSCREEN_NOTIFICATION_ID)

        val fullScreenIntent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra("timestamp", SystemClock.elapsedRealtime())
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_LOCKSCREEN_ID)
            .setContentTitle(getString(R.string.notification_clock_title))
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()

        notificationManager.notify(LOCKSCREEN_NOTIFICATION_ID, notification)
        Log.d(TAG, "Full-screen notification posted")
    }

    fun cancelLockscreenNotification() {
        notificationManager.cancel(LOCKSCREEN_NOTIFICATION_ID)
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

    private fun onClockAlarmFired() {
        releaseWakeLock()
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EinkScreensaver:AlarmUpdate"
        )
        wakeLock?.acquire(ALARM_WAKELOCK_TIMEOUT_MS)

        postFullScreenNotification()
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
