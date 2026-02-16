package com.eink.screensaver

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Activity скринсейвера, отображаемая поверх системного lockscreen.
 * Запускается через fullScreenIntent notification из ScreenSaverService.
 *
 * FINGERPRINT / РАЗБЛОКИРОВКА:
 *   Система обрабатывает fingerprint на hardware-уровне.
 *   Наша Activity НЕ блокирует датчик отпечатка — он работает "под" нами.
 *   
 *   Проблема: USER_PRESENT broadcast может не дойти до Activity.
 *   Решение: активный polling isDeviceLocked() каждые 1000ms пока экран ON.
 *   Это гарантирует мгновенную реакцию на fingerprint-разблокировку.
 *
 *   polling → isDeviceLocked==false → vibrateConfirmation(80ms) → finish()
 */
class LockScreenActivity : AppCompatActivity() {

    companion object {
        const val TAG = "LockScreenAct"
        const val ACTION_FINISH = "com.eink.screensaver.ACTION_FINISH_LOCKSCREEN"
        private const val MAX_OFFSET_PX = 40

        // Интервал проверки статуса разблокировки (ms)
        // 1000ms — polling только при включённом экране,
        // достаточно быстро для responsive unlock detection
        private const val UNLOCK_POLL_INTERVAL_MS = 1000L
    }

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var clockContainer: LinearLayout
    private lateinit var keyguardManager: KeyguardManager

    private val handler = Handler(Looper.getMainLooper())
    private var lastDisplayedTime = ""
    private var isPollingActive = false

    // ════════ Polling разблокировки ════════

    /**
     * Главный механизм обнаружения разблокировки.
     * Опрашиваем keyguardManager.isDeviceLocked каждые 1000ms.
     * Работает ВСЕГДА, независимо от broadcast'ов.
     */
    private val unlockPollRunnable = object : Runnable {
        override fun run() {
            if (!keyguardManager.isDeviceLocked) {
                Log.d(TAG, "✓ POLL: device unlocked → vibrate + finish")
                vibrateConfirmation()
                cancelNotificationAndFinish()
                return
            }
            // Устройство всё ещё заблокировано — проверяем снова
            handler.postDelayed(this, UNLOCK_POLL_INTERVAL_MS)
        }
    }

    // ════════ Receiver: backup + forced finish ════════

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    // Backup: если polling не поймал — USER_PRESENT поймает
                    Log.d(TAG, "USER_PRESENT broadcast → checking")
                    if (!keyguardManager.isDeviceLocked) {
                        vibrateConfirmation()
                        cancelNotificationAndFinish()
                    }
                }
                ACTION_FINISH -> {
                    Log.d(TAG, "ACTION_FINISH → closing")
                    finish()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "SCREEN_OFF → stop polling")
                    stopUnlockPolling()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "SCREEN_ON → start polling, update clock")
                    updateClock()
                    startUnlockPolling()
                }
            }
        }
    }

    // ════════ Lifecycle ════════

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        // ─── Brightness = 0 ПЕРВЫМ ДЕЛОМ ───
        window.attributes = window.attributes.apply {
            screenBrightness = if (PrefsManager.isBrightnessOff(this@LockScreenActivity)) {
                0.0f
            } else {
                -1.0f
            }
        }

        // ─── ShowWhenLocked + TurnScreenOn ───
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // ─── Fullscreen / immersive ───
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )

        setContentView(R.layout.activity_lockscreen)

        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)
        clockContainer = findViewById(R.id.clockContainer)

        // Перехват всех касаний
        findViewById<View>(R.id.touchInterceptor).setOnTouchListener { _, _ -> true }

        registerReceivers()
        updateClock()

        Log.d(TAG, "Activity created, clock drawn")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent → updateClock")
        updateClock()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")

        // Проверяем — вдруг устройство уже разблокировано
        if (!keyguardManager.isDeviceLocked) {
            Log.d(TAG, "onResume: device already unlocked → finish")
            vibrateConfirmation()
            cancelNotificationAndFinish()
            return
        }

        updateClock()
        startUnlockPolling()
    }

    override fun onPause() {
        super.onPause()
        // НЕ останавливаем polling в onPause —
        // на некоторых устройствах Activity может быть "paused"
        // но всё ещё видна на lockscreen
    }

    override fun onStop() {
        super.onStop()
        stopUnlockPolling()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceivers()
        Log.d(TAG, "Activity destroyed")
        super.onDestroy()
    }

    // ════════ Touch blocking ════════

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean = true

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Блокируем Back
    }

    // ════════ Unlock polling ════════

    private fun startUnlockPolling() {
        if (isPollingActive) return
        isPollingActive = true
        handler.postDelayed(unlockPollRunnable, UNLOCK_POLL_INTERVAL_MS)
        Log.d(TAG, "Unlock polling started (${UNLOCK_POLL_INTERVAL_MS}ms interval)")
    }

    private fun stopUnlockPolling() {
        isPollingActive = false
        handler.removeCallbacks(unlockPollRunnable)
        Log.d(TAG, "Unlock polling stopped")
    }

    // ════════ Finish ════════

    private fun cancelNotificationAndFinish() {
        stopUnlockPolling()
        // Убираем fullscreen notification
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(ScreenSaverService.LOCKSCREEN_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification: ${e.message}")
        }
        finish()
        // Без анимации закрытия
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    // ════════ Vibration ════════

    private fun vibrateConfirmation() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    // ════════ Clock ════════

    private fun updateClock() {
        val now = Date()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val newTime = timeFormat.format(now)

        if (newTime == lastDisplayedTime) return
        lastDisplayedTime = newTime

        clockText.text = newTime

        if (PrefsManager.isShowDate(this)) {
            val dateFormat = SimpleDateFormat("EEE, d MMMM", Locale("ru"))
            dateText.text = dateFormat.format(now)
            dateText.visibility = View.VISIBLE
        } else {
            dateText.visibility = View.GONE
        }

        // Антигостинг e-ink
        clockContainer.translationX = Random.nextInt(-MAX_OFFSET_PX, MAX_OFFSET_PX).toFloat()
        clockContainer.translationY = Random.nextInt(-MAX_OFFSET_PX, MAX_OFFSET_PX).toFloat()

        Log.d(TAG, "Clock updated: $newTime")
    }

    // ════════ Receivers ════════

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_FINISH)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
