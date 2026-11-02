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

    // Browser-driven calls (CALL_STARTED over /ws/cmd) keep the phone itself quiet — the operator
    // listens in the browser (see applyPhoneAudibility). A call dialed or answered on the phone
    // stays audible like in any dialer. Set in handleCallStarted, cleared in teardownCall.
    @Volatile private var browserDrivenCall = false

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // Start / end cues (res/raw/start_call.mp3, end_call.mp3). Owned here, not by GsmInCallService,
    // because a browser-driven call has EAR_S/SPK muted + voice volume 0 from dial time and only
    // this class may lift that around a clip — a clip started into the mute is just a click.
    private val callSounds = CallSounds(context, log)

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
        GsmInCallService.onSpeakerToggled = { onPhoneSpeakerToggled() }
        GsmInCallService.onCallDisconnected = { onCallDisconnected() }

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
        val am = audioManager
        am.mode = AudioManager.MODE_IN_CALL
        // Do NOT force isSpeakerphoneOn=true here — it overrides the HAL's USB-C mic routing.
        // tinycap reads ALSA card 0 device 0 directly and doesn't need speakerphone at Java level.

        Thread {
            try {
                // Remote answered — stop the website ringback tone; real downlink audio takes over.
                audioWsHandlerRef.handler?.stopRingback()

                // "Call started" cue on the phone. A browser-driven call has had the EAR_S/SPK mixer
                // muted since handleCallStarted — make the phone audible (max voice volume + mixer),
                // let the whole clip play out, then mute the mixer again so the phone on the desk
                // stays quiet (applyPhoneAudibility() settles volume + mixer below). A manual call
                // keeps whatever volume the operator set. Blocking on the clip's own completion, not
                // a fixed sleep: in-call playback starts late on this HAL and flipping the mixer
                // mid-clip chopped the cue to a click.
                if (browserDrivenCall) setPhoneAudible(true)
                callSounds.playBlocking(R.raw.start_call)
                if (browserDrivenCall) RootUtils.mutePhoneSpeaker()

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
                applyPhoneAudibility()
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
        // Cleared so the next call's connect signal is honored; the old value says whether this
        // call ever connected (busy / no answer never did).
        val wasConnected = callConnectedHandled.getAndSet(false)
        val wasBrowserDriven = browserDrivenCall
        browserDrivenCall = false
        AudioWebSocketHandler.useBrowserMicUplink = false  // reset to working PHONE/headset uplink
        onMicSourceReset()
        log("📴 Call ended — tearing down audio")
        try {
            val am = audioManager
            // Browser-driven call: the phone was quiet (mixer muted, voice volume 0), so make it
            // audible FIRST and only then start the "call ended" cue. Started from STATE_DISCONNECTED
            // into the mute (the old way) only its tail came through after the unmute — a click.
            // The clip sounds while the capture teardown below runs; awaited before the mode reset
            // so the route change does not cut it. A manual call keeps the operator's volume (the
            // unmute is an idempotent safety net) and got its cue in onCallDisconnected() already.
            // Like the start cue, only for a call that actually connected — busy / no answer gets
            // no "ended" without a "started".
            if (wasBrowserDriven) setPhoneAudible(true) else RootUtils.unmutePhoneSpeaker()
            if (wasBrowserDriven && wasConnected) callSounds.play(R.raw.end_call)
            RootUtils.disableIncallMusicCapture()
            RootUtils.disableIncallMusicInjection()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            // Every call: a manual call's cue (started in onCallDisconnected) may still be sounding
            // too, and the mode reset below would re-route it mid-clip. No-op when nothing plays.
            callSounds.awaitFinished()
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
            browserDrivenCall = true
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
                    abortBrowserCall("Call start failed - syncing CALL_ENDED state")
                }
            } catch (error: Exception) {
                abortBrowserCall("ERROR starting call: ${error.message}")
                error.printStackTrace()
            }
        } catch (e: Exception) {
            log("FATAL ERROR in handleCallStarted: ${e.message}")
            e.printStackTrace()
        }
    }

    // A browser dial that never left the phone: undo handleCallStarted's pre-dial state and tell the
    // backend. Not teardownCall() — no audio path was opened yet. The mixer unmute matters: a manual
    // call stays audible by design now, so it must not inherit the EAR_S/SPK mute armed for this one.
    private fun abortBrowserCall(reason: String) {
        log(reason)
        browserDrivenCall = false
        setCallActive(false)
        RootUtils.unmutePhoneSpeaker()
        audioWsHandlerRef.handler?.disconnect()
        cmdWsClient()?.sendEvent("CALL_ENDED", emptyMap())
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

    // Voice volume and the EAR_S/SPK mixer together: that pair is what "the phone is audible" means
    // for the gateway. Blocks on su — call from a background thread.
    private fun setPhoneAudible(audible: Boolean) {
        val am = audioManager
        val volume = if (audible) am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) else 0
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, volume, 0)
        if (audible) RootUtils.unmutePhoneSpeaker() else RootUtils.mutePhoneSpeaker()
    }

    // Phone-side audibility of the live call. Only a browser-driven call is touched: quiet ("audio
    // to frontend only") unless the in-call screen's loudspeaker is on — read from Telecom's real
    // route, not a mirror flag. A manual call is left alone — Telecom's own route handling is the
    // whole job there. Blocks on su — call from a background thread.
    private fun applyPhoneAudibility() {
        if (!browserDrivenCall) return
        val speakerOn = GsmInCallService.isSpeakerOn
        setPhoneAudible(speakerOn)
        log(if (speakerOn) "🔊 Phone audible — loudspeaker on during a browser-driven call"
            else "🔇 Phone quiet — browser-driven call, audio to frontend only")
    }

    // Wired to GsmInCallService.onCallDisconnected (Telecom STATE_DISCONNECTED, just before
    // onCallRemoved). A manual call is audible as-is, so its "call ended" cue plays right here,
    // still on the call route like any dialer's end tone. A browser-driven call is muted at this
    // point — its cue plays in teardownCall() once the phone is unmuted. A call that never
    // connected (missed or declined incoming) gets no cue: no "started" means no "ended".
    private fun onCallDisconnected() {
        if (browserDrivenCall || !callConnectedHandled.get()) return
        callSounds.play(R.raw.end_call)
    }

    // Wired to GsmInCallService.onSpeakerToggled — the route itself is already switched by Telecom.
    private fun onPhoneSpeakerToggled() {
        if (!browserDrivenCall) return
        Thread {
            try {
                applyPhoneAudibility()
            } catch (e: Exception) {
                log("ERROR applying loudspeaker toggle: ${e.message}")
            }
        }.start()
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
