package com.eink.screensaver

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class BookSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val etUserId = findViewById<EditText>(R.id.etBookmateUserId)
        val cbBookBackground = findViewById<CheckBox>(R.id.cbBookBackground)
        val fontSizeSeekBar = findViewById<SeekBar>(R.id.fontSizeSeekBar)
        val fontSizeLabel = findViewById<TextView>(R.id.fontSizeLabel)

        // Load
        etUserId.setText(PrefsManager.getBookmateUserId(this))
        cbBookBackground.isChecked = PrefsManager.isBookBackgroundEnabled(this)

        val fontSize = PrefsManager.getFontSizeBook(this)
        fontSizeSeekBar.progress = fontSize
        fontSizeLabel.text = getString(R.string.font_size_label, fontSize)

        // Listeners
        cbBookBackground.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBookBackgroundEnabled(this, isChecked)
        }

        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                fontSizeLabel.text = getString(R.string.font_size_label, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val userId = etUserId.text.toString().trim()
            PrefsManager.setBookmateUserId(this, userId)
            PrefsManager.setBookmateCache(this, "")
            PrefsManager.setFontSizeBook(this, fontSizeSeekBar.progress)
            Toast.makeText(this, R.string.yandex_books_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
