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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class LockScreenActivity : AppCompatActivity() {

    companion object {
        const val TAG = "LockScreenAct"
        const val ACTION_FINISH = "com.eink.screensaver.ACTION_FINISH_LOCKSCREEN"
        private const val MAX_OFFSET_PX = 40
        private const val UNLOCK_POLL_INTERVAL_MS = 1000L
    }

    private lateinit var mainContainer: LinearLayout
    private lateinit var backgroundImage: ImageView
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView

    // Weather
    private lateinit var weatherSection: LinearLayout
    private lateinit var weatherCurrentText: TextView
    private lateinit var weatherDayNightText: TextView
    private lateinit var weatherForecastText: TextView

    // News
    private lateinit var newsSection: FrameLayout
    private lateinit var newsText: TextView

    // Notes
    private lateinit var notesSection: FrameLayout
    private lateinit var notesText: TextView

    // Book
    private lateinit var bookSection: FrameLayout
    private lateinit var bookCoverImage: ImageView
    private lateinit var bookTitleText: TextView
    private lateinit var bookAuthorText: TextView
    private lateinit var bookAnnotationText: TextView

    private lateinit var keyguardManager: KeyguardManager
    private val handler = Handler(Looper.getMainLooper())
    private var isPollingActive = false

    private val unlockPollRunnable = object : Runnable {
        override fun run() {
            if (!keyguardManager.isDeviceLocked) {
                Log.d(TAG, "POLL: device unlocked → vibrate + finish")
                vibrateConfirmation()
                cancelNotificationAndFinish()
                return
            }
            handler.postDelayed(this, UNLOCK_POLL_INTERVAL_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
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
                    Log.d(TAG, "SCREEN_ON → start polling, update display")
                    updateDisplay()
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

        window.attributes = window.attributes.apply {
            screenBrightness = if (PrefsManager.isBrightnessOff(this@LockScreenActivity)) {
                0.0f
            } else {
                -1.0f
            }
        }

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

        mainContainer = findViewById(R.id.mainContainer)
        backgroundImage = findViewById(R.id.backgroundImage)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)

        weatherSection = findViewById(R.id.weatherSection)
        weatherCurrentText = findViewById(R.id.weatherCurrentText)
        weatherDayNightText = findViewById(R.id.weatherDayNightText)
        weatherForecastText = findViewById(R.id.weatherForecastText)

        newsSection = findViewById(R.id.newsSection)
        newsText = findViewById(R.id.newsText)

        notesSection = findViewById(R.id.notesSection)
        notesText = findViewById(R.id.notesText)

        bookSection = findViewById(R.id.bookSection)
        bookCoverImage = findViewById(R.id.bookCoverImage)
        bookTitleText = findViewById(R.id.bookTitleText)
        bookAuthorText = findViewById(R.id.bookAuthorText)
        bookAnnotationText = findViewById(R.id.bookAnnotationText)

        findViewById<View>(R.id.touchInterceptor).setOnTouchListener { _, _ -> true }

        registerReceivers()
        updateDisplay()

        Log.d(TAG, "Activity created, display drawn")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent → updateDisplay")
        updateDisplay()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")

        if (!keyguardManager.isDeviceLocked) {
            Log.d(TAG, "onResume: device already unlocked → finish")
            vibrateConfirmation()
            cancelNotificationAndFinish()
            return
        }

        updateDisplay()
        startUnlockPolling()
    }

    override fun onPause() {
        super.onPause()
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
    override fun onBackPressed() {}

    // ════════ Unlock polling ════════

    private fun startUnlockPolling() {
        if (isPollingActive) return
        isPollingActive = true
        handler.postDelayed(unlockPollRunnable, UNLOCK_POLL_INTERVAL_MS)
    }

    private fun stopUnlockPolling() {
        isPollingActive = false
        handler.removeCallbacks(unlockPollRunnable)
    }

    // ════════ Finish ════════

    private fun cancelNotificationAndFinish() {
        stopUnlockPolling()
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(ScreenSaverService.LOCKSCREEN_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification: ${e.message}")
        }
        finish()
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

    // ════════ Display ════════

    private fun weatherIcon(desc: String): String {
        val d = desc.lowercase()
        return when {
            d.contains("\u0433\u0440\u043E\u0437") -> "\u26A1"       // гроз → lightning
            d.contains("\u0441\u043D\u0435\u0433") || d.contains("\u043C\u0435\u0442\u0435\u043B") -> "\u2744\uFE0F" // снег/метел → snowflake
            d.contains("\u0434\u043E\u0436\u0434") || d.contains("\u043B\u0438\u0432\u0435\u043D") -> "\u2614"       // дождь/ливен → rain
            d.contains("\u043C\u043E\u0440\u043E\u0441") -> "\uD83C\uDF27\uFE0F"       // морос → drizzle
            d.contains("\u0442\u0443\u043C\u0430\u043D") || d.contains("\u0434\u044B\u043C\u043A") -> "\uD83C\uDF2B\uFE0F" // туман/дымк → fog
            d.contains("\u043F\u0430\u0441\u043C\u0443\u0440\u043D") -> "\u2601\uFE0F"  // пасмурн → cloud
            d.contains("\u043E\u0431\u043B\u0430\u0447\u043D") -> "\u26C5"              // облачн → partly cloudy
            d.contains("\u044F\u0441\u043D") -> "\u2600\uFE0F"                           // ясн → sun
            else -> "\uD83C\uDF21\uFE0F"                                                 // thermometer
        }
    }

    private fun updateDisplay() {
        val now = Date()
        val ctx = this

        // Clock
        if (PrefsManager.isBlockClockEnabled(ctx)) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            clockText.text = timeFormat.format(now)
            clockText.visibility = View.VISIBLE
        } else {
            clockText.visibility = View.GONE
        }

        // Date
        if (PrefsManager.isBlockClockEnabled(ctx) && PrefsManager.isShowDate(ctx)) {
            val dateFormat = SimpleDateFormat("dd.MM.yyyy, EEEE", Locale("ru"))
            dateText.text = dateFormat.format(now)
            dateText.visibility = View.VISIBLE
        } else {
            dateText.visibility = View.GONE
        }

        // Weather
        if (PrefsManager.isBlockWeatherEnabled(ctx)) {
            val weatherJson = PrefsManager.getWeatherCacheJson(ctx)
            if (weatherJson.isNotBlank()) {
                val weather = WeatherData.fromJson(weatherJson)
                if (weather != null) {
                    val icon = weatherIcon(weather.currentDesc)
                    val tempSign = if (weather.currentTemp > 0) "+" else ""
                    weatherCurrentText.text = "$icon ${tempSign}${weather.currentTemp}\u00B0C \u00B7 ${weather.currentDesc}"

                    val daySign = if (weather.dayTemp > 0) "+" else ""
                    val nightSign = if (weather.nightTemp > 0) "+" else ""
                    weatherDayNightText.text = "\u2600\uFE0F \u0414\u0435\u043D\u044C: ${daySign}${weather.dayTemp}\u00B0 / \uD83C\uDF19 \u041D\u043E\u0447\u044C: ${nightSign}${weather.nightTemp}\u00B0"

                    if (weather.forecast.isNotEmpty()) {
                        weatherForecastText.text = weather.forecast.joinToString(" \u00B7 ") { item ->
                            val sign = if (item.temp > 0) "+" else ""
                            "${item.hour} ${sign}${item.temp}\u00B0"
                        }
                        weatherForecastText.visibility = View.VISIBLE
                    } else {
                        weatherForecastText.visibility = View.GONE
                    }

                    weatherSection.visibility = View.VISIBLE
                } else {
                    weatherSection.visibility = View.GONE
                }
            } else {
                weatherSection.visibility = View.GONE
            }
        } else {
            weatherSection.visibility = View.GONE
        }

        // News
        if (PrefsManager.isBlockNewsEnabled(ctx)) {
            val newsJson = PrefsManager.getNewsCacheJson(ctx)
            if (newsJson.isNotBlank()) {
                val titles = NewsFetcher.titlesFromJson(newsJson)
                if (titles.isNotEmpty()) {
                    newsText.text = titles.joinToString("\n") { "\u2022 $it" }
                    newsSection.visibility = View.VISIBLE
                } else {
                    newsSection.visibility = View.GONE
                }
            } else {
                newsSection.visibility = View.GONE
            }
        } else {
            newsSection.visibility = View.GONE
        }

        // Notes
        if (PrefsManager.isBlockNotesEnabled(ctx)) {
            val notesJson = PrefsManager.getNotesJson(ctx)
            try {
                val arr = JSONArray(notesJson)
                if (arr.length() > 0) {
                    val sb = android.text.SpannableStringBuilder()
                    for (i in 0 until arr.length()) {
                        val note = arr.getJSONObject(i)
                        val text = note.optString("text", "")
                        val completed = note.optBoolean("completed", false)
                        if (i > 0) sb.append("\n")
                        val line = "\u2022 $text"
                        val start = sb.length
                        sb.append(line)
                        if (completed) {
                            sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    notesText.text = sb
                    notesSection.visibility = View.VISIBLE
                } else {
                    notesSection.visibility = View.GONE
                }
            } catch (e: Exception) {
                notesSection.visibility = View.GONE
            }
        } else {
            notesSection.visibility = View.GONE
        }

        // Book
        val bookBackgroundEnabled = PrefsManager.isBookBackgroundEnabled(ctx)
        if (PrefsManager.isBlockBookEnabled(ctx) || bookBackgroundEnabled) {
            val bookJson = PrefsManager.getBookmateCacheJson(ctx)
            if (bookJson.isNotBlank()) {
                val book = BookData.fromJson(bookJson)
                if (book != null && book.title.isNotBlank()) {
                    if (PrefsManager.isBlockBookEnabled(ctx)) {
                        bookTitleText.text = book.title
                        bookAuthorText.text = book.author
                        bookAnnotationText.text = book.annotation
                        bookSection.visibility = View.VISIBLE
                    } else {
                        bookSection.visibility = View.GONE
                    }

                    // Load cover on background thread (try cache first, then download)
                    if (book.coverUrl.isNotBlank()) {
                        kotlin.concurrent.thread {
                            var bitmap = ImageCache.getCachedBitmap(ctx, book.coverUrl)
                            if (bitmap == null) {
                                bitmap = ImageCache.downloadAndCache(ctx, book.coverUrl)
                            }
                            if (bitmap != null) {
                                val bmp = bitmap
                                handler.post {
                                    if (PrefsManager.isBlockBookEnabled(ctx)) {
                                        bookCoverImage.setImageBitmap(bmp)
                                        bookCoverImage.visibility = View.VISIBLE
                                    }
                                    if (bookBackgroundEnabled) {
                                        backgroundImage.setImageBitmap(bmp)
                                        backgroundImage.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    } else {
                        bookCoverImage.visibility = View.GONE
                        backgroundImage.visibility = View.GONE
                    }
                } else {
                    bookSection.visibility = View.GONE
                    backgroundImage.visibility = View.GONE
                }
            } else {
                bookSection.visibility = View.GONE
                backgroundImage.visibility = View.GONE
            }
        } else {
            bookSection.visibility = View.GONE
            backgroundImage.visibility = View.GONE
        }

        // Anti-ghosting offset on mainContainer
        mainContainer.translationX = Random.nextInt(-MAX_OFFSET_PX, MAX_OFFSET_PX).toFloat()
        mainContainer.translationY = Random.nextInt(-MAX_OFFSET_PX, MAX_OFFSET_PX).toFloat()

        Log.d(TAG, "Display updated: ${clockText.text}")
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
