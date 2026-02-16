package com.eink.screensaver

import android.util.Log
import org.json.JSONObject

data class WeatherData(
    val currentTemp: Int,
    val currentDesc: String,
    val dayTemp: Int,
    val nightTemp: Int,
    val forecast: List<ForecastItem>
) {
    data class ForecastItem(val hour: String, val temp: Int)

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("currentTemp", currentTemp)
        obj.put("currentDesc", currentDesc)
        obj.put("dayTemp", dayTemp)
        obj.put("nightTemp", nightTemp)
        val arr = org.json.JSONArray()
        for (f in forecast) {
            val fo = JSONObject()
            fo.put("hour", f.hour)
            fo.put("temp", f.temp)
            arr.put(fo)
        }
        obj.put("forecast", arr)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): WeatherData? {
            return try {
                val obj = JSONObject(json)
                val arr = obj.getJSONArray("forecast")
                val items = mutableListOf<ForecastItem>()
                for (i in 0 until arr.length()) {
                    val fo = arr.getJSONObject(i)
                    items.add(ForecastItem(fo.getString("hour"), fo.getInt("temp")))
                }
                WeatherData(
                    currentTemp = obj.getInt("currentTemp"),
                    currentDesc = obj.getString("currentDesc"),
                    dayTemp = obj.getInt("dayTemp"),
                    nightTemp = obj.getInt("nightTemp"),
                    forecast = items
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object WeatherFetcher {

    private const val TAG = "WeatherFetcher"

    fun fetch(city: String, apiKey: String): WeatherData? {
        return try {
            val currentJson = NetworkFetcher.fetchString(
                "https://api.openweathermap.org/data/2.5/weather?q=${java.net.URLEncoder.encode(city, "UTF-8")}&appid=$apiKey&units=metric&lang=ru"
            ) ?: return null
            val current = JSONObject(currentJson)
            val currentTemp = current.getJSONObject("main").getDouble("temp").toInt()
            val currentDesc = current.getJSONArray("weather").getJSONObject(0).getString("description")
                .replaceFirstChar { it.uppercase() }

            val forecastJson = NetworkFetcher.fetchString(
                "https://api.openweathermap.org/data/2.5/forecast?q=${java.net.URLEncoder.encode(city, "UTF-8")}&appid=$apiKey&units=metric&lang=ru&cnt=8"
            ) ?: return WeatherData(currentTemp, currentDesc, currentTemp, currentTemp, emptyList())

            val forecastObj = JSONObject(forecastJson)
            val list = forecastObj.getJSONArray("list")

            var dayTemp = currentTemp
            var nightTemp = currentTemp
            val forecastItems = mutableListOf<WeatherData.ForecastItem>()

            for (i in 0 until minOf(list.length(), 4)) {
                val item = list.getJSONObject(i)
                val temp = item.getJSONObject("main").getDouble("temp").toInt()
                val dtTxt = item.getString("dt_txt") // "2024-01-15 15:00:00"
                val hour = dtTxt.substring(11, 16) // "15:00"
                forecastItems.add(WeatherData.ForecastItem(hour, temp))

                val hourInt = hour.substring(0, 2).toIntOrNull() ?: 12
                if (hourInt in 6..18) {
                    if (temp > dayTemp) dayTemp = temp
                } else {
                    if (temp < nightTemp) nightTemp = temp
                }
            }

            // Also scan remaining items for day/night temps
            for (i in 4 until list.length()) {
                val item = list.getJSONObject(i)
                val temp = item.getJSONObject("main").getDouble("temp").toInt()
                val dtTxt = item.getString("dt_txt")
                val hour = dtTxt.substring(11, 16)
                val hourInt = hour.substring(0, 2).toIntOrNull() ?: 12
                if (hourInt in 6..18) {
                    if (temp > dayTemp) dayTemp = temp
                } else {
                    if (temp < nightTemp) nightTemp = temp
                }
            }

            WeatherData(currentTemp, currentDesc, dayTemp, nightTemp, forecastItems)
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed: ${e.message}")
            null
        }
    }
}
