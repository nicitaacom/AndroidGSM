package com.nicitaacom.androidgsm

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// WebSocket client - connects to backend and handles commands
class WebSocketClient(private val service: GsmService) {

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private var isManualDisconnect = false

    companion object {
        private const val TAG = "WebSocketClient"
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    fun connect(url: String) {
        isManualDisconnect = false

        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                reconnectAttempts = 0
                sendStatus("connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                if (!isManualDisconnect) {
                    scheduleReconnect(url)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                if (!isManualDisconnect) {
                    scheduleReconnect(url)
                }
            }
        })
    }

    fun disconnect() {
        isManualDisconnect = true
        webSocket?.close(1000, "Service stopped")
        client?.dispatcher?.executorService?.shutdown()
    }

    // Parse incoming JSON commands from backend
    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")

            when (type) {
                "CALL_START" -> {
                    val number = json.getString("number")
                    service.handleCommand("CALL_START", mapOf("number" to number))
                }
                "CALL_END" -> {
                    service.handleCommand("CALL_END", emptyMap())
                }
                "SEND_DTMF" -> {
                    val digit = json.getString("digit")
                    service.handleCommand("SEND_DTMF", mapOf("digit" to digit))
                }
                else -> Log.w(TAG, "Unknown command: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}")
        }
    }

    // Send status updates to backend
    fun sendStatus(status: String, data: Map<String, Any> = emptyMap()) {
        try {
            val json = JSONObject()
            json.put("type", "CALL_STATUS")
            json.put("status", status)
            data.forEach { (key, value) -> json.put(key, value) }

            val message = json.toString()
            Log.d(TAG, "Sending: $message")
            webSocket?.send(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status: ${e.message}")
        }
    }

    // Exponential backoff reconnect
    private fun scheduleReconnect(url: String) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached")
            return
        }

        val delay = (1 shl reconnectAttempts) * 1000L // 1s, 2s, 4s, 8s, 16s...
        reconnectAttempts++

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Reconnecting... attempt $reconnectAttempts")
            connect(url)
        }, delay)
    }
}