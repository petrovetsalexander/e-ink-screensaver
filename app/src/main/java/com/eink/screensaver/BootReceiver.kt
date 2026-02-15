package com.eink.screensaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            if (PrefsManager.isEnabled(context)) {
                startService(context)
            }
        }
    }

    companion object {
        fun startService(context: Context) {
            val serviceIntent = Intent(context, ScreenSaverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }

        fun stopService(context: Context) {
            val serviceIntent = Intent(context, ScreenSaverService::class.java).apply {
                action = ScreenSaverService.ACTION_STOP
            }
            context.startService(serviceIntent)
        }
    }
}
