// Improved GSM integration hook - based on Twilio pattern
// Handles call state management, audio streaming, DTMF, and proper cleanup
import { RefObject, useEffect, useRef } from "react"
import { useCallingSetup } from "../store/useCallingSetup"
import { useGSM } from "../store/useGSM"
import { getPusherClient } from "@/libs/Pusher/pusher"

type AudioPacket = {
  role: "browser" | "android"
  deviceToken: string
  dir: "toAndroid" | "toBrowser"
  codec: "pcm16"
  seq: number
  ts: number
  sampleRate: 8000 | 16000
  audio: string
}

type SimAccount = {
  id: string
  componentName: string
  label: string
  simSlotIndex: number
}

type GsmCallsEvent = {
  deviceToken?: string
  audio?: string
}

/**
 * Utility: Downsample Float32Array audio buffer to 16kHz.
 */
function downsampleTo(buffer: Float32Array, inputSampleRate: number, targetRate: number): Float32Array {
  if (inputSampleRate === targetRate) return buffer
  const ratio = inputSampleRate / targetRate
  const newLength = Math.round(buffer.length / ratio)
  const result = new Float32Array(newLength)
  let offsetResult = 0
  let offsetBuffer = 0
  while (offsetResult < result.length) {
    const nextOffset = Math.round((offsetResult + 1) * ratio)
    let accum = 0, count = 0
    for (let i = offsetBuffer; i < nextOffset && i < buffer.length; i++) { accum += buffer[i]; count++ }
    result[offsetResult] = count > 0 ? accum / count : 0
    offsetResult++
    offsetBuffer = nextOffset
  }
  return result
}

function downsampleTo16k(buffer: Float32Array, inputSampleRate: number): Float32Array {
  return downsampleTo(buffer, inputSampleRate, 16000)
}


/**
 * Converts a Float32Array of audio samples (range -1..1) to base64-encoded PCM16 (little-endian).
 */
function cleanAndEncodePcm16(downsampled: Float32Array) {
  const pcm16 = new Int16Array(downsampled.length)
  for (let i = 0; i < downsampled.length; i++) {
    const s = Math.max(-1, Math.min(1, downsampled[i]))
    pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7fff
  }

  const bytes = new Uint8Array(pcm16.buffer)
  let binary = ""
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }

  return btoa(binary)
}

/**
 * useInitGSM - Comprehensive GSM calling hook
 * Manages:
 * - Device token discovery and registration
 * - Call state machine (idle -> dialing -> connected -> ended)
 * - Audio capture and playback with jitter buffering
 * - WebSocket connection for bidirectional audio
 * - Pusher events for call state and fallback audio
 * - DTMF tone sending
 */
