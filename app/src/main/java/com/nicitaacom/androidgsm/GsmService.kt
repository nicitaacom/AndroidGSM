package com.nicitaacom.androidgsm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat

class GsmService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var cmdWsClient: CommandWebSocketClient? = null
    private var gsmDialer: GsmDialer? = null
    private var config: Config? = null
    private var isTestAudioActive = false
    private var isServiceAudioActive = false
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    private val log: GsmLogger = { MainActivity.log(it) }
    private val audioWsHandlerRef = AudioWsHandlerRef()
    val callController: CallController by lazy {
        CallController(
            context = this,
            config = config!!,
            cmdWsClient = { cmdWsClient },
            audioWsHandlerRef = audioWsHandlerRef,
            onCallActiveChanged = { /* GsmService reads callController.isCallActive directly */ },
            onMicSourceReset = { MainActivity.notifyMicSource(true) },
            log = log
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gsm_gateway_channel"
        // TEST: bidirectional audio with the phone's physical mic (no real GSM call).
        //       Phone mic → server → browser speaker; browser mic → server → phone speaker.
        const val ACTION_START_TEST = "com.nicitaacom.androidgsm.action.START_TEST"
        const val ACTION_STOP_TEST = "com.nicitaacom.androidgsm.action.STOP_TEST"

        // SERVICE: GSM gateway mode. Waits for CALL_STARTED from backend, then:
        //   downlink — GSM call audio (remote party) → tinycap → server → browser speaker
        //   uplink   — browser mic → server → AudioTrack → Incall_Music mixer → GSM TX → remote party
        //              (or phone's hardware mic if MIC_SOURCE toggled to "phone")
        const val ACTION_START_SERVICE = "com.nicitaacom.androidgsm.action.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.nicitaacom.androidgsm.action.STOP_SERVICE"
        const val ACTION_SET_MIC_SOURCE = "com.nicitaacom.androidgsm.action.SET_MIC_SOURCE"
        const val EXTRA_MIC_SOURCE = "mic_source"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            AppContextHolder.ctx = applicationContext
            log("GsmService: onCreate called")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(CHANNEL_ID, "GSM Gateway Service", NotificationManager.IMPORTANCE_HIGH)
                channel.description = "GSM Gateway background service"
                manager?.createNotificationChannel(channel)
            }

            try {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmService::WakeLock")
                wakeLock?.acquire()
                log("GsmService: Wake lock acquired")
            } catch (error: Exception) {
                log("WARNING: Could not acquire wake lock: ${error.message}")
            }

            // Unpack set_mixer_ctl native binary from assets on first run.
            // Enables full 2-slot BOOL mixer control on MIUI sdm660 (tinymix only handles slot 0).
            // If binary is missing from assets, RootUtils falls back to tinymix (slot 0 only).
            // To build: NDK=/home/kali/android-sdk/ndk/27.2.12479018 ./native/set_mixer_ctl/build_arm64.sh
            try {
                val dest = java.io.File(filesDir, "set_mixer_ctl")
                if (!dest.exists()) {
                    assets.open("set_mixer_ctl").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.setExecutable(true, false)
                }
                RootUtils.nativeBinDir = filesDir.absolutePath
                log("✅ set_mixer_ctl ready (full 2-slot mixer control)")
            } catch (_: Exception) {
                log("⚠️ set_mixer_ctl not in assets — using tinymix fallback (slot 0 only)")
            }

            try {
                config = ConfigReader.readConfig(this)
                log("GsmService: Config loaded — ${config?.BACKEND_URL} device=${config?.DEVICE_TOKEN}")

                // PhoneStateListener: safety net for IDLE only (no audio-mode changes — those are
                // owned by the GsmDialer callbacks below to avoid fighting each other).
                setupPhoneStateListener()

                gsmDialer = GsmDialer(this, log)
                log("GsmService: GsmDialer initialized")

                // Wire call lifecycle callbacks into CallController (once, in onCreate).
                // config must be non-null here — callController lazy init reads config!!
                if (config != null) {
                    callController.wireDialerCallbacks(gsmDialer!!)
                } else {
                    log("❌ Cannot wire call callbacks — config is null")
                }

                ensureRealtimeClientsInitialized()
            } catch (error: Exception) {
                log("ERROR in GsmService.onCreate (config/dialer): ${error.message}")
                error.printStackTrace()
            }
        } catch (e: Exception) {
            log("FATAL: Uncaught exception in onCreate: ${e.message}")
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
        if (serviceType == 0) { startForeground(NOTIFICATION_ID, notification); return }
        try {
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } catch (error: SecurityException) {
            log("WARNING: Foreground type denied (${error.message}); retrying without explicit type")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = createNotification()
            startForegroundSafely(notification)

            when (intent?.action) {
                ACTION_START_TEST -> {
                    ensureRealtimeClientsInitialized()
                    startTestMode()
                    return START_NOT_STICKY
                }
                ACTION_STOP_TEST -> {
                    stopTestMode()
                    return START_NOT_STICKY
                }
                ACTION_START_SERVICE -> {
                    ensureRealtimeClientsInitialized()
                    startServiceMode()
                    return START_NOT_STICKY
                }
                ACTION_STOP_SERVICE -> {
                    stopServiceMode()
                    return START_NOT_STICKY
                }
                ACTION_SET_MIC_SOURCE -> {
                    val source = intent.getStringExtra(EXTRA_MIC_SOURCE) ?: "browser"
                    val useBrowser = source != "phone"
                    AudioWebSocketHandler.useBrowserMicUplink = useBrowser
                    // Uplink is now pcmC0D19p direct write — stop/restart playback to toggle source
                    if (callController.isCallActive) {
                        Thread {
                            if (useBrowser) {
                                audioWsHandlerRef.handler?.startAudioPlayback() // restart PCM writer
                            } else {
                                audioWsHandlerRef.handler?.stopAudioPlayback() // stop PCM writer, phone mic takes over
                            }
                        }.start()
                    }
                    cmdWsClient?.sendEvent("MIC_SOURCE_CHANGED", mapOf("source" to source))
                    return START_NOT_STICKY
                }
            }
        } catch (error: Exception) {
            log("ERROR in onStartCommand: ${error.message}")
        }
        return START_NOT_STICKY
    }

    private fun ensureRealtimeClientsInitialized() {
        Thread {
            try {
                val safeConfig = config ?: run {
                    log("WARNING: Config unavailable, skipping realtime init")
                    return@Thread
                }

                if (!safeConfig.BACKEND_BEARER.isNullOrBlank() && !safeConfig.BACKEND_URL.isNullOrBlank()) {
                    if (cmdWsClient == null) {
                        val wsUrl = safeConfig.BACKEND_URL
                            .replace("http://", "ws://")
                            .replace("https://", "wss://")
                            .removeSuffix("/") + "/ws/audio"
                        cmdWsClient = CommandWebSocketClient(wsUrl, safeConfig.BACKEND_BEARER, safeConfig.DEVICE_TOKEN,
                            onCommand = { type, data -> handleWsCommand(type, data) },
                            stateProvider = { mapOf("isServiceActive" to isServiceAudioActive, "isTestActive" to isTestAudioActive) },
                            onConnected = {
                                val sims = gsmDialer?.getSimAccounts() ?: emptyList()
                                if (sims.isNotEmpty()) {
                                    cmdWsClient?.sendEvent("SIM_LIST", mapOf("sims" to sims.toString()))
                                    log("GsmService: SIM_LIST sent: ${sims.size} accounts")
                                }
                                if (isServiceAudioActive) {
                                    // Reconnect: server lost in-memory state — re-announce without touching audio
                                    log("GsmService: WS reconnected, re-sending SERVICE_STARTED")
                                    cmdWsClient?.sendEvent("SERVICE_STARTED", emptyMap())
                                } else {
                                    startServiceMode()
                                }
                            }
                        )
                        cmdWsClient?.connect()
                        log("GsmService: CommandWS connecting...")
                    }
                }

                // Do NOT pre-create the audio handler here — calls create a fresh one in CallController.
                // Pre-creating it caused the OFFHOOK path to reuse a stale unconnected handler,
                // so all tinycap chunks were silently dropped (isConnected=false → sendAudioChunk no-op).
                log("GsmService: realtime clients initialized")
            } catch (error: Exception) {
                log("WARNING: realtime init failed: ${error.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            audioWsHandlerRef.handler?.stopAudioCapture()
            audioWsHandlerRef.handler?.stopAudioPlayback()
            audioWsHandlerRef.handler?.disconnect()
            audioWsHandlerRef.handler = null
        } catch (_: Exception) {}
        Thread {
            try { cmdWsClient?.sendEvent("DISCONNECTED", emptyMap()) } catch (_: Exception) {}
            try { cmdWsClient?.disconnect() } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }.join(1000)
        unregisterPhoneStateListener()
        wakeLock?.let { if (it.isHeld) it.release() }
        gsmDialer?.cleanup()
        gsmDialer = null
        isServiceAudioActive = false
        isTestAudioActive = false
        log("GsmService: Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Gateway Active")
            .setContentText("Waiting for calls...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startTestMode() {
        if (isTestAudioActive) { log("Test audio already running"); return }
        if (isServiceAudioActive) {
            log("Stopping SERVICE audio before TEST start")
            stopServiceMode()
        }
        isTestAudioActive = true
        MainActivity.setStatus("Status: Test Audio Active", true)

        val baseUrl = config?.BACKEND_URL
        val bearerToken = config?.BACKEND_BEARER
        val deviceToken = config?.DEVICE_TOKEN

        if (baseUrl.isNullOrBlank() || bearerToken.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            log("⚠️ Test audio unavailable: missing backend configuration")
            isTestAudioActive = false
            return
        }

        Thread {
            try {
                val deadline = System.currentTimeMillis() + 5000
                while (cmdWsClient == null && System.currentTimeMillis() < deadline) {
                    log("⏳ Waiting for cmd WS to connect...")
                    Thread.sleep(300)
                }
                if (cmdWsClient == null) {
                    log("❌ Cmd WS not available after 5s - TEST AUDIO aborted")
                    isTestAudioActive = false
                    return@Thread
                }

                val safeConfig = config ?: run { isTestAudioActive = false; return@Thread }
                audioWsHandlerRef.handler?.disconnect()
                audioWsHandlerRef.handler = AudioWebSocketHandler(this@GsmService, safeConfig, log = log) { _: ShortArray -> }
                val ws = audioWsHandlerRef.handler!!
                val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://").removeSuffix("/") + "/ws/audio"
                ws.setCallActive(false)
                ws.connect(wsUrl, bearerToken, deviceToken)
                val wsDeadline = System.currentTimeMillis() + 5000
                while (!ws.isWsConnected && System.currentTimeMillis() < wsDeadline) Thread.sleep(100)
                if (!ws.isWsConnected) { log("❌ Audio WS failed to connect — TEST aborted"); isTestAudioActive = false; return@Thread }
                ws.startAudioPlayback()
                ws.startAudioCapture()
                cmdWsClient?.sendEvent("TEST_AUDIO_STARTED", emptyMap())
                log("✅ TEST mode active: duplex browser<->android over websocket")
            } catch (error: Exception) {
                log("ERROR starting test audio: ${error.message}")
                isTestAudioActive = false
            }
        }.start()
    }

    private fun stopTestMode() {
        isTestAudioActive = false
        MainActivity.setStatus("Status: Ready", false)
        audioWsHandlerRef.handler?.stopAudioCapture()
        audioWsHandlerRef.handler?.stopAudioPlayback()
        audioWsHandlerRef.handler?.disconnect()
        audioWsHandlerRef.handler = null
        Thread { try { cmdWsClient?.sendEvent("TEST_AUDIO_STOPPED", emptyMap()) } catch (_: Exception) {} }.start()
        log("🛑 TEST stopped")
    }

    private fun startServiceMode() {
        if (isServiceAudioActive) { log("SERVICE audio already running"); return }
        if (isTestAudioActive) {
            log("Stopping TEST audio before SERVICE start")
            stopTestMode()
        }

        val baseUrl = config?.BACKEND_URL
        val bearerToken = config?.BACKEND_BEARER
        val deviceToken = config?.DEVICE_TOKEN

        if (baseUrl.isNullOrBlank() || bearerToken.isNullOrBlank() || deviceToken.isNullOrBlank()) {
            log("⚠️ SERVICE audio unavailable: missing backend configuration")
            return
        }

        isServiceAudioActive = true
        MainActivity.setStatus("Status: Service Active", true)
        Thread {
            val deadline = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < deadline) {
                val client = cmdWsClient
                if (client != null && client.isConnected) {
                    client.sendEvent("SERVICE_STARTED", emptyMap())
                    break
                }
                Thread.sleep(300)
            }
        }.start()
        log("✅ SERVICE mode active: connected to backend, waiting for CALL_STARTED")
    }

    private fun stopServiceMode() {
        isServiceAudioActive = false
        MainActivity.setStatus("Status: Ready", false)
        Thread { cmdWsClient?.sendEvent("SERVICE_STOPPED", emptyMap()) }.start()
        try {
            audioWsHandlerRef.handler?.stopAudioCapture()
            audioWsHandlerRef.handler?.stopAudioPlayback()
            audioWsHandlerRef.handler?.disconnect()
        } catch (error: Exception) {
            log("WARNING: error stopping SERVICE ws: ${error.message}")
        }
        audioWsHandlerRef.handler = null
        log("🛑 SERVICE stopped")
    }

    fun handleCommand(type: String, data: Map<String, Any>) {
        try {
            if (type != "AUDIO_CHUNK") log("Command received: $type")

            val normalizedType = when (type) {
                "MAKE_CALL", "CALL_START" -> "CALL_STARTED"
                else -> type
            }

            when (normalizedType) {
                "CALL_STARTED" -> callController.handleCallStarted(data, gsmDialer)
                "CALL_ENDED" -> callController.handleCallEnded(gsmDialer)
                "SEND_DTMF" -> callController.handleSendDtmf(data)
                "AUDIO_CHUNK" -> { /* legacy path — audio flows over /ws/audio, not /ws/cmd */ }
                "SET_GAIN" -> {
                    val mic = (data["micGain"] as? Number)?.toFloat()
                    val playback = (data["playbackGain"] as? Number)?.toFloat()
                    if (mic != null) AudioWebSocketHandler.micGain = mic.coerceIn(0f, 4f)
                    if (playback != null) AudioWebSocketHandler.playbackGain = playback.coerceIn(0f, 4f)
                    log("🎚️ Gain: mic=${mic} playback=${playback}")
                }
                "SET_MIC_SOURCE" -> {
                    val source = data["source"] as? String
                    val useBrowser = source != "phone"
                    AudioWebSocketHandler.useBrowserMicUplink = useBrowser
                    MainActivity.notifyMicSource(useBrowser)
                    log("🎤 Mic source: ${if (useBrowser) "browser" else "phone"}")
                    if (callController.isCallActive) {
                        Thread {
                            if (useBrowser) audioWsHandlerRef.handler?.startAudioPlayback()
                            else audioWsHandlerRef.handler?.stopAudioPlayback()
                        }.start()
                    }
                    cmdWsClient?.sendEvent("MIC_SOURCE_CHANGED", mapOf("source" to if (useBrowser) "browser" else "phone"))
                }
                "START_SERVICE" -> {
                    ensureRealtimeClientsInitialized()
                    startServiceMode()
                    MainActivity.notifyServiceActive(true)
                }
                "STOP_SERVICE" -> {
                    stopServiceMode()
                    MainActivity.notifyServiceActive(false)
                }
                "START_TEST" -> {
                    ensureRealtimeClientsInitialized()
                    startTestMode()
                    MainActivity.notifyTestActive(true)
                }
                "STOP_TEST" -> {
                    stopTestMode()
                    MainActivity.notifyTestActive(false)
                }
                else -> log("Unhandled command: $type")
            }
        } catch (e: Exception) {
            log("FATAL: Uncaught exception in handleCommand: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleWsCommand(type: String, data: org.json.JSONObject) {
        val dataMap = mutableMapOf<String, Any>()
        data.keys().forEach { key -> dataMap[key] = data.get(key) }
        handleCommand(type, dataMap)
    }

    private fun setupPhoneStateListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    when (state) {
                        // OFFHOOK: do NOT touch audio mode here — GsmDialer's setCallConnectedCallback
                        // owns MODE_IN_CALL + speakerphone setup. Overriding it here caused the
                        // speakerphone to be flipped off, silencing GSM audio capture.
                        TelephonyManager.CALL_STATE_IDLE -> {
                            log("PhoneStateListener: IDLE")
                            // Safety net: if GsmDialer callback didn't fire (edge case), clean up.
                            if (callController.isCallActive || audioWsHandlerRef.handler != null) {
                                log("PhoneStateListener: IDLE safety net — tearing down call")
                                callController.teardownCall()
                            }
                        }
                        TelephonyManager.CALL_STATE_RINGING -> log("PhoneStateListener: RINGING")
                    }
                }
            }
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            log("PhoneStateListener registered")
        } catch (e: Exception) {
            log("Error setting up PhoneStateListener: ${e.message}")
        }
    }

    private fun unregisterPhoneStateListener() {
        try {
            if (phoneStateListener != null && telephonyManager != null) {
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {}
    }

}
