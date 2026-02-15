package com.nicitaacom.androidgsm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat

class GsmService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var pusherClient: PusherClient? = null
    private var gsmDialer: GsmDialer? = null
    private var audioStreamHandler: AudioStreamHandler? = null
    private var audioWsHandler: AudioWebSocketHandler? = null
    private var config: Config? = null
    private var isTestAudioActive = false
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CALL_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "gsm_gateway_channel"
        private const val CALL_CHANNEL_ID = "gsm_call_channel"
        const val ACTION_START_TEST_AUDIO = "com.nicitaacom.androidgsm.action.START_TEST_AUDIO"
        const val ACTION_STOP_TEST_AUDIO = "com.nicitaacom.androidgsm.action.STOP_TEST_AUDIO"
    }

    override fun onCreate() {
        super.onCreate()
        try {
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

                // Setup phone state listener to maintain audio during system dialer
                setupPhoneStateListener()

                // init dialer
                gsmDialer = GsmDialer(this)
                MainActivity.log("GsmService: GsmDialer initialized")
            } catch (error: Exception) {
                MainActivity.log("ERROR in GsmService.onCreate (config/dialer): ${error.message}")
                error.printStackTrace()
                return
            }
        } catch (e: Exception) {
            MainActivity.log("FATAL: Uncaught exception in onCreate: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            MainActivity.log("GsmService: onStartCommand called")

            if (intent?.action == ACTION_START_TEST_AUDIO) {
                startTestAudioStreaming()
                return START_STICKY
            }

            if (intent?.action == ACTION_STOP_TEST_AUDIO) {
                stopTestAudioStreaming()
                return START_STICKY
            }

            // 1. Start foreground IMMEDIATELY - before any async work
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notification = createNotification()
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

                    audioWsHandler = AudioWebSocketHandler(this, config!!) { _: ShortArray ->
                        // Callback for audio received (placeholder for future use)
                    }
                    MainActivity.log("GsmService: AudioWebSocketHandler initialized")
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
            unregisterPhoneStateListener()
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    MainActivity.log("Wake lock released")
                }
            }
            audioWsHandler?.disconnect()
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

    private fun startTestAudioStreaming() {
        if (isTestAudioActive) {
            MainActivity.log("Test audio already running")
            return
        }
        isTestAudioActive = true
        pusherClient?.sendEvent("TEST_AUDIO_STARTED", emptyMap())

        try {
            val baseUrl = config?.BACKEND_URL
            val bearerToken = config?.BACKEND_BEARER
            val deviceToken = config?.DEVICE_TOKEN

            if (baseUrl.isNullOrBlank() || bearerToken.isNullOrBlank() || deviceToken.isNullOrBlank()) {
                MainActivity.log("⚠️ Test audio unavailable: missing backend configuration")
                isTestAudioActive = false
                return
            }

            if (audioWsHandler == null) {
                audioWsHandler = AudioWebSocketHandler(this, config!!) { _: ShortArray -> }
                MainActivity.log("Test audio: WebSocket handler initialized on demand")
            }

            val ws = audioWsHandler ?: run {
                MainActivity.log("⚠️ Test audio unavailable: WebSocket handler not ready")
                isTestAudioActive = false
                return
            }

            MainActivity.log("Test audio: starting phone -> website stream")

            Thread {
                try {
                    val wsUrl = baseUrl
                        .replace("http://", "ws://")
                        .replace("https://", "wss://")
                        .removeSuffix("/") + "/ws/audio"

                    ws.connect(wsUrl, bearerToken, deviceToken)
                    ws.startAudioCapture()
                    MainActivity.log("✅ Test audio streaming started (phone -> website)")
                } catch (e: Exception) {
                    MainActivity.log("ERROR starting test audio stream: ${e.message}")
                    isTestAudioActive = false
                    e.printStackTrace()
                }
            }.start()
        } catch (e: Exception) {
            MainActivity.log("ERROR in startTestAudioStreaming: ${e.message}")
            isTestAudioActive = false
            e.printStackTrace()
        }
    }

    private fun stopTestAudioStreaming() {
        isTestAudioActive = false
        pusherClient?.sendEvent("TEST_AUDIO_STOPPED", emptyMap())
        audioWsHandler?.disconnect()
        audioStreamHandler?.stopAudioCapture()
        audioStreamHandler?.stopAudioPlayback()
        MainActivity.log("Test audio streaming stopped")
    }

    // central command dispatcher - single entrypoint
    fun handleCommand(type: String, data: Map<String, Any>) {
        try {
            MainActivity.log("Command received: $type")

            val normalizedType = when (type) {
                "MAKE_CALL", "CALL_START" -> "CALL_STARTED"
                else -> type
            }

            when (normalizedType) {
                "CALL_STARTED" -> handleCallStarted(data)
                "CALL_ENDED" -> handleCallEnded()
                "SEND_DTMF" -> handleSendDtmf(data)
                "AUDIO_CHUNK" -> handleAudioChunk(data)
                else -> MainActivity.log("Unhandled command: $type")
            }
        } catch (e: Exception) {
            MainActivity.log("FATAL: Uncaught exception in handleCommand: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleCallStarted(data: Map<String, Any>) {
        try {
            val number = data["number"] as? String
            if (number.isNullOrBlank()) {
                MainActivity.log("CALL_STARTED ignored: missing number")
                return
            }
            MainActivity.log("Starting call to: $number")

            // 1. set callbacks BEFORE starting call
            gsmDialer?.setCallConnectedCallback {
                try {
                    MainActivity.log("Call connected (OFFHOOK) - starting audio capture and WebSocket")

                    // Ensure audio mode is set correctly for call
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = true

                    // Start audio capture with retry
                    Thread {
                        try {
                            audioStreamHandler?.startAudioCapture()
                            MainActivity.log("Audio capture started successfully")
                        } catch (e: Exception) {
                            MainActivity.log("Error starting audio capture: ${e.message}")
                            e.printStackTrace()
                        }
                    }.start()

                    // Connect WebSocket for bidirectional audio (non-blocking)
                    Thread {
                        try {
                            val baseUrl = config?.BACKEND_URL ?: ""
                            val wsUrl = baseUrl
                                .replace("http://", "ws://")
                                .replace("https://", "wss://")
                                .removeSuffix("/") + "/ws/audio"
                            val bearerToken = config?.BACKEND_BEARER ?: ""

                            MainActivity.log("WS URL: $wsUrl")
                            MainActivity.log("Bearer token: ${bearerToken.take(10)}...")

                            // Small delay to ensure Pusher CALL_CONNECTED reaches server first
                            Thread.sleep(500)

                            audioWsHandler?.connect(wsUrl, bearerToken, config?.DEVICE_TOKEN ?: "")
                            audioWsHandler?.setCallActive(true)
                            audioWsHandler?.startAudioCapture()
                            audioWsHandler?.startAudioPlayback()
                            MainActivity.log("WebSocket audio connected (call mode)")
                        } catch (e: Exception) {
                            MainActivity.log("Error connecting WebSocket: ${e.message}")
                            e.printStackTrace()
                        }
                    }.start()

                    // Send CALL_CONNECTED to server (non-blocking)
                    Thread {
                        try {
                            pusherClient?.sendEvent("CALL_CONNECTED", emptyMap())
                            MainActivity.log("CALL_CONNECTED sent to server")
                        } catch (e: Exception) {
                            MainActivity.log("Error sending CALL_CONNECTED: ${e.message}")
                        }
                    }.start()
                } catch (e: Exception) {
                    MainActivity.log("ERROR in CALL_CONNECTED callback: ${e.message}")
                    e.printStackTrace()
                }
            }

            gsmDialer?.setCallEndedCallback {
                try {
                    MainActivity.log("Call ended - stopping audio and WebSocket")
                    audioStreamHandler?.stopAudioCapture()
                    audioStreamHandler?.stopAudioPlayback()
                    audioWsHandler?.setCallActive(false)
                    audioWsHandler?.disconnect()
                    pusherClient?.sendEvent("CALL_ENDED", emptyMap())
                } catch (e: Exception) {
                    MainActivity.log("ERROR in CALL_ENDED callback: ${e.message}")
                    e.printStackTrace()
                }
            }

            // 2. start call with error handling
            try {
                val started = gsmDialer?.startCall(number) ?: false
                if (started) {
                    MainActivity.log("Call started via GsmDialer to $number")
                } else {
                    MainActivity.log("Call start failed - syncing CALL_ENDED state")
                    audioStreamHandler?.stopAudioCapture()
                    audioStreamHandler?.stopAudioPlayback()
                    audioWsHandler?.disconnect()
                    pusherClient?.sendEvent("CALL_ENDED", emptyMap())
                }
            } catch (error: Exception) {
                MainActivity.log("ERROR starting call: ${error.message}")
                audioStreamHandler?.stopAudioCapture()
                audioStreamHandler?.stopAudioPlayback()
                audioWsHandler?.disconnect()
                pusherClient?.sendEvent("CALL_ENDED", emptyMap())
                error.printStackTrace()
            }
        } catch (e: Exception) {
            MainActivity.log("FATAL ERROR in handleCallStarted: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleCallEnded() {
        try {
            MainActivity.log("Ending call")
            gsmDialer?.endCall()
            audioStreamHandler?.stopAudioCapture()
            audioStreamHandler?.stopAudioPlayback()
            audioWsHandler?.disconnect()
            pusherClient?.sendEvent("CALL_ENDED", emptyMap())
        } catch (e: Exception) {
            MainActivity.log("ERROR in handleCallEnded: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleSendDtmf(data: Map<String, Any>) {
        try {
            val digit = data["digit"] as? String
            if (digit.isNullOrBlank()) {
                MainActivity.log("SEND_DTMF ignored: missing digit")
                return
            }
            MainActivity.log("Sending DTMF: $digit")
            gsmDialer?.sendDtmf(digit[0])
            pusherClient?.sendEvent("DTMF_SENT", mapOf("digit" to digit))
        } catch (e: Exception) {
            MainActivity.log("ERROR in handleSendDtmf: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupPhoneStateListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK -> {
                            // Call is active - ensure audio mode is set correctly
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            audioManager.isSpeakerphoneOn = true
                            MainActivity.log("PhoneStateListener: OFFHOOK - set MODE_IN_COMMUNICATION")
                        }
                        TelephonyManager.CALL_STATE_IDLE -> {
                            // Call ended - reset audio mode
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            audioManager.mode = AudioManager.MODE_NORMAL
                            MainActivity.log("PhoneStateListener: IDLE - reset audio mode")
                        }
                        TelephonyManager.CALL_STATE_RINGING -> {
                            MainActivity.log("PhoneStateListener: RINGING")
                        }
                    }
                }
            }
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            MainActivity.log("PhoneStateListener registered")
        } catch (e: Exception) {
            MainActivity.log("Error setting up PhoneStateListener: ${e.message}")
        }
    }

    private fun unregisterPhoneStateListener() {
        try {
            if (phoneStateListener != null && telephonyManager != null) {
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
                MainActivity.log("PhoneStateListener unregistered")
            }
        } catch (e: Exception) {
            MainActivity.log("Error unregistering PhoneStateListener: ${e.message}")
        }
    }

    private fun handleAudioChunk(data: Map<String, Any>) {
        try {
            val audioData = data["audio"] as? String
            if (audioData.isNullOrBlank()) {
                MainActivity.log("AUDIO_CHUNK ignored: missing audio data")
                return
            }

            if (audioStreamHandler == null) {
                MainActivity.log("AUDIO_CHUNK received but AudioStreamHandler not initialized")
                return
            }

            audioStreamHandler?.playAudioChunk(audioData)
        } catch (e: Exception) {
            MainActivity.log("ERROR playing audio chunk: ${e.message}")
            e.printStackTrace()
        }
    }
}
