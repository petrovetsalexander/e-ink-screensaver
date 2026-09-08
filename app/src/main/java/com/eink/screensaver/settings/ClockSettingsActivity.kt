package com.eink.screensaver.settings

import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R

class ClockSettingsActivity : AppCompatActivity() {

    private lateinit var etWeatherCity: EditText

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

        setUpWeather()
    }

    /**
     * Weather shares the header row with the clock — it cannot be positioned
     * independently — so it has no module of its own and its settings live on
     * this screen.
     */
    private fun setUpWeather() {
        val cbWeather = findViewById<CheckBox>(R.id.cbWeatherEnabled)
        etWeatherCity = findViewById(R.id.etWeatherCity)
        val intervalSeekBar = findViewById<SeekBar>(R.id.weatherIntervalSeekBar)
        val intervalValue = findViewById<TextView>(R.id.weatherIntervalValue)
        val stepSeekBar = findViewById<SeekBar>(R.id.weatherStepSeekBar)
        val stepValue = findViewById<TextView>(R.id.weatherStepValue)
        val slotsSeekBar = findViewById<SeekBar>(R.id.weatherSlotsSeekBar)
        val slotsValue = findViewById<TextView>(R.id.weatherSlotsValue)
        val wFontSeekBar = findViewById<SeekBar>(R.id.weatherFontSizeSeekBar)
        val wFontLabel = findViewById<TextView>(R.id.weatherFontSizeLabel)

        fun slotsLabel(count: Int) =
            if (count == 0) getString(R.string.weather_slots_off)
            else getString(R.string.weather_slots_value, count)

        cbWeather.isChecked = PrefsManager.isBlockWeatherEnabled(this)
        etWeatherCity.setText(PrefsManager.getWeatherCity(this))

        val interval = PrefsManager.getWeatherIntervalMin(this)
        intervalSeekBar.progress = interval - 1
        intervalValue.text = getString(R.string.interval_value_min, interval)

        val step = PrefsManager.getWeatherStepHours(this)
        stepSeekBar.progress = step - 1
        stepValue.text = getString(R.string.weather_step_value, step)

        val slots = PrefsManager.getWeatherSlots(this)
        slotsSeekBar.progress = slots
        slotsValue.text = slotsLabel(slots)

        val wFont = PrefsManager.getFontSizeWeather(this)
        wFontSeekBar.progress = wFont
        wFontLabel.text = getString(R.string.font_size_label, wFont)

        cbWeather.setOnCheckedChangeListener { _, checked ->
            PrefsManager.setBlockWeatherEnabled(this, checked)
        }

        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                intervalValue.text = getString(R.string.interval_value_min, progress + 1)
                if (fromUser) {
                    PrefsManager.setWeatherIntervalMin(this@ClockSettingsActivity, progress + 1)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        stepSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                stepValue.text = getString(R.string.weather_step_value, progress + 1)
                if (fromUser) {
                    PrefsManager.setWeatherStepHours(this@ClockSettingsActivity, progress + 1)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        slotsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                slotsValue.text = slotsLabel(progress)
                if (fromUser) {
                    PrefsManager.setWeatherSlots(this@ClockSettingsActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        wFontSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                wFontLabel.text = getString(R.string.font_size_label, progress)
                if (fromUser) {
                    PrefsManager.setFontSizeWeather(this@ClockSettingsActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    /**
     * The city is saved on the way out rather than on every keystroke: a
     * half-typed name would otherwise be written and looked up.
     */
    override fun onPause() {
        super.onPause()
        PrefsManager.setWeatherCity(this, etWeatherCity.text.toString().trim())
    }

    private fun updateClockIntervalLabel(label: TextView, value: Int) {
        label.text = if (value == 0) {
            getString(R.string.interval_disabled)
        } else {
            getString(R.string.interval_value_min, value)
        }
    }
}
