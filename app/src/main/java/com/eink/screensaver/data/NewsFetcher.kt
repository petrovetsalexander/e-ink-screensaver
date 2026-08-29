package com.eink.screensaver.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class NewsItem(val title: String, val body: String)

object NewsFetcher {

    private const val TAG = "NewsFetcher"

    fun fetch(rssUrl: String, maxItems: Int = 5, bodyTags: List<String> = listOf("rbc_news:full-text", "description")): List<NewsItem>? {
        return try {
            val xml = NetworkFetcher.fetchString(rssUrl) ?: return null
            parseRss(xml, maxItems, bodyTags)
        } catch (e: Exception) {
            Log.w(TAG, "News fetch failed: ${e.message}")
            null
        }
    }

    private fun parseRss(xml: String, maxItems: Int, bodyTags: List<String>): List<NewsItem> {
        val items = mutableListOf<NewsItem>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var insideItem = false
        var currentTag = ""
        var currentQualifiedTag = ""
        var currentTitle = ""
        // Map: tag name -> captured text (first occurrence wins per tag)
        var bodyByTag = mutableMapOf<String, String>()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    // Build qualified name (prefix:localName) if prefix exists
                    val prefix = parser.prefix
                    currentQualifiedTag = if (!prefix.isNullOrBlank()) "$prefix:$currentTag" else currentTag
                    if (currentTag == "item" || currentTag == "entry") {
                        insideItem = true
                        currentTitle = ""
                        bodyByTag = mutableMapOf()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideItem) {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotBlank()) {
                            if (currentTag == "title" && currentTitle.isBlank()) {
                                currentTitle = text
                            }
                            // Match against both qualified (prefix:name) and local name
                            for (tag in bodyTags) {
                                if ((tag == currentQualifiedTag || tag == currentTag) && !bodyByTag.containsKey(tag)) {
                                    bodyByTag[tag] = text
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" || parser.name == "entry") {
                        if (currentTitle.isNotBlank()) {
                            // Pick body from the first tag in priority order that matched
                            val rawBody = bodyTags.firstNotNullOfOrNull { bodyByTag[it] } ?: ""
                            val cleanBody = rawBody
                                .replace(Regex("<[^>]*>"), "")
                                .replace("&nbsp;", " ")
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&quot;", "\"")
                                .trim()
                            items.add(NewsItem(currentTitle, cleanBody))
                        }
                        insideItem = false
                    }
                    currentTag = ""
                    currentQualifiedTag = ""
                }
            }
            if (items.size >= maxItems) break
            parser.next()
        }
        return items
    }

    fun itemsToJson(items: List<NewsItem>): String {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("title", item.title)
            obj.put("body", item.body)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun itemsFromJson(json: String): List<NewsItem> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val element = arr.get(i)
                if (element is JSONObject) {
                    NewsItem(
                        element.optString("title", ""),
                        element.optString("body", "")
                    )
                } else {
                    // Backward compat: old format was plain string array
                    NewsItem(element.toString(), "")
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Backward compat helper
    fun titlesToJson(titles: List<String>): String {
        val arr = JSONArray()
        for (t in titles) arr.put(t)
        return arr.toString()
    }

    fun titlesFromJson(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val element = arr.get(i)
                if (element is JSONObject) {
                    element.optString("title", "")
                } else {
                    element.toString()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
