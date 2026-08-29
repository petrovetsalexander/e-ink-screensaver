package com.eink.screensaver.data

import android.util.Log
import org.json.JSONObject

data class BookData(
    val title: String,
    val author: String,
    val annotation: String,
    val coverUrl: String
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("title", title)
        obj.put("author", author)
        obj.put("annotation", annotation)
        obj.put("coverUrl", coverUrl)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): BookData? {
            return try {
                val obj = JSONObject(json)
                BookData(
                    title = obj.optString("title", ""),
                    author = obj.optString("author", ""),
                    annotation = obj.optString("annotation", ""),
                    coverUrl = obj.optString("coverUrl", "")
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

object BookmateFetcher {

    private const val TAG = "BookmateFetcher"

    fun fetch(userId: String): BookData? {
        return try {
            val json = NetworkFetcher.fetchString(
                "https://api.bookmate.ru/api/v5/users/$userId/books?page=1&per_page=1"
            ) ?: return null

            val root = JSONObject(json)
            val books = root.optJSONArray("books") ?: return null
            if (books.length() == 0) return null

            val book = books.getJSONObject(0)
            val title = book.optString("title", "")
            val annotation = book.optString("annotation", "")

            val authors = book.optJSONArray("authors")
            val author = if (authors != null && authors.length() > 0) {
                authors.getJSONObject(0).optString("name", "")
            } else ""

            val coverUrl = book.optJSONObject("cover")?.optString("large", "") ?: ""

            BookData(title, author, annotation, coverUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Bookmate fetch failed: ${e.message}")
            null
        }
    }
}
