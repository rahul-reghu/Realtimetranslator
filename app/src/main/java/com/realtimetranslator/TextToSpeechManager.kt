package com.realtimetranslator

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class TextToSpeechManager(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false
    private var pendingText: String? = null
    private var pendingLocale: Locale? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isReady = true
            Log.d("TTS", "TextToSpeech initialized successfully")
            val pending = pendingText
            val locale = pendingLocale
            if (pending != null && locale != null) {
                speak(pending, locale)
                pendingText = null
                pendingLocale = null
            }
        } else {
            Log.e("TTS", "TextToSpeech initialization failed with status: $status")
            isReady = false
        }
    }

    fun speak(text: String, locale: Locale) {
        if (text.isBlank()) return

        if (!isReady) {
            pendingText = text
            pendingLocale = locale
            return
        }

        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w("TTS", "Language not supported: $locale, falling back to default")
            tts.setLanguage(Locale.getDefault())
        }

        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }

    fun stop() {
        if (isReady) {
            tts.stop()
        }
    }

    fun shutdown() {
        stop()
        tts.shutdown()
        isReady = false
    }
}
