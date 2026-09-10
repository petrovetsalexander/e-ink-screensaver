package com.eink.screensaver

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.Log
import android.view.Gravity
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
import kotlin.math.roundToInt

class LockScreenActivity : AppCompatActivity() {

    companion object {
        const val TAG = "LockScreenAct"
        const val ACTION_FINISH = "com.eink.screensaver.ACTION_FINISH_LOCKSCREEN"
        const val ACTION_UPDATE_DISPLAY = "com.eink.screensaver.ACTION_UPDATE_DISPLAY"

        /**
         * Opens the same screen as a preview from the app, so what is on show is
         * the real thing rather than a second copy of the layout that could
         * drift. Everything tied to being an actual lock screen is skipped: the
         * screen stays at its normal brightness, the unlock poll never runs (it
         * would finish the activity at once on an unlocked device), and
         * [isActive] is left alone so the service still knows to launch for a
         * genuine lock.
         */
        const val EXTRA_PREVIEW = "com.eink.screensaver.EXTRA_PREVIEW"
        private const val UNLOCK_POLL_INTERVAL_MS = 1000L
        private const val EINK_FULL_REFRESH_DELAY_MS = 100L

        /**
         * How long after we finish the second clearing pass is due — long
         * enough for the keyguard to have gone and the screen behind it to have
         * drawn. Tune it here if the panel still catches the transition instead
         * of what follows it.
         */
        private const val EXIT_REFRESH_DELAY_MS = 400L

        /**
         * Waveform the window is pinned to between full refreshes. REGAL keeps
         * the 16 grey levels the book cover needs and updates only the pixels
         * that changed, so a clock tick no longer flashes the whole panel
         * black. [EinkCompat.MODE_DU] is the harder, faster fallback if this
         * firmware's REGAL turns out to smear.
         */
        private const val PARTIAL_REFRESH_MODE = EinkCompat.MODE_REGAL

        /**
         * How long the panel may go on taking partial updates before it is owed
         * a full clearing pass. Every partial update leaves a little ghosting
         * behind and it accumulates; three hours of clock ticks between flashes
         * is the trade-off this screen is tuned for.
         */
        private const val FULL_REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1000L

        /**
         * Static so it survives the activity: the service creates a fresh
         * instance on every lock, and a full refresh is owed against wall time,
         * not against how many times the screen has gone off. Elapsed-realtime
         * based, so it counts the time the device spent asleep.
         */
        @Volatile
        private var lastFullRefreshMs = 0L

        @Volatile
        var isActive = false
            private set
    }

    private lateinit var mainContainer: LinearLayout
    private lateinit var backgroundImage: ImageView
    private lateinit var headerRow: LinearLayout
    private lateinit var clockSection: LinearLayout
    private lateinit var clockText: TextView
    private lateinit var dateText: TextView

    // Battery
    private lateinit var batteryRow: LinearLayout
    private lateinit var batteryIcon: BatteryIndicatorView
    private lateinit var batteryText: TextView

    // Notifications
    private lateinit var notificationsRow: LinearLayout
    private lateinit var notifSmsText: TextView
    private lateinit var notifTelegramText: TextView
    private lateinit var notifCallsText: TextView

    // Weather

    private lateinit var weatherCurrentText: TextView
    private lateinit var weatherDayNightText: TextView
    private lateinit var weatherSummary: LinearLayout
    private lateinit var weatherForecastRow: LinearLayout

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
    private var isPreview = false

    /**
     * Set in [onStop]: something took the foreground off us — an incoming call
     * is the one that happens on its own — and whatever it drew is still on the
     * panel. Coming back from that needs a clearing pass, not a partial update
     * over someone else's pixels.
     */
    private var wasBackgrounded = false

    /**
     * A wake we believe the user caused arrived while the activity was not in a
     * state to ask the keyguard anything. Cleared by whichever of [onResume] or
     * [onWindowFocusChanged] gets there first.
     */
    private var unlockPromptPending = false
    private var promptRetries = 0
    private var lastPromptMs = 0L

