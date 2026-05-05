// Note: this is from backend gsm-outreach-tool github repository - this file exist only for context for AI
// this code hosted on VPS which allows bypass Vercel 60s API request timeout limit
import express from 'express'
import cors from 'cors'
import bodyParser from 'body-parser'
import http from 'http'
import { WebSocketServer, WebSocket } from 'ws'
import Pusher from 'pusher'
import * as dotenv from 'dotenv'

dotenv.config()

const BACKEND_AUTH_KEY = process.env.BACKEND_BEARER
const BACKEND_PORT = Number(process.env.BACKEND_PORT || 8080)

const FRONTEND_PORT = process.env.FRONTEND_PORT || 3000
const FRONTEND_URL = process.env.NODE_ENV === 'production' ? process.env.FRONTEND_URL : `http://localhost:${FRONTEND_PORT}`

const pusher = new Pusher({
  appId: process.env.PUSHER_APP_ID!,
  key: process.env.PUSHER_KEY!,
  secret: process.env.PUSHER_SECRET!,
  cluster: process.env.PUSHER_CLUSTER!,
  useTLS: true,
})

interface DeviceInfo {
  deviceToken: string
  lastSeen: Date
}

const connectedDevices = new Map<string, DeviceInfo>()

// One browser listener + one android uplink per device (single-user assumption)
const browserByDevice = new Map<string, WebSocket>()
const androidByDevice = new Map<string, WebSocket>()

// Debounce gsm:device-connected — only fire once per 5 min per device to save Pusher quota
const CONNECTED_DEBOUNCE_MS = 5 * 60 * 1000
const lastConnectedPusherFire = new Map<string, number>()

async function safeTrigger(channel: string, event: string, data: object) {
  try {
    await pusher.trigger(channel, event, data)
  } catch (err: any) {
    console.error(`❌ [pusher] trigger failed (${channel}/${event}): ${err?.message ?? err}`)
  }
}

const app = express()
app.use(cors())
app.use(bodyParser.json({ limit: '2mb' }))
app.use(bodyParser.urlencoded({ extended: true }))

// ---- CORS ----
const allowedOrigins = [
  FRONTEND_URL,
  `http://localhost:${FRONTEND_PORT}`,
  `http://127.0.0.1:${FRONTEND_PORT}`,
]

interface CorsOptions {
  origin: (origin: string | undefined, callback: (err: Error | null, allow?: boolean) => void) => void
  credentials: boolean
  methods: string[]
  allowedHeaders: string[]
  optionsSuccessStatus: number
}

const corsOptions: CorsOptions = {
  origin: (origin: string | undefined, callback: (err: Error | null, allow?: boolean) => void) => {
    if (!origin || allowedOrigins.includes(origin)) {
      callback(null, true)
    } else {
      callback(new Error(`CORS blocked for origin: ${origin}`), false)
    }
  },
  credentials: true,
  methods: ['GET', 'POST', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization'],
  optionsSuccessStatus: 200,
}

// APPLY CORS globally - this automatically handles pre-flight OPTIONS requests internally
app.use(cors(corsOptions))
// app.options('/api/*', cors(corsOptions)) // don't use it to fix throw new TypeError(`Missing parameter name at ${i}

function extractBearerToken(auth?: string): string {
  if (!auth) return ""
  const match = auth.match(/^Bearer\s+(.+)$/i)
  // Accept accidentally escaped '$' coming from env systems/UI copy-paste (e.g. \$ in token values).
  return (match?.[1] ?? auth).trim().replace(/\\\$/g, '$')
}

function authOk(auth?: string) {
  const incoming = extractBearerToken(auth)
  const expected = (BACKEND_AUTH_KEY || "").trim()
  return !!incoming && !!expected && incoming === expected
}

async function sendCommand(deviceToken: string, type: string, data: any = {}) {
  await safeTrigger(`private-device-${deviceToken}`, 'command', {
    type,
    data,
    timestamp: new Date().toISOString(),
  })
}

app.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    connectedDevices: connectedDevices.size,
    wsBrowserPeers: browserByDevice.size,
    wsAndroidPeers: androidByDevice.size,
    uptime: process.uptime(),
    timestamp: new Date().toISOString(),
  })
})

