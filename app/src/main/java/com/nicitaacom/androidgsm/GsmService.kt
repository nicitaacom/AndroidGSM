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

    private var wakeLock: PowerManager.WakeLock? = null
    private var pusherClient: PusherClient? = null
    private var gsmDialer: GsmDialer? = null
    private var audioStreamHandler: AudioStreamHandler? = null
    private var config: Config? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gsm_gateway_channel"
    }

    override fun onCreate() {
        super.onCreate()
        MainActivity.log("GsmService: onCreate called")

        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmService::WakeLock")
            wakeLock?.acquire()
            MainActivity.log("GsmService: Wake lock acquired")
        } catch (e: Exception) {
            MainActivity.log("WARNING: Could not acquire wake lock: ${e.message}")
        }

        try {
            config = ConfigReader.readConfig(this)
            MainActivity.log("GsmService: Config loaded")
            MainActivity.log("Backend URL: ${config?.BACKEND_URL}")
            MainActivity.log("Device Token: ${config?.DEVICE_TOKEN}")

            gsmDialer = GsmDialer(this)
            MainActivity.log("GsmService: GsmDialer initialized")

            try {
                // Initialize Pusher client for bi-directional communication
                pusherClient = PusherClient(this, config!!)
                pusherClient?.connect()
                MainActivity.log("GsmService: Pusher connecting...")

                // Initialize audio handler for call audio
                audioStreamHandler = AudioStreamHandler(this, pusherClient!!)
                MainActivity.log("GsmService: AudioStreamHandler initialized")
            } catch (e: Exception) {
                MainActivity.log("WARNING: Pusher/Audio failed: ${e.message}")
            }
        } catch (e: Exception) {
            MainActivity.log("ERROR in GsmService.onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            MainActivity.log("GsmService: onStartCommand called")
            // Android 8.0+ requires foreground service notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, createNotification())
                MainActivity.log("GsmService: Foreground notification created")
            } else {
                // Android 5.x doesn't require foreground notification
                MainActivity.log("GsmService: Running as background service (Android < 8)")
            }
        } catch (e: Exception) {
            MainActivity.log("ERROR in onStartCommand: ${e.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    MainActivity.log("Wake lock released")
                }
            }
        } catch (e: Exception) {
            MainActivity.log("Error releasing wake lock: ${e.message}")
        }
        pusherClient?.disconnect()
        audioStreamHandler?.cleanup()
        gsmDialer = null
        MainActivity.log("GsmService: Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "GSM Gateway Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "GSM Gateway background service"
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
                MainActivity.log("Notification channel created")
            }

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GSM Gateway Active")
                .setContentText("Waiting for calls...")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)

            return builder.build()
        } catch (e: Exception) {
            MainActivity.log("ERROR creating notification: ${e.message}")
            throw e
        }
    }

    fun handleCommand(type: String, data: Map<String, Any>) {
        MainActivity.log("Command received: $type")
        when (type) {
            "CALL_START" -> {
                val number = data["number"] as? String ?: return
                MainActivity.log("Starting call to: $number")
                gsmDialer?.startCall(number)
                // Start audio capture for call
                audioStreamHandler?.startAudioCapture()
                pusherClient?.sendEvent("CALL_STARTED", mapOf("number" to number))
            }
            "CALL_END" -> {
                MainActivity.log("Ending call")
                gsmDialer?.endCall()
                // Stop audio
                audioStreamHandler?.stopAudioCapture()
                audioStreamHandler?.stopAudioPlayback()
                pusherClient?.sendEvent("CALL_ENDED", emptyMap())
            }
            "SEND_DTMF" -> {
                val digit = data["digit"] as? String ?: return
                MainActivity.log("Sending DTMF: $digit")
                gsmDialer?.sendDtmf(digit[0])
                pusherClient?.sendEvent("DTMF_SENT", mapOf("digit" to digit))
            }
            "AUDIO_CHUNK" -> {
                // Receive audio from backend and play it
                val audioData = data["audio"] as? String ?: return
                audioStreamHandler?.playAudioChunk(audioData)
            }
        }
    }
}
