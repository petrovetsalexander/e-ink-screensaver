package com.eink.screensaver.settings

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
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.eink.screensaver.BootReceiver
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R
import com.eink.screensaver.SleepAccessibilityService

class SettingsActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var permissionSection: LinearLayout
    private lateinit var permissionStatusText: TextView
    private lateinit var btnGrantNotification: Button
    private lateinit var btnGrantFullScreen: Button
    private lateinit var btnGrantA11y: Button
    private lateinit var languageGroup: RadioGroup

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
        permissionSection = findViewById(R.id.permissionSection)
        permissionStatusText = findViewById(R.id.permissionStatusText)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)
        btnGrantFullScreen = findViewById(R.id.btnGrantFullScreen)
        btnGrantA11y = findViewById(R.id.btnGrantA11y)
        languageGroup = findViewById(R.id.languageGroup)

        // Language selector
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) {
            languageGroup.check(R.id.radioLangSystem)
        } else {
            when (currentLocales.get(0)?.language) {
                "ru" -> languageGroup.check(R.id.radioLangRussian)
                "en" -> languageGroup.check(R.id.radioLangEnglish)
                else -> languageGroup.check(R.id.radioLangSystem)
            }
        }

        languageGroup.setOnCheckedChangeListener { _, checkedId ->
            val localeList = when (checkedId) {
                R.id.radioLangEnglish -> LocaleListCompat.forLanguageTags("en")
                R.id.radioLangRussian -> LocaleListCompat.forLanguageTags("ru")
                else -> LocaleListCompat.getEmptyLocaleList()
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        }

        toggleButton.setOnClickListener { toggleService() }
        btnGrantNotification.setOnClickListener { requestNotificationPermission() }
        btnGrantFullScreen.setOnClickListener { requestFullScreenPermission() }
        btnGrantA11y.setOnClickListener { openAccessibilitySettings() }

        // Module settings
        findViewById<Button>(R.id.btnModuleSettings).setOnClickListener {
            startActivity(Intent(this, ModuleSettingsActivity::class.java))
        }

        // How it works popup
        findViewById<TextView>(R.id.btnHowItWorks).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.how_it_works_title)
                .setMessage(R.string.how_it_works_text)
                .setPositiveButton("OK", null)
                .show()
        }

        // About popup
        findViewById<TextView>(R.id.btnAbout).setOnClickListener {
            val message = SpannableString(getString(R.string.about_text))
            Linkify.addLinks(message, Linkify.WEB_URLS)
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
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

    @Suppress("SetTextI18n")
    private fun updateUI() {
        val isEnabled = PrefsManager.isEnabled(this)
        val hasNotif = hasNotificationPermission()
        val hasFullScreen = hasFullScreenPermission()
        val hasA11y = SleepAccessibilityService.isEnabledInSettings(this)
        // The accessibility service only suppresses the frontlight flash, so it
        // gates nothing \u2014 but the section stays up while it is off, otherwise the
        // offer would be undiscoverable.
        val allPermissions = hasNotif && hasFullScreen

        if (allPermissions && hasA11y) {
            permissionSection.visibility = View.GONE
        } else {
            permissionSection.visibility = View.VISIBLE
            val notifMark = if (hasNotif) "\u2713" else "\u2717"
            val fsMark = if (hasFullScreen) "\u2713" else "\u2717"
            val a11yMark = if (hasA11y) "\u2713" else "\u2717"
            permissionStatusText.text = buildString {
                append(getString(R.string.notifications_label, notifMark)).append("\n")
                append(getString(R.string.fullscreen_notifications_label, fsMark)).append("\n")
                append(getString(R.string.a11y_label, a11yMark))
                if (!hasA11y) append("\n").append(getString(R.string.a11y_hint))
            }
            btnGrantNotification.isEnabled = !hasNotif
            btnGrantFullScreen.isEnabled = !hasFullScreen
            btnGrantA11y.isEnabled = !hasA11y
        }

        toggleButton.isEnabled = allPermissions

        if (isEnabled) {
            toggleButton.text = getString(R.string.disable_screensaver)
            if (allPermissions) {
                statusText.text = getString(R.string.status_active) + " \u00B7 " + getString(R.string.permissions_granted)
            } else {
                statusText.text = getString(R.string.status_active)
            }
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            toggleButton.text = getString(R.string.enable_screensaver)
            if (allPermissions) {
                statusText.text = getString(R.string.status_off) + " \u00B7 " + getString(R.string.permissions_granted)
            } else {
                statusText.text = getString(R.string.status_needs_permissions)
            }
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun toggleService() {
        val isEnabled = PrefsManager.isEnabled(this)
        if (isEnabled) {
            PrefsManager.setEnabled(this, false)
            BootReceiver.stopService(this)
            Toast.makeText(this, R.string.screensaver_disabled, Toast.LENGTH_SHORT).show()
        } else {
            PrefsManager.setEnabled(this, true)
            BootReceiver.startService(this)
            Toast.makeText(this, R.string.screensaver_enabled, Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            fullScreenPermissionLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_settings_hint, Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, R.string.open_settings_hint, Toast.LENGTH_LONG).show()
            }
        }
    }
}
