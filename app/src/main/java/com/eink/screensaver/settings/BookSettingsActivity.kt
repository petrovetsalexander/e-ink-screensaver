package com.eink.screensaver.settings

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R

class BookSettingsActivity : AppCompatActivity() {

    private lateinit var etUserId: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        etUserId = findViewById(R.id.etBookmateUserId)
        val cbBookBackground = findViewById<CheckBox>(R.id.cbBookBackground)
        val fontSizeSeekBar = findViewById<SeekBar>(R.id.fontSizeSeekBar)
        val fontSizeLabel = findViewById<TextView>(R.id.fontSizeLabel)

        // Load
        etUserId.setText(PrefsManager.getBookmateUserId(this))
        cbBookBackground.isChecked = PrefsManager.isBookBackgroundEnabled(this)

        val fontSize = PrefsManager.getFontSizeBook(this)
        fontSizeSeekBar.progress = fontSize
        fontSizeLabel.text = getString(R.string.font_size_label, fontSize)

        // Listeners — everything is written as it changes; there is no save button.
        cbBookBackground.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setBookBackgroundEnabled(this, isChecked)
        }

        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                fontSizeLabel.text = getString(R.string.font_size_label, progress)
                if (fromUser) {
                    PrefsManager.setFontSizeBook(this@BookSettingsActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    /**
     * The user id is saved on the way out rather than on every keystroke, since a
     * half-typed one would be looked up. The cached book is dropped only when the
     * id actually changed — clearing it on every visit would throw away a good
     * cover for nothing.
     */
    override fun onPause() {
        super.onPause()
        val entered = etUserId.text.toString().trim()
        if (entered != PrefsManager.getBookmateUserId(this)) {
            PrefsManager.setBookmateUserId(this, entered)
            PrefsManager.setBookmateCache(this, "")
        }
    }
}
