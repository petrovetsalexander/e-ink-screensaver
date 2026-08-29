package com.eink.screensaver.data

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object NetworkFetcher {

    private const val TAG = "NetworkFetcher"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    fun fetchString(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "EinkScreensaver/1.0")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP $code for $url")
                return null
            }
            BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed: $url — ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
