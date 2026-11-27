package com.glaadwin.ai

import android.content.Context
import android.media.MediaPlayer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ElevenLabsSpeaker(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "glaadwin_voice_prefs"
        private const val KEY_API_KEY = "elevenlabs_api_key"
        private const val KEY_VOICE_ID = "elevenlabs_voice_id"
        const val DEFAULT_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb" // George (British Jarvis-style voice)
        private const val MODEL_ID = "eleven_flash_v2_5"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private var mediaPlayer: MediaPlayer? = null

    fun getApiKey(): String {
        val raw = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        return if (raw.startsWith("Sk_")) "sk_" + raw.substring(3) else raw
    }

    fun getVoiceId(): String {
        val saved = prefs.getString(KEY_VOICE_ID, "")?.trim().orEmpty()
        return saved.ifEmpty { DEFAULT_VOICE_ID }
    }

    fun isConfigured(): Boolean = getApiKey().isNotEmpty()

    fun saveSettings(apiKey: String, voiceId: String) {
        var cleanKey = apiKey.trim()
        if (cleanKey.startsWith("Sk_")) {
            cleanKey = "sk_" + cleanKey.substring(3)
        }
        prefs.edit()
            .putString(KEY_API_KEY, cleanKey)
            .putString(KEY_VOICE_ID, voiceId.trim().ifEmpty { DEFAULT_VOICE_ID })
            .apply()
    }

    fun speak(text: String, onFallback: () -> Unit) {
        val cleanText = text.trim()
        val apiKey = getApiKey()
        if (cleanText.isEmpty() || apiKey.isEmpty()) {
            onFallback()
            return
        }

        val voiceId = getVoiceId()
        executor.execute {
            try {
                val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10000
                    readTimeout = 15000
                    doOutput = true
                    setRequestProperty("xi-api-key", apiKey)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "audio/mpeg")
                }

                val payload = JSONObject().apply {
                    put("text", cleanText)
                    put("model_id", MODEL_ID)
                }.toString()

                conn.outputStream.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode in 200..299) {
                    val audioFile = File(context.cacheDir, "glaadwin_tts.mp3")
                    conn.inputStream.use { input ->
                        audioFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    playFile(audioFile)
                } else {
                    onFallback()
                }
                conn.disconnect()
            } catch (_: Exception) {
                onFallback()
            }
        }
    }

    @Synchronized
    private fun playFile(file: File) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { mp ->
                mp.release()
                if (mediaPlayer === mp) mediaPlayer = null
            }
            prepare()
            start()
        }
    }

    @Synchronized
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }
}
