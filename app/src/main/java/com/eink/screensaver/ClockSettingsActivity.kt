package com.eink.screensaver

import android.os.Bundle
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ClockSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clock_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val clockIntervalSeekBar = findViewById<SeekBar>(R.id.clockIntervalSeekBar)
        val clockIntervalValue = findViewById<TextView>(R.id.clockIntervalValue)
        val cbShowDate = findViewById<CheckBox>(R.id.cbShowDate)
        val clockPositionGroup = findViewById<RadioGroup>(R.id.clockPositionGroup)
        val fontSizeSeekBar = findViewById<SeekBar>(R.id.fontSizeSeekBar)
        val fontSizeLabel = findViewById<TextView>(R.id.fontSizeLabel)

        // Load
        val clockInterval = PrefsManager.getUpdateIntervalMinutes(this)
        clockIntervalSeekBar.progress = clockInterval
        updateClockIntervalLabel(clockIntervalValue, clockInterval)

        cbShowDate.isChecked = PrefsManager.isShowDate(this)

        when (PrefsManager.getClockPosition(this)) {
            "right" -> clockPositionGroup.check(R.id.radioPositionRight)
            else -> clockPositionGroup.check(R.id.radioPositionLeft)
        }

        val fontSize = PrefsManager.getFontSizeClock(this)
        fontSizeSeekBar.progress = fontSize
        fontSizeLabel.text = getString(R.string.font_size_label, fontSize)

        // Listeners
        clockIntervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateClockIntervalLabel(clockIntervalValue, progress)
                if (fromUser) {
                    PrefsManager.setUpdateIntervalMinutes(this@ClockSettingsActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        cbShowDate.setOnCheckedChangeListener { _, isChecked ->
            PrefsManager.setShowDate(this, isChecked)
        }

        clockPositionGroup.setOnCheckedChangeListener { _, checkedId ->
            val position = when (checkedId) {
                R.id.radioPositionRight -> "right"
                else -> "left"
            }
            PrefsManager.setClockPosition(this, position)
        }

        fontSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                fontSizeLabel.text = getString(R.string.font_size_label, progress)
                if (fromUser) {
                    PrefsManager.setFontSizeClock(this@ClockSettingsActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun updateClockIntervalLabel(label: TextView, value: Int) {
        label.text = if (value == 0) {
            getString(R.string.interval_disabled)
        } else {
            getString(R.string.interval_value_min, value)
        }
    }
}
