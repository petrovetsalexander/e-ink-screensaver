package com.eink.screensaver.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eink.screensaver.PrefsManager
import com.eink.screensaver.R

class WeatherSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_weather_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val etCity = findViewById<EditText>(R.id.etWeatherCity)
        val etApiKey = findViewById<EditText>(R.id.etWeatherApiKey)
        val intervalSeekBar = findViewById<SeekBar>(R.id.weatherIntervalSeekBar)
        val intervalValue = findViewById<TextView>(R.id.weatherIntervalValue)
        val fontSizeSeekBar = findViewById<SeekBar>(R.id.fontSizeSeekBar)
        val fontSizeLabel = findViewById<TextView>(R.id.fontSizeLabel)
        val stepSeekBar = findViewById<SeekBar>(R.id.weatherStepSeekBar)
        val stepValue = findViewById<TextView>(R.id.weatherStepValue)
        val slotsSeekBar = findViewById<SeekBar>(R.id.weatherSlotsSeekBar)
        val slotsValue = findViewById<TextView>(R.id.weatherSlotsValue)

        // Slot count starts at 0 (strip hidden), so progress is the value itself;
        // the step starts at 1, so it is offset by one.
        fun slotsLabel(count: Int) =
            if (count == 0) getString(R.string.weather_slots_off)
            else getString(R.string.weather_slots_value, count)

        // Load
        etCity.setText(PrefsManager.getWeatherCity(this))
        etApiKey.setText(PrefsManager.getWeatherApiKey(this))

        val weatherInterval = PrefsManager.getWeatherIntervalMin(this)
        intervalSeekBar.progress = weatherInterval - 1
        intervalValue.text = getString(R.string.interval_value_min, weatherInterval)

        val fontSize = PrefsManager.getFontSizeWeather(this)
        fontSizeSeekBar.progress = fontSize
        fontSizeLabel.text = getString(R.string.font_size_label, fontSize)

        val step = PrefsManager.getWeatherStepHours(this)
        stepSeekBar.progress = step - 1
        stepValue.text = getString(R.string.weather_step_value, step)

        val slots = PrefsManager.getWeatherSlots(this)
        slotsSeekBar.progress = slots
        slotsValue.text = slotsLabel(slots)

        // Listeners
        intervalSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                intervalValue.text = getString(R.string.interval_value_min, progress + 1)
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

        stepSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                stepValue.text = getString(R.string.weather_step_value, progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        slotsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                slotsValue.text = slotsLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            PrefsManager.setWeatherCity(this, etCity.text.toString().trim())
            PrefsManager.setWeatherApiKey(this, etApiKey.text.toString().trim())
            PrefsManager.setWeatherIntervalMin(this, intervalSeekBar.progress + 1)
            PrefsManager.setFontSizeWeather(this, fontSizeSeekBar.progress)
            PrefsManager.setWeatherStepHours(this, stepSeekBar.progress + 1)
            PrefsManager.setWeatherSlots(this, slotsSeekBar.progress)
            Toast.makeText(this, R.string.weather_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
