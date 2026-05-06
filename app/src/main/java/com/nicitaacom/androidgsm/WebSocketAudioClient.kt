package com.nicitaacom.androidgsm

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketAudioClient(
    private val wsUrl: String,
    private val bearerToken: String,
    private val deviceToken: String,
    private val onAudioPacket: (JSONObject) -> Unit,
    private val onCommand: ((type: String, data: JSONObject) -> Unit)? = null
) : WebSocketListener() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var isConnected = false

    companion object {
        private const val TAG = "WebSocketAudio"
    }

    init {
        connect()
    }

    private fun connect() {
        try {
            val urlWithToken = "$wsUrl?token=${java.net.URLEncoder.encode(bearerToken, "UTF-8")}"
            val request = Request.Builder()
                .url(urlWithToken)
                .build()

            ws = httpClient.newWebSocket(request, this)
            MainActivity.log("🔌 WebSocket: Connecting to $wsUrl")
            Log.d(TAG, "Connecting to: $urlWithToken")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection error: ${e.message}")
            MainActivity.log("ERROR: WebSocket connection failed: ${e.message}")
        }
    }

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        isConnected = true
        MainActivity.log("✅ WebSocket: Connected")
        Log.d(TAG, "Connected: ${response.code} ${response.message}")

        try {
            // Register android peer immediately so server can relay browser -> android audio over WS.
            val registerPacket = JSONObject().apply {
                put("role", "android")
                put("deviceToken", deviceToken)
                put("dir", "toBrowser")
            }
            webSocket.send(registerPacket.toString())
        } catch (e: Exception) {
            Log.e(TAG, "❌ Register packet send error: ${e.message}")
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val packet = JSONObject(text)
            // Route command messages to command handler; everything else is audio
            if (packet.optString("type") == "command") {
                val cmdType = packet.optString("cmdType", "")
                val data = packet.optJSONObject("data") ?: JSONObject()
                Log.d(TAG, "📨 WS command: $cmdType")
                onCommand?.invoke(cmdType, data)
            } else {
                onAudioPacket(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Parse error: ${e.message}")
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        isConnected = false
        Log.e(TAG, "❌ WebSocket failure: ${t.message}")
        MainActivity.log("ERROR: WebSocket failed: ${t.message}")
        
        // Attempt reconnect after delay
        Thread.sleep(5000)
        connect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        isConnected = false
        Log.d(TAG, "⚠️ Closed: code=$code, reason=$reason")
        MainActivity.log("WebSocket closed: $reason")
    }

    fun sendEvent(type: String, data: Map<String, Any> = emptyMap()) {
        if (!isConnected) return
        try {
            val payload = JSONObject().apply {
                put("type", "event")
                put("eventType", type)
                put("deviceToken", deviceToken)
                if (data.isNotEmpty()) put("data", JSONObject(data))
            }
            ws?.send(payload.toString())
        } catch (e: Exception) {
            Log.e(TAG, "❌ sendEvent error: ${e.message}")
        }
    }

    fun sendAudioChunk(audio: String, seq: Long, sampleRate: Int, codec: String) {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, skipping audio chunk")
            return
        }

        try {
            val packet = JSONObject().apply {
                put("role", "android")
                put("deviceToken", deviceToken)
                put("dir", "toBrowser")
                put("codec", codec)
                put("seq", seq)
                put("ts", System.currentTimeMillis())
                put("sampleRate", sampleRate)
                put("audio", audio)
            }

            ws?.send(packet.toString())
            Log.d(TAG, "📤 Sent chunk seq=$seq, size=${audio.length}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Send error: ${e.message}")
        }
    }

    fun close() {
        isConnected = false
        ws?.close(1000, "Client close")
        ws = null
        httpClient.dispatcher.executorService.shutdown()
        Log.d(TAG, "Closed")
    }
}
