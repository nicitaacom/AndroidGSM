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

function authOk(auth?: string) {
  return !!auth && auth === `Bearer ${BACKEND_AUTH_KEY}`
}

async function sendCommand(deviceToken: string, type: string, data: any = {}) {
  await pusher.trigger(`private-device-${deviceToken}`, 'command', {
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

app.post('/api/events', async (req, res) => {
  console.log('ℹ️ [api/events] request received')
  if (!authOk(req.headers.authorization)) {
    console.log('❌ [api/events] unauthorized')
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken, type, data } = req.body
  if (!deviceToken || !type) {
    console.log('❌ [api/events] missing deviceToken/type', { deviceToken, type })
    return res.status(400).json({ error: 'Missing deviceToken or type' })
  }

  console.log('ℹ️ [api/events] accepted', { deviceToken, type })

  connectedDevices.set(deviceToken, { deviceToken, lastSeen: new Date() })

  switch (type) {
    case 'CONNECTED':
      await pusher.trigger('gsm-devices', 'device-connected', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'CALL_STARTED':
      await pusher.trigger('gsm-calls', 'call-started', { deviceToken, number: data?.number, timestamp: new Date().toISOString() })
      break
    case 'CALL_CONNECTED':
      await pusher.trigger('gsm-calls', 'call-connected', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'CALL_ENDED':
      await pusher.trigger('gsm-calls', 'call-ended', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'DTMF_SENT':
      await sendCommand(deviceToken, 'SEND_DTMF', data)
      break
    default:
      break
  }

  res.json({ success: true })
})

app.post('/api/commands', async (req, res) => {
  console.log('ℹ️ [api/commands] request received')
  if (!authOk(req.headers.authorization)) {
    console.log('❌ [api/commands] unauthorized')
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
    console.log('❌ [pusher/auth] unauthorized')
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
    console.log('❌ [ws/audio] unauthorized connection')
    ws.close(1008, 'Unauthorized')
    return
  }

  console.log('✅ [ws/audio] connected')

  ws.on('message', (buf) => {
    let msg: any
    try {
      msg = JSON.parse(buf.toString('utf8'))
    } catch {
      return
    }

    const { role, deviceToken, dir } = msg
    if (!role || !deviceToken || !dir) return

    console.log('ℹ️ [ws/audio] packet', { role, deviceToken, dir, seq: msg.seq })

    if (role === 'browser') browserByDevice.set(deviceToken, ws)
    if (role === 'android') androidByDevice.set(deviceToken, ws)

    // Relay with sequence/timestamp untouched (receiver jitter buffer uses these)
    if (dir === 'toAndroid') {
      const peer = androidByDevice.get(deviceToken)
      if (peer?.readyState === WebSocket.OPEN) peer.send(JSON.stringify(msg))
    } else if (dir === 'toBrowser') {
      const peer = browserByDevice.get(deviceToken)
      if (peer?.readyState === WebSocket.OPEN) peer.send(JSON.stringify(msg))
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
