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
import com.eink.screensaver.data.BookData
import com.eink.screensaver.data.ImageCache
import com.eink.screensaver.data.NewsFetcher
import com.eink.screensaver.data.NewsItem
import com.eink.screensaver.data.WeatherData
import com.eink.screensaver.data.WeatherFetcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LockScreenActivity : AppCompatActivity() {

    companion object {
        const val TAG = "LockScreenAct"
        const val ACTION_FINISH = "com.eink.screensaver.ACTION_FINISH_LOCKSCREEN"
        const val ACTION_UPDATE_DISPLAY = "com.eink.screensaver.ACTION_UPDATE_DISPLAY"
        private const val UNLOCK_POLL_INTERVAL_MS = 1000L
        private const val EINK_FULL_REFRESH_DELAY_MS = 100L

        @Volatile
        var isActive = false
            private set
    }

    private lateinit var mainContainer: LinearLayout
    private lateinit var backgroundImage: ImageView
    private lateinit var clockSection: LinearLayout
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

    // Stickers
    private lateinit var stickersSection: LinearLayout

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
                ScreenSaverService.ACTION_DATA_UPDATED -> {
                    Log.d(TAG, "DATA_UPDATED → refresh display")
                    updateDisplay()
                }
                ACTION_UPDATE_DISPLAY -> {
                    Log.d(TAG, "UPDATE_DISPLAY → refresh display")
                    updateDisplay()
                }
            }
        }
    }

    // ════════ Lifecycle ════════

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        // Set brightness to 0 FIRST, before turning screen on.
        // This minimizes frontlight flash on e-ink — the window's brightness attribute
        // is applied as the screen wakes. On xrz firmware the service has already
        // dropped the hardware frontlight in onScreenOff(), so this is the second
        // line of defence rather than the only one.
        window.attributes = window.attributes.apply {
            screenBrightness = 0.0f
        }
        SystemBrightness.dim(this)
        EinkCompat.dimFrontlight(this)

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
        clockSection = findViewById(R.id.clockSection)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)

        weatherSection = findViewById(R.id.weatherSection)
        weatherCurrentText = findViewById(R.id.weatherCurrentText)
        weatherDayNightText = findViewById(R.id.weatherDayNightText)
        weatherForecastText = findViewById(R.id.weatherForecastText)

        newsSection = findViewById(R.id.newsSection)
        newsText = findViewById(R.id.newsText)

        stickersSection = findViewById(R.id.stickersSection)

        bookSection = findViewById(R.id.bookSection)
        bookCoverImage = findViewById(R.id.bookCoverImage)
        bookTitleText = findViewById(R.id.bookTitleText)
        bookAuthorText = findViewById(R.id.bookAuthorText)
        bookAnnotationText = findViewById(R.id.bookAnnotationText)

        registerReceivers()

        // Ask the panel for a full flashing waveform on every update of this window.
        // No-op off xrz firmware, where the black→white flash in forceFullEinkRefresh()
        // remains the only way to get one.
        EinkCompat.setWindowRefreshMode(window, EinkCompat.MODE_GC16)

        // Force full e-ink refresh: briefly show black screen, then draw content.
        // This forces every pixel to transition (full GC16 refresh), clearing ghosting.
        forceFullEinkRefresh()

        Log.d(TAG, "Activity created, display drawn")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent → updateDisplay")
        updateDisplay()
    }

    override fun onResume() {
        super.onResume()
        isActive = true
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
        isActive = false
        handler.removeCallbacksAndMessages(null)
        unregisterReceivers()
        Log.d(TAG, "Activity destroyed")
        super.onDestroy()
    }

    // ════════ Touch blocking ════════

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
        // The poll is the reliable unlock signal here — USER_PRESENT proved flaky —
        // so give the user their brightness back from this path too, not only from
        // the service.
        EinkCompat.restoreFrontlight(this)
        SystemBrightness.restore(this)
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

    // ════════ E-ink full refresh ════════

    private fun forceFullEinkRefresh() {
        val root = window.decorView

        if (EinkCompat.isSupported) {
            // Vendor path: draw the content, then ask the HAL for one clearing
            // waveform pass. Saves the two extra full frames and the 100 ms of
            // wake lock the flash below costs on every launch.
            root.setBackgroundColor(0xFFFFFFFF.toInt())
            updateDisplay()
            root.post { EinkCompat.forceGlobalRefresh(EinkCompat.MODE_CLEAN) }
            return
        }

        // Portable path: flash the root view black to force a full pixel transition
        // on the e-ink panel. Works on any e-ink device without vendor APIs.
        root.setBackgroundColor(0xFF000000.toInt())
        root.invalidate()

        handler.postDelayed({
            root.setBackgroundColor(0xFFFFFFFF.toInt())
            root.invalidate()
            updateDisplay()
        }, EINK_FULL_REFRESH_DELAY_MS)
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

    private fun getModuleView(key: String): View? = when (key) {
        "clock" -> clockSection
        "weather" -> weatherSection
        "news" -> newsSection
        "notes" -> stickersSection
        "book" -> bookSection
        else -> null
    }

    private fun reorderModules() {
        val order = PrefsManager.getModulesOrder(this)
        val moduleViews = order.mapNotNull { getModuleView(it) }

        // Remove all module views from container
        for (view in moduleViews) {
            mainContainer.removeView(view)
        }

        // Re-add in saved order
        for (view in moduleViews) {
            mainContainer.addView(view)
        }
    }

    private fun applyClockPosition() {
        val position = PrefsManager.getClockPosition(this)
        val gravity = if (position == "right") android.view.Gravity.END else android.view.Gravity.START

        clockText.gravity = gravity
        dateText.gravity = gravity

        val lp = clockText.layoutParams as LinearLayout.LayoutParams
        lp.gravity = gravity
        clockText.layoutParams = lp

        val dlp = dateText.layoutParams as LinearLayout.LayoutParams
        dlp.gravity = gravity
        dateText.layoutParams = dlp
    }

    private fun updateDisplay() {
        val now = Date()
        val ctx = this

        // Reorder modules according to saved order
        reorderModules()

        // Apply clock position (left/right)
        applyClockPosition()

        // Clock
        if (PrefsManager.isBlockClockEnabled(ctx)) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            clockText.text = timeFormat.format(now)
            clockText.textSize = PrefsManager.getFontSizeClock(ctx).toFloat()
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
                    val weatherFontSize = PrefsManager.getFontSizeWeather(ctx).toFloat()
                    val icon = weatherIcon(weather.currentDesc)
                    val tempSign = if (weather.currentTemp > 0) "+" else ""
                    weatherCurrentText.text = "$icon ${tempSign}${weather.currentTemp}\u00B0C \u00B7 ${weather.currentDesc}"
                    weatherCurrentText.textSize = weatherFontSize

                    val daySign = if (weather.dayTemp > 0) "+" else ""
                    val nightSign = if (weather.nightTemp > 0) "+" else ""
                    weatherDayNightText.text = "\u2600\uFE0F ${getString(R.string.weather_day)}: ${daySign}${weather.dayTemp}\u00B0 / \uD83C\uDF19 ${getString(R.string.weather_night)}: ${nightSign}${weather.nightTemp}\u00B0"

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
                var items = NewsFetcher.itemsFromJson(newsJson)
                if (items.isNotEmpty()) {
                    val displayCount = PrefsManager.getNewsDisplayCount(ctx)
                    if (PrefsManager.isNewsShuffle(ctx)) {
                        items = items.shuffled()
                    }
                    items = items.take(displayCount)
                    val onlyHeader = PrefsManager.isNewsOnlyHeader(ctx)
                    val headerSize = PrefsManager.getFontSizeNews(ctx).toFloat()
                    val bodySize = PrefsManager.getFontSizeNewsBody(ctx).toFloat()
                    if (onlyHeader) {
                        newsText.text = items.joinToString("\n") { "\u2022 ${it.title}" }
                        newsText.textSize = headerSize
                    } else {
                        val sb = android.text.SpannableStringBuilder()
                        for ((i, item) in items.withIndex()) {
                            if (i > 0) sb.append("\n\n")
                            val titleStart = sb.length
                            sb.append("\u2022 ${item.title}")
                            sb.setSpan(
                                android.text.style.RelativeSizeSpan(headerSize / bodySize),
                                titleStart, sb.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            sb.setSpan(
                                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                                titleStart, sb.length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            if (item.body.isNotBlank()) {
                                sb.append("\n")
                                sb.append(item.body)
                            }
                        }
                        newsText.text = sb
                        newsText.textSize = bodySize
                    }
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

        // Stickers (two-column grid)
        if (PrefsManager.isBlockNotesEnabled(ctx)) {
            try {
                val stickersJson = PrefsManager.getStickersJson(ctx)
                val allStickers = JSONArray(stickersJson)
                val visibleStickers = (0 until allStickers.length())
                    .map { allStickers.getJSONObject(it) }
                    .filter { it.optBoolean("visible", true) }

                if (visibleStickers.isNotEmpty()) {
                    stickersSection.removeAllViews()
                    val columns = PrefsManager.getNotesColumns(ctx).coerceIn(1, 3)
                    val notesFontSize = PrefsManager.getFontSizeNotes(ctx).toFloat()
                    for (i in visibleStickers.indices step columns) {
                        val row = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = 8 }
                        }
                        for (col in 0 until columns) {
                            if (i + col < visibleStickers.size) {
                                row.addView(buildStickerView(visibleStickers[i + col], notesFontSize))
                            } else {
                                row.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
                            }
                        }
                        stickersSection.addView(row)
                    }
                    stickersSection.visibility = View.VISIBLE
                } else {
                    stickersSection.visibility = View.GONE
                }
            } catch (e: Exception) {
                stickersSection.visibility = View.GONE
            }
        } else {
            stickersSection.visibility = View.GONE
        }

        // Book
        val bookBackgroundEnabled = PrefsManager.isBookBackgroundEnabled(ctx)
        if (PrefsManager.isBlockBookEnabled(ctx) || bookBackgroundEnabled) {
            val bookJson = PrefsManager.getBookmateCacheJson(ctx)
            if (bookJson.isNotBlank()) {
                val book = BookData.fromJson(bookJson)
                if (book != null && book.title.isNotBlank()) {
                    if (PrefsManager.isBlockBookEnabled(ctx)) {
                        val bookFontSize = PrefsManager.getFontSizeBook(ctx).toFloat()
                        bookTitleText.text = book.title
                        bookTitleText.textSize = bookFontSize
                        bookAuthorText.text = book.author
                        bookAuthorText.textSize = (bookFontSize - 1f).coerceAtLeast(8f)
                        bookAnnotationText.text = book.annotation
                        bookAnnotationText.textSize = (bookFontSize - 2f).coerceAtLeast(8f)
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

        Log.d(TAG, "Display updated: ${clockText.text}")
    }

    // ════════ Sticker view builder ════════

    @Suppress("SetTextI18n")
    private fun buildStickerView(sticker: org.json.JSONObject, notesFontSize: Float = 13f): FrameLayout {
        val ctx = this
        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 4
                marginEnd = 4
            }
            setBackgroundResource(R.drawable.section_border)
            setPadding(10, 10, 10, 10)
        }

        val nameLabel = TextView(ctx).apply {
            text = sticker.optString("name", "")
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        frame.addView(nameLabel)

        val notes = sticker.optJSONArray("notes") ?: JSONArray()
        if (notes.length() > 0) {
            val sb = android.text.SpannableStringBuilder()
            for (i in 0 until notes.length()) {
                val note = notes.getJSONObject(i)
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
            val notesView = TextView(ctx).apply {
                this.text = sb
                textSize = notesFontSize
                setTextColor(0xFF000000.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 20 }
                setLineSpacing(0f, 1.3f)
            }
            frame.addView(notesView)
        }

        return frame
    }

    // ════════ Receivers ════════

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_FINISH)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(ScreenSaverService.ACTION_DATA_UPDATED)
            addAction(ACTION_UPDATE_DISPLAY)
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
