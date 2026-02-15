package com.eink.screensaver

import android.content.Context
import android.content.SharedPreferences

object PrefsManager {

    private const val PREFS_NAME = "eink_screensaver_prefs"
    private const val KEY_ENABLED = "screensaver_enabled"
    private const val KEY_UPDATE_INTERVAL = "update_interval_minutes"
    private const val KEY_BRIGHTNESS_OFF = "brightness_off"
    private const val KEY_SHOW_DATE = "show_date"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Интервал обновления часов в минутах: 1, 2 или 5 */
    fun getUpdateIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_UPDATE_INTERVAL, 1)

    fun setUpdateIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_UPDATE_INTERVAL, minutes).apply()
    }

    /** Отключать подсветку (frontlight) при показе скринсейвера */
    fun isBrightnessOff(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BRIGHTNESS_OFF, true)

    fun setBrightnessOff(context: Context, off: Boolean) {
        prefs(context).edit().putBoolean(KEY_BRIGHTNESS_OFF, off).apply()
    }

    /** Показывать дату под часами */
    fun isShowDate(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_DATE, true)

    fun setShowDate(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_DATE, show).apply()
    }
}
