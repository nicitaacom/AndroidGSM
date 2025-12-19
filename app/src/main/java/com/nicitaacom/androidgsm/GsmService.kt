package com.nicitaacom.androidgsm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class GsmService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock
    private var wsClient: WebSocketClient? = null
    private var gsmDialer: GsmDialer? = null
    private var webRtcManager: WebRtcManager? = null
    private lateinit var config: Config

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gsm_gateway_channel"
    }

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmService::WakeLock")
        wakeLock.acquire()

        config = ConfigReader.readConfig(this)
        gsmDialer = GsmDialer(this)
        // webRtcManager = WebRtcManager(this)
        // webRtcManager?.initializePeerConnection(config.ICE_SERVERS.map { PeerConnection.IceServer.builder(it["urls"]).createIceServer() }) // Commented out due to WebRTC dependency issue

        wsClient = WebSocketClient(this)
        wsClient?.connect(config.WS_URL, config.BACKEND_AUTH_KEY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
        wsClient?.disconnect()
        gsmDialer = null
        // webRtcManager?.cleanup()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GSM Gateway Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Gateway Active")
            .setContentText("Waiting for calls...")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .build()
    }

    fun handleCommand(type: String, data: Map<String, Any>) {
        when (type) {
            "CALL_START" -> {
                val number = data["number"] as? String ?: return
                gsmDialer?.startCall(number)
                // Bridge audio: Send GSM audio to WebRTC track
            }
            "CALL_END" -> gsmDialer?.endCall()
            "SEND_DTMF" -> {
                val digit = data["digit"] as? String ?: return
                gsmDialer?.sendDtmf(digit[0])
            }
            // Add WebRTC signaling handlers
        }
    }
}
