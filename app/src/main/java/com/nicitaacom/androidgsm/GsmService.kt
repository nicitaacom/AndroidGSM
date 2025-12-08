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
    // TODO: uncomment after creating GsmDialer and WebRtcManager
    // private var gsmDialer: GsmDialer? = null
    // private var webRtcManager: WebRtcManager? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gsm_gateway_channel"
    }

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmService::WakeLock")
        wakeLock.acquire()

        wsClient = WebSocketClient(this)
        // gsmDialer = GsmDialer(this)
        // webRtcManager = WebRtcManager(this)

        wsClient?.connect(BuildConfig.WS_URL)
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
        // gsmDialer?.cleanup()
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
        // TODO: uncomment after creating GsmDialer
        // when (type) {
        //     "CALL_START" -> {
        //         val number = data["number"] as? String ?: return
        //         gsmDialer?.startCall(number)
        //     }
        //     "CALL_END" -> gsmDialer?.endCall()
        //     "SEND_DTMF" -> {
        //         val digit = data["digit"] as? String ?: return
        //         gsmDialer?.sendDtmf(digit[0])
        //     }
        // }
    }
}