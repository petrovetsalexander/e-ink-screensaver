package com.eink.screensaver.settings

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R

class NewsSettingsActivity : AppCompatActivity() {

    private lateinit var etRssUrl: EditText
    private lateinit var etBodyTags: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val etRssUrl = findViewById<EditText>(R.id.etNewsRssUrl).also { this.etRssUrl = it }
        val etBodyTags = findViewById<EditText>(R.id.etBodyTags).also { this.etBodyTags = it }
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
            PrefsManager.setNewsOnlyHeader(this, isChecked)
            updateBodyFontSizeVisibility(isChecked)
        }

        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                intervalValue.text = getString(R.string.interval_value_min, progress + 1)
                if (fromUser) PrefsManager.setNewsIntervalMin(this@NewsSettingsActivity, progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        downloadCountSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                downloadCountLabel.text = getString(R.string.news_download_count_label, progress)
                if (fromUser) PrefsManager.setNewsDownloadCount(this@NewsSettingsActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        displayCountSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                displayCountLabel.text = getString(R.string.news_display_count_label, progress)
                if (fromUser) PrefsManager.setNewsDisplayCount(this@NewsSettingsActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                fontSizeLabel.text = getString(R.string.font_size_label, progress)
                if (fromUser) PrefsManager.setFontSizeNews(this@NewsSettingsActivity, progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        bodyFontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                bodyFontSizeLabel.text = getString(R.string.news_body_font_size_label, progress)
                if (fromUser) PrefsManager.setFontSizeNewsBody(this@NewsSettingsActivity, progress)
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

        cbShuffle.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setNewsShuffle(this, isChecked)
        }
    }

    /**
     * The URL and the tag list are saved on the way out rather than on every
     * keystroke: a half-typed feed address would otherwise be fetched.
     */
    override fun onPause() {
        super.onPause()
        PrefsManager.setNewsRssUrl(this, etRssUrl.text.toString().trim())
        PrefsManager.setNewsBodyTags(this, etBodyTags.text.toString().trim())
    }
}
