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

/**
 * Foreground-сервис, запускающий LockScreenActivity через fullScreenIntent.
 *
 * ПРОБЛЕМА: Android 10+ запрещает startActivity() из фоновых процессов.
 *   Даже foreground service на Android 14 не может стартовать Activity,
 *   если приложение не в foreground.
 *
 * РЕШЕНИЕ: fullScreenIntent в notification — единственный одобренный Google
 *   способ показать UI на lockscreen из background (так работают будильники,
 *   входящие звонки, таймеры). Система ГАРАНТИРУЕТ показ Activity.
 *
 * WORKFLOW:
 *
 *   SCREEN_OFF
 *     → acquireWakeLock (5 сек, чтобы e-ink успел refresh)
 *     → postFullScreenNotification()
 *       → fullScreenIntent = PendingIntent → LockScreenActivity
 *       → Система показывает Activity поверх lockscreen
 *     → LockScreenActivity: brightness=0, drawClock
 *     → E-ink refresh → WakeLock timeout → экран спит, часы видны
 *
 *   [Device sleeping — zero CPU usage, e-ink retains image]
 *
 *   AlarmManager fires (every 1/2/5 min)
 *     → Brief WakeLock (3s) → re-post notification → update clock
 *     → WakeLock expires → sleep again
 *
 *   SCREEN_ON
 *     → LockScreenActivity.onResume() → updateClock, start unlock polling
 *
 *   FINGERPRINT / UNLOCK
 *     → Система обрабатывает fingerprint нативно (hardware-level)
 *     → LockScreenActivity polling isDeviceLocked каждые 1000ms
 *     → isDeviceLocked==false → vibrate → finish → cancel alarm
 */
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
    }

    private lateinit var powerManager: PowerManager
    private lateinit var keyguardManager: KeyguardManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var alarmManager: AlarmManager
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "══ SCREEN_OFF ══")
                    onScreenOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "══ SCREEN_ON ══")
                    // НЕ перевыпускаем fullScreenIntent здесь —
                    // это прерывает fingerprint-аутентификацию, которую keyguard
                    // начинает сразу при касании боковой кнопки (wake + scan).
                    // Activity выживает между screen off/on (noHistory убран),
                    // поэтому повторный запуск не нужен.
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "══ USER_PRESENT ══")
                    cancelClockAlarm()
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

        // Если сервис стартует, а экран уже выключен
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
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancelClockAlarm()
        cancelLockscreenNotification()
        releaseWakeLock()
        unregisterScreenReceiver()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ════════ SCREEN_OFF → WakeLock → FullScreen Notification ════════

    private fun onScreenOff() {
        acquireWakeLock()

        handler.postDelayed({
            postFullScreenNotification()
            // Schedule periodic clock updates while locked
            scheduleClockAlarm()
        }, LAUNCH_DELAY_MS)

        // WakeLock has a 5s timeout — enough for e-ink to complete refresh,
        // then CPU sleeps. AlarmManager handles periodic updates.
    }

    /**
     * Публикуем notification с fullScreenIntent.
     * На заблокированном экране система АВТОМАТИЧЕСКИ запускает
     * fullScreenIntent Activity вместо показа notification.
     * Это единственный легальный способ показать Activity из background
     * на lock screen (Android 10+).
     */
    private fun postFullScreenNotification() {
        val fullScreenIntent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_LOCKSCREEN_ID)
            .setContentTitle("E-Ink Часы")
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
            // SCHEDULE_EXACT_ALARM not granted — fall back to inexact alarm
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
        // Acquire a short WakeLock for the e-ink refresh
        releaseWakeLock()
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EinkScreensaver:AlarmUpdate"
        )
        wakeLock?.acquire(ALARM_WAKELOCK_TIMEOUT_MS)

        // Re-post notification to update the clock
        postFullScreenNotification()

        // Schedule the next alarm
        scheduleClockAlarm()
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
        // Основной канал (persistent foreground notification)
        val mainChannel = NotificationChannel(
            CHANNEL_ID, "E-Ink Скринсейвер", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Фоновый сервис скринсейвера"
            setShowBadge(false)
        }

        // Канал для lockscreen (fullScreenIntent требует IMPORTANCE_HIGH)
        val lockChannel = NotificationChannel(
            CHANNEL_LOCKSCREEN_ID, "Часы на экране блокировки", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Показ часов при блокировке"
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
            .setContentTitle("Скринсейвер активен")
            .setContentText("Часы на заблокированном экране")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Остановить", stopIntent).build())
            .setOngoing(true)
            .build()
    }
}
