package com.glaadwin.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_START = "com.glaadwin.ai.ACTION_START_WAKE"
        const val ACTION_STOP = "com.glaadwin.ai.ACTION_STOP_WAKE"
        const val ACTION_PAUSE = "com.glaadwin.ai.ACTION_PAUSE_WAKE"
        const val ACTION_RESUME = "com.glaadwin.ai.ACTION_RESUME_WAKE"
        const val EXTRA_WAKE_TRIGGERED = "extra_wake_triggered"

        private const val CHANNEL_ID = "glaadwin_wake_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_COOLDOWN_MS = 3500L

        private const val WAKE_GRAMMAR =
            """["hey glad win", "glad win", "hey gladwin", "gladwin", "hey jarvis", "[unk]"]"""

        @Volatile
        var isRunning: Boolean = false
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var lastTriggerTimeMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWakeService()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                speechService?.setPause(true)
                updateNotification("Glaadwin AI is active (wake-word paused)")
                return START_STICKY
            }

            ACTION_RESUME -> {
                if (speechService != null) {
                    speechService?.setPause(false)
                    updateNotification("Listening for \"Hey Glaadwin\"...")
                } else {
                    startAsForeground("Starting wake-word engine...")
                    initVoskModel()
                }
                return START_STICKY
            }

            else -> {
                isRunning = true
                startAsForeground("Loading \"Hey Glaadwin\" engine...")
                if (speechService == null) {
                    initVoskModel()
                } else {
                    speechService?.setPause(false)
                    updateNotification("Listening for \"Hey Glaadwin\"...")
                }
                return START_STICKY
            }
        }
    }

    private fun startAsForeground(statusText: String) {
        val notification = buildNotification(statusText)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun initVoskModel() {
        StorageService.unpack(
            this,
            "model-en-us",
            "model",
            { loadedModel: Model ->
                model = loadedModel
                startListening(loadedModel)
            },
            { _: IOException ->
                updateNotification("Wake-word model missing — tap to open Glaadwin AI")
            }
        )
    }

    private fun startListening(loadedModel: Model) {
        try {
            speechService?.stop()
            speechService?.shutdown()

            val recognizer = Recognizer(loadedModel, 16000.0f, WAKE_GRAMMAR)
            speechService = SpeechService(recognizer, 16000.0f).also {
                it.startListening(this)
            }
            updateNotification("Listening for \"Hey Glaadwin\"...")
        } catch (_: Exception) {
            updateNotification("Microphone busy — tap Stop or reopen Glaadwin AI")
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        checkWakePhrase(hypothesis, "partial")
    }

    override fun onResult(hypothesis: String?) {
        checkWakePhrase(hypothesis, "text")
    }

    override fun onFinalResult(hypothesis: String?) {
        checkWakePhrase(hypothesis, "text")
    }

    override fun onError(exception: Exception?) {
        updateNotification("Mic paused — reopen Glaadwin AI to resume")
    }

    override fun onTimeout() {
        // Keep listening continuously while foreground service is active.
    }

    private fun checkWakePhrase(jsonText: String?, key: String) {
        if (jsonText.isNullOrBlank()) return
        val spoken = try {
            JSONObject(jsonText).optString(key, "").lowercase().trim()
        } catch (_: Exception) {
            ""
        }

        if (spoken.isEmpty()) return

        val matched = spoken.contains("glad win") ||
            spoken.contains("gladwin") ||
            spoken.contains("hey jarvis")

        if (!matched) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < WAKE_COOLDOWN_MS) return
        lastTriggerTimeMs = now

        // Pause Vosk immediately so MainActivity / WebView can use the microphone cleanly.
        speechService?.setPause(true)
        updateNotification("Glaadwin AI is awake!")

        val wakeIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            putExtra(EXTRA_WAKE_TRIGGERED, true)
        }
        startActivity(wakeIntent)
    }

    private fun stopWakeService() {
        isRunning = false
        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null
        } catch (_: Exception) {
        }
        try {
            model?.close()
            model = null
        } catch (_: Exception) {
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Glaadwin AI Wake Word",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Glaadwin AI is listening for Hey Glaadwin"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Glaadwin AI")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Listening", stopPending)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    override fun onDestroy() {
        isRunning = false
        try {
            speechService?.stop()
            speechService?.shutdown()
        } catch (_: Exception) {
        }
        try {
            model?.close()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
