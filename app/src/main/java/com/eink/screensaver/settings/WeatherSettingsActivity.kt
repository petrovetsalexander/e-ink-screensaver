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

        // Load
        etCity.setText(PrefsManager.getWeatherCity(this))
        etApiKey.setText(PrefsManager.getWeatherApiKey(this))

        val weatherInterval = PrefsManager.getWeatherIntervalMin(this)
        intervalSeekBar.progress = weatherInterval - 1
        intervalValue.text = getString(R.string.interval_value_min, weatherInterval)

        val fontSize = PrefsManager.getFontSizeWeather(this)
        fontSizeSeekBar.progress = fontSize
        fontSizeLabel.text = getString(R.string.font_size_label, fontSize)

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

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            PrefsManager.setWeatherCity(this, etCity.text.toString().trim())
            PrefsManager.setWeatherApiKey(this, etApiKey.text.toString().trim())
            PrefsManager.setWeatherIntervalMin(this, intervalSeekBar.progress + 1)
            PrefsManager.setFontSizeWeather(this, fontSizeSeekBar.progress)
            Toast.makeText(this, R.string.weather_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
