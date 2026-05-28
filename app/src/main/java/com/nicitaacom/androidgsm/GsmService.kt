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
    private var cmdWsClient: CommandWebSocketClient? = null
    private var gsmDialer: GsmDialer? = null
    private var audioWsHandler: AudioWebSocketHandler? = null
    private var config: Config? = null
    private var isTestAudioActive = false
    private var isServiceAudioActive = false
    private var isCallActive = false
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gsm_gateway_channel"
        const val ACTION_START_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT =
            "com.nicitaacom.androidgsm.action.START_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT"
        const val ACTION_STOP_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT =
            "com.nicitaacom.androidgsm.action.STOP_TEST_DUPLEX_MIC_TO_SERVER_AND_SERVER_TO_OUTPUT"
        const val ACTION_START_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT =
            "com.nicitaacom.androidgsm.action.START_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT"
        const val ACTION_STOP_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT =
            "com.nicitaacom.androidgsm.action.STOP_SERVICE_DUPLEX_OUTPUT_TO_SERVER_AND_SERVER_TO_INPUT"
        const val ACTION_SET_MIC_SOURCE = "com.nicitaacom.androidgsm.action.SET_MIC_SOURCE"
        const val EXTRA_MIC_SOURCE = "mic_source"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            AppContextHolder.ctx = applicationContext
            MainActivity.log("GsmService: onCreate called")

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
                MainActivity.log("GsmService: Wake lock acquired")
            } catch (error: Exception) {
                MainActivity.log("WARNING: Could not acquire wake lock: ${error.message}")
            }

            // Unpack set_mixer_ctl native binary from assets on first run.
            // Required by RootUtils to write 2-slot BOOL mixer controls that tinymix
            // cannot set on MIUI sdm660 (broken mixer_ctl_get_array).
            try {
                val dest = java.io.File(filesDir, "set_mixer_ctl")
                if (!dest.exists()) {
                    assets.open("set_mixer_ctl").use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    dest.setExecutable(true, false)
                    MainActivity.log("✅ set_mixer_ctl unpacked to ${dest.absolutePath}")
                }
                RootUtils.nativeBinDir = filesDir.absolutePath
            } catch (e: Exception) {
                MainActivity.log("WARNING: Failed to unpack set_mixer_ctl: ${e.message}")
            }

            try {
                config = ConfigReader.readConfig(this)
                MainActivity.log("GsmService: Config loaded — ${config?.BACKEND_URL} device=${config?.DEVICE_TOKEN}")

                // PhoneStateListener: safety net for IDLE only (no audio-mode changes — those are
                // owned by the GsmDialer callbacks below to avoid fighting each other).
                setupPhoneStateListener()

                gsmDialer = GsmDialer(this)
                MainActivity.log("GsmService: GsmDialer initialized")

                // Single call-ended callback set once. Owns all teardown.
                gsmDialer?.setCallEndedCallback {
                    if (!isCallActive && audioWsHandler == null) return@setCallEndedCallback
                    teardownCall()
                }

                // Single call-connected callback set once. Owns all audio setup.
                // handleCallStarted must NOT override this — doing so per-call caused duplicate
                // teardown paths and triple CALL_ENDED events.
                gsmDialer?.setCallConnectedCallback {
                    isCallActive = true
                    MainActivity.log("📞 OFFHOOK — call connected, setting up audio")
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.mode = AudioManager.MODE_IN_CALL
                    am.isSpeakerphoneOn = true
                    val maxVoiceVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoiceVol, 0)

                    Thread {
                        try {
                            val captureEnabled = RootUtils.enableIncallMusicCapture()
                            MainActivity.log("📞 Incall capture path: $captureEnabled")
                            Thread.sleep(300)

                            val wsUrl = config?.BACKEND_URL?.replace("http://", "ws://")
                                ?.replace("https://", "wss://")?.removeSuffix("/") + "/ws/audio"
                            if (audioWsHandler == null) {
                                config?.let { c -> audioWsHandler = AudioWebSocketHandler(this@GsmService, c) { } }
                            }
                            audioWsHandler?.connect(wsUrl, config?.BACKEND_BEARER ?: "", config?.DEVICE_TOKEN ?: "")
                            Thread.sleep(300)
                            audioWsHandler?.setCallActive(true)
                            audioWsHandler?.startAudioCapture()
                            val amMute = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            amMute.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
                            RootUtils.mutePhoneSpeaker()
                            // Only enable injection if browser mic is the selected uplink source
                            if (AudioWebSocketHandler.useBrowserMicUplink) {
                                val injectionOk = RootUtils.enableIncallMusicInjection()
                                MainActivity.log("📞 Incall uplink injection enabled=$injectionOk")
                            } else {
                                MainActivity.log("📞 Phone mic selected — skipping uplink injection")
                            }
                            audioWsHandler?.startAudioPlayback()
                            cmdWsClient?.sendEvent("CALL_CONNECTED", emptyMap())
                            MainActivity.log("📞 CALL_CONNECTED sent to backend")
                        } catch (error: Exception) {
                            MainActivity.log("ERROR in CALL_CONNECTED callback: ${error.message}")
                        }
                    }.start()
                }

                ensureRealtimeClientsInitialized()
            } catch (error: Exception) {
                MainActivity.log("ERROR in GsmService.onCreate (config/dialer): ${error.message}")
                error.printStackTrace()
            }
        } catch (e: Exception) {
            MainActivity.log("FATAL: Uncaught exception in onCreate: ${e.message}")
            e.printStackTrace()
        }
    }

    // Single teardown path for call end — called from GsmDialer callback only.
    // PhoneStateListener IDLE is a safety net that calls this too, but only if still active.
    private fun teardownCall() {
        isCallActive = false
        AudioWebSocketHandler.useBrowserMicUplink = true  // reset for next call
        MainActivity.notifyMicSource(true)
        MainActivity.log("📴 Call ended (IDLE) — tearing down audio")
        try {
            RootUtils.disableIncallMusicCapture()
            RootUtils.disableIncallMusicInjection()
            RootUtils.unmutePhoneSpeaker()
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        try {
            audioWsHandler?.setCallActive(false)
            audioWsHandler?.stopAudioCapture()
            audioWsHandler?.stopAudioPlayback()
            audioWsHandler?.disconnect()
            audioWsHandler = null
        } catch (_: Exception) {}
        Thread {
            repeat(3) { attempt ->
                if (cmdWsClient?.isConnected == true) {
                    cmdWsClient?.sendEvent("CALL_ENDED", emptyMap())
                    MainActivity.log("📴 CALL_ENDED sent (attempt ${attempt + 1})")
                    return@Thread
                }
                MainActivity.log("📴 CALL_ENDED attempt ${attempt + 1} — WS not ready, retrying...")
                Thread.sleep(500)
            }
            cmdWsClient?.sendEvent("CALL_ENDED", emptyMap())
            MainActivity.log("📴 CALL_ENDED final attempt sent")
        }.start()
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
            MainActivity.log("WARNING: Foreground type denied (${error.message}); retrying without explicit type")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = createNotification()
            startForegroundSafely(notification)

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
                ACTION_SET_MIC_SOURCE -> {
                    val source = intent.getStringExtra(EXTRA_MIC_SOURCE) ?: "browser"
                    val useBrowser = source != "phone"
                    AudioWebSocketHandler.useBrowserMicUplink = useBrowser
                    if (isCallActive) {
                        Thread {
                            if (useBrowser) RootUtils.enableIncallMusicInjection()
                            else RootUtils.disableIncallMusicInjection()
                        }.start()
                    }
                    cmdWsClient?.sendEvent("MIC_SOURCE_CHANGED", mapOf("source" to source))
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
                val safeConfig = config ?: run {
                    MainActivity.log("WARNING: Config unavailable, skipping realtime init")
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
                                    MainActivity.log("GsmService: SIM_LIST sent: ${sims.size} accounts")
                                }
                                if (isServiceAudioActive) {
                                    // Reconnect: server lost in-memory state — re-announce without touching audio
                                    MainActivity.log("GsmService: WS reconnected, re-sending SERVICE_STARTED")
                                    cmdWsClient?.sendEvent("SERVICE_STARTED", emptyMap())
                                } else {
                                    startServiceDuplexOutputToServerAndServerToInput()
                                }
                            }
                        )
                        cmdWsClient?.connect()
                        MainActivity.log("GsmService: CommandWS connecting...")
                    }
                }

                if (audioWsHandler == null) {
                    audioWsHandler = AudioWebSocketHandler(this@GsmService, safeConfig) { _: ShortArray -> }
                }
                MainActivity.log("GsmService: realtime clients initialized")
            } catch (error: Exception) {
                MainActivity.log("WARNING: realtime init failed: ${error.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            audioWsHandler?.stopAudioCapture()
            audioWsHandler?.stopAudioPlayback()
            audioWsHandler?.disconnect()
            audioWsHandler = null
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
        MainActivity.log("GsmService: Destroyed")
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

    private fun startTestDuplexMicToServerAndServerToOutput() {
        if (isTestAudioActive) { MainActivity.log("Test audio already running"); return }
        if (isServiceAudioActive) {
            MainActivity.log("Stopping SERVICE audio before TEST start")
            stopServiceDuplexOutputToServerAndServerToInput()
        }
        isTestAudioActive = true
        MainActivity.setStatus("Status: Test Audio Active", true)

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
                val deadline = System.currentTimeMillis() + 5000
                while (cmdWsClient == null && System.currentTimeMillis() < deadline) {
                    MainActivity.log("⏳ Waiting for cmd WS to connect...")
                    Thread.sleep(300)
                }
                if (cmdWsClient == null) {
                    MainActivity.log("❌ Cmd WS not available after 5s - TEST AUDIO aborted")
                    isTestAudioActive = false
                    return@Thread
                }

                if (audioWsHandler == null) {
                    config?.let { safeConfig ->
                        audioWsHandler = AudioWebSocketHandler(this@GsmService, safeConfig) { _: ShortArray -> }
                    }
                }
                val ws = audioWsHandler ?: run { isTestAudioActive = false; return@Thread }
                val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://").removeSuffix("/") + "/ws/audio"
                ws.setCallActive(false)
                ws.connect(wsUrl, bearerToken, deviceToken)
                Thread.sleep(500)
                ws.startAudioPlayback()
                ws.startAudioCapture()
                cmdWsClient?.sendEvent("TEST_AUDIO_STARTED", emptyMap())
                MainActivity.log("✅ TEST mode active: duplex browser<->android over websocket")
            } catch (error: Exception) {
                MainActivity.log("ERROR starting test audio: ${error.message}")
                isTestAudioActive = false
            }
        }.start()
    }

    private fun stopTestDuplexMicToServerAndServerToOutput() {
        isTestAudioActive = false
        MainActivity.setStatus("Status: Ready", false)
        audioWsHandler?.stopAudioCapture()
        audioWsHandler?.stopAudioPlayback()
        audioWsHandler?.disconnect()
        audioWsHandler = null
        Thread { try { cmdWsClient?.sendEvent("TEST_AUDIO_STOPPED", emptyMap()) } catch (_: Exception) {} }.start()
        MainActivity.log("🛑 TEST stopped")
    }

    private fun startServiceDuplexOutputToServerAndServerToInput() {
        if (isServiceAudioActive) { MainActivity.log("SERVICE audio already running"); return }
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
        MainActivity.log("✅ SERVICE mode active: connected to backend, waiting for CALL_STARTED")
    }

    private fun stopServiceDuplexOutputToServerAndServerToInput() {
        isServiceAudioActive = false
        MainActivity.setStatus("Status: Ready", false)
        Thread { cmdWsClient?.sendEvent("SERVICE_STOPPED", emptyMap()) }.start()
        try {
            audioWsHandler?.stopAudioCapture()
            audioWsHandler?.stopAudioPlayback()
            audioWsHandler?.disconnect()
        } catch (error: Exception) {
            MainActivity.log("WARNING: error stopping SERVICE ws: ${error.message}")
        }
        audioWsHandler = null
        MainActivity.log("🛑 SERVICE stopped")
    }

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
                "AUDIO_CHUNK" -> { /* legacy path — audio flows over /ws/audio, not /ws/cmd */ }
                "SET_GAIN" -> {
                    val mic = (data["micGain"] as? Number)?.toFloat()
                    val playback = (data["playbackGain"] as? Number)?.toFloat()
                    if (mic != null) AudioWebSocketHandler.micGain = mic.coerceIn(0f, 4f)
                    if (playback != null) AudioWebSocketHandler.playbackGain = playback.coerceIn(0f, 4f)
                    MainActivity.log("🎚️ Gain: mic=${mic} playback=${playback}")
                }
                "SET_MIC_SOURCE" -> {
                    val source = data["source"] as? String
                    val useBrowser = source != "phone"
                    AudioWebSocketHandler.useBrowserMicUplink = useBrowser
                    MainActivity.notifyMicSource(useBrowser)
                    MainActivity.log("🎤 Mic source: ${if (useBrowser) "browser" else "phone"}")
                    if (isCallActive) {
                        Thread {
                            if (useBrowser) RootUtils.enableIncallMusicInjection()
                            else RootUtils.disableIncallMusicInjection()
                        }.start()
                    }
                    cmdWsClient?.sendEvent("MIC_SOURCE_CHANGED", mapOf("source" to if (useBrowser) "browser" else "phone"))
                }
                "START_SERVICE" -> {
                    ensureRealtimeClientsInitialized()
                    startServiceDuplexOutputToServerAndServerToInput()
                    MainActivity.notifyServiceActive(true)
                }
                "STOP_SERVICE" -> {
                    stopServiceDuplexOutputToServerAndServerToInput()
                    MainActivity.notifyServiceActive(false)
                }
                "START_TEST" -> {
                    ensureRealtimeClientsInitialized()
                    startTestDuplexMicToServerAndServerToOutput()
                    MainActivity.notifyTestActive(true)
                }
                "STOP_TEST" -> {
                    stopTestDuplexMicToServerAndServerToOutput()
                    MainActivity.notifyTestActive(false)
                }
                else -> MainActivity.log("Unhandled command: $type")
            }
        } catch (e: Exception) {
            MainActivity.log("FATAL: Uncaught exception in handleCommand: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleWsCommand(type: String, data: org.json.JSONObject) {
        val dataMap = mutableMapOf<String, Any>()
        data.keys().forEach { key -> dataMap[key] = data.get(key) }
        handleCommand(type, dataMap)
    }

    private fun handleCallStarted(data: Map<String, Any>) {
        try {
            val number = data["number"] as? String
            if (number.isNullOrBlank()) {
                MainActivity.log("CALL_STARTED ignored: missing number")
                return
            }
            val simAccountId = data["simAccountId"] as? String
            val simComponentName = data["simComponentName"] as? String
            MainActivity.log("Starting call to: $number (sim=$simAccountId)")
            RootUtils.mutePhoneSpeaker()
            isCallActive = true
            // GsmDialer callbacks (set once in onCreate) handle OFFHOOK and IDLE — do NOT
            // override setCallConnectedCallback here; doing so per-call created duplicate
            // teardown paths that fired triple CALL_ENDED events.
            try {
                val started = gsmDialer?.startCall(number, simAccountId, simComponentName) ?: false
                if (started) {
                    MainActivity.log("Call started via GsmDialer to $number")
                } else {
                    MainActivity.log("Call start failed - syncing CALL_ENDED state")
                    isCallActive = false
                    audioWsHandler?.disconnect()
                    cmdWsClient?.sendEvent("CALL_ENDED", emptyMap())
                }
            } catch (error: Exception) {
                MainActivity.log("ERROR starting call: ${error.message}")
                isCallActive = false
                audioWsHandler?.disconnect()
                cmdWsClient?.sendEvent("CALL_ENDED", emptyMap())
                error.printStackTrace()
            }
        } catch (e: Exception) {
            MainActivity.log("FATAL ERROR in handleCallStarted: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleCallEnded() {
        // Frontend requested hang-up. Call gsmDialer.endCall() to dismiss the call, then
        // let teardownCall() handle all audio/WS/event cleanup — same path as telephony IDLE.
        // Do NOT send CALL_ENDED here directly; teardownCall() does it, preventing double-send.
        try {
            MainActivity.log("Ending call (frontend request)")
            Thread {
                try {
                    gsmDialer?.endCall()
                    // teardownCall fires via GsmDialer's IDLE callback once the modem confirms.
                    // If for some reason the callback doesn't fire within 3s, the PhoneStateListener
                    // safety net will invoke teardownCall anyway.
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
            val digit = (data["digit"] as? String).orEmpty()
            if (digit.isEmpty()) { MainActivity.log("SEND_DTMF ignored: missing digit"); return }
            val call = GsmInCallService.currentCall
            if (call == null) {
                MainActivity.log("SEND_DTMF $digit ignored: no Call (app must be default dialer + call must be active)")
                cmdWsClient?.sendEvent("DTMF_FAILED", mapOf("digit" to digit, "reason" to "no_call"))
                return
            }
            val c = digit[0]
            MainActivity.log("Sending DTMF $c via Call.playDtmfTone() (out-of-band → modem)")
            call.playDtmfTone(c)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { call.stopDtmfTone() } catch (_: Exception) {}
            }, 200)
            cmdWsClient?.sendEvent("DTMF_SENT", mapOf("digit" to digit))
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
                        // OFFHOOK: do NOT touch audio mode here — GsmDialer's setCallConnectedCallback
                        // owns MODE_IN_CALL + speakerphone setup. Overriding it here caused the
                        // speakerphone to be flipped off, silencing GSM audio capture.
                        TelephonyManager.CALL_STATE_IDLE -> {
                            MainActivity.log("PhoneStateListener: IDLE")
                            // Safety net: if GsmDialer callback didn't fire (edge case), clean up.
                            if (isCallActive || audioWsHandler != null) {
                                MainActivity.log("PhoneStateListener: IDLE safety net — tearing down call")
                                teardownCall()
                            }
                        }
                        TelephonyManager.CALL_STATE_RINGING -> MainActivity.log("PhoneStateListener: RINGING")
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
            }
        } catch (_: Exception) {}
    }

}
