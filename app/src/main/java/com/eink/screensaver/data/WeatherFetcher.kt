package com.eink.screensaver.data

import android.util.Log
import org.json.JSONObject
import java.net.URLEncoder

data class WeatherData(
    val currentTemp: Int,
    val currentDesc: String,
    val dayTemp: Int,
    val nightTemp: Int,
    val forecast: List<ForecastItem>,
    val currentIcon: String = "",
    val currentIsDay: Boolean = true
) {
    /**
     * [icon] is a WMO weather code as a string ("61", "0", …). Caches written in
     * the OpenWeatherMap era hold codes like "10d" instead; the renderer accepts
     * both, so an old cache keeps drawing until the next fetch replaces it.
     *
     * [pop] is precipitation probability in percent, [precipMm] the millimetres
     * expected in that hour.
     */
    data class ForecastItem(
        val hour: String,
        val temp: Int,
        val icon: String = "",
        val pop: Int = 0,
        val precipMm: Double = 0.0,
        val isDay: Boolean = true
    ) {
        val hasPrecip: Boolean get() = precipMm > 0.0 || pop >= POP_SHOW_THRESHOLD
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("currentTemp", currentTemp)
        obj.put("currentDesc", currentDesc)
        obj.put("currentIcon", currentIcon)
        obj.put("currentIsDay", currentIsDay)
        obj.put("dayTemp", dayTemp)
        obj.put("nightTemp", nightTemp)
        val arr = org.json.JSONArray()
        for (f in forecast) {
            val fo = JSONObject()
            fo.put("hour", f.hour)
            fo.put("temp", f.temp)
            fo.put("icon", f.icon)
            fo.put("pop", f.pop)
            fo.put("precipMm", f.precipMm)
            fo.put("isDay", f.isDay)
            arr.put(fo)
        }
        obj.put("forecast", arr)
        return obj.toString()
    }

    companion object {
        /** Below this the percentage is noise, so the column stays hidden. */
        const val POP_SHOW_THRESHOLD = 30

        fun fromJson(json: String): WeatherData? {
            return try {
                val obj = JSONObject(json)
                val arr = obj.getJSONArray("forecast")
                val items = mutableListOf<ForecastItem>()
                for (i in 0 until arr.length()) {
                    val fo = arr.getJSONObject(i)
                    // opt* throughout: caches written by earlier builds lack these keys.
                    items.add(
                        ForecastItem(
                            hour = fo.getString("hour"),
                            temp = fo.getInt("temp"),
                            icon = fo.optString("icon", ""),
                            pop = fo.optInt("pop", 0),
                            precipMm = fo.optDouble("precipMm", 0.0),
                            isDay = fo.optBoolean("isDay", true)
                        )
                    )
                }
                WeatherData(
                    currentTemp = obj.getInt("currentTemp"),
                    currentDesc = obj.getString("currentDesc"),
                    dayTemp = obj.getInt("dayTemp"),
                    nightTemp = obj.getInt("nightTemp"),
                    forecast = items,
                    currentIcon = obj.optString("currentIcon", ""),
                    currentIsDay = obj.optBoolean("currentIsDay", true)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Open-Meteo. Chosen over OpenWeatherMap because it needs no API key at all and
 * its forecast is hourly — the three-hour granularity of the free OWM endpoint
 * made a user-set step of 1-6 hours impossible to honour.
 *
 * Two requests, both unauthenticated: geocoding for the city, then the forecast
 * for those coordinates.
 */
object WeatherFetcher {

    private const val TAG = "WeatherFetcher"

    /**
     * @param stepHours spacing between forecast slots, 1-6
     * @param slotCount how many slots to keep, 0-4
     */
    fun fetch(city: String, stepHours: Int, slotCount: Int): WeatherData? {
        return try {
            val geoJson = NetworkFetcher.fetchString(
                "https://geocoding-api.open-meteo.com/v1/search" +
                    "?name=" + URLEncoder.encode(city, "UTF-8") + "&count=1&language=ru&format=json"
            ) ?: return null
            val results = JSONObject(geoJson).optJSONArray("results")
            if (results == null || results.length() == 0) {
                Log.w(TAG, "City not found: $city")
                return null
            }
            val place = results.getJSONObject(0)
            val lat = place.getDouble("latitude")
            val lon = place.getDouble("longitude")

            val json = NetworkFetcher.fetchString(
                "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,is_day" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=2"
            ) ?: return null

            val root = JSONObject(json)
            val current = root.getJSONObject("current")
            val currentTemp = Math.round(current.getDouble("temperature_2m")).toInt()
            val currentCode = current.optInt("weather_code", -1)
            val currentIsDay = current.optInt("is_day", 1) == 1

            val daily = root.getJSONObject("daily")
            val dayTemp = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
            val nightTemp = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt()

            val hourly = root.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val codes = hourly.getJSONArray("weather_code")
            val pops = hourly.optJSONArray("precipitation_probability")
            val precip = hourly.optJSONArray("precipitation")
            val isDayArr = hourly.optJSONArray("is_day")

            // The hourly series starts at local midnight, so walk forward to the
            // first entry still ahead of us. Both strings are local ISO time from
            // the same response, so a lexicographic compare is a date compare.
            val nowIso = current.getString("time")
            var start = times.length()
            for (i in 0 until times.length()) {
                if (times.getString(i) > nowIso) {
                    start = i
                    break
                }
            }

            val step = stepHours.coerceIn(1, 6)
            val wanted = slotCount.coerceIn(0, 4)
            val items = mutableListOf<WeatherData.ForecastItem>()
            var i = start
            while (items.size < wanted && i < times.length()) {
                items.add(
                    WeatherData.ForecastItem(
                        hour = times.getString(i).substring(11, 16),
                        temp = Math.round(temps.getDouble(i)).toInt(),
                        icon = codes.getInt(i).toString(),
                        pop = pops?.optInt(i, 0) ?: 0,
                        precipMm = precip?.optDouble(i, 0.0) ?: 0.0,
                        isDay = (isDayArr?.optInt(i, 1) ?: 1) == 1
                    )
                )
                i += step
            }

            WeatherData(
                currentTemp = currentTemp,
                currentDesc = describe(currentCode),
                dayTemp = dayTemp,
                nightTemp = nightTemp,
                forecast = items,
                currentIcon = if (currentCode >= 0) currentCode.toString() else "",
                currentIsDay = currentIsDay
            )
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Short label for a WMO code. Not drawn on the lock screen — the summary is
     * icon plus temperature — so it stays untranslated, and exists for logs and
     * as a fallback for the old description-matching icon path.
     */
    private fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> ""
    }
}
