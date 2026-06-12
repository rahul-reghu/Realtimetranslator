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
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.util.Locale

class TranslatorService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "translator_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP_TRANSLATION = "com.realtimetranslator.STOP_TRANSLATION"
        const val ACTION_TOGGLE_LANGUAGE = "com.realtimetranslator.TOGGLE_LANGUAGE"
        const val ACTION_SERVICE_STATE = "com.realtimetranslator.SERVICE_STATE"
        const val EXTRA_RUNNING = "running"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var overlayManager: OverlayManager
    private lateinit var speechManager: SpeechRecognitionManager
    private lateinit var translationManager: TranslationManager
    private lateinit var ttsManager: TextToSpeechManager

    private var currentDirection: String = SettingsActivity.DIRECTION_ZH_TO_EN

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STOP_TRANSLATION -> stopSelf()
            }
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

        val filter = IntentFilter().apply {
            addAction(ACTION_STOP_TRANSLATION)
        }
        registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)

        ttsManager = TextToSpeechManager(this)
        translationManager = TranslationManager(currentDirection)

        overlayManager = OverlayManager(this).apply {
            onTtsRequested = { text ->
                val locale = if (currentDirection == SettingsActivity.DIRECTION_ZH_TO_EN) {
                    Locale.ENGLISH
                } else {
                    Locale.CHINESE
                }
                ttsManager.speak(text, locale)
            }
            onLanguageToggled = {
                currentDirection = if (currentDirection == SettingsActivity.DIRECTION_ZH_TO_EN) {
                    SettingsActivity.DIRECTION_EN_TO_ZH
                } else {
                    SettingsActivity.DIRECTION_ZH_TO_EN
                }
                prefs.edit().putString(SettingsActivity.KEY_LANGUAGE_DIRECTION, currentDirection).apply()
                translationManager.updateDirection(currentDirection)
                speechManager.updateLanguage(getSourceLanguageCode())
            }
        }

        speechManager = SpeechRecognitionManager(this, object : SpeechRecognitionManager.SpeechCallback {
            override fun onResult(text: String) {
                translationManager.translate(
                    text = text,
                    onSuccess = { translated ->
                        overlayManager.updateTranslation(text, translated)
                        if (prefs.getBoolean(SettingsActivity.KEY_TTS_ENABLED, true)) {
                            val locale = if (currentDirection == SettingsActivity.DIRECTION_ZH_TO_EN) {
                                Locale.ENGLISH
                            } else {
                                Locale.CHINESE
                            }
                            ttsManager.speak(translated, locale)
                        }
                    },
                    onFailure = { exception ->
                        overlayManager.updateTranslation(text, "Translation error: ${exception.message}")
                    }
                )
            }

            override fun onError(message: String) {
                overlayManager.updateTranslation("", "Error: $message")
            }
        })

        overlayManager.show()
        broadcastServiceState(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(NOTIFICATION_ID, buildNotification())
        speechManager.startListening(getSourceLanguageCode())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        broadcastServiceState(false)
        if (::speechManager.isInitialized) speechManager.destroy()
        if (::translationManager.isInitialized) translationManager.close()
        if (::ttsManager.isInitialized) ttsManager.shutdown()
        if (::overlayManager.isInitialized) overlayManager.remove()
        try {
            unregisterReceiver(stopReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered
        }
    }

    private fun broadcastServiceState(running: Boolean) {
        val intent = Intent(ACTION_SERVICE_STATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_RUNNING, running)
        sendBroadcast(intent)
    }

    private fun getSourceLanguageCode(): String {
        return if (currentDirection == SettingsActivity.DIRECTION_ZH_TO_EN) "zh-CN" else "en-US"
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Translator Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Real-time translator is running"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(ACTION_STOP_TRANSLATION)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
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