export const useInitGSM = (dtmfTimeoutRef: RefObject<NodeJS.Timeout | null>, endSoundRef: RefObject<HTMLAudioElement | null>) => {
  const { callingSetup, num, dtmfTone, isConnected, isMuted, setDTMFTone, setIsReady, setError, setIsConnected, setIsCalling } =
    useCallingSetup()
  // ⚠️ DO NOT change to `const { deviceToken, setDeviceToken } = useGSM()` — that subscribes the
  // parent component (TradingStyleDialer) to EVERY field in useGSM (sims, selectedSim, etc).
  // The status poll updates `sims` every 2s → infinite re-render loop. Use single-field selector
  // for reactive value, and `useGSM.getState().setX(...)` inside callbacks for setters.
  const deviceToken = useGSM(state => state.deviceToken)
  const deviceTokenRef = useRef(deviceToken)
  deviceTokenRef.current = deviceToken

  // Audio context and WebSocket management
  const audioContextRef = useRef<AudioContext | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const mediaStreamRef = useRef<MediaStream | null>(null)
  const processorRef = useRef<ScriptProcessorNode | null>(null)

  // Audio sequence tracking and jitter buffer
  const seqTxRef = useRef(0)
  const rxQueueRef = useRef<Map<number, Float32Array>>(new Map())
  const nextRxSeqRef = useRef(0)
  const rxEnqueueSeqRef = useRef(0)
  const playoutTimerRef = useRef<NodeJS.Timeout | null>(null)
  const isPlayoutRunningRef = useRef(false)

  const MAX_QUEUE = 80

  // Cleanup refs to prevent memory leaks
  const isCleaningUpRef = useRef(false)
  const statusFailureCountRef = useRef(0)
  const wsReconnectTimerRef = useRef<NodeJS.Timeout | null>(null)
  const duplexValidationModeRef = useRef(false)
  const isTestAudioActiveRef = useRef(false)
  const isCallActiveRef = useRef(false) // true between call() and hungUp()/CALL_ENDED
  const simsRef = useRef<SimAccount[]>([])
  const selectedSimRef = useRef<SimAccount | null>(null)
  const isServiceActiveRef = useRef(false)
  const isTestActiveRef = useRef(false)
  const triggerCallEndedRef = useRef<() => void>(() => {})
  const micGainRef = useRef(1.0)
  const playbackGainRef = useRef(3.0)

  const shouldStreamMic = () => isConnected || isTestAudioActiveRef.current || duplexValidationModeRef.current

  const isDuplexValidationEnabled = () => {
    if (typeof window === "undefined") return false

    try {
      const fromStorage = window.localStorage.getItem("gsm.duplexValidation")
      if (fromStorage === "1" || fromStorage === "true") return true
    } catch {
      // localStorage can fail in strict browser/privacy modes
    }

    const params = new URLSearchParams(window.location.search)
    const fromQuery = params.get("gsmDuplexValidation")
    return fromQuery === "1" || fromQuery === "true"
  }

  /**
   * 0. INITIALIZE AUDIO CONTEXT — DEFERRED TO FIRST USER GESTURE
   * ⚠️ DO NOT create AudioContext eagerly on mount. Browsers block AudioContexts that are
   * created OR resumed before a user gesture (click/keydown) and emit:
   *   "The AudioContext was not allowed to start. It must be resumed (or created) after a user gesture on the page."
   * We register click/keydown listeners and create-or-resume the context lazily on the first event.
   */
  useEffect(() => {
    duplexValidationModeRef.current = isDuplexValidationEnabled()
    if (duplexValidationModeRef.current) {
      console.info(
        "[gsm/duplex-test] enabled (query: ?gsmDuplexValidation=1 or localStorage[gsm.duplexValidation]=1); uses existing ws+pusher channels",
      )
    }

    const initOrResume = () => {
      if (!audioContextRef.current) {
        try {
          // Force 16kHz so createBuffer(…, 16000) is native — no browser resampling artifacts
          const ctx = new (window.AudioContext || (window as any).webkitAudioContext)({ sampleRate: 16000 })
          audioContextRef.current = ctx
          console.info("[gsm] AudioContext created on user gesture", { sampleRate: ctx.sampleRate })
        } catch (err) {
          console.error("[gsm] failed to create AudioContext", err)
          setError("Audio system not available")
        }
      } else if (audioContextRef.current.state === "suspended") {
        audioContextRef.current
          .resume()
          .then(() => console.info("[gsm] AudioContext resumed by user gesture"))
          .catch(() => {})
      }
    }

    document.addEventListener("click", initOrResume)
    document.addEventListener("keydown", initOrResume)
    return () => {
      document.removeEventListener("click", initOrResume)
      document.removeEventListener("keydown", initOrResume)
    }
  }, [setError])
  /**
   * Helper function to decode and queue audio chunks
   */
  const enqueueBase64Audio = (base64Audio: string) => {
    try {
      // Decode base64 to bytes
      const binaryString = atob(base64Audio)
      const bytes = new Uint8Array(binaryString.length)
      for (let i = 0; i < binaryString.length; i++) {
        bytes[i] = binaryString.charCodeAt(i)
      }

      // Convert bytes to Int16 explicitly as little-endian PCM16
      const sampleCount = Math.floor(bytes.byteLength / 2)
      const float32Array = new Float32Array(sampleCount)
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)

      const pg = playbackGainRef.current
      for (let i = 0; i < sampleCount; i++) {
        const s16 = view.getInt16(i * 2, true)
        float32Array[i] = Math.max(-1, Math.min(1, (s16 / 32768.0) * pg))
      }

      // Queue audio with sequence number for ordering
      const seq = rxEnqueueSeqRef.current++
      rxQueueRef.current.set(seq, float32Array)

      // Prevent unbounded queue growth (drop old packets if queue too large)
      if (rxQueueRef.current.size > MAX_QUEUE) {
        const oldestSeq = Math.min(...Array.from(rxQueueRef.current.keys()))
        rxQueueRef.current.delete(oldestSeq)

        // Keep playout cursor in sync with queue drops; otherwise we can stall forever
        // waiting for a sequence number that was already evicted.
        if (nextRxSeqRef.current <= oldestSeq) {
          nextRxSeqRef.current = oldestSeq + 1
        }

        console.warn("[gsm/audio-queue] dropped old packet", oldestSeq, "queue size:", rxQueueRef.current.size)
      }

      // Debug logging
      if (seq % 100 === 0) {
        console.log(
          "[gsm/audio-queue] enqueued packet",
          seq,
          "queue size:",
          rxQueueRef.current.size,
          "samples:",
          float32Array.length,
        )
      }
    } catch (err) {
      console.error("[gsm/audio-decode] failed to decode audio chunk", err)
    }
  }

  const resetInboundAudioState = () => {
    rxQueueRef.current.clear()
    nextRxSeqRef.current = 0
    rxEnqueueSeqRef.current = 0
  }

  // Tracks the AudioContext time at which the next chunk should start playing.
  // Scheduling chunks end-to-end eliminates gaps/overlaps caused by setInterval jitter.
  const nextPlayTimeRef = useRef(0)
  // Initial buffering delay before first playback (seconds) — absorbs network jitter
  const PLAYOUT_BUFFER_S = 0.15 // 150ms — enough to buffer ~7 chunks before starting
  // How far ahead to keep the schedule filled (seconds). The scheduler loop fires
  // whenever a new chunk arrives and fills the lookahead window.
  const SCHEDULE_AHEAD_S = 0.25 // fill 250ms of audio ahead of current time
  let playoutCount = 0

  // Drains the rx queue and schedules all available chunks into the AudioContext
  // timeline up to SCHEDULE_AHEAD_S ahead of current time. Called on every new
  // chunk arrival — no polling timer needed.
  const drainAndSchedule = () => {
    const ctx = audioContextRef.current
    if (!ctx || !isPlayoutRunningRef.current) return

    if (ctx.state !== "running") {
      ctx.resume().catch(() => {})
      return
    }

    // Anchor the schedule head the first time (or after a reset/gap)
    if (nextPlayTimeRef.current < ctx.currentTime + PLAYOUT_BUFFER_S) {
      nextPlayTimeRef.current = ctx.currentTime + PLAYOUT_BUFFER_S
    }

    // Fill the lookahead window with as many queued chunks as are available
    while (nextPlayTimeRef.current < ctx.currentTime + SCHEDULE_AHEAD_S) {
      const expectedSeq = nextRxSeqRef.current
      let seqToPlay = expectedSeq
      let chunk = rxQueueRef.current.get(seqToPlay)

      // Recover from packet loss/eviction gaps by jumping to the next available chunk.
      if (!chunk && rxQueueRef.current.size > 0) {
        const availableSeqs = Array.from(rxQueueRef.current.keys())
        const minAvailableSeq = Math.min(...availableSeqs)
        if (minAvailableSeq > expectedSeq) {
          console.warn("[gsm/playback] seq gap detected, skipping", { expected: expectedSeq, next: minAvailableSeq })
          seqToPlay = minAvailableSeq
          nextRxSeqRef.current = minAvailableSeq
          chunk = rxQueueRef.current.get(seqToPlay)
          // Gap in stream — reset schedule head so next chunk anchors fresh
          nextPlayTimeRef.current = ctx.currentTime + PLAYOUT_BUFFER_S
        }
      }

      if (!chunk || chunk.length === 0) break // queue empty — wait for more chunks

      rxQueueRef.current.delete(seqToPlay)
      nextRxSeqRef.current = seqToPlay + 1
      playoutCount++

      try {
        const buf = ctx.createBuffer(1, chunk.length, 16000)
        buf.getChannelData(0).set(chunk)

        const src = ctx.createBufferSource()
        src.buffer = buf
        src.connect(ctx.destination)
        src.start(nextPlayTimeRef.current)
        nextPlayTimeRef.current += chunk.length / 16000

        if (playoutCount % 50 === 0) {
          console.log(
            "[gsm/playback] scheduled chunk seq",
            seqToPlay,
            "total:",
            playoutCount,
            "ahead:",
            (nextPlayTimeRef.current - ctx.currentTime).toFixed(3) + "s",
          )
        }
      } catch (err) {
        console.error("[gsm/playback] error scheduling chunk", err)
      }
    }
  }

  const ensurePlayoutLoop = () => {
    const ctx = audioContextRef.current
    if (!ctx || isPlayoutRunningRef.current) return

    if (ctx.state === "suspended") {
      ctx.resume().catch((e: unknown) => console.error("[gsm/playback] resume error", e))
    }

    isPlayoutRunningRef.current = true
    nextPlayTimeRef.current = 0
    playoutCount = 0
    console.info("[gsm/playback] scheduler started", { state: ctx.state })

    // Heartbeat timer: fires every 20ms to catch any chunks that arrived between
    // drainAndSchedule() calls (e.g. during a gap recovery). Low overhead since
    // most work is done eagerly in drainAndSchedule() on each ws.onmessage.
    playoutTimerRef.current = setInterval(() => drainAndSchedule(), 20)
  }

  const stopPlayoutLoop = () => {
    if (playoutTimerRef.current) clearInterval(playoutTimerRef.current)
    playoutTimerRef.current = null
    isPlayoutRunningRef.current = false
    nextPlayTimeRef.current = 0
  }

  /**
   * 1. DEVICE DISCOVERY & REGISTRATION
   * Polls /api/gsm/status to find connected device and keep it alive
   */
  useEffect(() => {
    async function fetchDeviceToken() {
      try {
        const res = await fetch("/api/gsm/status", { cache: "no-store" })
        if (!res.ok) throw new Error(await res.text())
        const data = await res.json()

        const authorized = !!data?.isAuthorized
        const nextDeviceToken = data?.deviceToken as string | undefined

        if (nextDeviceToken) {
          // Avoid token flapping when multiple devices are online
          if (!deviceToken) {
            useGSM.getState().setDeviceToken(nextDeviceToken)
            console.info("[gsm] device discovered", { deviceToken: nextDeviceToken })
          } else if (deviceToken !== nextDeviceToken) {
            console.warn("[gsm] multiple devices detected, keeping selected token", {
              selected: deviceToken,
              suggested: nextDeviceToken,
            })
          }
        }

        // Update SIM list whenever status returns it; auto-select first SIM if none selected.
        // ⚠️ Defer store writes via queueMicrotask — calling setSims/setSelectedSim synchronously
        // inside the poll fires Zustand subscribers during React render → "setState during render" warning.
        if (Array.isArray(data?.sims) && data.sims.length > 0) {
          simsRef.current = data.sims as SimAccount[]
          if (!selectedSimRef.current) selectedSimRef.current = data.sims[0]
          queueMicrotask(() => {
            useGSM.getState().setSims(data.sims)
            if (!useGSM.getState().selectedSim) useGSM.getState().setSelectedSim(data.sims[0])
          })
        }

        const prevService = isServiceActiveRef.current
        const prevTest = isTestActiveRef.current
        isServiceActiveRef.current = data?.isServiceActive ?? false
        isTestActiveRef.current = data?.isTestActive ?? false

        if (prevService !== isServiceActiveRef.current || prevTest !== isTestActiveRef.current) {
          console.info("[gsm/status] state changed", {
            isServiceActive: isServiceActiveRef.current,
            isTestActive: isTestActiveRef.current,
            prev: { service: prevService, test: prevTest },
          })
        }

        statusFailureCountRef.current = 0
        setIsReady(authorized && !!nextDeviceToken)
        if (authorized && nextDeviceToken) setError("")
      } catch (error) {
        statusFailureCountRef.current += 1
        if (statusFailureCountRef.current >= 2) {
          setIsReady(false)
        }
        setError(String(error))
        console.error("[gsm/status] error", error)
      }
    }

    fetchDeviceToken()
    const id = setInterval(fetchDeviceToken, 2000) // Poll every 2s for near real-time readiness

    // Re-fetch immediately when tab regains focus — Pusher may have dropped while hidden
    const onVisible = () => {
      if (document.visibilityState === "visible") fetchDeviceToken()
    }
    document.addEventListener("visibilitychange", onVisible)

    return () => {
      clearInterval(id)
      document.removeEventListener("visibilitychange", onVisible)
    }
  }, [deviceToken, setError, setIsReady])

  /**
   * 2. PUSHER EVENTS - Call State Management
   * Handles: device-connected, call-started, call-connected, call-ended, audio-chunk
   */
  useEffect(() => {
    const pusher = getPusherClient()
    const devicesChannel = pusher.subscribe("gsm-devices")
    const callsChannel = pusher.subscribe("gsm-calls")

    console.info("[gsm/pusher] subscribing to gsm-calls")

    // Device connected to backend
    const onDeviceConnected = async (eventData: { deviceToken?: string }) => {
      if (eventData?.deviceToken) {
        useGSM.getState().setDeviceToken(eventData.deviceToken)
        setIsReady(true)
        setError("")
        console.info("[gsm/pusher] device-connected", eventData.deviceToken)
        return
      }

      // Fallback: fetch from status endpoint
      try {
        const res = await fetch("/api/gsm/status")
        if (!res.ok) return
        const data = await res.json()
        if (data?.deviceToken) useGSM.getState().setDeviceToken(data.deviceToken)
        setIsReady(!!data?.isAuthorized)
      } catch {
        // no-op
      }
    }

    // Call initiated (dialing/ringing phase)
    const onCallStarted = (eventData: GsmCallsEvent) => {
      const tok = deviceTokenRef.current
      if (!tok || eventData?.deviceToken !== tok) return
      setIsCalling(true)
      setIsConnected(false)
      console.info("[gsm/pusher] call-started (dialing phase)", { deviceToken: tok })
    }

    // Call answered (person picked up)
    const onCallConnected = (eventData: GsmCallsEvent) => {
      const tok = deviceTokenRef.current
      if (!tok || eventData?.deviceToken !== tok) return
      // Reset seq and audio state before enabling mic — ensures fresh start with no dialing-phase audio
      seqTxRef.current = 0
      resetInboundAudioState()
      isCallActiveRef.current = true
      setIsCalling(false)
      setIsConnected(true)
      console.info("[gsm/pusher] call-connected (answered)", { deviceToken: tok })
      // Tell Android to start tinycap + pcm_play now that remote has answered
      sendCommand("START_AUDIO")
    }

    // Call ended or rejected
    const onCallEnded = (eventData: GsmCallsEvent) => {
      const tok = deviceTokenRef.current
      console.info("[gsm/pusher] call-ended RAW", {
        eventToken: eventData?.deviceToken,
        localToken: tok,
        match: eventData?.deviceToken === tok,
      })
      if (!tok || eventData?.deviceToken !== tok) return
      console.info("[gsm/pusher] call-ended (hangup/reject) - audio stopped", { deviceToken: tok })
      triggerCallEndedRef.current()
    }

    // Test audio events from Android
    const onTestAudioStarted = () => {
      console.info("[gsm/pusher] test-audio-started")
      isTestAudioActiveRef.current = true

      // 1. Unlock AudioContext immediately
      const ctx = audioContextRef.current
      if (ctx?.state === "suspended") ctx.resume().catch(err => console.error("[gsm/test] resume failed", err))

      // 2. Start playout loop immediately
      ensurePlayoutLoop()

      // 3. Start mic with WS readiness check inline
      const tryStartMic = (attemptsLeft: number) => {
        if (attemptsLeft <= 0) {
          console.error("[gsm/test] WS never opened - mic aborted")
          return
        }
        if (wsRef.current?.readyState === WebSocket.OPEN) {
          startMicCapture().catch(err => console.error("[gsm/test-mode] mic error", err))
          return
        }
        console.warn(`[gsm/test] WS not open, retrying... (${attemptsLeft} left)`)
        setTimeout(() => tryStartMic(attemptsLeft - 1), 200)
      }
      tryStartMic(15) // 15 * 200ms = 3s max wait
    }

    const onTestAudioStopped = () => {
      console.info("[gsm/pusher] test-audio-stopped")
      isTestAudioActiveRef.current = false
      if (!shouldStreamMic()) {
        stopMicCapture()
      }

      if (!isConnected) {
        stopPlayoutLoop()
        resetInboundAudioState()
      }
    }

    // Incoming audio from Android device (fallback path via Pusher)
    const onAudioChunk = (eventData: GsmCallsEvent) => {
      const tok = deviceTokenRef.current
      if (!tok || eventData?.deviceToken !== tok || !eventData?.audio) return

      if (!mediaStreamRef.current && isTestAudioActiveRef.current) {
        startMicCapture().catch(error => setError(String(error)))
      }

      enqueueBase64Audio(eventData.audio)
      ensurePlayoutLoop()
    }

    // Bind Pusher events with gsm: namespace
    devicesChannel.bind("gsm:device-connected", onDeviceConnected)
    callsChannel.bind("gsm:call-started", onCallStarted)
    callsChannel.bind("gsm:call-connected", onCallConnected)
    callsChannel.bind("gsm:call-ended", onCallEnded)
    callsChannel.bind("gsm:test-audio-started", onTestAudioStarted)
    callsChannel.bind("gsm:test-audio-stopped", onTestAudioStopped)
    callsChannel.bind("gsm:audio-chunk", onAudioChunk)

    return () => {
      devicesChannel.unbind("gsm:device-connected", onDeviceConnected)
      callsChannel.unbind("gsm:call-started", onCallStarted)
      callsChannel.unbind("gsm:call-connected", onCallConnected)
      callsChannel.unbind("gsm:call-ended", onCallEnded)
      callsChannel.unbind("gsm:test-audio-started", onTestAudioStarted)
      callsChannel.unbind("gsm:test-audio-stopped", onTestAudioStopped)
      callsChannel.unbind("gsm:audio-chunk", onAudioChunk)

      pusher.unsubscribe("gsm-devices")
      pusher.unsubscribe("gsm-calls")
    }
  }, []) // Zustand setters are stable — no deps needed; effect must stay mounted for the lifetime of the hook

  /**
   * 3. AUDIO PIPELINE - Inbound audio decoding
   * Converts base64 PCM16 to Float32 and buffers with sequence tracking
   */

  /**
   * 4. WEBSOCKET CONNECTION - Primary audio transport
   * Establishes WebSocket at /ws/audio for bidirectional audio streaming
   */
  useEffect(() => {
    if (callingSetup !== "gsm" || !deviceToken) return

    let isUnmounted = false

    console.info("[gsm/ws] init", { callingSetup, deviceToken })

    const bearerToken = process.env.NEXT_PUBLIC_BACKEND_BEARER
    const wsBase = process.env.NEXT_PUBLIC_WS_URL
    if (!bearerToken || !wsBase) {
      setError("Missing NEXT_PUBLIC_BACKEND_BEARER or NEXT_PUBLIC_WS_URL")
      console.error("[gsm/ws] missing env config")
      return
    }

    const connect = () => {
      if (isUnmounted) return
      if (wsReconnectTimerRef.current) clearTimeout(wsReconnectTimerRef.current)

      console.info("[gsm/ws] connecting", { wsBase, deviceToken })
      const ws = new WebSocket(`${wsBase}/ws/audio?token=${encodeURIComponent(bearerToken)}`)
      wsRef.current = ws

      ws.onopen = () => {
        resetInboundAudioState()
        setError("")
        if (audioContextRef.current?.state === "suspended") audioContextRef.current.resume().catch(() => {})

        if (!deviceToken) {
          console.error("[gsm/ws] no deviceToken on open")
          return
        }

        ws.send(JSON.stringify({ role: "browser", deviceToken, dir: "toBrowser" }))
        ensurePlayoutLoop()

        // 1. Re-fetch test state from Android via status endpoint after reconnect
        fetch("/api/gsm/status", { cache: "no-store" })
          .then(res => res.json())
          .then(data => {
            if (data?.isAuthorized && shouldStreamMic()) {
              startMicCapture().catch(err => console.error("[gsm/ws] mic start failed", err))
            }
          })
          .catch(() => {})
      }

      ws.onerror = event => {
        setError("WebSocket connection error")
        console.error("[gsm/ws] error", event)
      }

      ws.onclose = event => {
        // Don't set isReady=false here — device may still be connected,
        // the status poll (every 2s) is the authoritative source for readiness
        console.warn("[gsm/ws] closed", { code: event.code, reason: event.reason })

        if (!isUnmounted) {
          wsReconnectTimerRef.current = setTimeout(() => connect(), 1500)
        }
      }

      ws.onmessage = ev => {
        try {
          const pkt = JSON.parse(ev.data)

          // Control frame: server forwarded gsm:call-ended directly over WS (Pusher bypass)
          if (pkt.type === "gsm:call-ended" && pkt.deviceToken === deviceToken) {
            console.info("[gsm/ws] call-ended received via WS direct", { deviceToken })
            triggerCallEndedRef.current()
            return
          }

          if (pkt.deviceToken !== deviceToken || pkt.dir !== "toBrowser") return
          if (!pkt.audio) return

          if (audioContextRef.current?.state === "suspended") audioContextRef.current.resume().catch(() => {})

          // Auto-start mic in TEST mode or during an active SERVICE call
          if (!mediaStreamRef.current && (isTestAudioActiveRef.current || isCallActiveRef.current)) {
            startMicCapture().catch(error => setError(String(error)))
          }

          enqueueBase64Audio(pkt.audio)
          ensurePlayoutLoop()
        } catch (err) {
          console.error("[gsm/ws] onmessage parse error", err)
        }
      }
    }

    connect()

    return () => {
      isUnmounted = true
      if (wsReconnectTimerRef.current) clearTimeout(wsReconnectTimerRef.current)
      wsReconnectTimerRef.current = null
      wsRef.current?.close()
      wsRef.current = null
      stopMicCapture()
      stopPlayoutLoop()
      resetInboundAudioState()
      setIsReady(false)
    }
  }, [callingSetup, deviceToken, setError, setIsReady])

  /**
   * 5. AUDIO PLAYBACK - Jitter buffer processing
   * Dequeues audio in order and plays via AudioContext with gain control
   */
  useEffect(() => {
    if (!isConnected) return
    // Force-restart playout loop on each call connect — stale isPlayoutRunningRef from
    // a previous TEST or call session would otherwise prevent it from starting.
    stopPlayoutLoop()
    resetInboundAudioState()
    ensurePlayoutLoop()
  }, [isConnected])

  /**
   * 6. MICROPHONE CAPTURE - Outbound audio encoding
   * Captures mic input, converts to PCM16, sends via WebSocket (with Pusher fallback)
   */
  const startMicCapture = async () => {
    if (!audioContextRef.current || !deviceToken || isCleaningUpRef.current) return
    if (mediaStreamRef.current && processorRef.current) return
    if (wsRef.current?.readyState !== WebSocket.OPEN) {
      console.warn("[gsm/mic] WS not open, skipping")
      return
    }

    try {
      // Defensive tear-down — protects against a race where two callers (status poll +
      // ws.onmessage + useEffect) all enter startMicCapture concurrently and leak processors.
      stopMicCapture()
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: false, sampleRate: 16000 } })
      // After the await, the call may have ended — bail out instead of leaking the stream.
      if (
        isCleaningUpRef.current ||
        (!isCallActiveRef.current && !isTestAudioActiveRef.current && !duplexValidationModeRef.current)
      ) {
        stream.getTracks().forEach(t => t.stop())
        return
      }
      mediaStreamRef.current = stream
      const source = audioContextRef.current.createMediaStreamSource(stream)
      // 512 samples = ~32ms chunks at 16kHz → 256 samples at 8kHz after downsample.
      // 4096 was 256ms bursts causing audible cut-off gaps at the remote GSM end.
      const processor = audioContextRef.current.createScriptProcessor(512, 1, 1)
      processorRef.current = processor

      processor.onaudioprocess = e => {
        if (isMuted || isCleaningUpRef.current) return
        if (wsRef.current?.readyState !== WebSocket.OPEN) return
        // Hard gate: only stream when call/test is actually active. Without this,
        // `processor` survives `stopMicCapture()` if disconnect raced with an in-flight
        // `getUserMedia` resolution, and would otherwise keep shipping mic to a dead Android peer.
        if (!isCallActiveRef.current && !isTestAudioActiveRef.current && !duplexValidationModeRef.current) return
        try {
          const inF32 = e.inputBuffer.getChannelData(0)
          // Apply mic gain in browser before encoding
          const gain = micGainRef.current
          if (gain !== 1.0) for (let i = 0; i < inF32.length; i++) inF32[i] = Math.max(-1, Math.min(1, inF32[i] * gain))
          // Send at 16kHz — Android AudioTrack plays at 16kHz which matches voice HAL natively.
          // Fewer resampling steps = much cleaner audio than 8kHz→kernel upsample→HAL.
          const downsampled = downsampleTo16k(inF32, e.inputBuffer.sampleRate)
          const audio = cleanAndEncodePcm16(downsampled)
          const pkt: AudioPacket = {
            role: "browser",
            deviceToken,
            dir: "toAndroid",
            codec: "pcm16",
            seq: seqTxRef.current++,
            ts: performance.now(),
            sampleRate: 16000,
            audio,
          }
          wsRef.current.send(JSON.stringify(pkt))
          if (pkt.seq % 100 === 0) console.log("[gsm/mic] sent seq", pkt.seq)
        } catch (err) {
          console.error("[gsm/mic] capture error", err)
        }
      }

      source.connect(processor)
      processor.connect(audioContextRef.current.destination)
      console.info("[gsm] microphone capture started")
    } catch (err) {
      console.error("[gsm] mic error", err)
      setError(`Microphone error: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const stopMicCapture = () => {
    mediaStreamRef.current?.getTracks().forEach(t => t.stop())
    mediaStreamRef.current = null
    processorRef.current?.disconnect()
    processorRef.current = null
  }

  const triggerCallEnded = () => {
    endSoundRef.current?.play().catch(() => {})
    isCallActiveRef.current = false
    setIsConnected(false)
    setIsCalling(false)
    setTimeout(() => {
      stopMicCapture()
      stopPlayoutLoop()
      rxQueueRef.current.clear()
      nextRxSeqRef.current = 0
      rxEnqueueSeqRef.current = 0
    }, 6000)
  }
  triggerCallEndedRef.current = triggerCallEnded

  const stopTestAudio = () => {
    isTestAudioActiveRef.current = false
    stopMicCapture()
  }

  /**
   * 7. START/STOP AUDIO BASED ON CALL STATE
   * When connected: start mic capture and audio playback
   * When not connected: stop everything cleanly
   */
  useEffect(() => {
    if (shouldStreamMic()) {
      startMicCapture().catch(e => setError(String(e)))
    } else {
      stopMicCapture()
    }
  }, [isConnected, setError])

  /**
   * 8. CALL MANAGEMENT - Initiate or end calls
   */
  const call = async () => {
    if (!deviceToken) return
    console.info("[gsm/call] initiating call", { deviceToken, num })

    try {
      // Do NOT set isCallActiveRef=true here — mic would stream during dialing/ringing.
      // isCallActiveRef is set in onCallConnected (when remote answers).
      setIsCalling(true)
      // ⚠️ Read from store (single source of truth) — selectedSimRef is only updated by selectSim()
      // which is never called from the UI. The UI calls useGSM's setSelectedSim directly.
      const sim = useGSM.getState().selectedSim
      const res = await fetch("/api/gsm/call-started", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          num,
          deviceToken,
          ...(sim ? { simAccountId: sim.id, simComponentName: sim.componentName } : {}),
        }),
      })

      if (!res.ok) {
        const errorText = await res.text()
        console.error("[gsm/call] failed", { status: res.status, errorText })
        setIsCalling(false)
        throw new Error(errorText)
      }

      // Note: Do NOT set isConnected=true here!
      // Wait for CALL_CONNECTED event from Pusher instead
      console.info("[gsm/call] call initiated, waiting for answer...")
    } catch (err) {
      console.error("[gsm/call] error", err)
      setIsCalling(false)
      throw err
    }
  }

  const hungUp = async () => {
    if (!deviceToken) return
    console.info("[gsm/call] hangup requested", { deviceToken })

    // Immediate local cleanup
    isCleaningUpRef.current = true
    isCallActiveRef.current = false
    setIsConnected(false)
    setIsCalling(false)
    stopMicCapture()
    stopPlayoutLoop()
    resetInboundAudioState()

    try {
      const res = await fetch("/api/gsm/call-ended", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ deviceToken }),
      })

      if (!res.ok) {
        const errorText = await res.text()
        console.error("[gsm/call] hangup failed", { status: res.status, errorText })
      }
    } catch (err) {
      console.error("[gsm/call] hangup network error", err)
    } finally {
      isCleaningUpRef.current = false
    }
  }

  /**
   * 9. DTMF TONE SENDING
   */
  const sendDTMF = async (digit: string) => {
    if (!deviceToken) return
    console.info("[gsm/dtmf] sending", { digit, deviceToken })

    try {
      await fetch("/api/gsm/send-dtmf", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ digit, deviceToken }),
      })

      if (dtmfTimeoutRef.current) clearTimeout(dtmfTimeoutRef.current)
      setDTMFTone(digit)
    } catch (err) {
      console.error("[gsm/dtmf] error", err)
    }
  }

  // Test audio mode is controlled by Android button via Pusher events
  // No need for URL params anymore
  useEffect(() => {
    return () => {
      stopMicCapture()
    }
  }, [])

  const selectSim = (sim: SimAccount) => {
    selectedSimRef.current = sim
  }

  const sendCommand = async (type: string, data: Record<string, unknown> = {}) => {
    const tok = deviceTokenRef.current
    if (!tok) {
      console.warn("[gsm/cmd] sendCommand called but deviceToken is empty", { type })
      return
    }
    await fetch("/api/gsm/commands", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceToken: tok, commands: { type, data } }),
    })
  }

  const setMicGain = (value: number) => { micGainRef.current = value; sendCommand("SET_GAIN", { micGain: value }) }
  const setPlaybackGain = (value: number) => { playbackGainRef.current = value; sendCommand("SET_GAIN", { playbackGain: value }) }

  const fetchLogs = async (): Promise<string[]> => {
    try {
      const res = await fetch("/api/gsm/logs", { cache: "no-store" })
      if (!res.ok) return []
      const data = await res.json()
      return data.logs ?? []
    } catch {
      return []
    }
  }

  return {
    call,
    hungUp,
    sendDTMF,
    stopTestAudio,
    fetchLogs,
    setMicGain,
    setPlaybackGain,
    isTestAudioActiveRef,
    isServiceActiveRef,
    isTestActiveRef,
    simsRef,
    selectedSimRef,
    selectSim,
  }
}
