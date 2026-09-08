package com.eink.screensaver

import android.content.Context
import android.content.SharedPreferences

object PrefsManager {

    const val NO_SAVED_FRONTLIGHT = -1

    private const val PREFS_NAME = "eink_screensaver_prefs"
    private const val KEY_ENABLED = "screensaver_enabled"
    private const val KEY_UPDATE_INTERVAL = "update_interval_minutes"
    private const val KEY_SHOW_DATE = "show_date"
    private const val KEY_SAVED_FRONTLIGHT = "saved_frontlight_level"

    // Weather
    private const val KEY_WEATHER_CITY = "weather_city"
    private const val KEY_WEATHER_INTERVAL_MIN = "weather_interval_min"
    private const val KEY_WEATHER_STEP_HOURS = "weather_forecast_step_hours"
    private const val KEY_WEATHER_SLOTS = "weather_forecast_slots"
    private const val KEY_WEATHER_CACHE_JSON = "weather_cache_json"
    private const val KEY_WEATHER_CACHE_TIME_MS = "weather_cache_time_ms"

    // News
    private const val KEY_NEWS_RSS_URL = "news_rss_url"
    private const val KEY_NEWS_INTERVAL_MIN = "news_interval_min"
    private const val KEY_NEWS_CACHE_JSON = "news_cache_json"
    private const val KEY_NEWS_CACHE_TIME_MS = "news_cache_time_ms"
    private const val KEY_NEWS_DISPLAY_COUNT = "news_display_count"
    private const val KEY_NEWS_DOWNLOAD_COUNT = "news_download_count"
    private const val KEY_NEWS_SHUFFLE = "news_shuffle"
    private const val KEY_NEWS_ONLY_HEADER = "news_only_header"
    private const val KEY_FONT_SIZE_NEWS_BODY = "font_size_news_body"
    private const val KEY_NEWS_BODY_TAGS = "news_body_tags"
    private const val DEFAULT_NEWS_BODY_TAGS = "rbc_news:full-text,description"

    // Notes
    private const val KEY_NOTES_JSON = "notes_json"
    private const val KEY_STICKERS_JSON = "notes_stickers_json"

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

    // Font sizes (sp values)
    private const val KEY_FONT_SIZE_CLOCK = "font_size_clock"
    private const val KEY_FONT_SIZE_WEATHER = "font_size_weather"
    private const val KEY_FONT_SIZE_NEWS = "font_size_news"
    private const val KEY_FONT_SIZE_NOTES = "font_size_notes"
    private const val KEY_FONT_SIZE_BOOK = "font_size_book"

    // Notes columns
    private const val KEY_NOTES_COLUMNS = "notes_columns"

