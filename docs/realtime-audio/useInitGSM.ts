import { RefObject, useEffect, useRef } from 'react'
import { useCallingSetup } from '../store/useCallingSetup'
import { useGSM } from '../store/useGSM'

type AudioPacket = {
  role: 'browser' | 'android'
  deviceToken: string
  dir: 'toAndroid' | 'toBrowser'
  codec: 'pcm16'
  seq: number
  ts: number
  sampleRate: 16000
  audio: string
}

export const useInitGSM = (dtmfTimeoutRef: RefObject<NodeJS.Timeout | null>) => {
  const {
    callingSetup,
    num,
    dtmfTone,
    isConnected,
    isMuted,
    setDTMFTone,
    setIsReady,
    setError,
    setIsConnected,
    setIsCalling,
  } = useCallingSetup()
  const { deviceToken } = useGSM()

  const audioContextRef = useRef<AudioContext | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  const mediaStreamRef = useRef<MediaStream | null>(null)
  const processorRef = useRef<ScriptProcessorNode | null>(null)

  const seqTxRef = useRef(0)
  const rxQueueRef = useRef<Map<number, Float32Array>>(new Map())
  const nextRxSeqRef = useRef(0)
  const playoutTimerRef = useRef<NodeJS.Timeout | null>(null)

  // ~250ms playout delay, good enough for <=1000ms user requirement
  const PLAYOUT_INTERVAL_MS = 20
  const MAX_QUEUE = 80

  useEffect(() => {
    if (callingSetup !== 'gsm' || !deviceToken) return

    if (!audioContextRef.current) {
      audioContextRef.current = new AudioContext({ sampleRate: 16000 })
    }

    const token = process.env.NEXT_PUBLIC_BACKEND_BEARER
    const wsBase = process.env.NEXT_PUBLIC_WS_URL
    if (!token || !wsBase) {
      setError('Missing NEXT_PUBLIC_BACKEND_BEARER or NEXT_PUBLIC_WS_URL')
      return
    }

    const ws = new WebSocket(`${wsBase}/ws/audio?token=${encodeURIComponent(token)}`)
    wsRef.current = ws

    ws.onopen = () => {
      setIsReady(true)
      ws.send(JSON.stringify({ role: 'browser', deviceToken, dir: 'toAndroid' }))
    }

    ws.onerror = () => {
      setIsReady(false)
      setError('WS connection error')
    }

    ws.onclose = () => {
      setIsReady(false)
    }

    ws.onmessage = async (ev) => {
      try {
        const pkt: AudioPacket = JSON.parse(ev.data)
        if (pkt.deviceToken !== deviceToken || pkt.dir !== 'toBrowser') return

        const audioBytes = Uint8Array.from(atob(pkt.audio), (c) => c.charCodeAt(0))
        const int16 = new Int16Array(audioBytes.buffer)
        const f32 = new Float32Array(int16.length)
        for (let i = 0; i < int16.length; i++) f32[i] = int16[i] / 32768

        if (rxQueueRef.current.size > MAX_QUEUE) {
          // drop oldest backlog for low-load resilience
          const minKey = Math.min(...rxQueueRef.current.keys())
          rxQueueRef.current.delete(minKey)
          nextRxSeqRef.current = Math.max(nextRxSeqRef.current, minKey + 1)
        }
        rxQueueRef.current.set(pkt.seq, f32)
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

    processor.onaudioprocess = (e) => {
      if (isMuted || wsRef.current?.readyState !== WebSocket.OPEN) return

      const inF32 = e.inputBuffer.getChannelData(0)
      const i16 = new Int16Array(inF32.length)
      for (let i = 0; i < inF32.length; i++) {
        i16[i] = Math.max(-32768, Math.min(32767, inF32[i] * 32767))
      }

      const bytes = new Uint8Array(i16.buffer)
      const audio = btoa(String.fromCharCode(...bytes))

      const pkt: AudioPacket = {
        role: 'browser',
        deviceToken,
        dir: 'toAndroid',
        codec: 'pcm16',
        seq: seqTxRef.current++,
        ts: performance.now(),
        sampleRate: 16000,
        audio,
      }
      wsRef.current!.send(JSON.stringify(pkt))
    }

    source.connect(processor)
    processor.connect(audioContextRef.current.destination)
  }

  const stopMicCapture = () => {
    mediaStreamRef.current?.getTracks().forEach((t) => t.stop())
    mediaStreamRef.current = null
    processorRef.current?.disconnect()
    processorRef.current = null
  }

  useEffect(() => {
    if (isConnected) startMicCapture().catch((e) => setError(String(e)))
    else stopMicCapture()
  }, [isConnected])

  const call = async () => {
    if (!deviceToken) return
    setIsCalling(true)
    const res = await fetch('/api/gsm/call-started', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ num, deviceToken }),
    })
    if (!res.ok) throw new Error(await res.text())
    setIsConnected(true)
  }

  const hungUp = async () => {
    await fetch('/api/gsm/call-ended', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceToken }),
    })
    setIsConnected(false)
    setIsCalling(false)
  }

  const sendDTMF = async (digit: string) => {
    await fetch('/api/gsm/send-dtmf', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ digit, deviceToken }),
    })
    if (dtmfTimeoutRef.current) clearTimeout(dtmfTimeoutRef.current)
    setDTMFTone(digit)
  }

  return { call, hungUp, sendDTMF }
}
