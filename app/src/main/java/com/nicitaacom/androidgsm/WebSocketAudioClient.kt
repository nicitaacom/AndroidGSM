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

    // No read timeout + its own ping: OkHttp fails a web socket that got no frame for readTimeout, and during a call
    // nothing comes down this socket (the website sends no mic) - with 30 s the socket dropped 30 s into every ring,
    // the server took the drop for the call's end and the website showed "Ready" with the call still on. The ping
    // also finds a link that went away without a word within PING_INTERVAL_SEC.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var isConnected = false
        private set
    // close() was called - no reconnect, no held chunks
    @Volatile private var isClosed = false
    private var reconnectAttempt = 0

    // The chunks captured while the socket was down (a Wi-Fi drop on the phone): the newest HELD_CHUNKS_MAX of them
    // (20 ms each = 2 s), sent in one go the moment it is open again - the website plays what the operator missed at
    // double speed and is back on live sound (outreach-tool useInitGSM drainAndSchedule). The lock keeps the order:
    // a chunk captured during the send waits for it and goes out after the held ones.
    private val heldChunksLock = Any()
    private val heldChunks = ArrayDeque<String>()

    // DialQualityLog's phone internet half: the most bytes OkHttp held back for the VPS at once (a slow upload grows
    // it, a fast one keeps it near 0) and how often this socket failed and reconnected
    @Volatile var maxQueuedBytes = 0L
        private set
    @Volatile var failureCount = 0
        private set

    companion object {
        private const val TAG = "WebSocketAudio"
        private const val PING_INTERVAL_SEC = 15L
        private const val HELD_CHUNKS_MAX = 100
        // 0.5 s, 1 s, 2 s, 4 s, then every 5 s - a short drop is back while the held 2 s still cover it
        private const val RECONNECT_FIRST_MS = 500L
        private const val RECONNECT_MAX_MS = 5000L
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
        reconnectAttempt = 0
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

        val heldCount = synchronized(heldChunksLock) {
            val count = heldChunks.size
            for (chunk in heldChunks) webSocket.send(chunk)
            heldChunks.clear()
            isConnected = true
            count
        }
        MainActivity.log(if (heldCount > 0) "✅ WebSocket: Connected - $heldCount held chunks sent" else "✅ WebSocket: Connected")
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
        // OkHttp gives each socket one last callback (onFailure or onClosed) - one reconnect per socket
        if (isClosed) return
        isConnected = false
        failureCount++
        Log.e(TAG, "❌ WebSocket failure: ${t.message}")
        MainActivity.log("ERROR: WebSocket failed: ${t.message}")
        scheduleReconnect()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        if (isClosed) return
        isConnected = false
        Log.d(TAG, "⚠️ Closed: code=$code, reason=$reason")
        MainActivity.log("WebSocket closed: $reason")
        // the server closed it (a restart) - the call is still on, so is this socket's job
        scheduleReconnect()
    }

    // On its own thread - a sleep inside OkHttp's callback held that thread for the whole wait
    private fun scheduleReconnect() {
        val delayMs = (RECONNECT_FIRST_MS shl reconnectAttempt.coerceAtMost(4)).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt++
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            if (!isClosed) connect()
        }.apply { isDaemon = true }.start()
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
            }.toString()

            val isSent = synchronized(heldChunksLock) {
                if (isClosed) return
                if (isConnected) {
                    ws?.send(packet)
                    true
                } else {
                    heldChunks.addLast(packet)
                    if (heldChunks.size > HELD_CHUNKS_MAX) heldChunks.removeFirst()
                    false
                }
            }
            if (!isSent) return
            val queuedBytes = ws?.queueSize() ?: 0L
            if (queuedBytes > maxQueuedBytes) maxQueuedBytes = queuedBytes
            // every 500th chunk - one line per chunk made this uid logd's chattiest and its lines the first pruned
            if (seq % 500L == 0L) Log.d(TAG, "📤 Sent chunk seq=$seq, size=${audio.length}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Send error: ${e.message}")
        }
    }

    fun close() {
        isClosed = true
        isConnected = false
        synchronized(heldChunksLock) { heldChunks.clear() }
        ws?.close(1000, "Client close")
        ws = null
        httpClient.dispatcher.executorService.shutdown()
        Log.d(TAG, "Closed")
    }
}
