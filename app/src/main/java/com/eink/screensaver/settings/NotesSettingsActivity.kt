package com.eink.screensaver.settings

import android.os.Bundle
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R

class NotesSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val columnsSeekBar = findViewById<SeekBar>(R.id.columnsSeekBar)
        val columnsLabel = findViewById<TextView>(R.id.columnsLabel)
        val fontSizeSeekBar = findViewById<SeekBar>(R.id.fontSizeSeekBar)
        val fontSizeLabel = findViewById<TextView>(R.id.fontSizeLabel)

        // Load
        val columns = PrefsManager.getNotesColumns(this)
        columnsSeekBar.progress = columns
        columnsLabel.text = getString(R.string.notes_columns_label, columns)

        val fontSize = PrefsManager.getFontSizeNotes(this)
        fontSizeSeekBar.progress = fontSize
        fontSizeLabel.text = getString(R.string.font_size_label, fontSize)

        // Listeners
        columnsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                columnsLabel.text = getString(R.string.notes_columns_label, progress)
                if (fromUser) {
                    PrefsManager.setNotesColumns(this@NotesSettingsActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                fontSizeLabel.text = getString(R.string.font_size_label, progress)
                if (fromUser) {
                    PrefsManager.setFontSizeNotes(this@NotesSettingsActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }
}
