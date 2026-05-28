package com.nicitaacom.androidgsm

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebSocketAudioClient(
    private val wsUrl: String,
    private val bearerToken: String,
    private val deviceToken: String,
    private val onAudioPacket: (JSONObject) -> Unit,
    private val onCommand: ((type: String, data: JSONObject) -> Unit)? = null
) : WebSocketListener() {

    // Shared across all instances — never shut down between calls.
    // Shutting down the dispatcher per-instance leaked a thread pool on every call cycle.
    companion object {
        private const val TAG = "WebSocketAudio"
        private const val RECONNECT_DELAY_MS = 5000L
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)  // no read timeout — streaming connection
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private var ws: WebSocket? = null
    @Volatile var isConnected = false
        private set
    private val isClosed = AtomicBoolean(false)

    init {
        connect()
    }

    private fun connect() {
        if (isClosed.get()) return
        try {
            val urlWithToken = "$wsUrl?token=${java.net.URLEncoder.encode(bearerToken, "UTF-8")}"
            val request = Request.Builder().url(urlWithToken).build()
            ws = httpClient.newWebSocket(request, this)
            MainActivity.log("🔌 WebSocket Audio: Connecting to $wsUrl")
            Log.d(TAG, "Connecting to: $wsUrl")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection error: ${e.message}")
            MainActivity.log("ERROR: WebSocket connection failed: ${e.message}")
            scheduleReconnect()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        isConnected = true
        MainActivity.log("✅ WebSocket Audio: Connected")
        Log.d(TAG, "Connected: ${response.code} ${response.message}")
        try {
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
        MainActivity.log("WebSocket audio failed: ${t.message}")
        // Schedule reconnect on a separate thread — never block the OkHttp callback thread
        scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        isConnected = false
        Log.d(TAG, "⚠️ Closed: code=$code, reason=$reason")
        MainActivity.log("WebSocket audio closed: $reason")
        if (!isClosed.get()) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (isClosed.get()) return
        Thread {
            try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: InterruptedException) { return@Thread }
            if (!isClosed.get()) {
                Log.d(TAG, "Reconnecting audio WS...")
                connect()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun sendAudioChunk(audio: String, seq: Long, sampleRate: Int, codec: String) {
        if (!isConnected) {
            Log.w(TAG, "⚠️ Not connected, skipping audio chunk seq=$seq")
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
        } catch (e: Exception) {
            Log.e(TAG, "❌ Send error: ${e.message}")
        }
    }

    fun close() {
        isClosed.set(true)
        isConnected = false
        ws?.close(1000, "Client close")
        ws = null
        Log.d(TAG, "Closed")
        // Do NOT shut down httpClient.dispatcher — it is shared across instances
    }
}
