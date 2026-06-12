package com.realtimetranslator

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.realtimetranslator.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "translator_prefs"
        const val KEY_LANGUAGE_DIRECTION = "language_direction"
        const val KEY_TTS_ENABLED = "tts_enabled"
        const val KEY_OVERLAY_OPACITY = "overlay_opacity"
        const val DIRECTION_ZH_TO_EN = "zh_to_en"
        const val DIRECTION_EN_TO_ZH = "en_to_zh"
        const val DEFAULT_OPACITY = 80
        const val ACTION_SETTINGS_CHANGED = "com.realtimetranslator.SETTINGS_CHANGED"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        loadSettings()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadSettings() {
        val direction = prefs.getString(KEY_LANGUAGE_DIRECTION, DIRECTION_ZH_TO_EN)
        if (direction == DIRECTION_ZH_TO_EN) {
            binding.rbZhToEn.isChecked = true
        } else {
            binding.rbEnToZh.isChecked = true
        }

        binding.switchTts.isChecked = prefs.getBoolean(KEY_TTS_ENABLED, false)

        val opacity = prefs.getInt(KEY_OVERLAY_OPACITY, DEFAULT_OPACITY)
        binding.seekBarOpacity.progress = opacity
        binding.tvOpacityValue.text = getString(R.string.opacity_percent, opacity)
    }

    private fun setupListeners() {
        binding.rgLanguageDirection.setOnCheckedChangeListener { _, checkedId ->
            val direction = when (checkedId) {
                R.id.rbZhToEn -> DIRECTION_ZH_TO_EN
                R.id.rbEnToZh -> DIRECTION_EN_TO_ZH
                else -> DIRECTION_ZH_TO_EN
            }
            prefs.edit().putString(KEY_LANGUAGE_DIRECTION, direction).apply()
            notifySettingsChanged()
        }

        binding.switchTts.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_TTS_ENABLED, isChecked).apply()
            notifySettingsChanged()
        }

        binding.seekBarOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvOpacityValue.text = getString(R.string.opacity_percent, progress)
                if (fromUser) {
                    prefs.edit().putInt(KEY_OVERLAY_OPACITY, progress).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: DEFAULT_OPACITY
                prefs.edit().putInt(KEY_OVERLAY_OPACITY, progress).apply()
                notifySettingsChanged()
            }
        })
    }

    private fun notifySettingsChanged() {
        val intent = Intent(ACTION_SETTINGS_CHANGED)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }
}
