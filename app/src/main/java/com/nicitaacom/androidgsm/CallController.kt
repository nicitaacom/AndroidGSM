package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.telecom.CallAudioState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

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

    // Guards the call audio setup so it happens once per call, whichever signal comes first:
    // TelephonyManager OFFHOOK (the dial itself for an outgoing call - Android reports OFFHOOK the
    // moment a call is dialing, not when it is answered; never at all for MIUI service numbers like
    // 3311) or Telecom Call.STATE_ACTIVE (the answer). Reset at the start of each call + on teardown.
    private val callConnectedHandled = AtomicBoolean(false)

    // CALL_CONNECTED to the browser goes out on STATE_ACTIVE only - it is the website's "answered"
    // (its call timer, its mic, the music panel's "keep music on answer at", the end of its ringing
    // gain). Sent on OFFHOOK it lit "answered" at the dial, so the website's ringing phase was 0 s
    // long (2026-11-02: "ringing while music plays does nothing", the music paused at the dial).
    private val callAnsweredSent = AtomicBoolean(false)

    // Browser-driven calls (CALL_STARTED over /ws/cmd) keep the phone itself quiet — the operator
    // listens in the browser (see applyPhoneAudibility). A call dialed or answered on the phone
    // stays audible like in any dialer. Set in handleCallStarted, cleared in teardownCall.
    @Volatile private var browserDrivenCall = false

    // The operator's voice-call volume at dial time, handed back after the call: a browser-driven
    // call drives that volume itself (the floor while the phone is quiet, max around a cue).
    // -1 = nothing captured (manual call).
    @Volatile private var voiceVolumeBeforeCall = -1

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val maxVoiceVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)

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

        // Both signals route into onCallConnected(), which self-guards against a second pass. OFFHOOK
        // comes at the dial of an outgoing call (and never for some MIUI numbers), STATE_ACTIVE at the
        // answer: the audio setup takes the first of the two, CALL_CONNECTED waits for the answer.
        gsmDialer.setCallConnectedCallback { onCallConnected(SOURCE_OFFHOOK) }
        GsmInCallService.onCallActive = { onCallConnected(SOURCE_ANSWERED) }
        GsmInCallService.onAudioRouteChanged = { onPhoneRouteChanged() }
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

    // Sets up call audio exactly once per call, triggered by whichever signal fires first (OFFHOOK at
    // the dial, or STATE_ACTIVE at the answer). A later signal only gets to send CALL_CONNECTED, the
    // answer. handleCallStarted must NOT re-wire this — doing setup per-call caused duplicate teardown
    // paths and triple CALL_ENDED events.
    private fun onCallConnected(source: String) {
        if (!callConnectedHandled.compareAndSet(false, true)) {
            log("📞 $source — audio already set up this call")
            sendCallConnectedIfAnswered(source)
            return
        }
        setCallActive(true)
        log("📞 $source — setting up the call audio")
        val am = audioManager
        am.mode = AudioManager.MODE_IN_CALL
        // Do NOT force isSpeakerphoneOn=true here — it overrides the HAL's USB-C mic routing.
        // tinycap reads ALSA card 0 device 0 directly and doesn't need speakerphone at Java level.

        Thread {
            try {
                // Remote answered — stop the website ringback tone; real downlink audio takes over.
                audioWsHandlerRef.handler?.stopRingback()

                // "Call started" cue on the phone. A browser-driven call has been quiet since
                // handleCallStarted — make the phone audible (max voice volume + mixer), let the
                // whole clip play out, then mute the mixer again so the phone on the desk stays
                // quiet (applyPhoneAudibility() settles volume + mixer below). A manual call keeps
                // whatever volume the operator set. Blocking on the clip's own completion, not a
                // fixed sleep: in-call playback starts late on this HAL and flipping the mixer
                // mid-clip chopped the cue to a click.
                if (browserDrivenCall) liftQuietState()
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
                sendCallConnectedIfAnswered(source)
            } catch (error: Exception) {
                log("ERROR in CALL_CONNECTED callback: ${error.message}")
            }
        }.start()
    }

    // The browser's "answered" - once per call, and only from Telecom's STATE_ACTIVE: OFFHOOK is the dial.
    private fun sendCallConnectedIfAnswered(source: String) {
        if (source != SOURCE_ANSWERED) return
        if (!callAnsweredSent.compareAndSet(false, true)) return
        cmdWsClient()?.sendEvent("CALL_CONNECTED", emptyMap())
        log("📞 CALL_CONNECTED sent to backend ($source)")
    }

    // Single teardown path for call end — called from GsmDialer callback only.
    // PhoneStateListener IDLE is a safety net that calls this too, but only if still active.
    // Marked internal so GsmService can call it from the PhoneStateListener safety net.
    internal fun teardownCall() {
        setCallActive(false)
        // Cleared so the next call's connect signal is honored; the old value says whether this
        // call ever connected (busy / no answer never did).
        val wasConnected = callConnectedHandled.getAndSet(false)
        callAnsweredSent.set(false)
        val wasBrowserDriven = browserDrivenCall
        browserDrivenCall = false
        AudioWebSocketHandler.useBrowserMicUplink = false  // reset to working PHONE/headset uplink
        onMicSourceReset()
        log("📴 Call ended — tearing down audio")
        try {
            val am = audioManager
            // Browser-driven call: the phone was quiet (mixer muted, voice volume at the floor), so
            // make it audible FIRST and only then start the "call ended" cue. Started from
            // STATE_DISCONNECTED into the mute (the old way) only its tail came through after the
            // unmute — a click. The clip sounds while the capture teardown below runs; awaited before
            // the mode reset so the route change does not cut it. A manual call keeps the operator's
            // volume (the unmute is an idempotent safety net) and got its cue in onCallDisconnected()
            // already. Like the start cue, only for a call that actually connected — busy / no
            // answer gets no "ended" without a "started".
            if (wasBrowserDriven) liftQuietState() else RootUtils.unmutePhoneSpeaker()
            if (wasBrowserDriven && wasConnected) callSounds.play(R.raw.end_call)
            RootUtils.disableIncallMusicCapture()
            RootUtils.disableIncallMusicInjection()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            // Every call: a manual call's cue (started in onCallDisconnected) may still be sounding
            // too, and the mode reset below would re-route it mid-clip. No-op when nothing plays.
            callSounds.awaitFinished()
            // The cue is over — the operator's own voice volume comes back, not the cue's max, so
            // the next call (manual, or this gateway's next ring) does not start at full blast.
            restoreVoiceVolume()
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
            voiceVolumeBeforeCall = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            // Quiet from before the dial. Not enough on its own: the HAL rewrites the EAR_S/SPK mixer
            // when it brings up the voice path for the route a moment later, which is how the phone
            // rang along with the browser for the whole ringing phase — onPhoneRouteChanged() applies
            // the state again once Telecom has settled that route.
            setPhoneQuiet()
            callConnectedHandled.set(false)  // arm for this call's OFFHOOK / STATE_ACTIVE signal
            callAnsweredSent.set(false)
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
    // backend. Not teardownCall() — no audio path was opened yet. The unmute + volume matter: a
    // manual call stays audible by design now, so it must not inherit the quiet state armed here.
    private fun abortBrowserCall(reason: String) {
        log(reason)
        browserDrivenCall = false
        setCallActive(false)
        RootUtils.unmutePhoneSpeaker()
        restoreVoiceVolume()
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

    private fun setVoiceVolume(level: Int) =
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, level, 0)

    // Voice volume and the EAR_S/SPK mixer together: that pair is what "the phone is quiet" means
    // for the gateway. Blocks on su — call from a background thread.
    private fun setPhoneQuiet() {
        setVoiceVolume(0)
        RootUtils.mutePhoneSpeaker()
    }

    // The opposite, whatever the route — around a cue, and before the volume is handed back.
    private fun liftQuietState() {
        setVoiceVolume(maxVoiceVolume)
        RootUtils.unmutePhoneSpeaker()
    }

    // The song during a call: AudioService keeps a music level per output, and the call link's own entry was
    // 10 of 15 - some 14 dB under the call itself, so the music came through quiet at "100%" (2026-11-02:
    // "its volume is lower even if I have 100%"). Max here, so the browser's percent is the only control and
    // 100% is as loud as the call. Written while the call link is the music's output, it is that link's entry
    // only - the music link between calls keeps the level the operator set on the phone.
    private fun liftMusicOnCallLink() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
    }

    private fun restoreVoiceVolume() {
        if (voiceVolumeBeforeCall < 0) return
        setVoiceVolume(voiceVolumeBeforeCall)
        voiceVolumeBeforeCall = -1
    }

    // SET_GAIN playbackGain (the website's slider, 0..4, 0.7 by default) as a voice volume step: 1.0 and above
    // is the max, never below step 1 so a headset is not left silent by a slider at 0.
    private fun headsetVoiceVolume(playbackGain: Float): Int =
        (maxVoiceVolume * playbackGain.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(1)

    // Wired to GsmService's SET_GAIN: the website moved "Playback Vol", or its ringing percent kicked in, during a
    // browser-driven call - the headset level follows at once. Blocks on su - its own thread.
    fun onPlaybackGainChanged() {
        if (!browserDrivenCall) return
        Thread {
            try {
                applyPhoneAudibility()
            } catch (e: Exception) {
                log("ERROR applying the playback gain: ${e.message}")
            }
        }.start()
    }

    // Phone-side audibility of a browser-driven call, from Telecom's real route, not a mirror flag:
    //  - loudspeaker: the operator turned it on — fully audible
    //  - a headset paired to the phone (Bluetooth / wired): it carries the call at the website's
    //    "Playback Vol", the phone's earpiece + loudspeaker stay muted
    //  - earpiece: quiet, audio to the frontend only
    // A manual call is left alone — Telecom's own route handling is the whole job there. Blocks on
    // su — call from a background thread.
    private fun applyPhoneAudibility() {
        if (!browserDrivenCall) return
        when (GsmInCallService.currentAudioState?.route) {
            CallAudioState.ROUTE_SPEAKER -> {
                liftQuietState()
                log("🔊 Phone audible — loudspeaker on during a browser-driven call")
            }
            CallAudioState.ROUTE_BLUETOOTH, CallAudioState.ROUTE_WIRED_HEADSET -> {
                // the headset's call volume follows the website's "Playback Vol" (SET_GAIN playbackGain); while
                // it rings with music on, the website lowers that gain to its "ringing while music plays" percent
                val level = headsetVoiceVolume(AudioWebSocketHandler.playbackGain)
                setVoiceVolume(level)
                liftMusicOnCallLink()
                RootUtils.mutePhoneSpeaker()
                log("🎧 Headset carries the browser-driven call at voice volume $level/$maxVoiceVolume — phone itself quiet")
            }
            else -> {
                setPhoneQuiet()
                log("🔇 Phone quiet — browser-driven call, audio to frontend only")
            }
        }
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

    // Wired to GsmInCallService.onAudioRouteChanged — the first route of the call (what it rings on)
    // and every switch after it (loudspeaker toggle, headset plugged in). Telecom has already switched
    // the route; the HAL then rewrites its own EAR_S/SPK mixer path for the new device a moment
    // later, which would undo a state applied at once — so it is applied after that window, and once
    // more in case the HAL was slower. Each pass reads the live route, so a quick on-off-on settles
    // on the real one.
    private fun onPhoneRouteChanged() {
        if (!browserDrivenCall) return
        Thread {
            try {
                for (delayMs in AUDIBILITY_REAPPLY_DELAYS_MS) {
                    Thread.sleep(delayMs)
                    if (!browserDrivenCall) return@Thread
                    applyPhoneAudibility()
                }
            } catch (e: Exception) {
                log("ERROR applying the call route: ${e.message}")
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

    companion object {
        // Waits between a route change and the audibility passes (see onPhoneRouteChanged): 0.5 s
        // clears the HAL's own mixer write for the new device, the second pass at 2 s is the margin.
        private val AUDIBILITY_REAPPLY_DELAYS_MS = longArrayOf(500, 1500)
        // the two "call audio" signals - see callConnectedHandled / callAnsweredSent
        private const val SOURCE_OFFHOOK = "OFFHOOK"
        private const val SOURCE_ANSWERED = "STATE_ACTIVE"
    }
}
