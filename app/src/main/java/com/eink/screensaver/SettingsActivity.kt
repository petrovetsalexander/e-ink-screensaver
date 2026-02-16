package com.eink.screensaver

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var intervalGroup: RadioGroup
    private lateinit var radio1min: RadioButton
    private lateinit var radio2min: RadioButton
    private lateinit var radio5min: RadioButton
    private lateinit var cbBrightness: CheckBox
    private lateinit var cbShowDate: CheckBox
    private lateinit var permissionStatusText: TextView
    private lateinit var btnGrantNotification: Button
    private lateinit var btnGrantFullScreen: Button

    // Module toggles
    private lateinit var cbBlockClock: CheckBox
    private lateinit var cbBlockWeather: CheckBox
    private lateinit var cbBlockNews: CheckBox
    private lateinit var cbBlockNotes: CheckBox
    private lateinit var cbBlockBook: CheckBox
    private lateinit var cbBookBackground: CheckBox

    // Weather
    private lateinit var etWeatherCity: EditText
    private lateinit var etWeatherApiKey: EditText
    private lateinit var weatherIntervalGroup: RadioGroup
    private lateinit var radioWeather30: RadioButton
    private lateinit var radioWeather60: RadioButton
    private lateinit var btnSaveWeather: Button

    // News
    private lateinit var etNewsRssUrl: EditText
    private lateinit var newsIntervalGroup: RadioGroup
    private lateinit var radioNews5: RadioButton
    private lateinit var radioNews15: RadioButton
    private lateinit var radioNews30: RadioButton
    private lateinit var radioNews60: RadioButton
    private lateinit var btnSaveNews: Button

    // Bookmate
    private lateinit var etBookmateUserId: EditText
    private lateinit var btnSaveBookmate: Button

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateUI()
    }

    private val fullScreenPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        intervalGroup = findViewById(R.id.intervalGroup)
        radio1min = findViewById(R.id.radio1min)
        radio2min = findViewById(R.id.radio2min)
        radio5min = findViewById(R.id.radio5min)
        cbBrightness = findViewById(R.id.cbBrightness)
        cbShowDate = findViewById(R.id.cbShowDate)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)
        btnGrantFullScreen = findViewById(R.id.btnGrantFullScreen)

        // Module toggles
        cbBlockClock = findViewById(R.id.cbBlockClock)
        cbBlockWeather = findViewById(R.id.cbBlockWeather)
        cbBlockNews = findViewById(R.id.cbBlockNews)
        cbBlockNotes = findViewById(R.id.cbBlockNotes)
        cbBlockBook = findViewById(R.id.cbBlockBook)
        cbBookBackground = findViewById(R.id.cbBookBackground)

        // Weather
        etWeatherCity = findViewById(R.id.etWeatherCity)
        etWeatherApiKey = findViewById(R.id.etWeatherApiKey)
        weatherIntervalGroup = findViewById(R.id.weatherIntervalGroup)
        radioWeather30 = findViewById(R.id.radioWeather30)
        radioWeather60 = findViewById(R.id.radioWeather60)
        btnSaveWeather = findViewById(R.id.btnSaveWeather)

        // News
        etNewsRssUrl = findViewById(R.id.etNewsRssUrl)
        newsIntervalGroup = findViewById(R.id.newsIntervalGroup)
        radioNews5 = findViewById(R.id.radioNews5)
        radioNews15 = findViewById(R.id.radioNews15)
        radioNews30 = findViewById(R.id.radioNews30)
        radioNews60 = findViewById(R.id.radioNews60)
        btnSaveNews = findViewById(R.id.btnSaveNews)

        // Bookmate
        etBookmateUserId = findViewById(R.id.etBookmateUserId)
        btnSaveBookmate = findViewById(R.id.btnSaveBookmate)

        loadSettings()

        toggleButton.setOnClickListener { toggleService() }

        intervalGroup.setOnCheckedChangeListener { _, checkedId ->
            val minutes = when (checkedId) {
                R.id.radio1min -> 1
                R.id.radio2min -> 2
                R.id.radio5min -> 5
                else -> 1
            }
            PrefsManager.setUpdateIntervalMinutes(this, minutes)
        }

        cbBrightness.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBrightnessOff(this, isChecked)
        }

        cbShowDate.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setShowDate(this, isChecked)
        }

        // Module toggles
        cbBlockClock.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBlockClockEnabled(this, isChecked)
        }
        cbBlockWeather.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBlockWeatherEnabled(this, isChecked)
        }
        cbBlockNews.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBlockNewsEnabled(this, isChecked)
        }
        cbBlockNotes.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBlockNotesEnabled(this, isChecked)
        }
        cbBlockBook.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBlockBookEnabled(this, isChecked)
        }
        cbBookBackground.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBookBackgroundEnabled(this, isChecked)
        }

        btnGrantNotification.setOnClickListener {
            requestNotificationPermission()
        }

        btnGrantFullScreen.setOnClickListener {
            requestFullScreenPermission()
        }

        // Weather save
        btnSaveWeather.setOnClickListener {
            PrefsManager.setWeatherCity(this, etWeatherCity.text.toString().trim())
            PrefsManager.setWeatherApiKey(this, etWeatherApiKey.text.toString().trim())
            val interval = when (weatherIntervalGroup.checkedRadioButtonId) {
                R.id.radioWeather60 -> 60
                else -> 30
            }
            PrefsManager.setWeatherIntervalMin(this, interval)
            Toast.makeText(this, "\u041F\u043E\u0433\u043E\u0434\u0430 \u0441\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u0430", Toast.LENGTH_SHORT).show()
        }

        // News save
        btnSaveNews.setOnClickListener {
            PrefsManager.setNewsRssUrl(this, etNewsRssUrl.text.toString().trim())
            val interval = when (newsIntervalGroup.checkedRadioButtonId) {
                R.id.radioNews15 -> 15
                R.id.radioNews30 -> 30
                R.id.radioNews60 -> 60
                else -> 5
            }
            PrefsManager.setNewsIntervalMin(this, interval)
            Toast.makeText(this, "\u041D\u043E\u0432\u043E\u0441\u0442\u0438 \u0441\u043E\u0445\u0440\u0430\u043D\u0435\u043D\u044B", Toast.LENGTH_SHORT).show()
        }

        // Bookmate save
        btnSaveBookmate.setOnClickListener {
            val userId = etBookmateUserId.text.toString().trim()
            PrefsManager.setBookmateUserId(this, userId)
            PrefsManager.setBookmateCache(this, "")
            Toast.makeText(this, "Bookmate \u0441\u043E\u0445\u0440\u0430\u043D\u0435\u043D", Toast.LENGTH_SHORT).show()
        }

        // How it works popup
        findViewById<TextView>(R.id.btnHowItWorks).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("\u041A\u0430\u043A \u044D\u0442\u043E \u0440\u0430\u0431\u043E\u0442\u0430\u0435\u0442")
                .setMessage(
                    "\u2022 \u041A\u043D\u043E\u043F\u043A\u0430 \u043F\u0438\u0442\u0430\u043D\u0438\u044F \u2192 \u0441\u0435\u0440\u0432\u0438\u0441 \u0437\u0430\u0445\u0432\u0430\u0442\u044B\u0432\u0430\u0435\u0442 WakeLock \u0438 \u043F\u043E\u043A\u0430\u0437\u044B\u0432\u0430\u0435\u0442 \u0447\u0430\u0441\u044B \u0447\u0435\u0440\u0435\u0437 \u043F\u043E\u043B\u043D\u043E\u044D\u043A\u0440\u0430\u043D\u043D\u043E\u0435 \u0443\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0435 (\u043A\u0430\u043A \u0431\u0443\u0434\u0438\u043B\u044C\u043D\u0438\u043A)\n\n" +
                    "\u2022 Brightness=0 \u2014 \u043F\u043E\u0434\u0441\u0432\u0435\u0442\u043A\u0430 \u043D\u0435 \u043C\u0438\u0433\u0430\u0435\u0442, e-ink \u0432\u044B\u043F\u043E\u043B\u043D\u044F\u0435\u0442 refresh\n\n" +
                    "\u2022 WakeLock \u0438\u0441\u0442\u0435\u043A\u0430\u0435\u0442 \u2192 \u044D\u043A\u0440\u0430\u043D \u0441\u043F\u0438\u0442, e-ink \u0441\u043E\u0445\u0440\u0430\u043D\u044F\u0435\u0442 \u0447\u0430\u0441\u044B\n\n" +
                    "\u2022 \u041F\u0440\u0438 \u043F\u0440\u043E\u0431\u0443\u0436\u0434\u0435\u043D\u0438\u0438 \u0447\u0430\u0441\u044B \u0443\u0436\u0435 \u0432\u0438\u0434\u043D\u044B, \u043E\u0431\u043D\u043E\u0432\u043B\u044F\u044E\u0442\u0441\u044F \u043F\u043E \u0438\u043D\u0442\u0435\u0440\u0432\u0430\u043B\u0443\n\n" +
                    "\u2022 \u041F\u043E\u0437\u0438\u0446\u0438\u044F \u0441\u043C\u0435\u0449\u0430\u0435\u0442\u0441\u044F \u0434\u043B\u044F \u043F\u0440\u0435\u0434\u043E\u0442\u0432\u0440\u0430\u0449\u0435\u043D\u0438\u044F \u0433\u043E\u0441\u0442\u0438\u043D\u0433\u0430\n\n" +
                    "\u2022 \u0420\u0430\u0437\u0431\u043B\u043E\u043A\u0438\u0440\u043E\u0432\u043A\u0430: \u043F\u0430\u043B\u0435\u0446 \u043D\u0430 \u0434\u0430\u0442\u0447\u0438\u043A\u0435 \u2192 \u043C\u0433\u043D\u043E\u0432\u0435\u043D\u043D\u044B\u0439 unlock \u0441 \u0432\u0438\u0431\u0440\u0430\u0446\u0438\u0435\u0439\n\n" +
                    "\u0412\u0430\u0436\u043D\u043E: \u0443\u0431\u0435\u0434\u0438\u0442\u0435\u0441\u044C \u0447\u0442\u043E \u043A\u0430\u043D\u0430\u043B \u00AB\u0427\u0430\u0441\u044B \u043D\u0430 \u044D\u043A\u0440\u0430\u043D\u0435 \u0431\u043B\u043E\u043A\u0438\u0440\u043E\u0432\u043A\u0438\u00BB \u0432\u043A\u043B\u044E\u0447\u0435\u043D \u0432 \u043D\u0430\u0441\u0442\u0440\u043E\u0439\u043A\u0430\u0445 \u0443\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u0439"
                )
                .setPositiveButton("OK", null)
                .show()
        }

        // About popup
        findViewById<TextView>(R.id.btnAbout).setOnClickListener {
            val message = SpannableString(
                "E-Ink \u0421\u043A\u0440\u0438\u043D\u0441\u0435\u0439\u0432\u0435\u0440\n\n" +
                "\u0410\u0432\u0442\u043E\u0440: Alexander Petrovets\n" +
                "Telegram: https://t.me/petrovets\n\n" +
                "\u00A9 2026 Alexander Petrovets"
            )
            Linkify.addLinks(message, Linkify.WEB_URLS)
            val dialog = AlertDialog.Builder(this)
                .setTitle("\u041E \u043F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u0438")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
            dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun loadSettings() {
        when (PrefsManager.getUpdateIntervalMinutes(this)) {
            1 -> radio1min.isChecked = true
            2 -> radio2min.isChecked = true
            5 -> radio5min.isChecked = true
        }
        cbBrightness.isChecked = PrefsManager.isBrightnessOff(this)
        cbShowDate.isChecked = PrefsManager.isShowDate(this)

        // Module toggles
        cbBlockClock.isChecked = PrefsManager.isBlockClockEnabled(this)
        cbBlockWeather.isChecked = PrefsManager.isBlockWeatherEnabled(this)
        cbBlockNews.isChecked = PrefsManager.isBlockNewsEnabled(this)
        cbBlockNotes.isChecked = PrefsManager.isBlockNotesEnabled(this)
        cbBlockBook.isChecked = PrefsManager.isBlockBookEnabled(this)
        cbBookBackground.isChecked = PrefsManager.isBookBackgroundEnabled(this)

        // Weather
        etWeatherCity.setText(PrefsManager.getWeatherCity(this))
        etWeatherApiKey.setText(PrefsManager.getWeatherApiKey(this))
        when (PrefsManager.getWeatherIntervalMin(this)) {
            60 -> radioWeather60.isChecked = true
            else -> radioWeather30.isChecked = true
        }

        // News
        etNewsRssUrl.setText(PrefsManager.getNewsRssUrl(this))
        when (PrefsManager.getNewsIntervalMin(this)) {
            15 -> radioNews15.isChecked = true
            30 -> radioNews30.isChecked = true
            60 -> radioNews60.isChecked = true
            else -> radioNews5.isChecked = true
        }

        // Bookmate
        etBookmateUserId.setText(PrefsManager.getBookmateUserId(this))
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasFullScreenPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.canUseFullScreenIntent()
        } else {
            true
        }
    }

    private fun updateUI() {
        val isEnabled = PrefsManager.isEnabled(this)
        val hasNotif = hasNotificationPermission()
        val hasFullScreen = hasFullScreenPermission()
        val allPermissions = hasNotif && hasFullScreen

        val notifMark = if (hasNotif) "\u2713" else "\u2717"
        val fsMark = if (hasFullScreen) "\u2713" else "\u2717"
        permissionStatusText.text =
            "\u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u044F: $notifMark\n\u041F\u043E\u043B\u043D\u043E\u044D\u043A\u0440\u0430\u043D\u043D\u044B\u0435 \u0443\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u044F: $fsMark"

        btnGrantNotification.isEnabled = !hasNotif
        btnGrantFullScreen.isEnabled = !hasFullScreen
        toggleButton.isEnabled = allPermissions

        if (isEnabled) {
            toggleButton.text = "\u0412\u044B\u043A\u043B\u044E\u0447\u0438\u0442\u044C \u0441\u043A\u0440\u0438\u043D\u0441\u0435\u0439\u0432\u0435\u0440"
            statusText.text = "\u0421\u0442\u0430\u0442\u0443\u0441: \u0430\u043A\u0442\u0438\u0432\u0435\u043D"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            toggleButton.text = "\u0412\u043A\u043B\u044E\u0447\u0438\u0442\u044C \u0441\u043A\u0440\u0438\u043D\u0441\u0435\u0439\u0432\u0435\u0440"
            statusText.text = if (allPermissions) "\u0421\u0442\u0430\u0442\u0443\u0441: \u0432\u044B\u043A\u043B\u044E\u0447\u0435\u043D" else "\u0421\u0442\u0430\u0442\u0443\u0441: \u043D\u0443\u0436\u043D\u044B \u0440\u0430\u0437\u0440\u0435\u0448\u0435\u043D\u0438\u044F"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun toggleService() {
        val isEnabled = PrefsManager.isEnabled(this)
        if (isEnabled) {
            PrefsManager.setEnabled(this, false)
            BootReceiver.stopService(this)
            Toast.makeText(this, "\u0421\u043A\u0440\u0438\u043D\u0441\u0435\u0439\u0432\u0435\u0440 \u0432\u044B\u043A\u043B\u044E\u0447\u0435\u043D", Toast.LENGTH_SHORT).show()
        } else {
            PrefsManager.setEnabled(this, true)
            BootReceiver.startService(this)
            Toast.makeText(this, "\u0421\u043A\u0440\u0438\u043D\u0441\u0435\u0439\u0432\u0435\u0440 \u0432\u043A\u043B\u044E\u0447\u0435\u043D", Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestFullScreenPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")
                )
                fullScreenPermissionLauncher.launch(intent)
                return
            } catch (_: Exception) {}

            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                fullScreenPermissionLauncher.launch(intent)
                return
            } catch (_: Exception) {}

            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                fullScreenPermissionLauncher.launch(intent)
                return
            } catch (_: Exception) {}

            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                fullScreenPermissionLauncher.launch(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "\u041E\u0442\u043A\u0440\u043E\u0439\u0442\u0435 \u041D\u0430\u0441\u0442\u0440\u043E\u0439\u043A\u0438 \u2192 \u041F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u044F \u2192 E-Ink \u0421\u043A\u0440\u0438\u043D\u0441\u0435\u0439\u0432\u0435\u0440 \u2192 \u0423\u0432\u0435\u0434\u043E\u043C\u043B\u0435\u043D\u0438\u044F",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
