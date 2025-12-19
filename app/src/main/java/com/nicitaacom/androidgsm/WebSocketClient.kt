package com.nicitaacom.androidgsm

import android.util.Log
import okhttp3.*
import org.json.JSONObject

// WebSocket client - connects to backend and handles commands
class WebSocketClient(private val service: GsmService) {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    companion object {
        private const val TAG = "WebSocketClient"
    }

    fun connect(url: String, authKey: String) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", authKey)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                MainActivity.log("✅ WebSocket connected to backend")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                MainActivity.log("📨 Message received: $text")
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $reason")
                MainActivity.log("❌ WebSocket closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Error: ${t.message}")
                MainActivity.log("⚠️ WebSocket error: ${t.message}")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Shutdown")
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
}
