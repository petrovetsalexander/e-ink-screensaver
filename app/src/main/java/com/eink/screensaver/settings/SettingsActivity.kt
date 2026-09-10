package com.eink.screensaver.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.eink.screensaver.NotificationListener
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R
import com.eink.screensaver.SleepAccessibilityService

class SettingsActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var permissionSection: LinearLayout
    private lateinit var permissionStatusText: TextView
    private lateinit var btnGrantNotification: Button
    private lateinit var btnGrantA11y: Button
    private lateinit var btnGrantNotifAccess: Button
    private lateinit var languageGroup: RadioGroup

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateUI()
    }

    private val settingsLauncher = registerForActivityResult(
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
        btnGrantA11y = findViewById(R.id.btnGrantA11y)
        btnGrantNotifAccess = findViewById(R.id.btnGrantNotifAccess)
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
        btnGrantA11y.setOnClickListener { openAccessibilitySettings() }
        btnGrantNotifAccess.setOnClickListener { openNotificationAccessSettings() }

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

    @Suppress("SetTextI18n")
    private fun updateUI() {
        val isEnabled = PrefsManager.isEnabled(this)
        val hasNotif = hasNotificationPermission()
        val hasA11y = SleepAccessibilityService.isEnabledInSettings(this)
        val hasNotifAccess = NotificationListener.isEnabledInSettings(this)
        // Notifications are the only hard requirement: a foreground service
        // cannot run without one. The accessibility service only suppresses the
        // frontlight flash and notification access only fills the missed-message
        // corner, so neither gates anything, but the section stays up while they
        // are off \u2014 otherwise the offer would be undiscoverable.
        if (hasNotif && hasA11y && hasNotifAccess) {
            permissionSection.visibility = View.GONE
        } else {
            permissionSection.visibility = View.VISIBLE
            val notifMark = if (hasNotif) "\u2713" else "\u2717"
            val a11yMark = if (hasA11y) "\u2713" else "\u2717"
            val accessMark = if (hasNotifAccess) "\u2713" else "\u2717"
            permissionStatusText.text = buildString {
                append(getString(R.string.notifications_label, notifMark)).append("\n")
                append(getString(R.string.a11y_label, a11yMark))
                if (!hasA11y) append("\n").append(getString(R.string.a11y_hint))
                append("\n").append(getString(R.string.notif_access_label, accessMark))
                if (!hasNotifAccess) append("\n").append(getString(R.string.notif_access_hint))
            }
            btnGrantNotification.isEnabled = !hasNotif
            btnGrantA11y.isEnabled = !hasA11y
            btnGrantNotifAccess.isEnabled = !hasNotifAccess
        }

        toggleButton.isEnabled = hasNotif

        if (isEnabled) {
            toggleButton.text = getString(R.string.disable_screensaver)
            if (hasNotif) {
                statusText.text = getString(R.string.status_active) + " \u00B7 " + getString(R.string.permissions_granted)
            } else {
                statusText.text = getString(R.string.status_active)
            }
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            toggleButton.text = getString(R.string.enable_screensaver)
            if (hasNotif) {
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
            settingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_settings_hint, Toast.LENGTH_LONG).show()
        }
    }

    private fun openNotificationAccessSettings() {
        try {
            settingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_settings_hint, Toast.LENGTH_LONG).show()
        }
    }

}
