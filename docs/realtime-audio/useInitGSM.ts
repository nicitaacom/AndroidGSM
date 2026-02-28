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
  sampleRate: 16000
  audio: string
}

type GsmCallsEvent = {
  deviceToken?: string
  audio?: string
}


/**
 * Utility: Downsample Float32Array audio buffer to 16kHz.
 */
function downsampleTo16k(buffer: Float32Array, inputSampleRate: number): Float32Array {
  if (inputSampleRate === 16000) return buffer
  const sampleRateRatio = inputSampleRate / 16000
  const newLength = Math.round(buffer.length / sampleRateRatio)
  const result = new Float32Array(newLength)
  let offsetResult = 0
  let offsetBuffer = 0

  while (offsetResult < result.length) {
    const nextOffsetBuffer = Math.round((offsetResult + 1) * sampleRateRatio)
    let accum = 0
    let count = 0

    for (let i = offsetBuffer; i < nextOffsetBuffer && i < buffer.length; i++) {
      accum += buffer[i]
      count++
    }

    result[offsetResult] = count > 0 ? accum / count : 0
    offsetResult++
    offsetBuffer = nextOffsetBuffer
  }

  return result
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
export const useInitGSM = (dtmfTimeoutRef: RefObject<NodeJS.Timeout | null>) => {
  const { callingSetup, num, dtmfTone, isConnected, isMuted, setDTMFTone, setIsReady, setError, setIsConnected, setIsCalling } =
    useCallingSetup()
  const { deviceToken, setDeviceToken } = useGSM()

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

  // Playout delay: ~250ms (good enough for <=1000ms user requirement)
  const PLAYOUT_INTERVAL_MS = 20
  const MAX_QUEUE = 80

  // Cleanup refs to prevent memory leaks
  const isCleaningUpRef = useRef(false)
  const statusFailureCountRef = useRef(0)
  const wsReconnectTimerRef = useRef<NodeJS.Timeout | null>(null)
  const duplexValidationModeRef = useRef(false)
  const isTestAudioActiveRef = useRef(false)

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
   * 0. INITIALIZE AUDIO CONTEXT
   * Must be created on first mount so we have it ready for audio playback
   */
  useEffect(() => {
    if (!audioContextRef.current) {
      try {
        const ctx = new (window.AudioContext || (window as any).webkitAudioContext)()
        audioContextRef.current = ctx
        console.info("[gsm] AudioContext created", { sampleRate: ctx.sampleRate })
      } catch (err) {
        console.error("[gsm] failed to create AudioContext", err)
        setError("Audio system not available")
      }
    }

    duplexValidationModeRef.current = isDuplexValidationEnabled()
    if (duplexValidationModeRef.current) {
      console.info(
        "[gsm/duplex-test] enabled (query: ?gsmDuplexValidation=1 or localStorage[gsm.duplexValidation]=1); uses existing ws+pusher channels",
      )
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

      for (let i = 0; i < sampleCount; i++) {
        const s16 = view.getInt16(i * 2, true)
        float32Array[i] = s16 / 32768.0 // Normalize to [-1, 1]
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
        console.log("[gsm/audio-queue] enqueued packet", seq, "queue size:", rxQueueRef.current.size, "samples:", float32Array.length)
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

  const ensurePlayoutLoop = () => {
    const ctx = audioContextRef.current
    if (!ctx || isPlayoutRunningRef.current) return

    if (ctx.state === "suspended") {
      ctx.resume().catch((e) => console.error("[gsm/playback] resume error", e))
    }

    isPlayoutRunningRef.current = true
    let playoutCount = 0
    console.info("[gsm/playback] loop started", { state: ctx.state })

    playoutTimerRef.current = setInterval(() => {
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
        }
      }

      if (!chunk || chunk.length === 0) return

      rxQueueRef.current.delete(seqToPlay)
      nextRxSeqRef.current = seqToPlay + 1
      playoutCount++

      try {
        const buf = ctx.createBuffer(1, chunk.length, 16000)
        const channelData = buf.getChannelData(0)
        channelData.set(chunk)

        const src = ctx.createBufferSource()
        src.buffer = buf

        const gainNode = ctx.createGain()
        gainNode.gain.value = 1.0
        src.connect(gainNode)
        gainNode.connect(ctx.destination)
        src.start(ctx.currentTime)

        if (playoutCount % 50 === 0) {
          console.log("[gsm/playback] playing chunk seq", seqToPlay, "total played:", playoutCount)
        }
      } catch (err) {
        console.error("[gsm/playback] error playing chunk", err)
      }
    }, PLAYOUT_INTERVAL_MS)
  }

  const stopPlayoutLoop = () => {
    if (playoutTimerRef.current) clearInterval(playoutTimerRef.current)
    playoutTimerRef.current = null
    isPlayoutRunningRef.current = false
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
            setDeviceToken(nextDeviceToken)
            console.info("[gsm] device discovered", { deviceToken: nextDeviceToken })
          } else if (deviceToken !== nextDeviceToken) {
            console.warn("[gsm] multiple devices detected, keeping selected token", {
              selected: deviceToken,
              suggested: nextDeviceToken,
            })
          }
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
    return () => clearInterval(id)
  }, [deviceToken, setDeviceToken, setError, setIsReady])

  /**
   * 2. PUSHER EVENTS - Call State Management
   * Handles: device-connected, call-started, call-connected, call-ended, audio-chunk
   */
  useEffect(() => {
    const pusher = getPusherClient()
    const devicesChannel = pusher.subscribe("gsm-devices")
    const callsChannel = pusher.subscribe("gsm-calls")

    // Device connected to backend
    const onDeviceConnected = async (eventData: { deviceToken?: string }) => {
      if (eventData?.deviceToken) {
        setDeviceToken(eventData.deviceToken)
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
        if (data?.deviceToken) setDeviceToken(data.deviceToken)
        setIsReady(!!data?.isAuthorized)
      } catch {
        // no-op
      }
    }

    // Call initiated (dialing/ringing phase)
    const onCallStarted = (eventData: GsmCallsEvent) => {
      if (!deviceToken || eventData?.deviceToken !== deviceToken) return
      // DIALING PHASE: Phone is ringing, NOT connected yet
      setIsCalling(true)
      setIsConnected(false)
      console.info("[gsm/pusher] call-started (dialing phase)", { deviceToken })
    }

    // Call answered (person picked up)
    const onCallConnected = (eventData: GsmCallsEvent) => {
      if (!deviceToken || eventData?.deviceToken !== deviceToken) return
      // CONNECTED PHASE: Call was answered, person is on the line
      setIsCalling(false) // Stop ringing sound
      setIsConnected(true) // Start audio streaming
      console.info("[gsm/pusher] call-connected (answered)", { deviceToken })
    }

    // Call ended or rejected
    const onCallEnded = (eventData: GsmCallsEvent) => {
      if (!deviceToken || eventData?.deviceToken !== deviceToken) return
      setIsConnected(false)
      setIsCalling(false)
      // Stop audio streams and clear queues when call ends
      stopMicCapture()
      rxQueueRef.current.clear()
      nextRxSeqRef.current = 0
      rxEnqueueSeqRef.current = 0
      console.info("[gsm/pusher] call-ended (hangup/reject) - audio stopped", { deviceToken })
    }

    // Test audio events from Android
    const onTestAudioStarted = () => {
      console.info("[gsm/pusher] test-audio-started")
      isTestAudioActiveRef.current = true
      startMicCapture().catch((e) => console.error("[gsm/test-mode] mic error", e))
      ensurePlayoutLoop()
    }

    const onTestAudioStopped = () => {
      console.info("[gsm/pusher] test-audio-stopped")
      isTestAudioActiveRef.current = false
      stopMicCapture()
    }

    // Incoming audio from Android device (fallback path via Pusher)
    const onAudioChunk = (eventData: GsmCallsEvent) => {
      if (!deviceToken || eventData?.deviceToken !== deviceToken || !eventData?.audio) return
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
  }, [deviceToken, setDeviceToken, setIsReady, setIsCalling, setIsConnected])

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

    // Create audio context if needed
    if (!audioContextRef.current) {
      audioContextRef.current = new AudioContext({ sampleRate: 16000 })
      console.log("[gsm/audio] AudioContext created, state:", audioContextRef.current.state)
    }

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
        setIsReady(true)
        setError("")
        if (audioContextRef.current?.state === "suspended") audioContextRef.current.resume().catch(() => {})

        // 1. Re-read deviceToken from closure — ensure it's current at connect time
        if (!deviceToken) {
          console.error("[gsm/ws] connected but deviceToken is empty — browser will not receive audio")
          return
        }

        console.info("[gsm/ws] connected, registering browser peer", { deviceToken })
        ws.send(JSON.stringify({ role: "browser", deviceToken, dir: "toBrowser" }))  // 2. dir toBrowser = I want to receive
        ensurePlayoutLoop()  // 3. start playout immediately so TEST audio plays without waiting for call
      }

      ws.onerror = (event) => {
        setError("WebSocket connection error")
        console.error("[gsm/ws] error", event)
      }

      ws.onclose = (event) => {
        setIsReady(false)
        console.warn("[gsm/ws] closed", { code: event.code, reason: event.reason })

        if (!isUnmounted) {
          wsReconnectTimerRef.current = setTimeout(() => connect(), 1500)
        }
      }

      ws.onmessage = (ev) => {
        try {
          const pkt: AudioPacket = JSON.parse(ev.data)
          if (pkt.deviceToken !== deviceToken || pkt.dir !== "toBrowser") return

          if (pkt.audio) {
            enqueueBase64Audio(pkt.audio)
            console.debug(`[gsm/ws-rx] seq=${pkt.seq} size=${pkt.audio.length}`)
            ensurePlayoutLoop() // test-audio works even before CALL_CONNECTED arrives
            if (pkt.seq % 100 === 0) {
              if (duplexValidationModeRef.current) {
                console.log("[gsm/duplex-test] ws-rx toBrowser packet", pkt.seq)
              } else {
                console.log("[gsm/ws-rx] received audio packet", pkt.seq)
              }
            }
          }
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
    ensurePlayoutLoop()
  }, [isConnected])

  /**
   * 6. MICROPHONE CAPTURE - Outbound audio encoding
   * Captures mic input, converts to PCM16, sends via WebSocket (with Pusher fallback)
   */
  const startMicCapture = async () => {
    if (!audioContextRef.current || !wsRef.current || !deviceToken || isCleaningUpRef.current) return

    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
      mediaStreamRef.current = stream

      const source = audioContextRef.current.createMediaStreamSource(stream)
      const processor = audioContextRef.current.createScriptProcessor(1024, 1, 1)
      processorRef.current = processor

      processor.onaudioprocess = (e) => {
        if (isMuted || wsRef.current?.readyState !== WebSocket.OPEN) return
        if (isCleaningUpRef.current) return

        try {
          const inF32 = e.inputBuffer.getChannelData(0)
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

          if (wsRef.current?.readyState === WebSocket.OPEN) {
            wsRef.current.send(JSON.stringify(pkt))
            if (duplexValidationModeRef.current && pkt.seq % 100 === 0) {
              console.log("[gsm/duplex-test] ws-tx toAndroid packet", pkt.seq)
            }
          } else {
            // Fallback: Send via Next.js API route (handled by frontend server)
            fetch("/api/gsm/send-audio-chunk", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ audio, deviceToken }),
            }).catch(() => {
              // Silent: avoid spamming UI errors during temporary reconnects
            })
          }
        } catch (err) {
          console.error("[gsm/mic] capture error", err)
        }
      }

      source.connect(processor)
      processor.connect(audioContextRef.current.destination)
      console.info("[gsm] microphone capture started")
    } catch (err) {
      console.error("[gsm] microphone permission denied or error", err)
      setError(`Microphone error: ${err instanceof Error ? err.message : String(err)}`)
    }
  }

  const stopMicCapture = () => {
    mediaStreamRef.current?.getTracks().forEach((t) => t.stop())
    mediaStreamRef.current = null
    processorRef.current?.disconnect()
    processorRef.current = null
  }

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
    if (isConnected || duplexValidationModeRef.current) {
      startMicCapture().catch((e) => setError(String(e)))
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
      setIsCalling(true)
      const res = await fetch("/api/gsm/call-started", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ num, deviceToken }),
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

  return { call, hungUp, sendDTMF, stopTestAudio, isTestAudioActiveRef }
}
