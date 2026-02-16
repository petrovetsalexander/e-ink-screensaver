package com.eink.screensaver

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var notesListContainer: LinearLayout
    private lateinit var etNewNote: EditText
    private lateinit var btnAddNote: Button
    private lateinit var btnSync: ImageButton
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        notesListContainer = findViewById(R.id.notesListContainer)
        etNewNote = findViewById(R.id.etNewNote)
        btnAddNote = findViewById(R.id.btnAddNote)
        btnSync = findViewById(R.id.btnSync)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnSync.setOnClickListener { performSync() }

        btnAddNote.setOnClickListener {
            val text = etNewNote.text.toString().trim()
            if (text.isNotBlank()) {
                addNote(text)
                etNewNote.text.clear()
            }
        }
    }

    private fun performSync() {
        btnSync.isEnabled = false
        Toast.makeText(this, "\u0421\u0438\u043D\u0445\u0440\u043E\u043D\u0438\u0437\u0430\u0446\u0438\u044F...", Toast.LENGTH_SHORT).show()
        val ctx = applicationContext
        kotlin.concurrent.thread {
            val results = mutableListOf<String>()

            // Weather
            val city = PrefsManager.getWeatherCity(ctx)
            val apiKey = PrefsManager.getWeatherApiKey(ctx)
            if (city.isNotBlank() && apiKey.isNotBlank()) {
                val weather = WeatherFetcher.fetch(city, apiKey)
                if (weather != null) {
                    PrefsManager.setWeatherCache(ctx, weather.toJson())
                    results.add("\u041F\u043E\u0433\u043E\u0434\u0430 \u2713")
                } else {
                    results.add("\u041F\u043E\u0433\u043E\u0434\u0430 \u2717")
                }
            }

            // News
            val rssUrl = PrefsManager.getNewsRssUrl(ctx)
            if (rssUrl.isNotBlank()) {
                val titles = NewsFetcher.fetch(rssUrl)
                if (titles != null && titles.isNotEmpty()) {
                    PrefsManager.setNewsCache(ctx, NewsFetcher.titlesToJson(titles))
                    results.add("\u041D\u043E\u0432\u043E\u0441\u0442\u0438 \u2713")
                } else {
                    results.add("\u041D\u043E\u0432\u043E\u0441\u0442\u0438 \u2717")
                }
            }

            // Bookmate
            val userId = PrefsManager.getBookmateUserId(ctx)
            if (userId.isNotBlank()) {
                val book = BookmateFetcher.fetch(userId)
                if (book != null) {
                    PrefsManager.setBookmateCache(ctx, book.toJson())
                    results.add("\u041A\u043D\u0438\u0433\u0430 \u2713")
                    // Download cover
                    if (book.coverUrl.isNotBlank()) {
                        val bitmap = ImageCache.downloadAndCache(ctx, book.coverUrl)
                        if (bitmap != null) {
                            results.add("\u041E\u0431\u043B\u043E\u0436\u043A\u0430 \u2713")
                        } else {
                            results.add("\u041E\u0431\u043B\u043E\u0436\u043A\u0430 \u2717")
                        }
                    }
                } else {
                    results.add("\u041A\u043D\u0438\u0433\u0430 \u2717")
                }
            }

            val message = if (results.isEmpty()) {
                "\u041D\u0435\u0442 \u043D\u0430\u0441\u0442\u0440\u043E\u0435\u043D\u043D\u044B\u0445 \u0438\u0441\u0442\u043E\u0447\u043D\u0438\u043A\u043E\u0432"
            } else {
                results.joinToString(", ")
            }

            handler.post {
                btnSync.isEnabled = true
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotesList()
    }

    // ════════ Notes ════════

    private fun getNotesArray(): JSONArray {
        return try {
            JSONArray(PrefsManager.getNotesJson(this))
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveNotesArray(arr: JSONArray) {
        PrefsManager.setNotesJson(this, arr.toString())
    }

    private fun addNote(text: String) {
        val arr = getNotesArray()
        val note = JSONObject()
        note.put("id", System.currentTimeMillis())
        note.put("text", text)
        note.put("completed", false)
        arr.put(note)
        saveNotesArray(arr)
        refreshNotesList()
    }

    private fun toggleNoteCompleted(index: Int) {
        val arr = getNotesArray()
        if (index < arr.length()) {
            val note = arr.getJSONObject(index)
            note.put("completed", !note.optBoolean("completed", false))
            saveNotesArray(arr)
            refreshNotesList()
        }
    }

    private fun deleteNote(index: Int) {
        val arr = getNotesArray()
        if (index < arr.length()) {
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                if (i != index) newArr.put(arr.getJSONObject(i))
            }
            saveNotesArray(newArr)
            refreshNotesList()
        }
    }

    @Suppress("SetTextI18n")
    private fun refreshNotesList() {
        notesListContainer.removeAllViews()
        val arr = getNotesArray()
        for (i in 0 until arr.length()) {
            val note = arr.getJSONObject(i)
            val text = note.optString("text", "")
            val completed = note.optBoolean("completed", false)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 4
                }
                gravity = Gravity.CENTER_VERTICAL
            }

            val label = TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(if (completed) 0xFF888888.toInt() else 0xFF000000.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (completed) {
                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                }
            }

            val btnComplete = Button(this).apply {
                this.text = if (completed) "\u21A9" else "\u2713"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 4 }
                val idx = i
                setOnClickListener { toggleNoteCompleted(idx) }
            }

            val btnDelete = Button(this).apply {
                this.text = "\u2717"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 4 }
                val idx = i
                setOnClickListener { deleteNote(idx) }
            }

            row.addView(label)
            row.addView(btnComplete)
            row.addView(btnDelete)
            notesListContainer.addView(row)
        }
    }
}
