package com.realtimetranslator

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechRecognitionManager(
    private val context: Context,
    private val callback: SpeechCallback
) {

    interface SpeechCallback {
        fun onResult(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SpeechManager"
        private const val RESTART_DELAY_MS = 1500L   // longer delay = fewer beeps
        private const val MUTE_DURATION_MS = 600L    // how long to mute the beep sound
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLanguageCode: String = "zh-CN"
    private var isListening = false
    private var isDestroyed = false
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun startListening(languageCode: String) {
        currentLanguageCode = languageCode
        isDestroyed = false
        initializeRecognizer()
        scheduleBeginListening(300)
    }

    fun updateLanguage(languageCode: String) {
        currentLanguageCode = languageCode
        if (isListening) {
            speechRecognizer?.stopListening()
            scheduleBeginListening(600)
        }
    }

    fun destroy() {
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
    }

    private fun initializeRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    private fun scheduleBeginListening(delayMs: Long = RESTART_DELAY_MS) {
        if (isDestroyed) return
        handler.postDelayed({ beginListening() }, delayMs)
    }

    private fun beginListening() {
        if (isDestroyed || isListening) return

        // Mute the beep that SpeechRecognizer plays on start
        muteRecognizerSound()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Extend silence timeout so it doesn't restart as often
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            isListening = false
            scheduleBeginListening()
        }
    }

    private fun muteRecognizerSound() {
        try {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_MUTE,
                0
            )
            // Unmute after the beep would have played
            handler.postDelayed({
                try {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE,
                        0
                    )
                } catch (e: Exception) { /* ignore */ }
            }, MUTE_DURATION_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not mute recognition sound", e)
        }
    }

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
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Normal silence — restart quietly
                    scheduleBeginListening()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Wait longer before retrying
                    scheduleBeginListening(2500)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    callback.onError("Microphone permission required")
                    isDestroyed = true
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    // Recreate recognizer on client error
                    initializeRecognizer()
                    scheduleBeginListening(1000)
                }
                else -> {
                    Log.w(TAG, "Recognition error code: $error")
                    scheduleBeginListening()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                callback.onResult(matches[0])
            }
            scheduleBeginListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                callback.onResult(matches[0])
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
