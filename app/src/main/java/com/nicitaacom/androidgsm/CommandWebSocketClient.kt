package com.nicitaacom.androidgsm

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent WebSocket connection used for command delivery and heartbeats.
 * Replaces PusherClient on Android — zero Pusher message cost.
 *
 * Server sends: { type:"command", cmdType:"CALL_STARTED"|"CALL_ENDED"|..., data:{...} }
 * Android sends: { type:"event", eventType:"CONNECTED"|"CALL_CONNECTED"|..., deviceToken, data:{...} }
 *
 * Heartbeat: sendEvent("CONNECTED") every 10s — server updates lastSeen, no Pusher triggered.
 */
class CommandWebSocketClient(
    private val wsUrl: String,
    private val bearerToken: String,
    private val deviceToken: String,
    private val onCommand: (type: String, data: JSONObject) -> Unit,
    private val stateProvider: (() -> Map<String, Any>)? = null,
    private val onConnected: (() -> Unit)? = null
) : WebSocketListener() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // no read timeout — persistent connection
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // WS-level ping keeps connection alive
        .build()

    private var ws: WebSocket? = null
    @Volatile var isConnected = false
        private set
    private val isClosed = AtomicBoolean(false)
    private var heartbeatThread: Thread? = null

    companion object {
        private const val TAG = "CmdWS"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val HEARTBEAT_INTERVAL_MS = 10000L
    }

    fun connect() {
        isClosed.set(false)
        doConnect()
    }

    private fun doConnect() {
        if (isClosed.get()) return
        try {
            val url = "$wsUrl?token=${java.net.URLEncoder.encode(bearerToken, "UTF-8")}&role=android-cmd&deviceToken=${java.net.URLEncoder.encode(deviceToken, "UTF-8")}"
            val request = Request.Builder().url(url).build()
            ws = httpClient.newWebSocket(request, this)
            Log.d(TAG, "Connecting to: $wsUrl | deviceToken empty=${deviceToken.isBlank()} | token empty=${bearerToken.isBlank()}")
        } catch (e: Exception) {
            Log.e(TAG, "Connect error: ${e.message}")
            scheduleReconnect()
        }
    }

    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        isConnected = true
        Log.d(TAG, "✅ Connected")
        sendEvent("CONNECTED_EXPLICIT", stateProvider?.invoke() ?: emptyMap())
        startHeartbeat()
        onConnected?.invoke()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "command" -> {
                    val cmdType = msg.optString("cmdType", "")
                    val data = msg.optJSONObject("data") ?: JSONObject()
                    Log.d(TAG, "📨 Command: $cmdType")
                    onCommand(cmdType, data)
                }
                "pong" -> Log.d(TAG, "pong")
                else -> Log.d(TAG, "Unknown msg type: ${msg.optString("type")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        isConnected = false
        stopHeartbeat()
        Log.e(TAG, "Failure: ${t.message}")
        scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        isConnected = false
        stopHeartbeat()
        Log.d(TAG, "Closed: $code $reason")
        if (!isClosed.get()) scheduleReconnect()
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
            Log.e(TAG, "sendEvent error: ${e.message}")
        }
    }

    fun disconnect() {
        isClosed.set(true)
        stopHeartbeat()
        isConnected = false
        ws?.close(1000, "Shutdown")
        ws = null
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatThread = Thread {
            while (isConnected && !isClosed.get()) {
                try { Thread.sleep(HEARTBEAT_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (isConnected && !isClosed.get()) sendEvent("CONNECTED", mapOf("heartbeat" to true) + (stateProvider?.invoke() ?: emptyMap()))
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopHeartbeat() {
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    private fun scheduleReconnect() {
        if (isClosed.get()) return
        Thread {
            try { Thread.sleep(RECONNECT_DELAY_MS) } catch (_: InterruptedException) { return@Thread }
            if (!isClosed.get()) {
                Log.d(TAG, "Reconnecting...")
                doConnect()
            }
        }.also { it.isDaemon = true; it.start() }
    }
}
