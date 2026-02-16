package com.eink.screensaver

import android.content.Context
import android.content.SharedPreferences

object PrefsManager {

    private const val PREFS_NAME = "eink_screensaver_prefs"
    private const val KEY_ENABLED = "screensaver_enabled"
    private const val KEY_UPDATE_INTERVAL = "update_interval_minutes"
    private const val KEY_BRIGHTNESS_OFF = "brightness_off"
    private const val KEY_SHOW_DATE = "show_date"

    // Weather
    private const val KEY_WEATHER_CITY = "weather_city"
    private const val KEY_WEATHER_API_KEY = "weather_api_key"
    private const val KEY_WEATHER_INTERVAL_MIN = "weather_interval_min"
    private const val KEY_WEATHER_CACHE_JSON = "weather_cache_json"
    private const val KEY_WEATHER_CACHE_TIME_MS = "weather_cache_time_ms"

    // News
    private const val KEY_NEWS_RSS_URL = "news_rss_url"
    private const val KEY_NEWS_INTERVAL_MIN = "news_interval_min"
    private const val KEY_NEWS_CACHE_JSON = "news_cache_json"
    private const val KEY_NEWS_CACHE_TIME_MS = "news_cache_time_ms"

    // Notes
    private const val KEY_NOTES_JSON = "notes_json"

    // Bookmate
    private const val KEY_BOOKMATE_USER_ID = "bookmate_user_id"
    private const val KEY_BOOKMATE_CACHE_JSON = "bookmate_cache_json"
    private const val KEY_BOOKMATE_CACHE_TIME_MS = "bookmate_cache_time_ms"

