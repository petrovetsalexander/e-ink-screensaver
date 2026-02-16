package com.eink.screensaver

import android.util.Log
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object NewsFetcher {

    private const val TAG = "NewsFetcher"
    private const val MAX_TITLES = 5

    fun fetch(rssUrl: String): List<String>? {
        return try {
            val xml = NetworkFetcher.fetchString(rssUrl) ?: return null
            parseRss(xml)
        } catch (e: Exception) {
            Log.w(TAG, "News fetch failed: ${e.message}")
            null
        }
    }

    private fun parseRss(xml: String): List<String> {
        val titles = mutableListOf<String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var insideItem = false
        var currentTag = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "item" || currentTag == "entry") {
                        insideItem = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideItem && currentTag == "title") {
                        val text = parser.text?.trim()
                        if (!text.isNullOrBlank()) {
                            titles.add(text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" || parser.name == "entry") {
                        insideItem = false
                    }
                    currentTag = ""
                }
            }
            if (titles.size >= MAX_TITLES) break
            parser.next()
        }
        return titles
    }

    fun titlesToJson(titles: List<String>): String {
        val arr = JSONArray()
        for (t in titles) arr.put(t)
        return arr.toString()
    }

    fun titlesFromJson(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
