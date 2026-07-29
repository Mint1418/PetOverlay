package com.qiuyu.petoverlay.utils

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask

class SupabaseSync(
    private val supabaseUrl: String,
    private val supabaseKey: String,
    private val webView: WebView?
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pollTimer: Timer? = null
    private var latestMessageId: Long = 0
    private var serviceRef: Any? = null

    companion object {
        private const val POLL_INTERVAL = 5000L
        private const val TAG = "SupabaseSync"

        // MOSS TTS
        private const val MOSS_API_URL = "https://api.mosi.cn/v1/audio/speech"
        private const val MOSS_API_KEY = "YOUR_MOSS_API_KEY_HERE"
        private const val MOSS_VOICE_ID = "YOUR_MOSS_VOICE_ID_HERE"
    }

    fun setService(svc: Any) {
        serviceRef = svc
        Log.d(TAG, "Service reference set")
    }

    fun pushPetState(key: String, value: String) {
        val body = JSONObject().apply {
            put("state_key", key)
            put("state_value", value)
        }
        postToTable("pet_state", body)
    }

    fun pushBubbleLocal(text: String) { pushPetState("speech_bubble", text) }
    fun pushMood(mood: String) { pushPetState("mood", mood) }

    fun sendMessage(content: String) {
        val body = JSONObject().apply {
            put("sender", "pet")
            put("content", content)
        }
        postToTable("pet_messages", body)
    }

    fun startPolling() {
        Log.d(TAG, "Supabase polling started: $supabaseUrl (interval=${POLL_INTERVAL}ms)")
        pollTimer = Timer()
        pollTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() { pollMessages() }
        }, POLL_INTERVAL, POLL_INTERVAL)
    }

    private fun pollMessages() {
        try {
            val url = URL("$supabaseUrl/rest/v1/pet_messages?order=id.desc&limit=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("apikey", supabaseKey)
            conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val arr = org.json.JSONArray(response)
                if (arr.length() > 0) {
                    val latest = arr.getJSONObject(0)
                    val id = latest.optLong("id", 0)
                    val sender = latest.optString("sender", "")
                    if (id > latestMessageId) {
                        latestMessageId = id
                        val content = latest.getString("content")
                        Log.d(TAG, "New message[$sender]: $content")
                        showBubbleViaService(content)
                        synthesizeAndPlay(content)
                    }
                }
            } else {
                Log.w(TAG, "Poll failed: ${conn.responseCode}")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Poll error", e)
        }
    }

    private fun showBubbleViaService(content: String) {
        try {
            val svc = serviceRef ?: run {
                Log.w(TAG, "serviceRef is null, fallback to evaluateJavascript")
                showBubbleViaWebView(content)
                return
            }
            val method = svc.javaClass.getMethod("pushBubble", String::class.java)
            handler.post { method.invoke(svc, content) }
            Log.d(TAG, "Bubble shown via pushBubble: $content")
        } catch (e: Exception) {
            Log.e(TAG, "pushBubble reflection failed", e)
            showBubbleViaWebView(content)
        }
    }

    private fun showBubbleViaWebView(content: String) {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        handler.post {
            try {
                webView?.evaluateJavascript(
                    "try { window.petEngine && window.petEngine.showBubble(\"$escaped\"); } catch(e) {}",
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "evaluateJavascript failed", e)
            }
        }
    }

    fun synthesizeAndPlay(text: String) {
        Thread {
            try {
                val body = JSONObject().apply {
                    put("model", "moss-tts")
                    put("input", text)
                    put("voice_id", MOSS_VOICE_ID)
                    put("response_format", "mp3")
                    put("delivery_method", "url")
                }

                val apiConn = URL(MOSS_API_URL).openConnection() as HttpURLConnection
                apiConn.requestMethod = "POST"
                apiConn.setRequestProperty("Content-Type", "application/json")
                apiConn.setRequestProperty("Authorization", "Bearer $MOSS_API_KEY")
                apiConn.doOutput = true
                apiConn.connectTimeout = 10000
                apiConn.readTimeout = 10000
                apiConn.outputStream.use { it.write(body.toString().toByteArray()) }

                if (apiConn.responseCode != 200) {
                    Log.w(TAG, "MOSS TTS failed: ${apiConn.responseCode}")
                    apiConn.disconnect()
                    return@Thread
                }

                val resp = JSONObject(apiConn.inputStream.bufferedReader().readText())
                apiConn.disconnect()
                val mp3Url = resp.optString("url", "")

                if (mp3Url.isEmpty()) {
                    Log.w(TAG, "MOSS TTS returned empty URL")
                    return@Thread
                }

                Log.d(TAG, "MOSS TTS URL: $mp3Url")

                val dlConn = URL(mp3Url).openConnection() as HttpURLConnection
                dlConn.connectTimeout = 15000
                dlConn.readTimeout = 15000
                val audioData = dlConn.inputStream.readBytes()
                dlConn.disconnect()

                val ctx = webView?.context ?: return@Thread
                val tmpFile = File(ctx.cacheDir, "moss_tts_${System.currentTimeMillis()}.mp3")
                FileOutputStream(tmpFile).use { it.write(audioData) }

                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build())
                mp.setDataSource(tmpFile.absolutePath)
                mp.prepareAsync()
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener {
                    it.release()
                    tmpFile.delete()
                }
                mp.setOnErrorListener { _, _, _ ->
                    mp.release()
                    tmpFile.delete()
                    false
                }

                Log.d(TAG, "MOSS TTS playing: ${text.take(30)}...")
            } catch (e: Exception) {
                Log.e(TAG, "MOSS TTS error", e)
            }
        }.start()
    }

    private fun postToTable(table: String, body: JSONObject) {
        Thread {
            try {
                val url = URL("$supabaseUrl/rest/v1/$table")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("apikey", supabaseKey)
                conn.setRequestProperty("Authorization", "Bearer $supabaseKey")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                if (code != 201) {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                    Log.w(TAG, "POST $table failed: $code $err")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "POST $table error", e)
            }
        }.start()
    }

    fun stopPolling() {
        pollTimer?.cancel()
        pollTimer = null
    }
}