    // Module toggles
    private const val KEY_BLOCK_CLOCK_ENABLED = "block_clock_enabled"
    private const val KEY_BLOCK_WEATHER_ENABLED = "block_weather_enabled"
    private const val KEY_BLOCK_NOTES_ENABLED = "block_notes_enabled"
    private const val KEY_BLOCK_NEWS_ENABLED = "block_news_enabled"
    private const val KEY_BLOCK_BOOK_ENABLED = "block_book_enabled"
    private const val KEY_BOOK_BACKGROUND_ENABLED = "book_background_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ════════ Core ════════

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getUpdateIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_UPDATE_INTERVAL, 1)

    fun setUpdateIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_UPDATE_INTERVAL, minutes).apply()
    }

    fun isBrightnessOff(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BRIGHTNESS_OFF, true)

    fun setBrightnessOff(context: Context, off: Boolean) {
        prefs(context).edit().putBoolean(KEY_BRIGHTNESS_OFF, off).apply()
    }

    fun isShowDate(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_DATE, true)

    fun setShowDate(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_DATE, show).apply()
    }

    // ════════ Weather ════════

    fun getWeatherCity(context: Context): String =
        prefs(context).getString(KEY_WEATHER_CITY, "") ?: ""

    fun setWeatherCity(context: Context, city: String) {
        prefs(context).edit().putString(KEY_WEATHER_CITY, city).apply()
    }

    fun getWeatherApiKey(context: Context): String =
        prefs(context).getString(KEY_WEATHER_API_KEY, "") ?: ""

    fun setWeatherApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_WEATHER_API_KEY, key).apply()
    }

    fun getWeatherIntervalMin(context: Context): Int =
        prefs(context).getInt(KEY_WEATHER_INTERVAL_MIN, 30)

    fun setWeatherIntervalMin(context: Context, min: Int) {
        prefs(context).edit().putInt(KEY_WEATHER_INTERVAL_MIN, min).apply()
    }

    fun getWeatherCacheJson(context: Context): String =
        prefs(context).getString(KEY_WEATHER_CACHE_JSON, "") ?: ""

    fun getWeatherCacheTimeMs(context: Context): Long =
        prefs(context).getLong(KEY_WEATHER_CACHE_TIME_MS, 0L)

    fun setWeatherCache(context: Context, json: String) {
        prefs(context).edit()
            .putString(KEY_WEATHER_CACHE_JSON, json)
            .putLong(KEY_WEATHER_CACHE_TIME_MS, System.currentTimeMillis())
            .apply()
    }

    fun isWeatherEnabled(context: Context): Boolean =
        getWeatherCity(context).isNotBlank() && getWeatherApiKey(context).isNotBlank()

    // ════════ News ════════

    fun getNewsRssUrl(context: Context): String =
        prefs(context).getString(KEY_NEWS_RSS_URL, "https://news.yandex.ru/index.rss") ?: "https://news.yandex.ru/index.rss"

    fun setNewsRssUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_NEWS_RSS_URL, url).apply()
    }

    fun getNewsIntervalMin(context: Context): Int =
        prefs(context).getInt(KEY_NEWS_INTERVAL_MIN, 5)

    fun setNewsIntervalMin(context: Context, min: Int) {
        prefs(context).edit().putInt(KEY_NEWS_INTERVAL_MIN, min).apply()
    }

    fun getNewsCacheJson(context: Context): String =
        prefs(context).getString(KEY_NEWS_CACHE_JSON, "") ?: ""

    fun getNewsCacheTimeMs(context: Context): Long =
        prefs(context).getLong(KEY_NEWS_CACHE_TIME_MS, 0L)

    fun setNewsCache(context: Context, json: String) {
        prefs(context).edit()
            .putString(KEY_NEWS_CACHE_JSON, json)
            .putLong(KEY_NEWS_CACHE_TIME_MS, System.currentTimeMillis())
            .apply()
    }

    fun isNewsEnabled(context: Context): Boolean =
        getNewsRssUrl(context).isNotBlank()

    // ════════ Notes ════════

    fun getNotesJson(context: Context): String =
        prefs(context).getString(KEY_NOTES_JSON, "[]") ?: "[]"

    fun setNotesJson(context: Context, json: String) {
        prefs(context).edit().putString(KEY_NOTES_JSON, json).apply()
    }

    // ════════ Bookmate ════════

    fun getBookmateUserId(context: Context): String =
        prefs(context).getString(KEY_BOOKMATE_USER_ID, "b1234567890") ?: "b1234567890"

    fun setBookmateUserId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_BOOKMATE_USER_ID, id).apply()
    }

    fun getBookmateCacheJson(context: Context): String =
        prefs(context).getString(KEY_BOOKMATE_CACHE_JSON, "") ?: ""

    fun getBookmateCacheTimeMs(context: Context): Long =
        prefs(context).getLong(KEY_BOOKMATE_CACHE_TIME_MS, 0L)

    fun setBookmateCache(context: Context, json: String) {
        prefs(context).edit()
            .putString(KEY_BOOKMATE_CACHE_JSON, json)
            .putLong(KEY_BOOKMATE_CACHE_TIME_MS, System.currentTimeMillis())
            .apply()
    }

    fun isBookmateEnabled(context: Context): Boolean =
        getBookmateUserId(context).isNotBlank()

    // ════════ Module toggles ════════

    fun isBlockClockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_CLOCK_ENABLED, true)

    fun setBlockClockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_CLOCK_ENABLED, enabled).apply()
    }

    fun isBlockWeatherEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_WEATHER_ENABLED, true)

    fun setBlockWeatherEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_WEATHER_ENABLED, enabled).apply()
    }

    fun isBlockNotesEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_NOTES_ENABLED, true)

    fun setBlockNotesEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_NOTES_ENABLED, enabled).apply()
    }

    fun isBlockNewsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_NEWS_ENABLED, true)

    fun setBlockNewsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_NEWS_ENABLED, enabled).apply()
    }

    fun isBlockBookEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_BOOK_ENABLED, true)

    fun setBlockBookEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_BOOK_ENABLED, enabled).apply()
    }

    fun isBookBackgroundEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BOOK_BACKGROUND_ENABLED, false)

    fun setBookBackgroundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BOOK_BACKGROUND_ENABLED, enabled).apply()
    }
}
