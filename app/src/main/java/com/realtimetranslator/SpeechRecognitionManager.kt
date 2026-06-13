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
        private const val SPEAKING_RMS_THRESHOLD = 0.5f
        private const val SILENCE_TO_COMMIT_MS = 1500L
        private const val RESTART_DELAY_MS = 400L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLanguageCode = "zh-CN"
    private var isListening = false
    private var isActive = false   // true = user tapped "listen", false = paused

    private var personIsSpeaking = false
    private var latestPartialText = ""

    private val handler = Handler(Looper.getMainLooper())
    private val silenceHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Saved volumes — set to 0 while session is active, restored on pause/destroy
    private var savedMusicVol = -1
    private var savedSystemVol = -1

    private val silenceCommitRunnable = Runnable {
        val text = latestPartialText.trim()
        if (text.isNotBlank()) {
            latestPartialText = ""
            personIsSpeaking = false
            callback.onResult(text)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startListening(languageCode: String) {
        currentLanguageCode = languageCode
        isActive = true
        muteForSession()          // silence beep for entire session
        initializeRecognizer()
        scheduleBegin(300)
    }

    fun pause() {
        isActive = false
        isListening = false
        handler.removeCallbacksAndMessages(null)
        silenceHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        restoreVolume()           // restore volume when user pauses
    }

    fun updateLanguage(languageCode: String) {
        currentLanguageCode = languageCode
        if (isListening) {
            speechRecognizer?.cancel()
            scheduleBegin(400)
        }
    }

    fun destroy() {
        isActive = false
        handler.removeCallbacksAndMessages(null)
        silenceHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
        restoreVolume()
    }

    // ── Volume management ─────────────────────────────────────────────────────

    private fun muteForSession() {
        try {
            savedMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedSystemVol = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not mute streams", e)
        }
    }

    private fun restoreVolume() {
        try {
            if (savedMusicVol >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0)
                savedMusicVol = -1
            }
            if (savedSystemVol >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0)
                savedSystemVol = -1
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore volume", e)
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun initializeRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
    }

    private fun scheduleBegin(delayMs: Long = RESTART_DELAY_MS) {
        if (!isActive) return
        handler.postDelayed({ beginListening() }, delayMs)
    }

    private fun beginListening() {
        if (!isActive || isListening) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageCode)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            isListening = false
            scheduleBegin(1000)
        }
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (rmsdB > SPEAKING_RMS_THRESHOLD) {
                personIsSpeaking = true
                silenceHandler.removeCallbacks(silenceCommitRunnable)
            } else if (personIsSpeaking && latestPartialText.isNotBlank()) {
                silenceHandler.removeCallbacks(silenceCommitRunnable)
                silenceHandler.postDelayed(silenceCommitRunnable, SILENCE_TO_COMMIT_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim() ?: return
            if (text.isNotBlank()) {
                latestPartialText = text
                personIsSpeaking = true
                silenceHandler.removeCallbacks(silenceCommitRunnable)
                silenceHandler.postDelayed(silenceCommitRunnable, SILENCE_TO_COMMIT_MS)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim() ?: ""
            silenceHandler.removeCallbacks(silenceCommitRunnable)
            val toCommit = if (text.isNotBlank()) text else latestPartialText.trim()
            if (toCommit.isNotBlank()) {
                latestPartialText = ""
                personIsSpeaking = false
                callback.onResult(toCommit)
            }
            scheduleBegin()
        }

        override fun onError(error: Int) {
            isListening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    val accumulated = latestPartialText.trim()
                    if (accumulated.isNotBlank()) {
                        silenceHandler.removeCallbacks(silenceCommitRunnable)
                        latestPartialText = ""
                        personIsSpeaking = false
                        callback.onResult(accumulated)
                    }
                    scheduleBegin()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleBegin(2000)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    callback.onError("Microphone permission required")
                    isActive = false
                }
                SpeechRecognizer.ERROR_CLIENT -> {
                    initializeRecognizer()
                    scheduleBegin(800)
                }
                else -> {
                    Log.w(TAG, "Recognition error: $error")
                    scheduleBegin()
                }
            }
        }

        override fun onBeginningOfSpeech() {
            personIsSpeaking = true
            silenceHandler.removeCallbacks(silenceCommitRunnable)
        }

        override fun onEndOfSpeech() {
            isListening = false
            // Person stopped speaking — start silence timer regardless of VAD state
            if (latestPartialText.isNotBlank()) {
                personIsSpeaking = true
                silenceHandler.removeCallbacks(silenceCommitRunnable)
                silenceHandler.postDelayed(silenceCommitRunnable, SILENCE_TO_COMMIT_MS)
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
