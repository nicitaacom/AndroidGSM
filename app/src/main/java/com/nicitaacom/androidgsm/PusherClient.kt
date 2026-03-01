package com.nicitaacom.androidgsm

import android.util.Log
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.PrivateChannelEventListener
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PusherClient(
    private val service: GsmService,
    private val config: Config
) {
    private var pusher: Pusher? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var isSubscribed = false  // Track subscription state
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect() {
        try {
            val options = PusherOptions().apply {
                setCluster(config.PUSHER_CLUSTER)
                authorizer = com.pusher.client.util.HttpAuthorizer("${config.BACKEND_URL}/pusher/auth").apply {
                    setHeaders(mapOf("Authorization" to "Bearer ${config.BACKEND_BEARER}"))
                }
            }

            pusher = Pusher(config.PUSHER_KEY, options)

            pusher?.connect(object : ConnectionEventListener {
                override fun onConnectionStateChange(change: ConnectionStateChange) {
                    try {
                        MainActivity.log("ℹ️ Pusher: ${change.previousState} → ${change.currentState}")
                        if (change.currentState == ConnectionState.CONNECTED) {
                            subscribeToChannels()
                            sendEvent("CONNECTED")
                            startHeartbeat()
                        } else if (change.currentState == ConnectionState.DISCONNECTED) {
                            val channelName = "private-device-${config.DEVICE_TOKEN}"
                            pusher?.unsubscribe(channelName)
                            MainActivity.log("ℹ️ Unsubscribed from $channelName on disconnect")
                            isSubscribed = false
                            stopHeartbeat()
                        }
                    } catch (error: Exception) {
                        MainActivity.log("❌ ERROR in onConnectionStateChange: ${error.message}")
                        error.printStackTrace()
                    }
                }

                override fun onError(message: String, code: String?, error: Exception?) {
                    if (message.contains("Existing subscription", ignoreCase = true)) {
                        MainActivity.log("⚠️ Pusher duplicate subscription ignored: $message")
                    } else {
                        MainActivity.log("❌ Pusher error: $message $code")
                    }
                    error?.printStackTrace()
                }
            }, ConnectionState.ALL)
        } catch (error: Exception) {
            MainActivity.log("❌ ERROR in Pusher.connect(): ${error.message}")
            error.printStackTrace()
        }
    }

    private fun startHeartbeat() {
        MainActivity.log("ℹ️ Heartbeat started (CONNECTED keepalive)")
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(15000)
                sendEvent("CONNECTED", mapOf("heartbeat" to true))
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun disconnect() {
        stopHeartbeat()
        val channelName = "private-device-${config.DEVICE_TOKEN}"
        pusher?.unsubscribe(channelName)
        MainActivity.log("ℹ️ Unsubscribed from $channelName on manual disconnect")
        isSubscribed = false
        pusher?.disconnect()
        scope.cancel()
    }

    private fun subscribeToChannels() {
        try {
            val channelName = "private-device-${config.DEVICE_TOKEN}"
            MainActivity.log("ℹ️ Pusher: subscribing to $channelName")
            // Ensure clean re-subscribe on reconnect to avoid stale subscription state.
            pusher?.unsubscribe(channelName)
            isSubscribed = false

            val channel = pusher?.subscribePrivate(
                channelName,
                object : PrivateChannelEventListener {
                    override fun onAuthenticationFailure(message: String, error: Exception?) {
                        MainActivity.log("❌ Private channel auth failed: $message")
                        error?.printStackTrace()
                        isSubscribed = false
                    }

                    override fun onSubscriptionSucceeded(channelName: String) {
                        MainActivity.log("✅ Pusher subscribed to $channelName")
                        isSubscribed = true
                    }

                    override fun onEvent(event: com.pusher.client.channel.PusherEvent) {
                        try {
                            MainActivity.log("Pusher event: ${event.eventName} -> ${event.data}")
                            if (event.eventName == "command") handleCommand(event.data)
                        } catch (error: Exception) {
                            MainActivity.log("❌ ERROR in onEvent: ${error.message}")
                            error.printStackTrace()
                        }
                    }
                },
                "command"
            )
        } catch (error: Exception) {
            MainActivity.log("❌ ERROR in subscribeToChannels: ${error.message}")
            error.printStackTrace()
            isSubscribed = false
        }
    }

    private fun handleCommand(jsonData: String) {
        try {
            MainActivity.log("Command received raw: $jsonData")
            val json = JSONObject(jsonData)
            val type = json.optString("type")
            val dataObj = if (json.has("data")) json.getJSONObject("data") else JSONObject()
            val dataMap = mutableMapOf<String, Any>()
            dataObj.keys().forEach { key -> dataMap[key] = dataObj.get(key) }

            when (type) {
                "CALL_STARTED" -> {
                    val number = dataMap["number"]?.toString() ?: dataObj.optString("number")
                    if (!number.isNullOrEmpty()) {
                        MainActivity.log("COMMAND -> CALL_STARTED $number")
                        service.handleCommand("CALL_STARTED", mapOf("number" to number))
                    } else MainActivity.log("CALL_STARTED missing number")
                }
                "CALL_ENDED" -> {
                    MainActivity.log("COMMAND -> CALL_ENDED")
                    service.handleCommand("CALL_ENDED", emptyMap())
                }
                "SEND_DTMF" -> service.handleCommand("SEND_DTMF", dataMap)
                "AUDIO_CHUNK" -> service.handleCommand("AUDIO_CHUNK", dataMap)
                else -> MainActivity.log("Unknown command type: $type")
            }
        } catch (error: Exception) {
            MainActivity.log("❌ Command parse error: ${error.message}")
            error.printStackTrace()
        }
    }

    fun sendEvent(type: String, data: Map<String, Any> = emptyMap()) {
        scope.launch {
            try {
                val url = "${config.BACKEND_URL}/api/events"
                val payload = JSONObject().apply {
                    put("deviceToken", config.DEVICE_TOKEN)
                    put("type", type)
                    if (data.isNotEmpty()) put("data", JSONObject(data))
                }

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", "Bearer ${config.BACKEND_BEARER}")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    MainActivity.log("❌ Event send failed: ${response.code}")
                }
            } catch (error: Exception) {
                MainActivity.log("❌ Send event error: ${error.message}")
                error.printStackTrace()
            }
        }
    }
}
