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

/**
 * Manual-commit speech recognition.
 *
 * While active (user tapped "Listen"), it continuously captures speech and
 * accumulates every recognized segment into a buffer. Nothing is translated
 * until the user taps "Stop", at which point the entire accumulated text is
 * delivered via [SpeechCallback.onResult]. Live in-progress text is reported
 * through [SpeechCallback.onPreview] so the user can see what's being heard.
 */
class SpeechRecognitionManager(
    private val context: Context,
    private val callback: SpeechCallback
) {

    interface SpeechCallback {
        /** Called once, with the full accumulated text, when the user stops. */
        fun onResult(text: String)
        /** Called continuously with live captured text (for on-screen preview). */
        fun onPreview(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SpeechManager"
        private const val RESTART_DELAY_MS = 300L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentLanguageCode = "zh-CN"
    private var isListening = false
    private var isActive = false   // true = user tapped "listen", false = stopped

    // Finalized segments from completed recognition sessions, joined together.
    private var accumulatedText = ""
    // Text from the in-progress recognition session (not yet finalized).
    private var latestPartialText = ""

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var savedMusicVol = -1
    private var savedSystemVol = -1
    private var savedRingVol = -1
    private var savedNotifVol = -1

    // ── Public API ────────────────────────────────────────────────────────────

    /** Start listening. Clears any previously accumulated text. */
    fun startListening(languageCode: String) {
        currentLanguageCode = languageCode
        isActive = true
        accumulatedText = ""
        latestPartialText = ""
        muteForSession()
        initializeRecognizer()
        scheduleBegin(300)
    }

    /**
     * Stop listening and deliver the full accumulated transcript for translation.
     */
    fun stopAndCommit() {
        isActive = false
        isListening = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        restoreVolume()

        val full = buildFullText()
        accumulatedText = ""
        latestPartialText = ""
        if (full.isNotBlank()) {
            callback.onResult(full)
        }
    }

    /** Stop listening without translating (used internally / on language change). */
    fun pause() {
        isActive = false
        isListening = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        restoreVolume()
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
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
        restoreVolume()
    }

    private fun buildFullText(): String =
        (accumulatedText + " " + latestPartialText).trim().replace(Regex("\\s+"), " ")

    // ── Volume management ─────────────────────────────────────────────────────

    private fun muteForSession() {
        try {
            // Mute every stream Samsung could route the recognition beep through
            savedMusicVol  = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedSystemVol = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
            savedRingVol   = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            savedNotifVol  = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,        0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM,       0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_RING,         0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Could not mute streams", e)
        }
    }

    private fun restoreVolume() {
        try {
            if (savedMusicVol  >= 0) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,        savedMusicVol,  0); savedMusicVol  = -1 }
            if (savedSystemVol >= 0) { audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM,       savedSystemVol, 0); savedSystemVol = -1 }
            if (savedRingVol   >= 0) { audioManager.setStreamVolume(AudioManager.STREAM_RING,         savedRingVol,   0); savedRingVol   = -1 }
            if (savedNotifVol  >= 0) { audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVol,  0); savedNotifVol  = -1 }
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

    /** Push the current live transcript to the preview callback. */
    private fun emitPreview() {
        callback.onPreview(buildFullText())
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim() ?: return
            if (text.isNotBlank()) {
                latestPartialText = text
                emitPreview()
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim() ?: ""
            // A recognition session completed — append its final text to the buffer.
            val finalSegment = if (text.isNotBlank()) text else latestPartialText.trim()
            if (finalSegment.isNotBlank()) {
                accumulatedText = (accumulatedText + " " + finalSegment).trim()
            }
            latestPartialText = ""
            emitPreview()
            scheduleBegin()   // keep listening — don't translate yet
        }

        override fun onError(error: Int) {
            isListening = false
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // Silence — preserve the in-progress text into the buffer, restart.
                    if (latestPartialText.isNotBlank()) {
                        accumulatedText = (accumulatedText + " " + latestPartialText.trim()).trim()
                        latestPartialText = ""
                        emitPreview()
                    }
                    scheduleBegin()
                }
                SpeechRecognizer.ERROR_AUDIO -> {
                    // Microphone is held by another app (e.g. WeChat/WhatsApp during a call).
                    // Stop retrying — notify the user to switch to speakerphone.
                    Log.w(TAG, "Mic unavailable — another app holds it")
                    isActive = false
                    restoreVolume()
                    callback.onError("MIC_BUSY")
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

        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() { isListening = false }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