    private val unlockPollRunnable = object : Runnable {
        override fun run() {
            if (!keyguardManager.isDeviceLocked) {
                Log.d(TAG, "POLL: device unlocked → vibrate + finish")
                vibrateConfirmation()
                finishOnUnlock()
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
                        finishOnUnlock()
                    }
                }
                ACTION_FINISH -> {
                    Log.d(TAG, "ACTION_FINISH → closing")
                    clearPanelOnExit()
                    finish()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    EventLog.log(EventLog.SRC_LOCK, "SCREEN_OFF", "polling stopped")
                    stopUnlockPolling()
                }
                Intent.ACTION_SCREEN_ON -> {
                    EventLog.log(
                        EventLog.SRC_LOCK, "SCREEN_ON",
                        "selfWake=${ScreenSaverService.isSelfWake()}"
                    )
                    refreshDisplay()
                    startUnlockPolling()
                    promptForUnlockIfUserWoke()
                }
                ScreenSaverService.ACTION_DATA_UPDATED -> {
                    Log.d(TAG, "DATA_UPDATED → refresh display")
                    refreshDisplay()
                }
                ACTION_UPDATE_DISPLAY -> {
                    Log.d(TAG, "UPDATE_DISPLAY → refresh display")
                    refreshDisplay()
                }
            }
        }
    }

    // ════════ Lifecycle ════════

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        isPreview = intent?.getBooleanExtra(EXTRA_PREVIEW, false) == true

        if (!isPreview) {
            // Set brightness to 0 FIRST, before turning screen on.
            // This minimizes frontlight flash on e-ink — the window's brightness
            // attribute is applied as the screen wakes. On xrz firmware the service
            // has already dropped the hardware frontlight in onScreenOff(), so this
            // is the second line of defence rather than the only one.
            window.attributes = window.attributes.apply {
                screenBrightness = 0.0f
            }
            EinkCompat.dimFrontlight(this)
        }

        if (!isPreview) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                // setTurnScreenOn issues its own wake, carrying WindowManager's
                // details string rather than the service's — which clears the
                // vendor's wake-up-only flag and brings the frontlight straight
                // back up. When the service took the tagged wake lock the panel is
                // already on, so asking again is both redundant and the thing that
                // reintroduces the flash. Without that path we still need it.
                if (!ScreenSaverService.canUseVendorWake()) {
                    setTurnScreenOn(true)
                }
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }

            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)
        }

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

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
        headerRow = findViewById(R.id.headerRow)
        clockSection = findViewById(R.id.clockSection)
        clockText = findViewById(R.id.clockText)
        dateText = findViewById(R.id.dateText)

        batteryRow = findViewById(R.id.batteryRow)
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryText = findViewById(R.id.batteryText)

        notificationsRow = findViewById(R.id.notificationsRow)
        notifSmsText = findViewById(R.id.notifSmsText)
        notifTelegramText = findViewById(R.id.notifTelegramText)
        notifCallsText = findViewById(R.id.notifCallsText)


        weatherCurrentText = findViewById(R.id.weatherCurrentText)
        weatherDayNightText = findViewById(R.id.weatherDayNightText)
        weatherSummary = findViewById(R.id.weatherSummary)
        weatherForecastRow = findViewById(R.id.weatherForecastRow)

        newsSection = findViewById(R.id.newsSection)
        newsText = findViewById(R.id.newsText)

        stickersSection = findViewById(R.id.stickersSection)

        bookSection = findViewById(R.id.bookSection)
        bookCoverImage = findViewById(R.id.bookCoverImage)
        bookTitleText = findViewById(R.id.bookTitleText)
        bookAuthorText = findViewById(R.id.bookAuthorText)
        bookAnnotationText = findViewById(R.id.bookAnnotationText)

        if (isPreview) {
            // The screen is immersive with no visible navigation, so a tap has to
            // close it as well as Back.
            findViewById<View>(R.id.touchInterceptor).setOnClickListener { finish() }
        }

        registerReceivers()

        // Pin the window to a partial waveform. Every redraw after the one below
        // is a clock tick or a data refresh — a few hundred pixels — and none of
        // them are worth flashing the whole panel for. No-op off xrz firmware,
        // where there is no waveform control and the flash in fullRefresh() is
        // the only lever we have.
        EinkCompat.setWindowRefreshMode(window, PARTIAL_REFRESH_MODE)

        // A launch always clears: the panel is still holding whatever was on
        // screen before the lock, and no partial waveform gets rid of that.
        fullRefresh()

        EventLog.log(
            EventLog.SRC_LOCK, "CREATE",
            "preview=$isPreview vendorWake=${ScreenSaverService.canUseVendorWake()} " +
                "turnScreenOn=${!isPreview && !ScreenSaverService.canUseVendorWake()} " +
                "locked=${keyguardManager.isKeyguardLocked}"
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent → updateDisplay")
        refreshDisplay()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")

        if (isPreview) {
            // isActive stays false: it is the service's guard for whether a real
            // lock screen is already up, and a preview must not suppress one.
            updateDisplay()
            return
        }

        isActive = true

        if (!keyguardManager.isDeviceLocked) {
            EventLog.log(EventLog.SRC_LOCK, "RESUME", "device already unlocked → finish")
            vibrateConfirmation()
            finishOnUnlock()
            return
        }

        EventLog.log(
            EventLog.SRC_LOCK, "RESUME",
            "backgrounded=$wasBackgrounded promptPending=$unlockPromptPending"
        )

        if (wasBackgrounded) {
            wasBackgrounded = false
            fullRefresh()
        } else {
            updateDisplay()
        }
        startUnlockPolling()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        EventLog.log(EventLog.SRC_LOCK, "STOP", "preview=$isPreview")
        stopUnlockPolling()
        if (!isPreview) wasBackgrounded = true
        // The activity is singleTask, so a preview left in the background would be
        // handed to the service's next launch through onNewIntent and would keep
        // behaving like a preview — full brightness, no unlock poll. Ending it
        // here means only a real lock screen can ever be the live instance.
        if (isPreview) finish()
    }

    override fun onDestroy() {
        if (!isPreview) isActive = false
        handler.removeCallbacksAndMessages(null)
        unregisterReceivers()
        EventLog.log(EventLog.SRC_LOCK, "DESTROY", "preview=$isPreview")
        super.onDestroy()
    }

    // ════════ Touch blocking ════════

    /** Back is swallowed on the real lock screen; a preview has to be closable. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isPreview) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /**
     * This activity sits over the keyguard with setShowWhenLocked, so pressing
     * power used to light the panel on the dashboard and nothing else — no
     * pattern, no fingerprint hint. Asking the keyguard to dismiss brings its
     * own prompt up, and it takes the fingerprint as readily as the pattern.
     *
     * Only for wakes the user caused: the service marks its own redraw wakes,
     * which arrive as an identical SCREEN_ON every update interval and must not
     * put a prompt in front of nobody.
     */
    private fun promptForUnlockIfUserWoke() {
        if (ScreenSaverService.isSelfWake()) {
            Log.d(TAG, "SCREEN_ON came from our own redraw → no prompt")
            return
        }
        // isKeyguardLocked, not isDeviceLocked: the latter is false whenever the
        // keyguard is not secure or the device is in a trusted state, and the
        // prompt is exactly what is wanted in those cases too. (The unlock poll
        // keeps using isDeviceLocked — it answers a different question.)
        if (!keyguardManager.isKeyguardLocked) return
        try {
            keyguardManager.requestDismissKeyguard(this, null)
            Log.d(TAG, "asked the keyguard for its unlock prompt")
        } catch (e: Throwable) {
            Log.w(TAG, "requestDismissKeyguard failed: ${e.message}")
        }
    }

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

    private fun finishOnUnlock() {
        EventLog.log(EventLog.SRC_LOCK, "UNLOCK", "clearing the panel and finishing")
        stopUnlockPolling()
        // The poll is the reliable unlock signal here — USER_PRESENT proved flaky —
        // so restore the frontlight from this path too, not just from the service.
        EinkCompat.restoreFrontlight(this)
        clearPanelOnExit()
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

    // ════════ Battery ════════

    /**
     * Reads the sticky ACTION_BATTERY_CHANGED instead of registering a receiver:
     * the panel only ever shows what the last redraw put there, so a value
     * pulled at redraw time is exactly as fresh as the clock beside it, and
     * nothing has to stay registered while the device sleeps.
     */
    private fun updateBattery() {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (status == null || level < 0 || scale <= 0) {
            batteryRow.visibility = View.GONE
            return
        }

        val percent = (level * 100f / scale).roundToInt().coerceIn(0, 100)
        val charging = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        batteryIcon.level = percent
        batteryText.text = if (charging) "⚡$percent%" else "$percent%"
        batteryRow.visibility = View.VISIBLE
    }

    // ════════ Notifications ════════

    /**
     * Missed SMS, Telegram and calls, one glyph and a number each, hidden when
     * the count is zero. The whole row goes with them, so an empty corner is
     * genuinely empty rather than three zeroes.
     *
     * The counting lives in [NotificationListener]; if the user has not granted
     * notification access it is never bound and every count stays 0.
     */
    private fun updateNotifications() {
        val counts = NotificationListener.snapshot(this)
        showCount(notifSmsText, "✉", counts.sms)
        showCount(notifTelegramText, "✈", counts.telegram)
        showCount(notifCallsText, "☎", counts.missedCalls)
        notificationsRow.visibility = if (counts.isEmpty) View.GONE else View.VISIBLE
    }

    private fun showCount(view: TextView, glyph: String, count: Int) {
        if (count <= 0) {
            view.visibility = View.GONE
            return
        }
        view.text = "$glyph $count"
        view.visibility = View.VISIBLE
    }

    // ════════ E-ink refresh ════════

    /**
     * The entry point for every redraw that is not the activity's first. It is
     * a plain [updateDisplay] — the window's partial waveform does the rest —
     * until [FULL_REFRESH_INTERVAL_MS] has gone by, and then one full pass
     * clears the ghosting the partial updates have piled up.
     */
    private fun refreshDisplay() {
        val since = SystemClock.elapsedRealtime() - lastFullRefreshMs
        if (since >= FULL_REFRESH_INTERVAL_MS) {
            EventLog.log(EventLog.SRC_LOCK, "DRAW", "mode=full due=${since / 60_000}min")
            fullRefresh()
        } else {
            EventLog.log(EventLog.SRC_LOCK, "DRAW", "mode=partial since=${since / 60_000}min")
            updateDisplay()
        }
    }

    /**
     * Clearing passes on the way out, which is the one moment the panel is
     * handed to something that does not know what we did to it.
     *
     * Between full refreshes the window runs on a partial waveform, so by the
     * time the user unlocks the panel is carrying whatever ghosting the clock
     * ticks since the last clearing pass have left — and it stays there under
     * the launcher, because nothing else on the device will clear it.
     *
     * Two passes: one now, while our content is still what the panel holds, and
     * one [EXIT_REFRESH_DELAY_MS] later, by which point the screen behind us has
     * drawn and any smearing from the transition itself goes with it. The second
     * gets a handler of its own — the activity's is emptied in `onDestroy`, long
     * before this is due.
     *
     * Vendor path only. Without the xrz framework there is no waveform to ask
     * for, and flashing the decor view black on the way out would look worse
     * than the ghosting it cleared.
     */
    private fun clearPanelOnExit() {
        if (!EinkCompat.isSupported) return
        lastFullRefreshMs = SystemClock.elapsedRealtime()
        EinkCompat.forceGlobalRefresh(EinkCompat.MODE_CLEAN)
        Handler(Looper.getMainLooper()).postDelayed(
            { EinkCompat.forceGlobalRefresh(EinkCompat.MODE_CLEAN) },
            EXIT_REFRESH_DELAY_MS
        )
    }

    /** Redraw plus one clearing pass over the whole panel. */
    private fun fullRefresh() {
        lastFullRefreshMs = SystemClock.elapsedRealtime()
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

    /**
     * Weather code to emoji. Handles both what the current source stores and
     * what the previous one did, because a cache written before the switch to
     * Open-Meteo is still valid until the next fetch:
     *
     *  - a WMO code as a plain number ("0", "61", "95") — Open-Meteo
     *  - an OpenWeatherMap icon id ("10d", "01n")
     *  - neither, in which case the description is matched as a last resort
     */
    private fun weatherIconForCode(code: String, fallbackDesc: String, isDay: Boolean = true): String {
        val wmo = code.toIntOrNull()
        if (wmo != null) {
            return when (wmo) {
                0 -> if (isDay) "☀️" else "🌙"                  // clear
                1, 2 -> "⛅"                                                   // partly cloudy
                3 -> "☁️"                                                // overcast
                45, 48 -> "🌫️"                                     // fog
                in 51..57 -> "🌦️"                                  // drizzle
                in 61..67 -> "☔"                                              // rain
                in 71..77 -> "❄️"                                        // snow
                in 80..82 -> "🌧️"                                  // rain showers
                85, 86 -> "❄️"                                           // snow showers
                95, 96, 99 -> "⚡"                                             // thunderstorm
                else -> "☁️"
            }
        }
        // The trailing d/n is day/night; only the clear-sky icon differs by it.
        return when (code.take(2)) {
            "01" -> if (code.endsWith("n")) "🌙" else "☀️"
            "02" -> "⛅"
            "03", "04" -> "☁️"
            "09" -> "🌧️"
            "10" -> "☔"
            "11" -> "⚡"
            "13" -> "❄️"
            "50" -> "🌫️"
            else -> weatherIcon(fallbackDesc)
        }
    }

    /**
     * One forecast slot: hour on top, then the icon with a small two-line
     * precipitation column beside it, then the temperature. The column appears
     * only when rain or snow is actually expected — see [WeatherData.ForecastItem.hasPrecip].
     *
     * [baseSp] is small by design (7-10sp): these sit in the corner beside the
     * clock, so the sizes here are offsets from it rather than from the module
     * font size.
     */
    private fun buildForecastSlot(item: WeatherData.ForecastItem, baseSp: Float): View {
        val density = resources.displayMetrics.density
        val slot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // wrap_content, not weight: the row itself is wrap_content in the
            // corner, so weights would collapse every slot to nothing.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (6 * density).toInt() }
        }

        slot.addView(TextView(this).apply {
            text = item.hour
            textSize = baseSp
            setTextColor(0xFF555555.toInt())
            includeFontPadding = false
        })

        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconRow.addView(TextView(this).apply {
            text = weatherIconForCode(item.icon, "", item.isDay)
            textSize = baseSp + 3f
            includeFontPadding = false
        })
        if (item.hasPrecip) {
            val precip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = (2 * density).toInt() }
            }
            precip.addView(TextView(this).apply {
                text = "${item.pop}%"
                textSize = baseSp - 1f
                setTextColor(0xFF333333.toInt())
                includeFontPadding = false
            })
            if (item.precipMm > 0.0) {
                precip.addView(TextView(this).apply {
                    text = getString(R.string.weather_precip_mm, item.precipMm)
                    textSize = baseSp - 1f
                    setTextColor(0xFF555555.toInt())
                    includeFontPadding = false
                })
            }
            iconRow.addView(precip)
        }
        slot.addView(iconRow)

        slot.addView(TextView(this).apply {
            val sign = if (item.temp > 0) "+" else ""
            text = "$sign${item.temp}°"
            textSize = baseSp + 1f
            setTextColor(0xFF000000.toInt())
            includeFontPadding = false
        })

        return slot
    }

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
        "clock" -> headerRow
        // Weather now lives inside the header row, so it owns no slot in the
        // stack. The key stays valid for saved orders; mapNotNull skips it.
        "weather" -> null
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

    /**
     * Applies the clock-position setting to the whole header row: the clock and
     * the weather always sit on opposite sides, so putting the clock on the
     * right swaps their order and mirrors the alignment of both.
     */
    private fun applyClockPosition() {
        val clockOnRight = PrefsManager.getClockPosition(this) == "right"
        val clockGravity = if (clockOnRight) Gravity.END else Gravity.START
        val weatherGravity = if (clockOnRight) Gravity.START else Gravity.END

        clockText.gravity = clockGravity
        dateText.gravity = clockGravity

        val lp = clockText.layoutParams as LinearLayout.LayoutParams
        lp.gravity = clockGravity
        clockText.layoutParams = lp

        val dlp = dateText.layoutParams as LinearLayout.LayoutParams
        dlp.gravity = clockGravity
        dateText.layoutParams = dlp

        weatherSummary.gravity = weatherGravity
        weatherCurrentText.gravity = weatherGravity
        weatherDayNightText.gravity = weatherGravity
        weatherForecastRow.gravity = weatherGravity

        // The 12dp gap belongs between the two columns, so it moves to whichever
        // side of the weather block faces the clock.
        val gap = (12 * resources.displayMetrics.density).toInt()
        (weatherSummary.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = if (clockOnRight) 0 else gap
            marginEnd = if (clockOnRight) gap else 0
            weatherSummary.layoutParams = this
        }

        // Re-add in the right order only when it actually changed; removeAllViews
        // on every redraw would throw away the forecast slots for nothing.
        val clockFirst = headerRow.getChildAt(0) === clockSection
        if (clockFirst == clockOnRight) {
            headerRow.removeView(clockSection)
            headerRow.removeView(weatherSummary)
            if (clockOnRight) {
                headerRow.addView(weatherSummary)
                headerRow.addView(clockSection)
            } else {
                headerRow.addView(clockSection)
                headerRow.addView(weatherSummary)
            }
        }
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

        // Battery and the missed-notification corner
        updateBattery()
        updateNotifications()

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
                    val icon = weatherIconForCode(weather.currentIcon, weather.currentDesc, weather.currentIsDay)
                    val tempSign = if (weather.currentTemp > 0) "+" else ""
                    // The summary sits beside the clock now, so it drops the long
                    // description \u2014 that line would push the row off the screen.
                    weatherCurrentText.text = "$icon ${tempSign}${weather.currentTemp}\u00B0C"
                    weatherCurrentText.textSize = weatherFontSize

                    val daySign = if (weather.dayTemp > 0) "+" else ""
                    val nightSign = if (weather.nightTemp > 0) "+" else ""
                    weatherDayNightText.text = "\u2600\uFE0F ${daySign}${weather.dayTemp}\u00B0 / \uD83C\uDF19 ${nightSign}${weather.nightTemp}\u00B0"
                    weatherDayNightText.textSize = weatherFontSize - 3f
                    weatherSummary.visibility = View.VISIBLE

                    // Deliberately much smaller than the summary: four slots with
                    // an icon and a precipitation column each have to fit the
                    // corner beside the clock.
                    val slotSize = (weatherFontSize - 6f).coerceIn(7f, 10f)
                    weatherForecastRow.removeAllViews()
                    if (weather.forecast.isNotEmpty()) {
                        for (item in weather.forecast) {
                            weatherForecastRow.addView(buildForecastSlot(item, slotSize))
                        }
                        weatherForecastRow.visibility = View.VISIBLE
                    } else {
                        weatherForecastRow.visibility = View.GONE
                    }
                } else {
                    weatherSummary.visibility = View.GONE
                }
            } else {
                weatherSummary.visibility = View.GONE
            }
        } else {
            weatherSummary.visibility = View.GONE
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
                // 24dp in pixels, matching the offset the news and book sections
                // use. The old value was 20 raw pixels, which is shorter than the
                // 12sp name label on this density, so the notes overlapped it.
                ).apply { topMargin = (24 * resources.displayMetrics.density).toInt() }
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
