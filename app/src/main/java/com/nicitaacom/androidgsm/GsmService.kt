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
    private var audioStreamManager: AudioStreamManager? = null
    private lateinit var config: Config

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gsm_gateway_channel"
    }

    override fun onCreate() {
        super.onCreate()
        MainActivity.log("GsmService: onCreate called")

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmService::WakeLock")
        wakeLock.acquire()
        MainActivity.log("GsmService: Wake lock acquired")

        try {
            config = ConfigReader.readConfig(this)
            MainActivity.log("GsmService: Config loaded")
            MainActivity.log("WS URL: ${config.WS_URL}")
            MainActivity.log("Device Token: ${config.DEVICE_TOKEN}")

            gsmDialer = GsmDialer(this)
            MainActivity.log("GsmService: GsmDialer initialized")

            // Initialize Pusher-based audio streaming
            audioStreamManager = AudioStreamManager(
                this,
                config.PUSHER_APP_ID,
                config.PUSHER_KEY,
                config.PUSHER_SECRET,
                config.PUSHER_CLUSTER
            )
            audioStreamManager?.initialize(config.DEVICE_TOKEN)
            MainActivity.log("GsmService: AudioStreamManager initialized")

            // Initialize WebSocket for commands
            wsClient = WebSocketClient(this)
            wsClient?.connect(config.WS_URL, config.BACKEND_AUTH_KEY)
            MainActivity.log("GsmService: WebSocket connecting...")
        } catch (e: Exception) {
            MainActivity.log("ERROR in GsmService.onCreate: ${e.message}")
        }
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
        audioStreamManager?.cleanup()
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
        MainActivity.log("Command received: $type")
        when (type) {
            "CALL_START" -> {
                val number = data["number"] as? String ?: return
                MainActivity.log("Starting call to: $number")
                gsmDialer?.startCall(number)
                audioStreamManager?.startAudioCapture()
                wsClient?.sendStatus("CALL_STARTED", mapOf("number" to number))
            }
            "CALL_END" -> {
                MainActivity.log("Ending call")
                gsmDialer?.endCall()
                audioStreamManager?.stopAudioCapture()
                wsClient?.sendStatus("CALL_ENDED")
            }
            "SEND_DTMF" -> {
                val digit = data["digit"] as? String ?: return
                MainActivity.log("Sending DTMF: $digit")
                gsmDialer?.sendDtmf(digit[0])
                wsClient?.sendStatus("DTMF_SENT", mapOf("digit" to digit))
            }
        }
    }
}
