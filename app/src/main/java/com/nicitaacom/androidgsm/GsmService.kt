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
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
    private var isServiceAudioActive = false
    private var isCallActive = false
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    private val callStateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            try {
                val action = intent?.action
                when (action) {
                    ACTION_CALL_CONNECTED_BROADCAST -> {
                        MainActivity.log("callStateReceiver: CALL_CONNECTED received")
                        try {
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            audioManager.isSpeakerphoneOn = false

                            Thread {
                                try {
                                    // 1. Re-init WS handler if null — call can arrive without SERVICE mode active
                                    if (audioWsHandler == null) {
                                        config?.let { safeConfig ->
                                            audioWsHandler = AudioWebSocketHandler(this@GsmService, safeConfig) { _: ShortArray -> }
                                        }
                                        val wsUrl = config?.BACKEND_URL
                                            ?.replace("http://", "ws://")
                                            ?.replace("https://", "wss://")
                                            ?.removeSuffix("/") + "/ws/audio"
                                        audioWsHandler?.connect(wsUrl, config?.BACKEND_BEARER ?: "", config?.DEVICE_TOKEN ?: "")
                                        Thread.sleep(500)
                                    }
                                    audioWsHandler?.stopAudioCapture()
                                    audioWsHandler?.stopAudioPlayback()
                                    Thread.sleep(200)
                                    audioWsHandler?.setCallActive(true)
                                    audioWsHandler?.startAudioCapture()
                                    audioWsHandler?.startAudioPlayback()
                                    pusherClient?.sendEvent("CALL_CONNECTED", emptyMap())
                                    MainActivity.log("callStateReceiver: WS audio started (call mode)")
                                } catch (error: Exception) {
                                    MainActivity.log("callStateReceiver error: ${error.message}")
                                }
                            }.start()
                        } catch (error: Exception) {
                            MainActivity.log("callStateReceiver outer error: ${error.message}")
                        }
                    }
                    ACTION_CALL_DISCONNECTED_BROADCAST -> {
                        MainActivity.log("callStateReceiver: CALL_DISCONNECTED received")
                        try {
                            audioStreamHandler?.stopAudioCapture()
                            audioStreamHandler?.stopAudioPlayback()
                            audioWsHandler?.setCallActive(false)
                            audioWsHandler?.disconnect()
                            Thread {
                                try { pusherClient?.sendEvent("CALL_ENDED", emptyMap()) } catch (_: Exception) {}
                            }.start()
                        } catch (e: Exception) {
                            MainActivity.log("callStateReceiver disconnect error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                MainActivity.log("callStateReceiver unexpected error: ${e.message}")
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CALL_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "gsm_gateway_channel"
        private const val CALL_CHANNEL_ID = "gsm_call_channel"
        // TEST mode: Android mic -> server, and server audio -> Android output route.
        const val ACTION_START_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT =
            "com.nicitaacom.androidgsm.action.START_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT"
        const val ACTION_STOP_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT =
            "com.nicitaacom.androidgsm.action.STOP_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT"

        // SERVICE mode: Android audio output -> server, and server audio -> Android audio input path.
        const val ACTION_START_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT =
            "com.nicitaacom.androidgsm.action.START_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT"
        const val ACTION_STOP_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT =
            "com.nicitaacom.androidgsm.action.STOP_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT"
        const val ACTION_CALL_CONNECTED_BROADCAST = "com.nicitaacom.androidgsm.ACTION_CALL_CONNECTED"
        const val ACTION_CALL_DISCONNECTED_BROADCAST = "com.nicitaacom.androidgsm.ACTION_CALL_DISCONNECTED"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            AppContextHolder.ctx = applicationContext
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

                // Set callbacks once at init — these fire on ANY call state change,
                // including manual hang-up or remote party ending the call.
                gsmDialer?.setCallEndedCallback {
                    if (!isCallActive) return@setCallEndedCallback  // ignore IDLE fired on boot/init
                    isCallActive = false
                    MainActivity.log("📴 Call ended (IDLE) — stopping audio, notifying backend")
                    try {
                        audioWsHandler?.setCallActive(false)
                        audioWsHandler?.stopAudioCapture()
                        audioWsHandler?.stopAudioPlayback()
                        audioWsHandler?.disconnect()
                        audioWsHandler = null
                    } catch (_: Exception) {}
                    Thread { pusherClient?.sendEvent("CALL_ENDED", emptyMap()) }.start()
                }
                gsmDialer?.setCallConnectedCallback {
                    isCallActive = true
                    MainActivity.log("📞 Call connected (OFFHOOK)")
                }
                // Register for broadcasts from ConnectionService (default-dialer)
                try {
                    val filter = IntentFilter().apply {
                        addAction(ACTION_CALL_CONNECTED_BROADCAST)
                        addAction(ACTION_CALL_DISCONNECTED_BROADCAST)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(callStateReceiver, filter, RECEIVER_NOT_EXPORTED)
                    } else {
                        registerReceiver(callStateReceiver, filter)
                    }
                    MainActivity.log("GsmService: callStateReceiver registered")
                } catch (e: Exception) {
                    MainActivity.log("Failed to register callStateReceiver: ${e.message}")
                }
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

    private fun buildForegroundServiceTypeMask(): Int {
        var serviceType = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return serviceType
    }

    private fun startForegroundSafely(notification: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }

        val serviceType = buildForegroundServiceTypeMask()
        if (serviceType == 0) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }

        try {
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } catch (error: SecurityException) {
            MainActivity.log("WARNING: Foreground type denied (${error.message}); retrying without explicit type")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            MainActivity.log("GsmService: onStartCommand called")

            // 1. ALWAYS start foreground first — required for background mic capture on Android 10+
            val notification = createNotification()
            startForegroundSafely(notification)
            MainActivity.log("GsmService: Foreground started")

            when (intent?.action) {
                ACTION_START_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT -> {
                    ensureRealtimeClientsInitialized()
                    startTestDuplexMicToServerAndServerToOutput()
                    return START_NOT_STICKY
                }
                ACTION_STOP_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT -> {
                    stopTestDuplexMicToServerAndServerToOutput()
                    return START_NOT_STICKY
                }
                ACTION_START_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT -> {
                    ensureRealtimeClientsInitialized()
                    startServiceDuplexOutputToServerAndServerToInput()
                    return START_NOT_STICKY
                }
                ACTION_STOP_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT -> {
                    stopServiceDuplexOutputToServerAndServerToInput()
                    return START_NOT_STICKY
                }
            }

        } catch (error: Exception) {
            MainActivity.log("ERROR in onStartCommand: ${error.message}")
        }
        return START_NOT_STICKY
    }

    private fun ensureRealtimeClientsInitialized() {
        Thread {
            try {
                val hasPusherCreds = !config?.PUSHER_KEY.isNullOrBlank() &&
                    !config?.PUSHER_CLUSTER.isNullOrBlank() &&
                    !config?.BACKEND_BEARER.isNullOrBlank() &&
                    !config?.BACKEND_URL.isNullOrBlank()

                if (!hasPusherCreds) {
                    MainActivity.log("WARNING: Missing Pusher/Backend config")
                    return@Thread
                }

               if (pusherClient == null) {
                val safeConfig = config ?: run {
                    MainActivity.log("WARNING: Config unavailable, skipping realtime init")
                    return@Thread
                }

                pusherClient = PusherClient(this, safeConfig)
                pusherClient?.connect()
                MainActivity.log("GsmService: Pusher connecting...")

                // 1. Wait for connection then notify backend so connectedDevices is populated
                Thread {
                    try {
                        Thread.sleep(1500) // allow WS handshake to complete
                        pusherClient?.sendEvent("CONNECTED", emptyMap())
                        MainActivity.log("GsmService: CONNECTED event sent to backend")
                    } catch (error: Exception) {
                        MainActivity.log("WARNING: Failed to send CONNECTED event: ${error.message}")
                    }
                }.start()
            }
                if (audioStreamHandler == null && pusherClient != null) {
                    audioStreamHandler = AudioStreamHandler(this, pusherClient!!)
                }
                if (audioWsHandler == null) {
                    config?.let { safeConfig ->
                                        audioWsHandler = AudioWebSocketHandler(this@GsmService, safeConfig) { _: ShortArray -> }
                                    }
                }
                MainActivity.log("GsmService: Audio handlers initialized")
            } catch (error: Exception) {
                MainActivity.log("WARNING: Pusher/Audio init failed: ${error.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 1. Force-stop all audio first — prevents zombie audio after crash/kill
        try {
            audioWsHandler?.stopAudioCapture()
            audioWsHandler?.stopAudioPlayback()
            audioWsHandler?.disconnect()
            audioWsHandler = null
        } catch (_: Exception) {}

        try {
            audioStreamHandler?.stopAudioCapture()
            audioStreamHandler?.stopAudioPlayback()
        } catch (_: Exception) {}

        // 2. Notify backend device is gone
        Thread {
            try { pusherClient?.sendEvent("DISCONNECTED", emptyMap()) } catch (_: Exception) {}
            try { pusherClient?.disconnect() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }.join(1000)

        try { unregisterReceiver(callStateReceiver) } catch (_: Exception) {}
        unregisterPhoneStateListener()
        wakeLock?.let { if (it.isHeld) it.release() }
        audioStreamHandler?.cleanup()
        gsmDialer?.cleanup()
        gsmDialer = null
        isServiceAudioActive = false
        isTestAudioActive = false
        MainActivity.log("GsmService: Destroyed")
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

    private fun startTestDuplexMicToServerAndServerToOutput() {
        if (isTestAudioActive) { MainActivity.log("Test audio already running"); return }
        if (isServiceAudioActive) {
            MainActivity.log("Stopping SERVICE audio before TEST start")
            stopServiceDuplexOutputToServerAndServerToInput()
        }
        isTestAudioActive = true

        val baseUrl = config?.BACKEND_URL
        val bearerToken = config?.BACKEND_BEARER
        val deviceToken = config?.DEVICE_TOKEN

        if (baseUrl.isNullOrBlank() || bearerToken.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            MainActivity.log("⚠️ Test audio unavailable: missing backend configuration")
            isTestAudioActive = false
            return
        }

        Thread {
            try {
                // 1. Wait up to 5s for pusherClient to be ready before sending event
                val deadline = System.currentTimeMillis() + 5000
                while (pusherClient == null && System.currentTimeMillis() < deadline) {
                    MainActivity.log("⏳ Waiting for Pusher to connect...")
                    Thread.sleep(300)
                }

                if (pusherClient == null) {
                    MainActivity.log("❌ Pusher not available after 5s - TEST AUDIO aborted")
                    isTestAudioActive = false
                    return@Thread
                }

                // 2. Init WS handler if needed
                if (audioWsHandler == null) {
                    config?.let { safeConfig ->
                                        audioWsHandler = AudioWebSocketHandler(this@GsmService, safeConfig) { _: ShortArray -> }
                                    }
                    MainActivity.log("Test audio: WebSocket handler initialized")
                }

                val ws = audioWsHandler ?: run {
                    isTestAudioActive = false
                    return@Thread
                }

                val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://").removeSuffix("/") + "/ws/audio"

                // 3. Connect WS for TEST mode duplex
                ws.setCallActive(false)
                ws.connect(wsUrl, bearerToken, deviceToken)
                Thread.sleep(500) // wait for WS handshake

                // 4. TEST mode is duplex:
                // Android mic -> server -> browser AND browser mic -> server -> Android speaker
                ws.startAudioPlayback()
                ws.startAudioCapture()

                // 5. Notify browser AFTER WS is ready so browser starts mic capture immediately
                pusherClient?.sendEvent("TEST_AUDIO_STARTED", emptyMap())
                MainActivity.log("✅ TEST mode active: duplex browser<->android over websocket")
            } catch (error: Exception) {
                MainActivity.log("ERROR starting test audio: ${error.message}")
                isTestAudioActive = false
            }
        }.start()
    }

    private fun stopTestDuplexMicToServerAndServerToOutput() {
        isTestAudioActive = false
        audioWsHandler?.stopAudioCapture()
        audioWsHandler?.stopAudioPlayback()
        audioWsHandler?.disconnect()
        audioWsHandler = null
        Thread {
            try { pusherClient?.sendEvent("TEST_AUDIO_STOPPED", emptyMap()) } catch (_: Exception) {}
            Thread.sleep(300)
            pusherClient?.disconnect()
            pusherClient = null
        }.start()
        MainActivity.log("🛑 TEST stopped — Pusher disconnected")
    }

    private fun startServiceDuplexOutputToServerAndServerToInput() {
        if (isServiceAudioActive) {
            MainActivity.log("SERVICE audio already running")
            return
        }
        if (isTestAudioActive) {
            MainActivity.log("Stopping TEST audio before SERVICE start")
            stopTestDuplexMicToServerAndServerToOutput()
        }

        val baseUrl = config?.BACKEND_URL
        val bearerToken = config?.BACKEND_BEARER
        val deviceToken = config?.DEVICE_TOKEN

        if (baseUrl.isNullOrBlank() || bearerToken.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            MainActivity.log("⚠️ SERVICE audio unavailable: missing backend configuration")
            return
        }

        isServiceAudioActive = true

        // SERVICE mode: just connect to backend and wait for CALL_STARTED command.
        // Audio capture/playback starts only when a call connects (OFFHOOK via GsmDialer callback).
        // Do NOT start audio here — REMOTE_SUBMIX would stream all system sounds.
        Thread {
            try {
                // Wait for Pusher to be ready
                val deadline = System.currentTimeMillis() + 5000
                while (pusherClient == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(300)
                }
                pusherClient?.sendEvent("CONNECTED", emptyMap())
                MainActivity.log("✅ SERVICE mode active: connected to backend, waiting for CALL_STARTED")
            } catch (error: Exception) {
                MainActivity.log("ERROR in SERVICE start: ${error.message}")
                isServiceAudioActive = false
            }
        }.start()
    }

    private fun stopServiceDuplexOutputToServerAndServerToInput() {
        isServiceAudioActive = false
        try {
            audioWsHandler?.stopAudioCapture()
            audioWsHandler?.stopAudioPlayback()
            audioWsHandler?.disconnect()
        } catch (error: Exception) {
            MainActivity.log("WARNING: error stopping SERVICE ws: ${error.message}")
        }
        audioWsHandler = null
        Thread {
            try { pusherClient?.sendEvent("DISCONNECTED", emptyMap()) } catch (_: Exception) {}
            Thread.sleep(300)
            pusherClient?.disconnect()
            pusherClient = null
        }.start()
        MainActivity.log("🛑 SERVICE stopped — Pusher disconnected")
    }

    // central command dispatcher - single entrypoint
    fun handleCommand(type: String, data: Map<String, Any>) {
        try {
            if (type != "AUDIO_CHUNK") MainActivity.log("Command received: $type")

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

            // Override onCallConnected for this call — sets up WS audio for SERVICE mode
            gsmDialer?.setCallConnectedCallback {
                MainActivity.log("📞 OFFHOOK callback fired — call connected")
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false

                Thread {
                    try {
                        val wsUrl = config?.BACKEND_URL?.replace("http://", "ws://")
                            ?.replace("https://", "wss://")?.removeSuffix("/") + "/ws/audio"
                        if (audioWsHandler == null) {
                            config?.let { c -> audioWsHandler = AudioWebSocketHandler(this@GsmService, c) { } }
                        }
                        audioWsHandler?.connect(wsUrl, config?.BACKEND_BEARER ?: "", config?.DEVICE_TOKEN ?: "")
                        Thread.sleep(300)
                        isCallActive = true
                        audioWsHandler?.setCallActive(true)
                        audioWsHandler?.startAudioCapture()
                        audioWsHandler?.startAudioPlayback()
                        MainActivity.log("📞 WS audio started, pusherClient=${if (pusherClient != null) "alive" else "NULL"}")
                        pusherClient?.sendEvent("CALL_CONNECTED", emptyMap())
                        MainActivity.log("📞 CALL_CONNECTED sent to backend")
                    } catch (error: Exception) {
                        MainActivity.log("ERROR in CALL_CONNECTED callback: ${error.message}")
                    }
                }.start()
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
            isServiceAudioActive = false
            Thread {
                try {
                    gsmDialer?.endCall()
                    Thread.sleep(300)
                    audioWsHandler?.stopAudioCapture()
                    audioWsHandler?.stopAudioPlayback()
                    audioStreamHandler?.stopAudioCapture()
                    audioStreamHandler?.stopAudioPlayback()
                    pusherClient?.sendEvent("CALL_ENDED", emptyMap())
                } catch (error: Exception) {
                    MainActivity.log("ERROR in handleCallEnded thread: ${error.message}")
                }
            }.start()
        } catch (error: Exception) {
            MainActivity.log("ERROR in handleCallEnded: ${error.message}")
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
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                            audioManager.isSpeakerphoneOn = false
                            MainActivity.log("PhoneStateListener: OFFHOOK - set MODE_IN_COMMUNICATION + earpiece")
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
