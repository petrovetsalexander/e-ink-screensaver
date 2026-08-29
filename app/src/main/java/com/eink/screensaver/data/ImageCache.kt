package com.eink.screensaver.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ImageCache {

    private const val TAG = "ImageCache"
    private const val CACHE_DIR = "img_cache"

    private fun cacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun urlToFilename(url: String): String {
        return url.hashCode().toUInt().toString() + ".png"
    }

    fun getCachedBitmap(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val file = File(cacheDir(context), urlToFilename(url))
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    fun downloadAndCache(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        val file = File(cacheDir(context), urlToFilename(url))
        if (file.exists()) {
            return BitmapFactory.decodeFile(file.absolutePath)
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "EinkScreensaver/1.0")
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bitmap = BitmapFactory.decodeStream(connection.inputStream) ?: return null
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Image download failed: $url — ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