app.get('/api/devices', (_req, res) => {
  const now = Date.now()

  for (const [token, info] of [...connectedDevices.entries()]) {
    if (now - info.lastSeen.getTime() > 10000) {
      connectedDevices.delete(token)
      console.log('ℹ️ [api/devices] removed stale device', { token })
    }
  }

  const devices = Array.from(connectedDevices.values())
    .sort((a, b) => a.lastSeen.getTime() - b.lastSeen.getTime())
    .map((d) => ({
      deviceToken: d.deviceToken,
      lastSeen: d.lastSeen,
    }))

  console.log('ℹ️ [api/devices] returning devices', { count: devices.length })
  res.json({ devices })
})

app.get('/api/device-status/:deviceToken', (req, res) => {
  if (!authOk(req.headers.authorization)) {
    const incoming = extractBearerToken(req.headers.authorization)
    const expected = (BACKEND_AUTH_KEY || "").trim()
    console.log('❌ [api/device-status] unauthorized', {
      incomingToken: incoming,
      incomingLen: incoming.length,
      expectedToken: expected,
      expectedLen: expected.length,
      rawAuthorization: req.headers.authorization,
    })
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken } = req.params
  const now = Date.now()
  const device = connectedDevices.get(deviceToken)

  if (!device || now - device.lastSeen.getTime() > 10000) {
    if (device) {
      connectedDevices.delete(deviceToken)
      console.log('ℹ️ [api/device-status] removed stale device', { deviceToken })
    }
    return res.json({ isAuthorized: false, lastSeen: null })
  }

  console.log('✅ [api/device-status] active', { deviceToken, lastSeen: device.lastSeen.toISOString() })
  return res.json({
    isAuthorized: true,
    lastSeen: device.lastSeen.toISOString(),
    deviceToken: device.deviceToken,
  })
})

