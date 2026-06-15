package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioManager
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

// Mutable holder so CallController can create/null the handler across threads without
// passing it back to GsmService on every call.
class AudioWsHandlerRef {
    var handler: AudioWebSocketHandler? = null
}

/**
 * Owns all call lifecycle logic extracted from GsmService:
 *  - isCallActive flag
 *  - setCallConnectedCallback body (audio setup on OFFHOOK)
 *  - teardownCall()
 *  - handleCallStarted()
 *  - handleCallEnded()
 *  - handleSendDtmf()
 *
 * @param context          Application/service context for AudioManager.
 * @param config           App configuration (backend URL, tokens).
 * @param cmdWsClient      Lambda returning the current CommandWebSocketClient (may be null).
 * @param audioWsHandlerRef Mutable holder for the AudioWebSocketHandler instance.
 * @param onCallActiveChanged Notified whenever isCallActive changes so GsmService can gate
 *                           SET_MIC_SOURCE injection toggling.
 * @param log              GsmLogger — never call MainActivity.log directly inside this class.
 */
class CallController(
    private val context: Context,
    private val config: Config,
    private val cmdWsClient: () -> CommandWebSocketClient?,
    private val audioWsHandlerRef: AudioWsHandlerRef,
    private val onCallActiveChanged: (Boolean) -> Unit,
    private val onMicSourceReset: () -> Unit,
    private val log: GsmLogger
) {
    @Volatile var isCallActive: Boolean = false
        private set

    // Guards the call-connected audio setup so it runs exactly once per call, no matter which
    // "remote answered" signal arrives first: TelephonyManager OFFHOOK (fires for most numbers)
    // or Telecom Call.STATE_ACTIVE (the only signal that fires for MIUI service numbers like
    // 3311, where OFFHOOK never comes). Reset at the start of each call + on teardown.
    private val callConnectedHandled = AtomicBoolean(false)

    // Called from GsmService.onCreate to wire up the dialer + InCallService callbacks.
    fun wireDialerCallbacks(gsmDialer: GsmDialer) {
        // Single call-ended callback set once. Owns all teardown.
        gsmDialer.setCallEndedCallback {
            if (!isCallActive && audioWsHandlerRef.handler == null) return@setCallEndedCallback
            teardownCall()
        }

        // Both "remote answered" signals route into onCallConnected(), which self-guards against
        // running twice. OFFHOOK alone is unreliable on this MIUI build for some numbers, so
        // STATE_ACTIVE (delivered via GsmInCallService) is wired as the authoritative fallback.
        gsmDialer.setCallConnectedCallback { onCallConnected("OFFHOOK") }
        GsmInCallService.onCallActive = { onCallConnected("STATE_ACTIVE") }

        // Authoritative "call ended" signal. Telecom's onCallRemoved fires the INSTANT the call
        // ends — far faster/more reliable than MIUI's PhoneStateListener IDLE (which can lag ~10s
        // on this build, leaving downlink audio playing after hang-up). teardownCall() self-guards
        // against double-run, so the later IDLE event is a harmless no-op.
        GsmInCallService.onCallEnded = {
            if (isCallActive || audioWsHandlerRef.handler != null) {
                log("📴 Call removed (Telecom) — tearing down audio immediately")
                teardownCall()
            }
        }
    }

    // Sets up call audio exactly once per call, triggered by whichever remote-answered signal
    // fires first (OFFHOOK or STATE_ACTIVE). Later signals are ignored via callConnectedHandled.
    // handleCallStarted must NOT re-wire this — doing setup per-call caused duplicate teardown
    // paths and triple CALL_ENDED events.
    private fun onCallConnected(source: String) {
        if (!callConnectedHandled.compareAndSet(false, true)) {
            log("📞 Call-connected ($source) ignored — audio already set up this call")
            return
        }
        setCallActive(true)
        log("📞 $source — call connected, setting up audio")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_CALL
        // Do NOT force isSpeakerphoneOn=true here — it overrides the HAL's USB-C mic routing.
        // tinycap reads ALSA card 0 device 0 directly and doesn't need speakerphone at Java level.
        val maxVoiceVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoiceVol, 0)

        Thread {
            try {
                // Remote answered — stop the website ringback tone; real downlink audio takes over.
                audioWsHandlerRef.handler?.stopRingback()

                val captureEnabled = RootUtils.enableIncallMusicCapture()
                log("📞 Incall capture path: $captureEnabled")
                Thread.sleep(300)

                val wsUrl = audioWsUrl()

                // The audio WS was already opened in handleCallStarted (for ringback). Reuse it
                // if still connected — avoids a reconnect gap. Only create fresh if missing/dead.
                if (audioWsHandlerRef.handler?.isWsConnected != true) {
                    audioWsHandlerRef.handler?.disconnect()
                    audioWsHandlerRef.handler = AudioWebSocketHandler(context, config, log = log) { }
                    audioWsHandlerRef.handler?.connect(wsUrl, config.BACKEND_BEARER ?: "", config.DEVICE_TOKEN ?: "")
                }

                // Wait for WS to actually open before starting capture — blind sleep(300) was
                // not enough on slow networks and caused all tinycap chunks to be silently dropped.
                val wsDeadline = System.currentTimeMillis() + 5000
                while (audioWsHandlerRef.handler?.isWsConnected != true && System.currentTimeMillis() < wsDeadline) {
                    Thread.sleep(100)
                }
                if (audioWsHandlerRef.handler?.isWsConnected != true) {
                    log("❌ Audio WS failed to connect after 5s — call audio will not work")
                } else {
                    log("✅ Audio WS connected")
                }

                audioWsHandlerRef.handler?.setCallActive(true)
                audioWsHandlerRef.handler?.startAudioCapture()
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
                RootUtils.mutePhoneSpeaker()
                // Uplink injection via Incall_Music mixer is NOT used — MIUI CAF kernel
                // blocks slot 1 (VoiceMMode2) ELEM_WRITE silently. Instead, startAudioPlayback()
                // writes browser mic PCM directly to /dev/snd/pcmC0D19p (VoiceMMode2 TX PCM device).
                audioWsHandlerRef.handler?.startAudioPlayback()
                cmdWsClient()?.sendEvent("CALL_CONNECTED", emptyMap())
                log("📞 CALL_CONNECTED sent to backend")
            } catch (error: Exception) {
                log("ERROR in CALL_CONNECTED callback: ${error.message}")
            }
        }.start()
    }

    // Single teardown path for call end — called from GsmDialer callback only.
    // PhoneStateListener IDLE is a safety net that calls this too, but only if still active.
    // Marked internal so GsmService can call it from the PhoneStateListener safety net.
    internal fun teardownCall() {
        setCallActive(false)
        callConnectedHandled.set(false)  // clear so the next call's connect signal is honored
        AudioWebSocketHandler.useBrowserMicUplink = false  // reset to working PHONE/headset uplink
        onMicSourceReset()
        log("📴 Call ended — tearing down audio")
        try {
            RootUtils.disableIncallMusicCapture()
            RootUtils.disableIncallMusicInjection()
            RootUtils.unmutePhoneSpeaker()
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        try {
            audioWsHandlerRef.handler?.setCallActive(false)
            audioWsHandlerRef.handler?.stopAudioCapture()
            audioWsHandlerRef.handler?.stopAudioPlayback()
            audioWsHandlerRef.handler?.disconnect()
            audioWsHandlerRef.handler = null
        } catch (_: Exception) {}
        Thread {
            repeat(3) { attempt ->
                val client = cmdWsClient()
                if (client?.isConnected == true) {
                    client.sendEvent("CALL_ENDED", emptyMap())
                    log("📴 CALL_ENDED sent (attempt ${attempt + 1})")
                    return@Thread
                }
                log("📴 CALL_ENDED attempt ${attempt + 1} — WS not ready, retrying...")
                Thread.sleep(500)
            }
            cmdWsClient()?.sendEvent("CALL_ENDED", emptyMap())
            log("📴 CALL_ENDED final attempt sent")
        }.start()
    }

    fun handleCallStarted(data: Map<String, Any>, gsmDialer: GsmDialer?) {
        try {
            val number = data["number"] as? String
            if (number.isNullOrBlank()) {
                log("CALL_STARTED ignored: missing number")
                return
            }
            val simAccountId = data["simAccountId"] as? String
            val simComponentName = data["simComponentName"] as? String
            log("Starting call to: $number (sim=$simAccountId)")
            RootUtils.mutePhoneSpeaker()
            callConnectedHandled.set(false)  // arm for this call's OFFHOOK / STATE_ACTIVE signal
            setCallActive(true)
            // GsmDialer callbacks (set once in wireDialerCallbacks) handle OFFHOOK and IDLE — do NOT
            // override setCallConnectedCallback here; doing so per-call created duplicate
            // teardown paths that fired triple CALL_ENDED events.
            try {
                val started = gsmDialer?.startCall(number, simAccountId, simComponentName) ?: false
                if (started) {
                    log("Call started via GsmDialer to $number")
                    // Open the audio WS now (before OFFHOOK) and stream a ringback tone to the
                    // website while dialing. Phone plays nothing — the tone is generated as PCM
                    // and sent over /ws/audio, exactly like downlink call audio.
                    startRingbackToWebsite()
                } else {
                    log("Call start failed - syncing CALL_ENDED state")
                    setCallActive(false)
                    audioWsHandlerRef.handler?.disconnect()
                    cmdWsClient()?.sendEvent("CALL_ENDED", emptyMap())
                }
            } catch (error: Exception) {
                log("ERROR starting call: ${error.message}")
                setCallActive(false)
                audioWsHandlerRef.handler?.disconnect()
                cmdWsClient()?.sendEvent("CALL_ENDED", emptyMap())
                error.printStackTrace()
            }
        } catch (e: Exception) {
            log("FATAL ERROR in handleCallStarted: ${e.message}")
            e.printStackTrace()
        }
    }

    // Opens the audio WS (if needed) during dialing and streams a ringback tone to the website.
    // Runs on its own thread so it doesn't block command handling. OFFHOOK stops the ringback;
    // teardownCall() disconnects the handler if the call never connects.
    private fun startRingbackToWebsite() {
        Thread {
            try {
                val wsUrl = audioWsUrl()

                audioWsHandlerRef.handler?.disconnect()
                val handler = AudioWebSocketHandler(context, config, log = log) { }
                audioWsHandlerRef.handler = handler
                handler.connect(wsUrl, config.BACKEND_BEARER ?: "", config.DEVICE_TOKEN ?: "")

                val deadline = System.currentTimeMillis() + 5000
                while (handler.isWsConnected != true && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                }
                // Only start the tone if the call hasn't already connected (race with fast OFFHOOK).
                if (handler.isWsConnected && isCallActive) {
                    handler.startRingback()
                } else {
                    log("⚠️ Ringback skipped (wsConnected=${handler.isWsConnected}, callActive=$isCallActive)")
                }
            } catch (e: Exception) {
                log("ERROR starting ringback to website: ${e.message}")
            }
        }.start()
    }

    fun handleCallEnded(gsmDialer: GsmDialer?) {
        // Frontend requested hang-up. Call gsmDialer.endCall() to dismiss the call, then
        // let teardownCall() handle all audio/WS/event cleanup — same path as telephony IDLE.
        // Do NOT send CALL_ENDED here directly; teardownCall() does it, preventing double-send.
        try {
            log("Ending call (frontend request)")
            Thread {
                try {
                    gsmDialer?.endCall()
                    // teardownCall fires via GsmDialer's IDLE callback once the modem confirms.
                    // If for some reason the callback doesn't fire within 3s, the PhoneStateListener
                    // safety net will invoke teardownCall anyway.
                } catch (error: Exception) {
                    log("ERROR in handleCallEnded thread: ${error.message}")
                }
            }.start()
        } catch (error: Exception) {
            log("ERROR in handleCallEnded: ${error.message}")
        }
    }

    fun handleSendDtmf(data: Map<String, Any>) {
        try {
            val digit = (data["digit"] as? String).orEmpty()
            if (digit.isEmpty()) { log("SEND_DTMF ignored: missing digit"); return }
            val call = GsmInCallService.currentCall
            if (call == null) {
                log("SEND_DTMF $digit ignored: no Call (app must be default dialer + call must be active)")
                cmdWsClient()?.sendEvent("DTMF_FAILED", mapOf("digit" to digit, "reason" to "no_call"))
                return
            }
            val c = digit[0]
            log("Sending DTMF $c via Call.playDtmfTone() (out-of-band → modem)")
            call.playDtmfTone(c)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { call.stopDtmfTone() } catch (_: Exception) {}
            }, 200)
            cmdWsClient()?.sendEvent("DTMF_SENT", mapOf("digit" to digit))
        } catch (e: Exception) {
            log("ERROR in handleSendDtmf: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setCallActive(active: Boolean) {
        isCallActive = active
        onCallActiveChanged(active)
    }

    private fun audioWsUrl(): String =
        config.BACKEND_URL
            ?.replace("http://", "ws://")
            ?.replace("https://", "wss://")
            ?.removeSuffix("/") + "/ws/audio"
}
