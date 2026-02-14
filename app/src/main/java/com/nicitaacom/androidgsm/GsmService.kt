package com.nicitaacom.androidgsm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
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
        private const val CALL_CHANNEL_ID = "gsm_call_channel"
    }

    override fun onCreate() {
        super.onCreate()
        MainActivity.log("GsmService: onCreate called")

        // 1. Create notification channels FIRST
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                CHANNEL_ID,
                "GSM Gateway Service",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "GSM Gateway background service"
            manager?.createNotificationChannel(channel)
            MainActivity.log("Service notification channel created")

            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                "GSM Calls",
                NotificationManager.IMPORTANCE_HIGH
            )
            callChannel.description = "Notifications for initiating calls"
            manager?.createNotificationChannel(callChannel)
            MainActivity.log("Call notification channel created")
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
                val notification = createNotification()
                // Avoid strict Android 14/15 microphone FGS eligibility gate during startup.
                // Audio recording still works with RECORD_AUDIO permission when call is active.
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, serviceType)
                else startForeground(NOTIFICATION_ID, notification)
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
            pusherClient?.disconnect()
            audioStreamHandler?.cleanup()
            gsmDialer?.cleanup()
            gsmDialer = null
            MainActivity.log("GsmService: Destroyed")
        } catch (error: Exception) {
            MainActivity.log("ERROR in onDestroy: ${error.message}")
            error.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Gateway Active")
            .setContentText("Waiting for calls...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

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

                // 1. set callbacks BEFORE starting call
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

                // 2. start call with error handling
                try {
                    val started = gsmDialer?.startCall(number) ?: false
                    if (started) {
                        MainActivity.log("Call started via GsmDialer to $number")
                    } else {
                        MainActivity.log("❌ Call start failed - syncing CALL_ENDED state")
                        audioStreamHandler?.stopAudioCapture()
                        audioStreamHandler?.stopAudioPlayback()
                        pusherClient?.sendEvent("CALL_ENDED", emptyMap())
                    }
                } catch (error: Exception) {
                    MainActivity.log("ERROR starting call: ${error.message}")
                    audioStreamHandler?.stopAudioCapture()
                    audioStreamHandler?.stopAudioPlayback()
                    pusherClient?.sendEvent("CALL_ENDED", emptyMap())
                    error.printStackTrace()
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

                // Optional: bring to front after DTMF if needed, but probably not necessary
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
