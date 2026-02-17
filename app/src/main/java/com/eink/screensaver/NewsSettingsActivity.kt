package com.eink.screensaver

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class NewsSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val etRssUrl = findViewById<EditText>(R.id.etNewsRssUrl)
        val etBodyTags = findViewById<EditText>(R.id.etBodyTags)
        val intervalSeekBar = findViewById<SeekBar>(R.id.newsIntervalSeekBar)
        val intervalValue = findViewById<TextView>(R.id.newsIntervalValue)
        val downloadCountSeekBar = findViewById<SeekBar>(R.id.downloadCountSeekBar)
        val downloadCountLabel = findViewById<TextView>(R.id.downloadCountLabel)
        val displayCountSeekBar = findViewById<SeekBar>(R.id.displayCountSeekBar)
        val displayCountLabel = findViewById<TextView>(R.id.displayCountLabel)
        val cbOnlyHeader = findViewById<CheckBox>(R.id.cbOnlyHeader)
        val cbShuffle = findViewById<CheckBox>(R.id.cbShuffle)
        val fontSizeSeekBar = findViewById<SeekBar>(R.id.fontSizeSeekBar)
        val fontSizeLabel = findViewById<TextView>(R.id.fontSizeLabel)
        val bodyFontSizeSeekBar = findViewById<SeekBar>(R.id.bodyFontSizeSeekBar)
        val bodyFontSizeLabel = findViewById<TextView>(R.id.bodyFontSizeLabel)

        // Load
        etRssUrl.setText(PrefsManager.getNewsRssUrl(this))
        etBodyTags.setText(PrefsManager.getNewsBodyTags(this))

        val newsInterval = PrefsManager.getNewsIntervalMin(this)
        intervalSeekBar.progress = newsInterval - 1
        intervalValue.text = getString(R.string.interval_value_min, newsInterval)

        val downloadCount = PrefsManager.getNewsDownloadCount(this)
        downloadCountSeekBar.progress = downloadCount
        downloadCountLabel.text = getString(R.string.news_download_count_label, downloadCount)

        val displayCount = PrefsManager.getNewsDisplayCount(this)
        displayCountSeekBar.progress = displayCount
        displayCountLabel.text = getString(R.string.news_display_count_label, displayCount)

        cbOnlyHeader.isChecked = PrefsManager.isNewsOnlyHeader(this)
        cbShuffle.isChecked = PrefsManager.isNewsShuffle(this)

        val fontSize = PrefsManager.getFontSizeNews(this)
        fontSizeSeekBar.progress = fontSize
        fontSizeLabel.text = getString(R.string.font_size_label, fontSize)

        val bodyFontSize = PrefsManager.getFontSizeNewsBody(this)
        bodyFontSizeSeekBar.progress = bodyFontSize
        bodyFontSizeLabel.text = getString(R.string.news_body_font_size_label, bodyFontSize)

        // Show/hide body font size based on only-header state
        fun updateBodyFontSizeVisibility(onlyHeader: Boolean) {
            val vis = if (onlyHeader) View.GONE else View.VISIBLE
            bodyFontSizeLabel.visibility = vis
            bodyFontSizeSeekBar.visibility = vis
        }
        updateBodyFontSizeVisibility(cbOnlyHeader.isChecked)

        // Listeners
        cbOnlyHeader.setOnCheckedChangeListener { _, isChecked ->
            updateBodyFontSizeVisibility(isChecked)
        }

        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                intervalValue.text = getString(R.string.interval_value_min, progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        downloadCountSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                downloadCountLabel.text = getString(R.string.news_download_count_label, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        displayCountSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                displayCountLabel.text = getString(R.string.news_display_count_label, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                fontSizeLabel.text = getString(R.string.font_size_label, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        bodyFontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                bodyFontSizeLabel.text = getString(R.string.news_body_font_size_label, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // Shuffle info button
        findViewById<TextView>(R.id.btnShuffleInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.news_shuffle)
                .setMessage(R.string.news_shuffle_explanation)
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            PrefsManager.setNewsRssUrl(this, etRssUrl.text.toString().trim())
            PrefsManager.setNewsBodyTags(this, etBodyTags.text.toString().trim())
            PrefsManager.setNewsIntervalMin(this, intervalSeekBar.progress + 1)
            PrefsManager.setNewsDownloadCount(this, downloadCountSeekBar.progress)
            PrefsManager.setNewsDisplayCount(this, displayCountSeekBar.progress)
            PrefsManager.setNewsOnlyHeader(this, cbOnlyHeader.isChecked)
            PrefsManager.setNewsShuffle(this, cbShuffle.isChecked)
            PrefsManager.setFontSizeNews(this, fontSizeSeekBar.progress)
            PrefsManager.setFontSizeNewsBody(this, bodyFontSizeSeekBar.progress)
            Toast.makeText(this, R.string.news_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
