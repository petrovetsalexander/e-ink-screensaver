package com.eink.screensaver.settings

import android.Manifest
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
import android.widget.CheckBox
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
import androidx.core.content.FileProvider
import androidx.core.os.LocaleListCompat
import com.eink.screensaver.BootReceiver
import com.eink.screensaver.EventLog
import com.eink.screensaver.NotificationListener
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R
import com.eink.screensaver.SleepAccessibilityService

class SettingsActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var permissionSection: LinearLayout
    private lateinit var permNotifLabel: TextView
    private lateinit var permA11yLabel: TextView
    private lateinit var permNotifAccessLabel: TextView
    private lateinit var permOverlayLabel: TextView
    private lateinit var a11yLostWarning: TextView
    private lateinit var btnGrantNotification: Button
    private lateinit var btnGrantA11y: Button
    private lateinit var btnGrantNotifAccess: Button
    private lateinit var btnGrantOverlay: Button
    private lateinit var languageGroup: RadioGroup
    private lateinit var checkEventLog: CheckBox
    private lateinit var eventLogStatus: TextView

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
        permNotifLabel = findViewById(R.id.permNotifLabel)
        permA11yLabel = findViewById(R.id.permA11yLabel)
        permNotifAccessLabel = findViewById(R.id.permNotifAccessLabel)
        permOverlayLabel = findViewById(R.id.permOverlayLabel)
        a11yLostWarning = findViewById(R.id.a11yLostWarning)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)
        btnGrantA11y = findViewById(R.id.btnGrantA11y)
        btnGrantNotifAccess = findViewById(R.id.btnGrantNotifAccess)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        languageGroup = findViewById(R.id.languageGroup)
        checkEventLog = findViewById(R.id.checkEventLog)
        eventLogStatus = findViewById(R.id.eventLogStatus)

        checkEventLog.isChecked = PrefsManager.isEventLogEnabled(this)
        checkEventLog.setOnCheckedChangeListener { _, checked ->
            EventLog.setEnabled(this, checked)
            updateEventLogStatus()
        }
        findViewById<Button>(R.id.btnShareLog).setOnClickListener { shareEventLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            EventLog.clear()
            Toast.makeText(this, R.string.event_log_cleared, Toast.LENGTH_SHORT).show()
            // The clear runs on the log's own writer thread; give it a moment
            // before reading the size back, or the label shows the old one.
            eventLogStatus.postDelayed({ updateEventLogStatus() }, 300)
        }

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
        btnGrantOverlay.setOnClickListener { openOverlaySettings() }

        findViewById<ImageButton>(R.id.btnWhyNotif).setOnClickListener {
            showPermissionHelp(R.string.perm_notifications_title, R.string.notifications_hint)
        }
        findViewById<ImageButton>(R.id.btnWhyA11y).setOnClickListener {
            showPermissionHelp(R.string.perm_a11y_title, R.string.a11y_hint)
        }
        findViewById<ImageButton>(R.id.btnWhyNotifAccess).setOnClickListener {
            showPermissionHelp(R.string.perm_notif_access_title, R.string.notif_access_hint)
        }
        findViewById<ImageButton>(R.id.btnWhyOverlay).setOnClickListener {
            showPermissionHelp(R.string.perm_overlay_title, R.string.overlay_hint)
        }

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
        val hasOverlay = Settings.canDrawOverlays(this)
        // The system clears the accessibility toggle on its own often enough
        // that "it was on and now it is not" is worth saying out loud.
        val a11yLost = !hasA11y && PrefsManager.wasA11yGranted(this)
        if (hasA11y) PrefsManager.setA11yGranted(this, true)
        updateEventLogStatus()
        // Notifications are the only hard requirement: a foreground service
        // cannot run without one. Return-to-sleep, notification access and the
        // overlay grant each only cost a feature, so none of them gates
        // anything \u2014 but the section stays up while any is off, otherwise the
        // offer would be undiscoverable. What each one buys lives behind its own
        // "?" rather than in a paragraph over the buttons.
        if (hasNotif && hasA11y && hasNotifAccess && hasOverlay) {
            permissionSection.visibility = View.GONE
        } else {
            permissionSection.visibility = View.VISIBLE
            permNotifLabel.text = getString(R.string.notifications_label, mark(hasNotif))
            permA11yLabel.text = getString(R.string.a11y_label, mark(hasA11y))
            permNotifAccessLabel.text = getString(R.string.notif_access_label, mark(hasNotifAccess))
            permOverlayLabel.text = getString(R.string.overlay_label, mark(hasOverlay))
            // The only thing that stays on the screen rather than hiding behind
            // a "?": it is not an explanation, it is something that has gone
            // wrong since the last time the user looked.
            a11yLostWarning.visibility = if (a11yLost) View.VISIBLE else View.GONE
            btnGrantNotification.isEnabled = !hasNotif
            btnGrantA11y.isEnabled = !hasA11y
            btnGrantNotifAccess.isEnabled = !hasNotifAccess
            btnGrantOverlay.isEnabled = !hasOverlay
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

    private fun mark(granted: Boolean): String = if (granted) "✓" else "✗"

    /** The "?" next to a permission: what it buys and what breaks without it. */
    private fun showPermissionHelp(titleRes: Int, messageRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openOverlaySettings() {
        try {
            settingsLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.open_settings_hint, Toast.LENGTH_LONG).show()
        }
    }

    // ════════ Event log ════════

    private fun updateEventLogStatus() {
        val kb = (EventLog.sizeBytes() / 1024L).toInt()
        eventLogStatus.text = when {
            !PrefsManager.isEventLogEnabled(this) && kb == 0 -> getString(R.string.event_log_off)
            kb == 0 -> getString(R.string.event_log_empty)
            else -> getString(R.string.event_log_size, kb)
        }
    }

    /**
     * Hands the trace to whatever the user picks — a messenger, mail, a file
     * manager. Plain text through a FileProvider uri rather than
     * `EXTRA_TEXT`: the log outgrows an intent extra within a day, and a file
     * survives being forwarded.
     */
    private fun shareEventLog() {
        val file = try {
            EventLog.export(this)
        } catch (e: Throwable) {
            null
        }
        if (file == null) {
            Toast.makeText(this, R.string.event_log_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.event_log_share_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.event_log_share)))
    }
}
