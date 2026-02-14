// Note: this is from frontend github repository - this file exist only for context for AI
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

export const useInitGSM = (dtmfTimeoutRef: RefObject<NodeJS.Timeout | null>) => {
  const { callingSetup, num, dtmfTone, isConnected, isMuted, setDTMFTone, setIsReady, setError, setIsConnected, setIsCalling } =
    useCallingSetup()
  const { deviceToken, setDeviceToken } = useGSM()

  const audioContextRef = useRef<AudioContext | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const mediaStreamRef = useRef<MediaStream | null>(null)
  const processorRef = useRef<ScriptProcessorNode | null>(null)

  const seqTxRef = useRef(0)
  const rxQueueRef = useRef<Map<number, Float32Array>>(new Map())
  const nextRxSeqRef = useRef(0)
  const rxEnqueueSeqRef = useRef(0)
  const playoutTimerRef = useRef<NodeJS.Timeout | null>(null)

  // ~250ms playout delay, good enough for <=1000ms user requirement
  const PLAYOUT_INTERVAL_MS = 20
  const MAX_QUEUE = 80

  /**
   * Inbound audio pipeline used by both transports:
   * - primary: WebSocket `/ws/audio` (preferred media path)
   * - fallback: Pusher `gsm-calls` -> `audio-chunk` (backward compatibility)
   */
  const enqueueBase64Audio = (base64Audio: string) => {
    try {
      const audioBytes = Uint8Array.from(atob(base64Audio), c => c.charCodeAt(0))
      const int16 = new Int16Array(audioBytes.buffer)
      const f32 = new Float32Array(int16.length)
      for (let i = 0; i < int16.length; i++) f32[i] = int16[i] / 32768

      if (rxQueueRef.current.size > MAX_QUEUE) {
        const minKey = Math.min(...rxQueueRef.current.keys())
        rxQueueRef.current.delete(minKey)
        nextRxSeqRef.current = Math.max(nextRxSeqRef.current, minKey + 1)
      }

      const nextSeq = rxEnqueueSeqRef.current++
      rxQueueRef.current.set(nextSeq, f32)
    } catch {
      // ignore malformed packet
    }
  }

  useEffect(() => {
    async function fetchDeviceToken() {
      try {
        const res = await fetch("/api/gsm/status")
        if (!res.ok) throw new Error(await res.text())
        const data = await res.json()
        if (data?.deviceToken) {
          // Avoid token flapping when multiple devices are online.
          if (!deviceToken) {
            setDeviceToken(data.deviceToken)
          } else if (deviceToken !== data.deviceToken) {
            console.warn("[gsm/status] multiple devices online, keeping selected token", {
              selected: deviceToken,
              suggested: data.deviceToken,
            })
          }
          setIsReady(!!data.isAuthorized)
          console.info("[gsm/status] fetched", data)
        }
      } catch (error) {
        setError(String(error))
        console.error("[gsm/status] error", error)
      }
    }

    fetchDeviceToken()
    const id = setInterval(fetchDeviceToken, 5000)
    return () => clearInterval(id)
  }, [deviceToken, setDeviceToken, setError, setIsReady])

  useEffect(() => {
    const pusher = getPusherClient()
    const devicesChannel = pusher.subscribe("gsm-devices")
    const callsChannel = pusher.subscribe("gsm-calls")

    const onDeviceConnected = async (eventData: { deviceToken?: string }) => {
      // If payload has token, apply immediately; otherwise fallback to status endpoint.
      if (eventData?.deviceToken) {
        setDeviceToken(eventData.deviceToken)
        setIsReady(true)
        console.info("[gsm/pusher] device-connected", eventData)
        return
      }

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

    const onCallStarted = (eventData: GsmCallsEvent) => {
      if (!deviceToken || eventData?.deviceToken !== deviceToken) return
      setIsCalling(true)
      console.info("[gsm/pusher] call-started", eventData)
    }

    const onCallConnected = (eventData: GsmCallsEvent) => {
      if (!deviceToken || eventData?.deviceToken !== deviceToken) return
      setIsConnected(true)
      console.info("[gsm/pusher] call-connected", eventData)
    }

    const onCallEnded = (eventData: GsmCallsEvent) => {
      if (!deviceToken || eventData?.deviceToken !== deviceToken) return
      setIsConnected(false)
      setIsCalling(false)
      console.info("[gsm/pusher] call-ended", eventData)
    }

    const onAudioChunk = (eventData: GsmCallsEvent) => {
      if (!deviceToken || eventData?.deviceToken !== deviceToken || !eventData?.audio) return
      enqueueBase64Audio(eventData.audio)
    }

    // Bind only namespaced events from your PusherEventMap (`gsm:*`) to avoid duplication/confusion.
    devicesChannel.bind("gsm:device-connected", onDeviceConnected)
    callsChannel.bind("gsm:call-started", onCallStarted)
    callsChannel.bind("gsm:call-connected", onCallConnected)
    callsChannel.bind("gsm:call-ended", onCallEnded)
    callsChannel.bind("gsm:audio-chunk", onAudioChunk)

    return () => {
      devicesChannel.unbind("gsm:device-connected", onDeviceConnected)
      callsChannel.unbind("gsm:call-started", onCallStarted)
      callsChannel.unbind("gsm:call-connected", onCallConnected)
      callsChannel.unbind("gsm:call-ended", onCallEnded)
      callsChannel.unbind("gsm:audio-chunk", onAudioChunk)
      pusher.unsubscribe("gsm-devices")
      pusher.unsubscribe("gsm-calls")
    }
  }, [deviceToken, setDeviceToken, setIsReady, setIsCalling, setIsConnected])

  useEffect(() => {
    if (callingSetup !== "gsm" || !deviceToken) return
    console.info("[gsm/ws] init", { callingSetup, deviceToken })

    if (!audioContextRef.current) {
      audioContextRef.current = new AudioContext({ sampleRate: 16000 })
    }

    const bearerToken = process.env.NEXT_PUBLIC_BACKEND_BEARER
    const wsBase = process.env.NEXT_PUBLIC_WS_URL
    if (!bearerToken || !wsBase) {
      setError("Missing NEXT_PUBLIC_BACKEND_BEARER or NEXT_PUBLIC_WS_URL")
      console.error("[gsm/ws] missing env", { hasBearerToken: !!bearerToken, wsBase })
      return
    }

    console.info("[gsm/ws] connecting", { wsBase, deviceToken })
    const ws = new WebSocket(`${wsBase}/ws/audio?token=${encodeURIComponent(bearerToken)}`)
    wsRef.current = ws

    ws.onopen = () => {
      setIsReady(true)
      console.info("[gsm/ws] connected", { deviceToken })
      ws.send(JSON.stringify({ role: "browser", deviceToken, dir: "toAndroid" }))
    }

    ws.onerror = event => {
      setIsReady(false)
      setError("WS connection error")
      console.error("[gsm/ws] error", event)
    }

    ws.onclose = event => {
      setIsReady(false)
      console.warn("[gsm/ws] closed", { code: event.code, reason: event.reason, wasClean: event.wasClean })
    }

    ws.onmessage = async ev => {
      try {
        const pkt: AudioPacket = JSON.parse(ev.data)
        if (pkt.deviceToken !== deviceToken || pkt.dir !== "toBrowser") return

        enqueueBase64Audio(pkt.audio)
      } catch {
        // ignore malformed packet
      }
    }

    return () => {
      ws.close()
      wsRef.current = null
      stopMicCapture()
      if (playoutTimerRef.current) clearInterval(playoutTimerRef.current)
    }
  }, [callingSetup, deviceToken, setError, setIsReady])

  useEffect(() => {
    if (!isConnected || !audioContextRef.current) return
    if (playoutTimerRef.current) clearInterval(playoutTimerRef.current)

    playoutTimerRef.current = setInterval(() => {
      const seq = nextRxSeqRef.current
      const chunk = rxQueueRef.current.get(seq)
      if (!chunk) return

      rxQueueRef.current.delete(seq)
      nextRxSeqRef.current = seq + 1

      const ctx = audioContextRef.current!
      const buf = ctx.createBuffer(1, chunk.length, 16000)
      // TS DOM lib can infer chunk as Float32Array<ArrayBufferLike>; copyToChannel expects ArrayBuffer-backed view.
      const channelData = new Float32Array(chunk.length)
      channelData.set(chunk)
      buf.copyToChannel(channelData, 0)
      const src = ctx.createBufferSource()
      src.buffer = buf
      src.connect(ctx.destination)
      src.start()
    }, PLAYOUT_INTERVAL_MS)

    return () => {
      if (playoutTimerRef.current) clearInterval(playoutTimerRef.current)
    }
  }, [isConnected])

  const startMicCapture = async () => {
    if (!audioContextRef.current || !wsRef.current || !deviceToken) return
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    mediaStreamRef.current = stream

    const source = audioContextRef.current.createMediaStreamSource(stream)
    const processor = audioContextRef.current.createScriptProcessor(1024, 1, 1)
    processorRef.current = processor

    processor.onaudioprocess = e => {
      if (isMuted || wsRef.current?.readyState !== WebSocket.OPEN) return

      const inF32 = e.inputBuffer.getChannelData(0)
      const i16 = new Int16Array(inF32.length)
      for (let i = 0; i < inF32.length; i++) {
        i16[i] = Math.max(-32768, Math.min(32767, inF32[i] * 32767))
      }

      const bytes = new Uint8Array(i16.buffer)
      const audio = btoa(String.fromCharCode(...bytes))

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
      } else {
        // Backward-compatible fallback path via Next.js API route -> backend /api/commands (AUDIO_CHUNK)
        fetch("/api/gsm/send-audio-chunk", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ audio, deviceToken }),
        }).catch(() => {
          // no-op to avoid spamming UI errors during temporary reconnects
        })
      }
    }

    source.connect(processor)
    processor.connect(audioContextRef.current.destination)
  }

  const stopMicCapture = () => {
    mediaStreamRef.current?.getTracks().forEach(t => t.stop())
    mediaStreamRef.current = null
    processorRef.current?.disconnect()
    processorRef.current = null
  }

  useEffect(() => {
    if (isConnected) startMicCapture().catch(e => setError(String(e)))
    else stopMicCapture()
  }, [isConnected])

  const call = async () => {
    if (!deviceToken) return
    console.info("[gsm/call] start requested", { deviceToken, num })
    setIsCalling(true)
    const res = await fetch("/api/gsm/call-started", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ num, deviceToken }),
    })
    if (!res.ok) {
      const errorText = await res.text()
      console.error("[gsm/call] start failed", { status: res.status, errorText })
      throw new Error(errorText)
    }
    setIsConnected(true)
  }

  const hungUp = async () => {
    console.info("[gsm/call] end requested", { deviceToken })
    const res = await fetch("/api/gsm/call-ended", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ deviceToken }),
    })
    if (!res.ok) {
      const errorText = await res.text()
      console.error("[gsm/call] end failed", { status: res.status, errorText })
      throw new Error(errorText)
    }
    setIsConnected(false)
    setIsCalling(false)
  }

  const sendDTMF = async (digit: string) => {
    console.info("[gsm/dtmf] send", { digit, deviceToken })
    await fetch("/api/gsm/send-dtmf", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ digit, deviceToken }),
    })
    if (dtmfTimeoutRef.current) clearTimeout(dtmfTimeoutRef.current)
    setDTMFTone(digit)
  }

  return { call, hungUp, sendDTMF }
}
