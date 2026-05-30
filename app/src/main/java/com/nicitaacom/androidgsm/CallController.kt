package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioManager

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

    // Called from GsmService.onCreate to wire up the two GsmDialer callbacks.
    fun wireDialerCallbacks(gsmDialer: GsmDialer) {
        // Single call-ended callback set once. Owns all teardown.
        gsmDialer.setCallEndedCallback {
            if (!isCallActive && audioWsHandlerRef.handler == null) return@setCallEndedCallback
            teardownCall()
        }

        // Single call-connected callback set once. Owns all audio setup.
        // handleCallStarted must NOT override this — doing so per-call caused duplicate
        // teardown paths and triple CALL_ENDED events.
        gsmDialer.setCallConnectedCallback {
            setCallActive(true)
            log("📞 OFFHOOK — call connected, setting up audio")
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_CALL
            am.isSpeakerphoneOn = true
            val maxVoiceVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVoiceVol, 0)

            Thread {
                try {
                    val captureEnabled = RootUtils.enableIncallMusicCapture()
                    log("📞 Incall capture path: $captureEnabled")
                    Thread.sleep(300)

                    val wsUrl = config.BACKEND_URL
                        ?.replace("http://", "ws://")
                        ?.replace("https://", "wss://")
                        ?.removeSuffix("/") + "/ws/audio"

                    // Always create a fresh handler for calls — the pre-init handler from
                    // ensureRealtimeClientsInitialized has no active WS and wrong callActive state.
                    audioWsHandlerRef.handler?.disconnect()
                    audioWsHandlerRef.handler = AudioWebSocketHandler(context, config, log = log) { }
                    audioWsHandlerRef.handler?.connect(wsUrl, config.BACKEND_BEARER ?: "", config.DEVICE_TOKEN ?: "")

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
                    val amMute = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    amMute.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
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
    }

    // Single teardown path for call end — called from GsmDialer callback only.
    // PhoneStateListener IDLE is a safety net that calls this too, but only if still active.
    // Marked internal so GsmService can call it from the PhoneStateListener safety net.
    internal fun teardownCall() {
        setCallActive(false)
        AudioWebSocketHandler.useBrowserMicUplink = true  // reset for next call
        onMicSourceReset()
        log("📴 Call ended (IDLE) — tearing down audio")
        try {
            RootUtils.disableIncallMusicCapture()
            RootUtils.disableIncallMusicInjection()
            RootUtils.unmutePhoneSpeaker()
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
            setCallActive(true)
            // GsmDialer callbacks (set once in wireDialerCallbacks) handle OFFHOOK and IDLE — do NOT
            // override setCallConnectedCallback here; doing so per-call created duplicate
            // teardown paths that fired triple CALL_ENDED events.
            try {
                val started = gsmDialer?.startCall(number, simAccountId, simComponentName) ?: false
                if (started) {
                    log("Call started via GsmDialer to $number")
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
}