app.post('/api/events', async (req, res) => {
  if (!authOk(req.headers.authorization)) {
    const incoming = extractBearerToken(req.headers.authorization)
    const expected = (BACKEND_AUTH_KEY || "").trim()
    console.error('❌ [api/events] unauthorized', {
      incomingLen: incoming.length,
      expectedLen: expected.length,
    })
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken, type, data } = req.body
  if (!deviceToken || !type) {
    console.error('❌ [api/events] missing deviceToken/type')
    return res.status(400).json({ error: 'Missing deviceToken or type' })
  }

  console.log(`📱 [api/events] ${type}`, { deviceToken })

  connectedDevices.set(deviceToken, { deviceToken, lastSeen: new Date() })

  switch (type) {
    case 'CONNECTED':
    case 'CONNECTED_EXPLICIT': {
      const now = Date.now()
      const last = lastConnectedPusherFire.get(deviceToken) ?? 0
      const forced = type === 'CONNECTED_EXPLICIT'
      if (forced || now - last >= CONNECTED_DEBOUNCE_MS) {
        lastConnectedPusherFire.set(deviceToken, now)
        await safeTrigger('gsm-devices', 'gsm:device-connected', { deviceToken, timestamp: new Date().toISOString() })
        console.log(`✅ [api/events] CONNECTED pusher fired for ${deviceToken}${forced ? ' (explicit)' : ''}`)
      } else {
        console.log(`⏭️ [api/events] CONNECTED debounced for ${deviceToken} (next in ${Math.round((CONNECTED_DEBOUNCE_MS - (now - last)) / 1000)}s)`)
      }
      break
    }
    case 'DISCONNECTED':
      // Clear debounce so reconnect fires immediately next time
      lastConnectedPusherFire.delete(deviceToken)
      connectedDevices.delete(deviceToken)
      console.log(`📴 [api/events] DISCONNECTED ${deviceToken}`)
      break
    case 'CALL_STARTED':
      console.log(`📞 [api/events] CALL_STARTED to ${data?.number}`)
      await safeTrigger('gsm-calls', 'gsm:call-started', { deviceToken, number: data?.number, timestamp: new Date().toISOString() })
      break
    case 'CALL_CONNECTED':
      console.log(`✅ [api/events] CALL_CONNECTED - call is being answered`)
      await safeTrigger('gsm-calls', 'gsm:call-connected', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'CALL_ENDED':
      console.log(`❌ [api/events] CALL_ENDED`)
      await safeTrigger('gsm-calls', 'gsm:call-ended', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'DTMF_SENT':
      console.log(`🔢 [api/events] DTMF sent: ${data?.digit}`)
      await sendCommand(deviceToken, 'SEND_DTMF', data)
      break
    case 'TEST_AUDIO_STARTED':
      console.log(`🎧 [api/events] TEST_AUDIO_STARTED device=${deviceToken}`)
      await safeTrigger('gsm-calls', 'gsm:test-audio-started', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'TEST_AUDIO_STOPPED':
      console.log(`🛑 [api/events] TEST_AUDIO_STOPPED device=${deviceToken}`)
      await safeTrigger('gsm-calls', 'gsm:test-audio-stopped', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'AUDIO_CHUNK': {
      // Route audio to browser WebSocket peer (primary path — no Pusher cost)
      const browserPeer = browserByDevice.get(deviceToken)
      if (browserPeer?.readyState === WebSocket.OPEN) {
        try {
          browserPeer.send(
            JSON.stringify({
              role: 'android',
              deviceToken,
              dir: 'toBrowser',
              codec: data?.codec || 'pcm16',
              seq: data?.seq,
              ts: data?.ts,
              sampleRate: data?.sampleRate || 16000,
              audio: data?.audio,
            }),
          )
        } catch (err) {
          console.error('❌ [api/events/audio] failed to send to browser ws peer', String(err))
        }
      } else {
        // Pusher fallback only when no WS peer — avoid if possible to save quota
        console.warn(`⚠️ [ws/audio] no browser peer, dropping chunk (Pusher fallback disabled to save quota)`)
      }
      break
    }
    default:
      console.warn(`⚠️ [api/events] unknown event type: ${type}`)
      break
  }

  res.json({ success: true })
})

app.post('/api/commands', async (req, res) => {
  console.log('ℹ️ [api/commands] request received')
  if (!authOk(req.headers.authorization)) {
    const incoming = extractBearerToken(req.headers.authorization)
    const expected = (BACKEND_AUTH_KEY || "").trim()
    console.log('❌ [api/commands] unauthorized', {
      incomingToken: incoming,
      incomingLen: incoming.length,
      expectedToken: expected,
      expectedLen: expected.length,
      rawAuthorization: req.headers.authorization,
    })
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken, commands } = req.body
  if (!deviceToken || !commands?.type) {
    console.log('❌ [api/commands] missing payload', { deviceToken, type: commands?.type })
    return res.status(400).json({ error: 'Missing deviceToken or commands.type' })
  }

  console.log('ℹ️ [api/commands] accepted', { deviceToken, type: commands.type })

  await sendCommand(deviceToken, commands.type, commands.data || {})
  res.json({ success: true })
})

app.post('/pusher/auth', (req, res) => {
  console.log('ℹ️ [pusher/auth] request received')
  if (!authOk(req.headers.authorization)) {
    const incoming = extractBearerToken(req.headers.authorization)
    const expected = (BACKEND_AUTH_KEY || "").trim()
    console.log('❌ [pusher/auth] unauthorized', {
      incomingToken: incoming,
      incomingLen: incoming.length,
      expectedToken: expected,
      expectedLen: expected.length,
      rawAuthorization: req.headers.authorization,
    })
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { socket_id, channel_name } = req.body
  if (!socket_id || !channel_name) {
    console.log('❌ [pusher/auth] missing params')
    return res.status(400).json({ error: 'Missing params' })
  }

  console.log('✅ [pusher/auth] authorized', { channel_name })

  const authResponse = pusher.authorizeChannel(socket_id, channel_name)
  res.json(authResponse)
})

const server = http.createServer(app)
const wss = new WebSocketServer({ server, path: '/ws/audio' })

/**
 * Message schema (JSON text frame):
 * {
 *   role: 'browser' | 'android',
 *   deviceToken: string,
 *   dir: 'toAndroid' | 'toBrowser',
 *   codec: 'pcm16' | 'opus',
 *   seq: number,
 *   ts: number, // monotonic milliseconds
 *   sampleRate: 16000,
 *   audio: string // base64 payload
 * }
 */
wss.on('connection', (ws, req) => {
  const url = new URL(req.url || '', `http://${req.headers.host}`)
  const token = url.searchParams.get('token')
  if (!authOk(token ? `Bearer ${token}` : undefined)) {
    const incoming = (token || "").trim()
    const expected = (BACKEND_AUTH_KEY || "").trim()
    console.log('❌ [ws/audio] unauthorized connection', {
      incomingToken: incoming,
      incomingLen: incoming.length,
      expectedToken: expected,
      expectedLen: expected.length,
    })
    ws.close(1008, 'Unauthorized')
    return
  }

  console.log('✅ [ws/audio] connected')

  ws.on('message', async (buf) => {
    let msg: any
    try {
      msg = JSON.parse(buf.toString('utf8'))
    } catch {
      return
    }

    try {
      const { role, deviceToken, dir } = msg
      if (!role || !deviceToken || !dir) return

      // First message from browser is often a registration packet (no audio/seq yet).
      console.log('ℹ️ [ws/audio] packet', { role, deviceToken, dir, seq: msg.seq })

      if (role === 'browser') browserByDevice.set(deviceToken, ws)
      if (role === 'android') androidByDevice.set(deviceToken, ws)

      // Relay with sequence/timestamp untouched (receiver jitter buffer uses these)
      if (dir === 'toAndroid') {
        const peer = androidByDevice.get(deviceToken)
          console.log('[ws/audio] toAndroid relay', { deviceToken, hasPeer: !!peer, peerState: peer?.readyState, androidKeys: [...androidByDevice.keys()] })
        if (peer?.readyState === WebSocket.OPEN) {
          try {
            peer.send(JSON.stringify(msg))
          } catch (err) {
            console.error('❌ [ws/audio] failed to send to android peer', { deviceToken, err: String(err) })
          }
        } else if (msg.audio) {
          // Backward-compatible fallback: Android app currently receives media via Pusher command events.
          // Don't await here in the hot path to avoid blocking the message loop; sendCommand itself handles errors.
          void sendCommand(deviceToken, 'AUDIO_CHUNK', {
            audio: msg.audio,
            codec: msg.codec || 'pcm16',
            seq: msg.seq,
            ts: msg.ts,
            sampleRate: msg.sampleRate || 16000,
          })
          console.log('⚠️ [ws/audio] no android ws peer, relayed chunk via pusher command', { deviceToken, seq: msg.seq })
        }
      } else if (dir === 'toBrowser') {
        const peer = browserByDevice.get(deviceToken)
        console.log('[ws/audio] toBrowser relay', { deviceToken, hasPeer: !!peer, peerState: peer?.readyState, browserKeys: [...browserByDevice.keys()] })
        if (peer?.readyState === WebSocket.OPEN) {
          try {
            peer.send(JSON.stringify(msg))
          } catch (err) {
            console.error('❌ [ws/audio] failed to send to browser peer', { deviceToken, err: String(err) })
          }
        } else console.log('⚠️ [ws/audio] no browser ws peer for toBrowser packet', { deviceToken, seq: msg.seq })
      }
    } catch (err) {
      console.error('❌ [ws/audio] unexpected error processing message', { err: String(err), raw: msg })
    }
  })

  ws.on('close', () => {
    console.log('ℹ️ [ws/audio] disconnected')
    for (const [k, v] of browserByDevice) if (v === ws) browserByDevice.delete(k)
    for (const [k, v] of androidByDevice) if (v === ws) androidByDevice.delete(k)
  })
})

server.listen(BACKEND_PORT, '0.0.0.0', () => {
  console.log(`Backend listening on ${BACKEND_PORT}`)
})
