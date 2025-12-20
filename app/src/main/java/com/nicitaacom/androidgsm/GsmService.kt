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

        // acquire wake lock if possible
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmService::WakeLock")
            wakeLock?.acquire()
            MainActivity.log("GsmService: Wake lock acquired")
        } catch (error: Exception) {
            MainActivity.log("WARNING: Could not acquire wake lock: ${error.message}")
        }

        // load config
        try {
            config = ConfigReader.readConfig(this)
            if (config == null) {
                MainActivity.log("ERROR: ConfigReader returned null - service will run in degraded mode")
                return
            }

            MainActivity.log("GsmService: Config loaded")
            MainActivity.log("Backend URL: ${config?.BACKEND_URL}")
            MainActivity.log("Device Token: ${config?.DEVICE_TOKEN}")

            // init dialer
            gsmDialer = GsmDialer(this)
            MainActivity.log("GsmService: GsmDialer initialized")
        } catch (error: Exception) {
            MainActivity.log("ERROR in GsmService.onCreate (config/dialer): ${error.message}")
            return
        }

        // init pusher + audio if pusher config present
        try {
            val hasPusherCreds = !config?.PUSHER_KEY.isNullOrBlank() &&
                    !config?.PUSHER_CLUSTER.isNullOrBlank() &&
                    !config?.BACKEND_BEARER.isNullOrBlank() &&
                    !config?.BACKEND_URL.isNullOrBlank()

            if (!hasPusherCreds) {
                MainActivity.log("WARNING: Missing Pusher/Backend config - realtime features disabled")
                return
            }

            // create pusher client and connect
            pusherClient = PusherClient(this, config!!)
            pusherClient?.connect()
            MainActivity.log("GsmService: Pusher connecting...")

            // audio handler depends on pusher for events
            audioStreamHandler = AudioStreamHandler(this, pusherClient!!)
            MainActivity.log("GsmService: AudioStreamHandler initialized")
        } catch (error: Exception) {
            MainActivity.log("WARNING: Pusher/Audio failed: ${error.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            MainActivity.log("GsmService: onStartCommand called")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundSafely()
                MainActivity.log("GsmService: Foreground notification created")
            } else {
                MainActivity.log("GsmService: Running as background service (Android < 8)")
            }
        } catch (error: Exception) {
            MainActivity.log("ERROR in onStartCommand: ${error.message}")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    MainActivity.log("Wake lock released")
                }
            }
        } catch (error: Exception) {
            MainActivity.log("Error releasing wake lock: ${error.message}")
        }

        // cleanup clients
        pusherClient?.disconnect()
        audioStreamHandler?.cleanup()
        gsmDialer = null
        MainActivity.log("GsmService: Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // build and show notification (separate tiny helper)
    private fun startForegroundSafely() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

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
        } catch (error: Exception) {
            MainActivity.log("ERROR creating notification: ${error.message}")
            throw error
        }
    }

    // central command dispatcher - single entrypoint
    fun handleCommand(type: String, data: Map<String, Any>) {
        MainActivity.log("Command received: $type")

        // handle both server and legacy names
        val normalizedType = when (type) {
            "MAKE_CALL", "CALL_START" -> "CALL_STARTED"
            else -> type
        }

        when (normalizedType) {
            "CALL_STARTED" -> {
                val number = data["number"] as? String ?: run {
                    MainActivity.log("CALL_STARTED ignored: missing number")
                    return
                }
                MainActivity.log("Starting call to: $number")
                gsmDialer?.startCall(number)
                audioStreamHandler?.startAudioCapture()
                pusherClient?.sendEvent("CALL_STARTED", mapOf("number" to number))
            }

            // only 1 type to avoid confusion
            "CALL_ENDED" -> {
                MainActivity.log("Ending call")
                gsmDialer?.endCall()
                audioStreamHandler?.stopAudioCapture()
                audioStreamHandler?.stopAudioPlayback()
                pusherClient?.sendEvent("CALL_ENDED", emptyMap())
            }

            "SEND_DTMF" -> {
                val digit = data["digit"] as? String ?: run {
                    MainActivity.log("SEND_DTMF ignored: missing digit")
                    return
                }
                MainActivity.log("Sending DTMF: $digit")
                gsmDialer?.sendDtmf(digit[0])
                pusherClient?.sendEvent("DTMF_SENT", mapOf("digit" to digit))
            }

            "AUDIO_CHUNK" -> {
                val audioData = data["audio"] as? String ?: run {
                    MainActivity.log("AUDIO_CHUNK ignored: missing audio")
                    return
                }
                audioStreamHandler?.playAudioChunk(audioData)
            }

            else -> MainActivity.log("Unhandled command: $type")
        }
    }
}
