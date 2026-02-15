package com.eink.screensaver

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

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
        setContentView(R.layout.activity_main)

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

        btnGrantNotification.setOnClickListener {
            requestNotificationPermission()
        }

        btnGrantFullScreen.setOnClickListener {
            requestFullScreenPermission()
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
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    /**
     * На Android 14+ USE_FULL_SCREEN_INTENT требует отдельного разрешения
     * через настройки уведомлений приложения.
     */
    private fun hasFullScreenPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.canUseFullScreenIntent()
        } else {
            true // До Android 14 разрешение не требуется
        }
    }

    private fun updateUI() {
        val isEnabled = PrefsManager.isEnabled(this)
        val hasNotif = hasNotificationPermission()
        val hasFullScreen = hasFullScreenPermission()
        val allPermissions = hasNotif && hasFullScreen

        val notifMark = if (hasNotif) "✓" else "✗"
        val fsMark = if (hasFullScreen) "✓" else "✗"
        permissionStatusText.text =
            "Уведомления: $notifMark\nПолноэкранные уведомления: $fsMark"

        btnGrantNotification.isEnabled = !hasNotif
        btnGrantFullScreen.isEnabled = !hasFullScreen
        toggleButton.isEnabled = allPermissions

        if (isEnabled) {
            toggleButton.text = "Выключить скринсейвер"
            statusText.text = "Статус: активен"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            toggleButton.text = "Включить скринсейвер"
            statusText.text = if (allPermissions) "Статус: выключен" else "Статус: нужны разрешения"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    private fun toggleService() {
        val isEnabled = PrefsManager.isEnabled(this)
        if (isEnabled) {
            PrefsManager.setEnabled(this, false)
            BootReceiver.stopService(this)
            Toast.makeText(this, "Скринсейвер выключен", Toast.LENGTH_SHORT).show()
        } else {
            PrefsManager.setEnabled(this, true)
            BootReceiver.startService(this)
            Toast.makeText(this, "Скринсейвер включен", Toast.LENGTH_SHORT).show()
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
            // Способ 1: прямой intent с URI (стандартный Android 14)
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")
                )
                fullScreenPermissionLauncher.launch(intent)
                return
            } catch (_: Exception) {}

            // Способ 2: без URI
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                fullScreenPermissionLauncher.launch(intent)
                return
            } catch (_: Exception) {}

            // Способ 3: настройки уведомлений приложения (fallback)
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
                fullScreenPermissionLauncher.launch(intent)
                return
            } catch (_: Exception) {}

            // Способ 4: общие настройки приложения (последний fallback)
            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                fullScreenPermissionLauncher.launch(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Откройте Настройки → Приложения → E-Ink Скринсейвер → Уведомления",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
