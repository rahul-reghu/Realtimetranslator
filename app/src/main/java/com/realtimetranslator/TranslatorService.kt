package com.realtimetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.util.Locale

class TranslatorService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "translator_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_TRANSLATION = "com.realtimetranslator.STOP_TRANSLATION"
        const val ACTION_SERVICE_STATE = "com.realtimetranslator.SERVICE_STATE"
        const val EXTRA_RUNNING = "running"
        private const val TAG = "TranslatorService"
    }

    private lateinit var prefs: SharedPreferences
    private var overlayManager: OverlayManager? = null
    private var speechManager: SpeechRecognitionManager? = null
    private var translationManager: TranslationManager? = null
    private var ttsManager: TextToSpeechManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentDirection: String = SettingsActivity.DIRECTION_ZH_TO_EN

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_TRANSLATION) stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, MODE_PRIVATE)
        currentDirection = prefs.getString(
            SettingsActivity.KEY_LANGUAGE_DIRECTION,
            SettingsActivity.DIRECTION_ZH_TO_EN
        ) ?: SettingsActivity.DIRECTION_ZH_TO_EN

        createNotificationChannel()

        try {
            registerReceiver(
                stopReceiver,
                IntentFilter(ACTION_STOP_TRANSLATION),
                RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Must call startForeground immediately
        startForeground(NOTIFICATION_ID, buildNotification())

        // Delay initialization slightly to let the service settle
        handler.postDelayed({ initializeComponents() }, 500)

        return START_STICKY
    }

    private fun initializeComponents() {
        try {
            ttsManager = TextToSpeechManager(this)
        } catch (e: Exception) {
            Log.e(TAG, "TTS init failed", e)
        }

        try {
            translationManager = TranslationManager(currentDirection)
        } catch (e: Exception) {
            Log.e(TAG, "Translation init failed", e)
        }

        try {
            overlayManager = OverlayManager(this).apply {
                onTtsRequested = { text ->
                    val locale = if (currentDirection == SettingsActivity.DIRECTION_ZH_TO_EN)
                        Locale.ENGLISH else Locale.CHINESE
                    ttsManager?.speak(text, locale)
                }
                onLanguageToggled = {
                    currentDirection = if (currentDirection == SettingsActivity.DIRECTION_ZH_TO_EN)
                        SettingsActivity.DIRECTION_EN_TO_ZH
                    else
                        SettingsActivity.DIRECTION_ZH_TO_EN
                    prefs.edit().putString(SettingsActivity.KEY_LANGUAGE_DIRECTION, currentDirection).apply()
                    translationManager?.updateDirection(currentDirection)
                    speechManager?.updateLanguage(getSourceLanguageCode())
                }
            }
            overlayManager?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Overlay init failed", e)
            Toast.makeText(this, "Overlay failed: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // Delay speech start further to avoid conflicts with overlay init
        handler.postDelayed({ startSpeechRecognition() }, 1000)
    }

    private fun startSpeechRecognition() {
        try {
            speechManager = SpeechRecognitionManager(
                this,
                object : SpeechRecognitionManager.SpeechCallback {
                    override fun onResult(text: String) {
                        translationManager?.translate(
                            text = text,
                            onSuccess = { translated ->
                                overlayManager?.updateTranslation(text, translated)
                                if (prefs.getBoolean(SettingsActivity.KEY_TTS_ENABLED, false)) {
                                    val locale = if (currentDirection == SettingsActivity.DIRECTION_ZH_TO_EN)
                                        Locale.ENGLISH else Locale.CHINESE
                                    ttsManager?.speak(translated, locale)
                                }
                            },
                            onFailure = { ex ->
                                overlayManager?.updateTranslation(text, "Translation error")
                                Log.e(TAG, "Translation failed", ex)
                            }
                        )
                    }

                    override fun onError(message: String) {
                        Log.w(TAG, "Speech error: $message")
                    }
                }
            )
            speechManager?.startListening(getSourceLanguageCode())
            broadcastServiceState(true)
        } catch (e: Exception) {
            Log.e(TAG, "Speech recognition init failed", e)
            Toast.makeText(this, "Speech recognition failed: ${e.message}", Toast.LENGTH_LONG).show()
            broadcastServiceState(true) // still mark as running (overlay works without STT)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        broadcastServiceState(false)
        speechManager?.destroy()
        translationManager?.close()
        ttsManager?.shutdown()
        overlayManager?.remove()
        try { unregisterReceiver(stopReceiver) } catch (e: Exception) { }
    }

    private fun broadcastServiceState(running: Boolean) {
        val intent = Intent(ACTION_SERVICE_STATE).apply { setPackage(packageName) }
        intent.putExtra(EXTRA_RUNNING, running)
        sendBroadcast(intent)
    }

    private fun getSourceLanguageCode() =
        if (currentDirection == SettingsActivity.DIRECTION_ZH_TO_EN) "zh-CN" else "en-US"

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Translator Service", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Real-time translator is running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(ACTION_STOP_TRANSLATION).apply { setPackage(packageName) }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translator Active")
            .setContentText("Real-time translation is running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
