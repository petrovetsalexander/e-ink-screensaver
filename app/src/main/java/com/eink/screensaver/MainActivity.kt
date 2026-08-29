package com.eink.screensaver

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.eink.screensaver.data.BookmateFetcher
import com.eink.screensaver.data.ImageCache
import com.eink.screensaver.data.NewsFetcher
import com.eink.screensaver.data.WeatherFetcher
import com.eink.screensaver.settings.SettingsActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var stickersListContainer: LinearLayout
    private lateinit var notesListContainer: LinearLayout
    private lateinit var tvSelectedSticker: TextView
    private lateinit var addNoteRow: LinearLayout
    private lateinit var etNewNote: EditText
    private lateinit var btnAddNote: Button
    private lateinit var btnNewSticker: Button
    private lateinit var btnSync: ImageButton
    private lateinit var visibilityDivider: android.view.View
    private lateinit var tvVisibilityTitle: TextView
    private lateinit var visibilityContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())

    private var stickers = JSONArray()
    private var selectedStickerIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stickersListContainer = findViewById(R.id.stickersListContainer)
        notesListContainer = findViewById(R.id.notesListContainer)
        tvSelectedSticker = findViewById(R.id.tvSelectedSticker)
        addNoteRow = findViewById(R.id.addNoteRow)
        etNewNote = findViewById(R.id.etNewNote)
        btnAddNote = findViewById(R.id.btnAddNote)
        btnNewSticker = findViewById(R.id.btnNewSticker)
        btnSync = findViewById(R.id.btnSync)
        visibilityDivider = findViewById(R.id.visibilityDivider)
        tvVisibilityTitle = findViewById(R.id.tvVisibilityTitle)
        visibilityContainer = findViewById(R.id.visibilityContainer)

        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnSync.setOnClickListener { performSync() }

        btnNewSticker.setOnClickListener { showCreateStickerDialog() }

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
        Toast.makeText(this, R.string.syncing, Toast.LENGTH_SHORT).show()
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
                    results.add(getString(R.string.weather_ok))
                } else {
                    results.add(getString(R.string.weather_fail))
                }
            }

            // News
            val rssUrl = PrefsManager.getNewsRssUrl(ctx)
            if (rssUrl.isNotBlank()) {
                val downloadCount = PrefsManager.getNewsDownloadCount(ctx)
                val bodyTags = PrefsManager.getNewsBodyTagList(ctx)
                val items = NewsFetcher.fetch(rssUrl, downloadCount, bodyTags)
                if (items != null && items.isNotEmpty()) {
                    PrefsManager.setNewsCache(ctx, NewsFetcher.itemsToJson(items))
                    results.add(getString(R.string.news_ok))
                } else {
                    results.add(getString(R.string.news_fail))
                }
            }

            // Bookmate
            val userId = PrefsManager.getBookmateUserId(ctx)
            if (userId.isNotBlank()) {
                val book = BookmateFetcher.fetch(userId)
                if (book != null) {
                    PrefsManager.setBookmateCache(ctx, book.toJson())
                    results.add(getString(R.string.book_ok))
                    if (book.coverUrl.isNotBlank()) {
                        val bitmap = ImageCache.downloadAndCache(ctx, book.coverUrl)
                        if (bitmap != null) {
                            results.add(getString(R.string.cover_ok))
                        } else {
                            results.add(getString(R.string.cover_fail))
                        }
                    }
                } else {
                    results.add(getString(R.string.book_fail))
                }
            }

            val message = if (results.isEmpty()) {
                getString(R.string.no_configured_sources)
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
        loadStickers()
        refreshAll()
    }

    // ════════ Stickers data ════════

    private fun loadStickers() {
        stickers = try {
            JSONArray(PrefsManager.getStickersJson(this))
        } catch (e: Exception) {
            JSONArray()
        }

        // Migration: if stickers empty but old notes exist, create a default sticker
        if (stickers.length() == 0) {
            val oldNotes = try {
                JSONArray(PrefsManager.getNotesJson(this))
            } catch (e: Exception) {
                JSONArray()
            }
            if (oldNotes.length() > 0) {
                val sticker = JSONObject()
                sticker.put("id", System.currentTimeMillis())
                sticker.put("name", getString(R.string.sticker_default_name))
                sticker.put("visible", true)
                sticker.put("notes", oldNotes)
                stickers.put(sticker)
                saveStickers()
                // Clear old notes
                PrefsManager.setNotesJson(this, "[]")
            }
        }

        // Fix selected index
        if (selectedStickerIndex >= stickers.length()) {
            selectedStickerIndex = stickers.length() - 1
        }
        if (selectedStickerIndex < 0 && stickers.length() > 0) {
            selectedStickerIndex = 0
        }
    }

    private fun saveStickers() {
        PrefsManager.setStickersJson(this, stickers.toString())
    }

    private fun refreshAll() {
        refreshStickersListUI()
        refreshNotesListUI()
        refreshVisibilityUI()
    }

    // ════════ Sticker list UI ════════

    @Suppress("SetTextI18n")
    private fun refreshStickersListUI() {
        stickersListContainer.removeAllViews()

        for (i in 0 until stickers.length()) {
            val sticker = stickers.getJSONObject(i)
            val name = sticker.optString("name", "")
            val isSelected = (i == selectedStickerIndex)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
                gravity = Gravity.CENTER_VERTICAL
                if (isSelected) {
                    setBackgroundColor(0xFFE8E8E8.toInt())
                }
                setPadding(8, 8, 8, 8)
            }

            val label = TextView(this).apply {
                text = "\uD83D\uDCCC $name"
                textSize = 15f
                setTextColor(0xFF000000.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (isSelected) setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val btnEdit = Button(this).apply {
                text = "\u270E"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 4 }
                val idx = i
                setOnClickListener { showRenameStickerDialog(idx) }
            }

            val btnDelete = Button(this).apply {
                text = "\uD83D\uDDD1"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 4 }
                val idx = i
                setOnClickListener { showDeleteStickerDialog(idx) }
            }

            val idx = i
            row.setOnClickListener {
                selectedStickerIndex = idx
                refreshAll()
            }

            row.addView(label)
            row.addView(btnEdit)
            row.addView(btnDelete)
            stickersListContainer.addView(row)
        }
    }

    // ════════ Notes list UI ════════

    @Suppress("SetTextI18n")
    private fun refreshNotesListUI() {
        notesListContainer.removeAllViews()

        if (selectedStickerIndex < 0 || selectedStickerIndex >= stickers.length()) {
            tvSelectedSticker.visibility = android.view.View.GONE
            addNoteRow.visibility = android.view.View.GONE
            return
        }

        val sticker = stickers.getJSONObject(selectedStickerIndex)
        val name = sticker.optString("name", "")
        tvSelectedSticker.text = "\u2014\u2014 $name \u2014\u2014"
        tvSelectedSticker.visibility = android.view.View.VISIBLE
        addNoteRow.visibility = android.view.View.VISIBLE

        val notes = sticker.optJSONArray("notes") ?: JSONArray()

        for (i in 0 until notes.length()) {
            val note = notes.getJSONObject(i)
            val text = note.optString("text", "")
            val completed = note.optBoolean("completed", false)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
                gravity = Gravity.CENTER_VERTICAL
            }

            val label = TextView(this).apply {
                this.text = "\u2022 $text"
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

    // ════════ Visibility UI ════════

    private fun refreshVisibilityUI() {
        visibilityContainer.removeAllViews()

        if (stickers.length() == 0) {
            visibilityDivider.visibility = android.view.View.GONE
            tvVisibilityTitle.visibility = android.view.View.GONE
            return
        }

        visibilityDivider.visibility = android.view.View.VISIBLE
        tvVisibilityTitle.visibility = android.view.View.VISIBLE

        for (i in 0 until stickers.length()) {
            val sticker = stickers.getJSONObject(i)
            val name = sticker.optString("name", "")
            val visible = sticker.optBoolean("visible", true)

            val cb = CheckBox(this).apply {
                text = name
                isChecked = visible
                textSize = 14f
                val idx = i
                setOnCheckedChangeListener { _, isChecked ->
                    stickers.getJSONObject(idx).put("visible", isChecked)
                    saveStickers()
                }
            }
            visibilityContainer.addView(cb)
        }
    }

    // ════════ Sticker CRUD ════════

    private fun showCreateStickerDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.sticker_name_hint)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sticker_new)
            .setView(input)
            .setPositiveButton(R.string.sticker_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    createSticker(name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createSticker(name: String) {
        val sticker = JSONObject()
        sticker.put("id", System.currentTimeMillis())
        sticker.put("name", name)
        sticker.put("visible", true)
        sticker.put("notes", JSONArray())
        stickers.put(sticker)
        selectedStickerIndex = stickers.length() - 1
        saveStickers()
        refreshAll()
    }

    private fun showRenameStickerDialog(index: Int) {
        if (index < 0 || index >= stickers.length()) return
        val sticker = stickers.getJSONObject(index)
        val currentName = sticker.optString("name", "")

        val input = EditText(this).apply {
            setText(currentName)
            setPadding(48, 32, 48, 16)
            setSelection(currentName.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sticker_name_hint)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank()) {
                    renameSticker(index, newName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameSticker(index: Int, newName: String) {
        stickers.getJSONObject(index).put("name", newName)
        saveStickers()
        refreshAll()
    }

    private fun showDeleteStickerDialog(index: Int) {
        if (index < 0 || index >= stickers.length()) return
        val name = stickers.getJSONObject(index).optString("name", "")
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.sticker_delete_confirm, name))
            .setPositiveButton(R.string.sticker_delete) { _, _ ->
                deleteSticker(index)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSticker(index: Int) {
        val newArr = JSONArray()
        for (i in 0 until stickers.length()) {
            if (i != index) newArr.put(stickers.getJSONObject(i))
        }
        stickers = newArr
        if (selectedStickerIndex >= stickers.length()) {
            selectedStickerIndex = stickers.length() - 1
        }
        if (selectedStickerIndex == index) {
            selectedStickerIndex = if (stickers.length() > 0) 0 else -1
        }
        saveStickers()
        refreshAll()
    }

    // ════════ Note CRUD ════════

    private fun addNote(text: String) {
        if (selectedStickerIndex < 0 || selectedStickerIndex >= stickers.length()) return
        val sticker = stickers.getJSONObject(selectedStickerIndex)
        val notes = sticker.optJSONArray("notes") ?: JSONArray()
        val note = JSONObject()
        note.put("id", System.currentTimeMillis())
        note.put("text", text)
        note.put("completed", false)
        notes.put(note)
        sticker.put("notes", notes)
        saveStickers()
        refreshNotesListUI()
    }

    private fun toggleNoteCompleted(noteIndex: Int) {
        if (selectedStickerIndex < 0 || selectedStickerIndex >= stickers.length()) return
        val sticker = stickers.getJSONObject(selectedStickerIndex)
        val notes = sticker.optJSONArray("notes") ?: return
        if (noteIndex < notes.length()) {
            val note = notes.getJSONObject(noteIndex)
            note.put("completed", !note.optBoolean("completed", false))
            saveStickers()
            refreshNotesListUI()
        }
    }

    private fun deleteNote(noteIndex: Int) {
        if (selectedStickerIndex < 0 || selectedStickerIndex >= stickers.length()) return
        val sticker = stickers.getJSONObject(selectedStickerIndex)
        val notes = sticker.optJSONArray("notes") ?: return
        if (noteIndex < notes.length()) {
            val newNotes = JSONArray()
            for (i in 0 until notes.length()) {
                if (i != noteIndex) newNotes.put(notes.getJSONObject(i))
            }
            sticker.put("notes", newNotes)
            saveStickers()
            refreshNotesListUI()
        }
    }
}
