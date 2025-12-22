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
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun connect() {
        try {
            val options = PusherOptions().apply {
                setCluster(config.PUSHER_CLUSTER)
                // 1. custom authorizer for private channels
                authorizer = com.pusher.client.util.HttpAuthorizer("${config.BACKEND_URL}/pusher/auth").apply {
                    setHeaders(mapOf("Authorization" to "Bearer ${config.BACKEND_BEARER}"))
                }
            }

            pusher = Pusher(config.PUSHER_KEY, options)

            pusher?.connect(object : ConnectionEventListener {
                override fun onConnectionStateChange(change: ConnectionStateChange) {
                    try {
                        MainActivity.log("Pusher: ${change.previousState} → ${change.currentState}")
                        if (change.currentState == ConnectionState.CONNECTED) {
                            subscribeToChannels()
                            sendEvent("CONNECTED") // 2. tell backend we're online
                        }
                    } catch (error: Exception) {
                        MainActivity.log("ERROR in onConnectionStateChange: ${error.message}")
                        error.printStackTrace()
                    }
                }

                override fun onError(message: String, code: String?, error: Exception?) {
                    MainActivity.log("Pusher error: $message $code")
                    error?.printStackTrace()
                }
            }, ConnectionState.ALL)
        } catch (error: Exception) {
            MainActivity.log("ERROR in Pusher.connect(): ${error.message}")
            error.printStackTrace()
        }
    }

    private fun authPrivateChannel(channelName: String, socketId: String): String {
        return try {
            val url = "${config.BACKEND_URL}/pusher/auth"
            val json = JSONObject().apply {
                put("socket_id", socketId)
                put("channel_name", channelName)
            }

            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${config.BACKEND_BEARER}")
                .build()

            val response = httpClient.newCall(request).execute()
            response.body?.string() ?: ""
        } catch (error: Exception) {
            MainActivity.log("Pusher auth failed: ${error.message}")
            error.printStackTrace()
            ""
        }
    }

    private fun subscribeToChannels() {
        try {
            val channelName = "private-device-${config.DEVICE_TOKEN}"
            MainActivity.log("Pusher: subscribing to $channelName")

            val channel = pusher?.subscribePrivate(
                channelName,
                object : PrivateChannelEventListener {
                    override fun onAuthenticationFailure(message: String, error: Exception?) {
                        MainActivity.log("Private channel auth failed: $message")
                        error?.printStackTrace()
                    }

                    override fun onSubscriptionSucceeded(channelName: String) {
                        MainActivity.log("Pusher subscribed to $channelName")
                    }

                    override fun onEvent(event: com.pusher.client.channel.PusherEvent) {
                        try {
                            MainActivity.log("Pusher event: ${event.eventName} -> ${event.data}")
                            if (event.eventName == "command") handleCommand(event.data)
                        } catch (error: Exception) {
                            MainActivity.log("ERROR in onEvent: ${error.message}")
                            error.printStackTrace()
                        }
                    }
                },
                "command" // Bind to command event during subscription
            )
        } catch (error: Exception) {
            MainActivity.log("ERROR in subscribeToChannels: ${error.message}")
            error.printStackTrace()
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
                else -> MainActivity.log("Unknown command type: $type")
            }
        } catch (error: Exception) {
            MainActivity.log("Command parse error: ${error.message}")
            error.printStackTrace()
        }
    }

    // 4. unified send event via HTTP (not Pusher trigger)
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
                    MainActivity.log("Event send failed: ${response.code}")
                }
            } catch (error: Exception) {
                MainActivity.log("Send event error: ${error.message}")
                error.printStackTrace()
            }
        }
    }

    fun disconnect() {
        try {
            pusher?.disconnect()
            scope.cancel()
        } catch (error: Exception) {
            MainActivity.log("ERROR in disconnect: ${error.message}")
            error.printStackTrace()
        }
    }
}