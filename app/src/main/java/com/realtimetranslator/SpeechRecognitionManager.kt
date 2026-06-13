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

        // RMS level above this = someone is speaking
        private const val SPEAKING_RMS_THRESHOLD = 2.0f

        // How long silence must last before we consider the person done speaking (ms)
        private const val SILENCE_TO_COMMIT_MS = 1500L

        // How long to wait before restarting recognition after a session ends (ms)
        private const val RESTART_DELAY_MS = 300L

        // Mute the beep for this long after starting recognition (ms)
        private const val MUTE_DURATION_MS = 500L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLanguageCode: String = "zh-CN"
    private var isListening = false
    private var isDestroyed = false

    // VAD state
    private var personIsSpeaking = false
    private var latestPartialText = ""
    private val silenceHandler = Handler(Looper.getMainLooper())
    private val handler = Handler(Looper.getMainLooper())

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Fires when silence has lasted long enough after the person spoke
    private val silenceCommitRunnable = Runnable {
        if (latestPartialText.isNotBlank()) {
            val text = latestPartialText.trim()
            latestPartialText = ""
            personIsSpeaking = false
            callback.onResult(text)
        }
    }

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
        silenceHandler.removeCallbacksAndMessages(null)
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

        muteRecognizerBeep()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Set a generous silence window — our VAD handles the real end-of-speech
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            isListening = false
            scheduleBeginListening(1000)
        }
    }

    private fun muteRecognizerBeep() {
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            handler.postDelayed({
                try {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                } catch (e: Exception) { }
            }, MUTE_DURATION_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not mute recognition sound", e)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            // This is the heart of VAD: monitor real-time audio level
            if (rmsdB > SPEAKING_RMS_THRESHOLD) {
                // Audio detected — person is speaking, cancel any pending commit
                if (!personIsSpeaking) {
                    personIsSpeaking = true
                }
                silenceHandler.removeCallbacks(silenceCommitRunnable)
            } else if (personIsSpeaking && latestPartialText.isNotBlank()) {
                // Audio dropped — person may have paused
                // Start silence timer only if not already running
                silenceHandler.removeCallbacks(silenceCommitRunnable)
                silenceHandler.postDelayed(silenceCommitRunnable, SILENCE_TO_COMMIT_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: return
            if (text.isNotBlank()) {
                latestPartialText = text
                // Reset the silence timer every time new partial text arrives
                if (personIsSpeaking) {
                    silenceHandler.removeCallbacks(silenceCommitRunnable)
                    silenceHandler.postDelayed(silenceCommitRunnable, SILENCE_TO_COMMIT_MS)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""

            // Commit whatever we have (final result from recognizer)
            silenceHandler.removeCallbacks(silenceCommitRunnable)
            val toCommit = if (text.isNotBlank()) text else latestPartialText.trim()
            if (toCommit.isNotBlank()) {
                latestPartialText = ""
                personIsSpeaking = false
                callback.onResult(toCommit)
            }

            scheduleBeginListening()
        }

        override fun onError(error: Int) {
            isListening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Commit anything accumulated before restarting
                    val accumulated = latestPartialText.trim()
                    if (accumulated.isNotBlank()) {
                        silenceHandler.removeCallbacks(silenceCommitRunnable)
                        latestPartialText = ""
                        personIsSpeaking = false
                        callback.onResult(accumulated)
                    }
                    scheduleBeginListening()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    scheduleBeginListening(2000)
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    callback.onError("Microphone permission required")
                    isDestroyed = true
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    initializeRecognizer()
                    scheduleBeginListening(1000)
                }
                else -> {
                    Log.w(TAG, "Recognition error: $error")
                    scheduleBeginListening()
                }
            }
        }

        override fun onBeginningOfSpeech() {
            personIsSpeaking = true
            silenceHandler.removeCallbacks(silenceCommitRunnable)
        }

        override fun onEndOfSpeech() {
            isListening = false
            // Don't commit immediately — wait for silence timer to fire
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
