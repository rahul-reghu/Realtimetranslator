package com.realtimetranslator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class SpeechRecognitionManager(
    private val context: Context,
    private val callback: SpeechCallback
) {

    interface SpeechCallback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLanguageCode: String = "zh-CN"
    private var isListening = false
    private var isDestroyed = false
    private val handler = Handler(Looper.getMainLooper())

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
        }

        override fun onError(error: Int) {
            isListening = false
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> null // don't report busy errors
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                else -> "Unknown error"
            }

            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                // Don't restart on busy error, just wait
                handler.postDelayed({ restartListening() }, 1000)
                return
            }

            errorMessage?.let { callback.onError(it) }

            if (!isDestroyed) {
                handler.postDelayed({ restartListening() }, 500)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                callback.onResult(matches[0])
            }
            if (!isDestroyed) {
                handler.postDelayed({ restartListening() }, 500)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches[0].isNotEmpty()) {
                callback.onResult(matches[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun startListening(languageCode: String) {
        currentLanguageCode = languageCode
        initializeRecognizer()
        beginListening()
    }

    fun updateLanguage(languageCode: String) {
        currentLanguageCode = languageCode
        if (isListening) {
            speechRecognizer?.stopListening()
            handler.postDelayed({ beginListening() }, 500)
        }
    }

    private fun initializeRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    private fun beginListening() {
        if (isDestroyed) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            callback.onError("Failed to start listening: ${e.message}")
        }
    }

    private fun restartListening() {
        if (isDestroyed) return
        if (!isListening) {
            beginListening()
        }
    }

    fun destroy() {
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
