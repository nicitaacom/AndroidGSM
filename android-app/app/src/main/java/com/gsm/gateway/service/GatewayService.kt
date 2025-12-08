package com.gsm.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Log
import com.pusher.client.Pusher
import com.pusher.client.channel.PrivateChannelEventListener
import com.pusher.client.PusherOptions

class GatewayService : Service() {
    private lateinit var telecomManager: TelecomManager
    private var currentCall: Call? = null
    private lateinit var pusher: Pusher
    private lateinit var webSocketManager: WebSocketManager

    override fun onCreate() {
        super.onCreate()
        telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
        createNotificationChannel()
        setupPusher()
        webSocketManager = WebSocketManager("wss://your-backend-url.com/gateway")
        webSocketManager.connect() // Start WebSocket connection
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GSM Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    private fun setupPusher() {
private lateinit var pusher: Pusher
private lateinit var webSocketManager: WebSocketManager
private lateinit var pusherConfig: PusherConfig

override fun onCreate() {
    super.onCreate()
    telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
    createNotificationChannel()
    pusherConfig = PusherConfig()
    pusher = pusherConfig.getPusher()
ul        pusher.connect()

    val channel = pusher.subscribe("private-channel-name")
    channel.bind("event-name") { event ->
        handlePusherEvent(event.data)
    }
}
        val channel = pusher.subscribe("private-channel-name")
        channel.bind("event-name") { event ->
            handlePusherEvent(event.data)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun createNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Gateway")
            .setContentText("Service is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "GSMGatewayServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    private fun endCall() {
        currentCall?.disconnect()
        Log.d("GSM", "Ending call")
    }

    private fun handlePusherEvent(data: String) {
        when {
            data.startsWith("CALL_START") -> {
                val phoneNumber = data.split(" ").last()
                startCall(phoneNumber)
            }
            data.startsWith("CALL_END") -> {
                endCall()
            }
            data.startsWith("SEND_DTMF") -> {
                val dtmf = data.split(" ").last()
                sendDtmf(dtmf)
            }
        }
    }

    private fun sendDtmf(dtmf: String) {
        Log.d("GSM", "Sending DTMF: $dtmf")
    }
    private fun startCall(phoneNumber: String) {
        val uri = Uri.parse("tel:$phoneNumber")
        telecomManager.placeCall(uri, null)
        Log.d("GSM", "Starting call to: $phoneNumber")
    }
}

dependencies {
    implementation "org.webrtc:google-webrtc:1.0.32006"
    implementation "com.squareup.okhttp3:okhttp:4.10.0"

}
