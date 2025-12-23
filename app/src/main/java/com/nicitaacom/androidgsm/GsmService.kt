package com.nicitaacom.androidgsm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
        private const val CALL_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "gsm_gateway_channel"
    }

    override fun onCreate() {
        super.onCreate()
        MainActivity.log("GsmService: onCreate called")

        // 1. Create notification channel FIRST
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GSM Gateway Service",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "GSM Gateway background service"
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            MainActivity.log("Notification channel created")
        }

        // 2. acquire wake lock if possible
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmService::WakeLock")
            wakeLock?.acquire()
            MainActivity.log("GsmService: Wake lock acquired")
        } catch (error: Exception) {
            MainActivity.log("WARNING: Could not acquire wake lock: ${error.message}")
        }

        // 3. load config
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            MainActivity.log("GsmService: onStartCommand called")

            // 1. Start foreground IMMEDIATELY - before any async work
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, createNotification())
                MainActivity.log("GsmService: Foreground notification created")
            }

            // 2. Init pusher + audio in background thread with error handling
            Thread {
                try {
                    val hasPusherCreds = !config?.PUSHER_KEY.isNullOrBlank() &&
                            !config?.PUSHER_CLUSTER.isNullOrBlank() &&
                            !config?.BACKEND_BEARER.isNullOrBlank() &&
                            !config?.BACKEND_URL.isNullOrBlank()

                    if (!hasPusherCreds) {
                        MainActivity.log("WARNING: Missing Pusher/Backend config - realtime features disabled")
                        return@Thread
                    }

                    pusherClient = PusherClient(this, config!!)
                    pusherClient?.connect()
                    MainActivity.log("GsmService: Pusher connecting...")

                    audioStreamHandler = AudioStreamHandler(this, pusherClient!!)
                    MainActivity.log("GsmService: AudioStreamHandler initialized")
                } catch (error: Exception) {
                    MainActivity.log("WARNING: Pusher/Audio failed: ${error.message}")
                    error.printStackTrace()
                }
            }.start()

        } catch (error: Exception) {
            MainActivity.log("ERROR in onStartCommand: ${error.message}")
            error.printStackTrace()
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

        pusherClient?.disconnect()
        audioStreamHandler?.cleanup()
        gsmDialer?.cleanup()
        gsmDialer = null
        MainActivity.log("GsmService: Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Gateway Active")
            .setContentText("Waiting for calls...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        return builder.build()
    }

    // central command dispatcher - single entrypoint
    fun handleCommand(type: String, data: Map<String, Any>) {
        MainActivity.log("Command received: $type")

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

                // 1. set callbacks
                gsmDialer?.setCallConnectedCallback {
                    MainActivity.log("Call connected (OFFHOOK) - starting audio capture")
                    audioStreamHandler?.startAudioCapture()
                    pusherClient?.sendEvent("CALL_CONNECTED", emptyMap())
                }

                gsmDialer?.setCallEndedCallback {
                    MainActivity.log("Call ended - stopping audio")
                    audioStreamHandler?.stopAudioCapture()
                    audioStreamHandler?.stopAudioPlayback()
                    pusherClient?.sendEvent("CALL_ENDED", emptyMap())
                }

                // 2. Launch CallInitiatorActivity directly (no notification needed if USE_FULL_SCREEN_INTENT granted)
                try {
                    val callIntent = Intent(this, CallInitiatorActivity::class.java).apply {
                        putExtra("number", number)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(callIntent)
                    MainActivity.log("CallInitiatorActivity launched")
                } catch (error: Exception) {
                    MainActivity.log("ERROR launching CallInitiatorActivity: ${error.message}")
                }
            }

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