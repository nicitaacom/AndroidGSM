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
                MainActivity.log("Pusher: ${change.previousState} → ${change.currentState}")
                if (change.currentState == ConnectionState.CONNECTED) {
                    subscribeToChannels()
                    sendEvent("CONNECTED") // 2. tell backend we're online
                }
            }

            override fun onError(message: String, code: String?, e: Exception?) {
                MainActivity.log("Pusher error: $message $code")
            }
        }, ConnectionState.ALL)
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
        } catch (e: Exception) {
            MainActivity.log("Pusher auth failed: ${e.message}")
            ""
        }
    }

    private fun subscribeToChannels() {
        val channelName = "private-device-${config.DEVICE_TOKEN}"
        MainActivity.log("Pusher: subscribing to $channelName")
        val channel = pusher?.subscribePrivate(channelName, object : PrivateChannelEventListener {
            override fun onAuthenticationFailure(message: String, e: Exception?) {
                MainActivity.log("Private channel auth failed: $message")
            }

            override fun onSubscriptionSucceeded(channelName: String) {
                MainActivity.log("Pusher subscribed to $channelName")
            }

            override fun onEvent(event: com.pusher.client.channel.PusherEvent) {
                MainActivity.log("Pusher event: ${event.eventName} -> ${event.data}")
                if (event.eventName == "command") handleCommand(event.data)
            }
        })

        channel?.bind("command") { event ->
            MainActivity.log("Bound command event -> ${event}")
            handleCommand(event.data)
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
                "MAKE_CALL", "CALL_START" -> {
                    val number = dataMap["number"]?.toString() ?: dataObj.optString("number")
                    if (!number.isNullOrEmpty()) {
                        MainActivity.log("COMMAND -> MAKE_CALL $number")
                        service.handleCommand("MAKE_CALL", mapOf("number" to number))
                    } else MainActivity.log("MAKE_CALL missing number")
                }
                "SEND_DTMF" -> {
                    service.handleCommand("SEND_DTMF", dataMap)
                }
                else -> MainActivity.log("Unknown command type: $type")
            }
        } catch (e: Exception) {
            MainActivity.log("Command parse error: ${e.message}")
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
            } catch (e: Exception) {
                MainActivity.log("Send event error: ${e.message}")
            }
        }
    }

    fun disconnect() {
        pusher?.disconnect()
        scope.cancel()
    }
}