    // Module order & clock position
    private const val KEY_MODULES_ORDER = "modules_order"
    private const val KEY_CLOCK_POSITION = "clock_position"
    private const val DEFAULT_MODULES_ORDER = "clock,news,notes,book"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ════════ Core ════════

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getUpdateIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_UPDATE_INTERVAL, 5)

    fun setUpdateIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_UPDATE_INTERVAL, minutes).apply()
    }


    fun isShowDate(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_DATE, true)

    fun setShowDate(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_DATE, show).apply()
    }

    // ════════ Frontlight ════════

    /**
     * Hardware frontlight level saved before EinkCompat dimmed it, or
     * [NO_SAVED_FRONTLIGHT] when the light is at the user's own level.
     * Persisted so a process death cannot strand the panel light at 0 —
     * the service restores it on next start.
     */
    fun getSavedFrontlight(context: Context): Int =
        prefs(context).getInt(KEY_SAVED_FRONTLIGHT, NO_SAVED_FRONTLIGHT)

    fun setSavedFrontlight(context: Context, level: Int) {
        // commit(), not apply(): written right before the device goes to sleep.
        prefs(context).edit().putInt(KEY_SAVED_FRONTLIGHT, level).commit()
    }

    // ════════ Weather ════════

    fun getWeatherCity(context: Context): String =
        prefs(context).getString(KEY_WEATHER_CITY, "") ?: ""

    fun setWeatherCity(context: Context, city: String) {
        prefs(context).edit().putString(KEY_WEATHER_CITY, city).apply()
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
        // Open-Meteo needs no API key; the city is the only requirement now.
        getWeatherCity(context).isNotBlank()

    /** Hours between forecast slots, 1-6. Open-Meteo is hourly, so any value is real. */
    fun getWeatherStepHours(context: Context): Int =
        prefs(context).getInt(KEY_WEATHER_STEP_HOURS, 1)

    fun setWeatherStepHours(context: Context, hours: Int) {
        prefs(context).edit().putInt(KEY_WEATHER_STEP_HOURS, hours.coerceIn(1, 6)).apply()
    }

    /** How many forecast slots to draw, 0-4. Zero hides the strip. */
    fun getWeatherSlots(context: Context): Int =
        prefs(context).getInt(KEY_WEATHER_SLOTS, 4)

    fun setWeatherSlots(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_WEATHER_SLOTS, count.coerceIn(0, 4)).apply()
    }

    // ════════ News ════════

    fun getNewsRssUrl(context: Context): String =
        prefs(context).getString(KEY_NEWS_RSS_URL, "https://rssexport.rbc.ru/rbcnews/news/30/full.rss") ?: "https://rssexport.rbc.ru/rbcnews/news/30/full.rss"

    fun setNewsRssUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_NEWS_RSS_URL, url).apply()
    }

    fun getNewsIntervalMin(context: Context): Int =
        prefs(context).getInt(KEY_NEWS_INTERVAL_MIN, 30)

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

    fun getNewsDisplayCount(context: Context): Int =
        prefs(context).getInt(KEY_NEWS_DISPLAY_COUNT, 5)

    fun setNewsDisplayCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_NEWS_DISPLAY_COUNT, count.coerceIn(1, 20)).apply()
    }

    fun getNewsDownloadCount(context: Context): Int =
        prefs(context).getInt(KEY_NEWS_DOWNLOAD_COUNT, 5)

    fun setNewsDownloadCount(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_NEWS_DOWNLOAD_COUNT, count.coerceIn(1, 30)).apply()
    }

    fun isNewsShuffle(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NEWS_SHUFFLE, false)

    fun setNewsShuffle(context: Context, shuffle: Boolean) {
        prefs(context).edit().putBoolean(KEY_NEWS_SHUFFLE, shuffle).apply()
    }

    fun isNewsOnlyHeader(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NEWS_ONLY_HEADER, true)

    fun setNewsOnlyHeader(context: Context, onlyHeader: Boolean) {
        prefs(context).edit().putBoolean(KEY_NEWS_ONLY_HEADER, onlyHeader).apply()
    }

    fun getFontSizeNewsBody(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE_NEWS_BODY, 11)

    fun setFontSizeNewsBody(context: Context, sp: Int) {
        prefs(context).edit().putInt(KEY_FONT_SIZE_NEWS_BODY, sp).apply()
    }

    fun getNewsBodyTags(context: Context): String =
        prefs(context).getString(KEY_NEWS_BODY_TAGS, DEFAULT_NEWS_BODY_TAGS) ?: DEFAULT_NEWS_BODY_TAGS

    fun setNewsBodyTags(context: Context, tags: String) {
        prefs(context).edit().putString(KEY_NEWS_BODY_TAGS, tags).apply()
    }

    fun getNewsBodyTagList(context: Context): List<String> =
        getNewsBodyTags(context).split(",").map { it.trim() }.filter { it.isNotBlank() }

    // ════════ Notes ════════

    fun getNotesJson(context: Context): String =
        prefs(context).getString(KEY_NOTES_JSON, "[]") ?: "[]"

    fun setNotesJson(context: Context, json: String) {
        prefs(context).edit().putString(KEY_NOTES_JSON, json).apply()
    }

    // ════════ Stickers ════════

    fun getStickersJson(context: Context): String =
        prefs(context).getString(KEY_STICKERS_JSON, "[]") ?: "[]"

    fun setStickersJson(context: Context, json: String) {
        prefs(context).edit().putString(KEY_STICKERS_JSON, json).apply()
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

    // ════════ Font sizes ════════

    fun getFontSizeClock(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE_CLOCK, 50)

    fun setFontSizeClock(context: Context, sp: Int) {
        prefs(context).edit().putInt(KEY_FONT_SIZE_CLOCK, sp).apply()
    }

    fun getFontSizeWeather(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE_WEATHER, 16)

    fun setFontSizeWeather(context: Context, sp: Int) {
        prefs(context).edit().putInt(KEY_FONT_SIZE_WEATHER, sp).apply()
    }

    fun getFontSizeNews(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE_NEWS, 13)

    fun setFontSizeNews(context: Context, sp: Int) {
        prefs(context).edit().putInt(KEY_FONT_SIZE_NEWS, sp).apply()
    }

    fun getFontSizeNotes(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE_NOTES, 13)

    fun setFontSizeNotes(context: Context, sp: Int) {
        prefs(context).edit().putInt(KEY_FONT_SIZE_NOTES, sp).apply()
    }

    fun getFontSizeBook(context: Context): Int =
        prefs(context).getInt(KEY_FONT_SIZE_BOOK, 13)

    fun setFontSizeBook(context: Context, sp: Int) {
        prefs(context).edit().putInt(KEY_FONT_SIZE_BOOK, sp).apply()
    }

    // ════════ Notes columns ════════

    fun getNotesColumns(context: Context): Int =
        prefs(context).getInt(KEY_NOTES_COLUMNS, 2)

    fun setNotesColumns(context: Context, columns: Int) {
        prefs(context).edit().putInt(KEY_NOTES_COLUMNS, columns.coerceIn(1, 3)).apply()
    }

    // ════════ Module order ════════

    /**
     * "weather" is dropped on read: it used to be a module of its own, but the
     * weather now shares the header row with the clock and cannot be positioned
     * separately, so it is part of the "clock" slot. Orders saved before that
     * still list it, and without this it would show up in the reorder screen as
     * a row with no meaning.
     */
    fun getModulesOrder(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_MODULES_ORDER, DEFAULT_MODULES_ORDER) ?: DEFAULT_MODULES_ORDER
        return raw.split(",").filter { it.isNotBlank() && it != "weather" }
    }

    fun setModulesOrder(context: Context, order: List<String>) {
        prefs(context).edit().putString(KEY_MODULES_ORDER, order.joinToString(",")).apply()
    }

    // ════════ Clock position ════════

    fun getClockPosition(context: Context): String =
        prefs(context).getString(KEY_CLOCK_POSITION, "left") ?: "left"

    fun setClockPosition(context: Context, position: String) {
        prefs(context).edit().putString(KEY_CLOCK_POSITION, position).apply()
    }
}
