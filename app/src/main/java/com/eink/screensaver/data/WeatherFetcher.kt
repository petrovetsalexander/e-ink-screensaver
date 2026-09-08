package com.eink.screensaver.data

import android.util.Log
import org.json.JSONObject

data class WeatherData(
    val currentTemp: Int,
    val currentDesc: String,
    val dayTemp: Int,
    val nightTemp: Int,
    val forecast: List<ForecastItem>,
    val currentIcon: String = ""
) {
    /**
     * [icon] is OpenWeatherMap's own code ("10d", "01n", …) rather than the
     * description, because the description arrives translated — matching on it
     * only worked as long as the request stayed `lang=ru`.
     *
     * [pop] is the probability of precipitation in percent, [precipMm] the
     * amount forecast for the three-hour window. The API omits rain/snow blocks
     * entirely when none is expected, so 0.0 means dry.
     */
    data class ForecastItem(
        val hour: String,
        val temp: Int,
        val icon: String = "",
        val pop: Int = 0,
        val precipMm: Double = 0.0
    ) {
        /** Whether the slot has anything worth showing next to the icon. */
        val hasPrecip: Boolean get() = precipMm > 0.0 || pop >= POP_SHOW_THRESHOLD
    }

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("currentTemp", currentTemp)
        obj.put("currentDesc", currentDesc)
        obj.put("currentIcon", currentIcon)
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
                    // opt* throughout: a cache written by an older build has no
                    // icon/pop/precip, and it must keep rendering until the next fetch.
                    items.add(
                        ForecastItem(
                            hour = fo.getString("hour"),
                            temp = fo.getInt("temp"),
                            icon = fo.optString("icon", ""),
                            pop = fo.optInt("pop", 0),
                            precipMm = fo.optDouble("precipMm", 0.0)
                        )
                    )
                }
                WeatherData(
                    currentTemp = obj.getInt("currentTemp"),
                    currentDesc = obj.getString("currentDesc"),
                    dayTemp = obj.getInt("dayTemp"),
                    nightTemp = obj.getInt("nightTemp"),
                    forecast = items,
                    currentIcon = obj.optString("currentIcon", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object WeatherFetcher {

    private const val TAG = "WeatherFetcher"
    private const val FORECAST_SLOTS = 4

    fun fetch(city: String, apiKey: String): WeatherData? {
        return try {
            val currentJson = NetworkFetcher.fetchString(
                "https://api.openweathermap.org/data/2.5/weather?q=${java.net.URLEncoder.encode(city, "UTF-8")}&appid=$apiKey&units=metric&lang=ru"
            ) ?: return null
            val current = JSONObject(currentJson)
            val currentTemp = current.getJSONObject("main").getDouble("temp").toInt()
            val currentWeather = current.getJSONArray("weather").getJSONObject(0)
            val currentDesc = currentWeather.getString("description")
                .replaceFirstChar { it.uppercase() }
            val currentIcon = currentWeather.optString("icon", "")

            val forecastJson = NetworkFetcher.fetchString(
                "https://api.openweathermap.org/data/2.5/forecast?q=${java.net.URLEncoder.encode(city, "UTF-8")}&appid=$apiKey&units=metric&lang=ru&cnt=8"
            ) ?: return WeatherData(currentTemp, currentDesc, currentTemp, currentTemp, emptyList(), currentIcon)

            val forecastObj = JSONObject(forecastJson)
            val list = forecastObj.getJSONArray("list")

            var dayTemp = currentTemp
            var nightTemp = currentTemp
            val forecastItems = mutableListOf<WeatherData.ForecastItem>()

            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val temp = item.getJSONObject("main").getDouble("temp").toInt()
                val dtTxt = item.getString("dt_txt") // "2024-01-15 15:00:00"
                val hour = dtTxt.substring(11, 16)   // "15:00"

                if (i < FORECAST_SLOTS) {
                    // "rain"/"snow" are absent when nothing is expected, hence opt*.
                    val rain = item.optJSONObject("rain")?.optDouble("3h", 0.0) ?: 0.0
                    val snow = item.optJSONObject("snow")?.optDouble("3h", 0.0) ?: 0.0
                    forecastItems.add(
                        WeatherData.ForecastItem(
                            hour = hour,
                            temp = temp,
                            icon = item.getJSONArray("weather").getJSONObject(0).optString("icon", ""),
                            pop = Math.round(item.optDouble("pop", 0.0) * 100).toInt(),
                            precipMm = rain + snow
                        )
                    )
                }

                val hourInt = hour.substring(0, 2).toIntOrNull() ?: 12
                if (hourInt in 6..18) {
                    if (temp > dayTemp) dayTemp = temp
                } else {
                    if (temp < nightTemp) nightTemp = temp
                }
            }

            WeatherData(currentTemp, currentDesc, dayTemp, nightTemp, forecastItems, currentIcon)
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed: ${e.message}")
            null
        }
    }
}
