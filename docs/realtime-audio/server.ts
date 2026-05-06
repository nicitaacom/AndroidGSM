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

interface SimAccount {
  id: string
  componentName: string
  label: string
  simSlotIndex: number
}

interface DeviceInfo {
  deviceToken: string
  lastSeen: Date
  sims?: SimAccount[]
  isServiceActive?: boolean
  isTestActive?: boolean
}

const connectedDevices = new Map<string, DeviceInfo>()

// Circular log buffer — last 200 lines, available via GET /api/logs
const logBuffer: string[] = []
const MAX_LOG_LINES = 200

function serverLog(...args: any[]) {
  const line = `[${new Date().toISOString()}] ${args.map(a => (typeof a === 'object' ? JSON.stringify(a) : String(a))).join(' ')}`
  logBuffer.push(line)
  if (logBuffer.length > MAX_LOG_LINES) logBuffer.shift()
  console.log(...args)
}

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

// sendCommand is defined below after androidCmdByDevice is initialized

app.get('/api/logs', (req, res) => {
  if (!authOk(req.headers.authorization)) return res.status(401).json({ error: 'Unauthorized' })
  const n = Math.min(Number((req.query as any).n ?? 50), 200)
  res.json({ logs: logBuffer.slice(-n) })
})

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
    sims: device.sims ?? [],
    isServiceActive: device.isServiceActive ?? false,
    isTestActive: device.isTestActive ?? false,
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

  serverLog(`📱 [api/events] ${type}`, { deviceToken })

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
        serverLog(`✅ [api/events] CONNECTED pusher fired for ${deviceToken}${forced ? ' (explicit)' : ''}`)
      } else {
        serverLog(`⏭️ [api/events] CONNECTED debounced for ${deviceToken} (next in ${Math.round((CONNECTED_DEBOUNCE_MS - (now - last)) / 1000)}s)`)
      }
      break
    }
    case 'DISCONNECTED':
      // Clear debounce so reconnect fires immediately next time
      lastConnectedPusherFire.delete(deviceToken)
      connectedDevices.delete(deviceToken)
      console.log(`📴 [api/events] DISCONNECTED ${deviceToken}`)
      break
    case 'SIM_LIST': {
      // Android sends its available SIM accounts at startup so the frontend can offer a picker
      const simsRaw: string = data?.sims ?? ''
      try {
        // Android serializes the list as Kotlin's List.toString() — parse it manually
        // Format: [{id=xxx, componentName=yyy, label=zzz, simSlotIndex=0}, ...]
        const matches = [...simsRaw.matchAll(/\{([^}]+)\}/g)]
        const sims: SimAccount[] = matches.map(m => {
          const pairs: Record<string, string> = {}
          m[1].split(', ').forEach(p => {
            const eq = p.indexOf('=')
            if (eq > 0) pairs[p.slice(0, eq).trim()] = p.slice(eq + 1).trim()
          })
          return {
            id: pairs.id ?? '',
            componentName: pairs.componentName ?? '',
            label: pairs.label ?? `SIM ${(Number(pairs.simSlotIndex) ?? 0) + 1}`,
            simSlotIndex: Number(pairs.simSlotIndex ?? 0),
          }
        })
        const device = connectedDevices.get(deviceToken)
        if (device) device.sims = sims
        console.log(`📱 [api/events] SIM_LIST for ${deviceToken}: ${sims.length} accounts`, sims.map(s => s.label))
      } catch (e) {
        console.error(`❌ [api/events] SIM_LIST parse error: ${e}`)
      }
      break
    }
    case 'CALL_STARTED':
      serverLog(`📞 [api/events] CALL_STARTED to ${data?.number}`)
      await safeTrigger('gsm-calls', 'gsm:call-started', { deviceToken, number: data?.number, timestamp: new Date().toISOString() })
      break
    case 'CALL_CONNECTED':
      serverLog(`✅ [api/events] CALL_CONNECTED - call is being answered`)
      await safeTrigger('gsm-calls', 'gsm:call-connected', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'CALL_ENDED':
      serverLog(`❌ [api/events] CALL_ENDED`)
      await safeTrigger('gsm-calls', 'gsm:call-ended', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'DTMF_SENT':
      console.log(`🔢 [api/events] DTMF sent: ${data?.digit}`)
      await sendCommand(deviceToken, 'SEND_DTMF', data)
      break
    case 'TEST_AUDIO_STARTED':
      serverLog(`🎧 [api/events] TEST_AUDIO_STARTED device=${deviceToken}`)
      await safeTrigger('gsm-calls', 'gsm:test-audio-started', { deviceToken, timestamp: new Date().toISOString() })
      break
    case 'TEST_AUDIO_STOPPED':
      serverLog(`🛑 [api/events] TEST_AUDIO_STOPPED device=${deviceToken}`)
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

  serverLog('ℹ️ [api/commands] accepted', { deviceToken, type: commands.type })

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

// Android command WebSocket peers: deviceToken -> WebSocket
// Used by /ws/cmd for persistent command delivery without Pusher
const androidCmdByDevice = new Map<string, WebSocket>()

// Deliver a command to the Android device over /ws/cmd (preferred) or Pusher fallback
async function sendCommand(deviceToken: string, type: string, data: any = {}) {
  const cmdWs = androidCmdByDevice.get(deviceToken)
  if (cmdWs?.readyState === WebSocket.OPEN) {
    try {
      cmdWs.send(JSON.stringify({ type: 'command', cmdType: type, data, timestamp: new Date().toISOString() }))
      serverLog(`✅ [ws/cmd] command sent to android: ${type}`)
      return
    } catch (err) {
      console.error(`❌ [ws/cmd] failed to send command: ${err}`)
    }
  }
  // Fallback to Pusher if WS not available
  await safeTrigger(`private-device-${deviceToken}`, 'command', {
    type,
    data,
    timestamp: new Date().toISOString(),
  })
}

const server = http.createServer(app)
const wss = new WebSocketServer({ server, path: '/ws/audio' })

// Persistent command channel: Android connects once at startup, stays connected.
// Handles heartbeats (updates lastSeen), receives commands with zero Pusher cost.
const wsCmd = new WebSocketServer({ server, path: '/ws/cmd' })

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

wsCmd.on('connection', (ws, req) => {
  const url = new URL(req.url || '', `http://${req.headers.host}`)
  const token = url.searchParams.get('token')
  const deviceToken = url.searchParams.get('deviceToken') || ''
  if (!authOk(token ? `Bearer ${token}` : undefined) || !deviceToken) {
    ws.close(1008, 'Unauthorized')
    return
  }

  androidCmdByDevice.set(deviceToken, ws)
  connectedDevices.set(deviceToken, { deviceToken, lastSeen: new Date(), sims: connectedDevices.get(deviceToken)?.sims })
  serverLog(`✅ [ws/cmd] android connected: ${deviceToken}`)

  ws.on('message', (buf) => {
    try {
      const msg = JSON.parse(buf.toString('utf8'))
      if (msg.type !== 'event') return

      const { eventType, deviceToken: dt, data } = msg
      const tok = dt || deviceToken
      // Update lastSeen on every event (heartbeat or otherwise)
      const existing = connectedDevices.get(tok)
      connectedDevices.set(tok, { deviceToken: tok, lastSeen: new Date(), sims: existing?.sims })

      serverLog(`📱 [ws/cmd] event: ${eventType} from ${tok}`)

      // Only forward state-change events to browser via Pusher — not heartbeats
      switch (eventType) {
        case 'CONNECTED_EXPLICIT':
          serverLog(`✅ [ws/cmd] device-connected: ${tok}`)
          safeTrigger('gsm-devices', 'gsm:device-connected', { deviceToken: tok, timestamp: new Date().toISOString() })
          break
        case 'CALL_CONNECTED':
          serverLog(`📞 [ws/cmd] CALL_CONNECTED: ${tok}`)
          safeTrigger('gsm-calls', 'gsm:call-connected', { deviceToken: tok, timestamp: new Date().toISOString() })
          break
        case 'CALL_ENDED':
          serverLog(`❌ [ws/cmd] CALL_ENDED: ${tok}`)
          safeTrigger('gsm-calls', 'gsm:call-ended', { deviceToken: tok, timestamp: new Date().toISOString() })
          break
        case 'TEST_AUDIO_STARTED': {
          serverLog(`🎧 [ws/cmd] TEST_AUDIO_STARTED: ${tok}`)
          const devTas = connectedDevices.get(tok); if (devTas) { devTas.isTestActive = true; devTas.isServiceActive = false }
          safeTrigger('gsm-calls', 'gsm:test-audio-started', { deviceToken: tok, timestamp: new Date().toISOString() })
          break
        }
        case 'TEST_AUDIO_STOPPED': {
          serverLog(`🛑 [ws/cmd] TEST_AUDIO_STOPPED: ${tok}`)
          const devTasp = connectedDevices.get(tok); if (devTasp) devTasp.isTestActive = false
          safeTrigger('gsm-calls', 'gsm:test-audio-stopped', { deviceToken: tok, timestamp: new Date().toISOString() })
          break
        }
        case 'SERVICE_STARTED': {
          serverLog(`▶️ [ws/cmd] SERVICE_STARTED: ${tok}`)
          const devSs = connectedDevices.get(tok); if (devSs) { devSs.isServiceActive = true; devSs.isTestActive = false }
          break
        }
        case 'SERVICE_STOPPED': {
          serverLog(`⏹️ [ws/cmd] SERVICE_STOPPED: ${tok}`)
          const devSsp = connectedDevices.get(tok); if (devSsp) devSsp.isServiceActive = false
          break
        }
        case 'SIM_LIST': {
          try {
            const simsRaw: string = data?.sims ?? ''
            const matches = [...simsRaw.matchAll(/\{([^}]+)\}/g)]
            const sims = matches.map(m => {
              const pairs: Record<string, string> = {}
              m[1].split(', ').forEach((p: string) => { const eq = p.indexOf('='); if (eq > 0) pairs[p.slice(0, eq).trim()] = p.slice(eq + 1).trim() })
              return { id: pairs.id ?? '', componentName: pairs.componentName ?? '', label: pairs.label ?? 'SIM', simSlotIndex: Number(pairs.simSlotIndex ?? 0) }
            })
            const dev = connectedDevices.get(tok)
            if (dev) dev.sims = sims
          } catch (e) { console.error('SIM_LIST parse error', e) }
          break
        }
        case 'CONNECTED':
          // heartbeat — lastSeen already updated above, no Pusher trigger needed
          break
        default:
          console.log(`⚠️ [ws/cmd] unknown event: ${eventType}`)
      }
    } catch (e) {
      console.error('❌ [ws/cmd] parse error', e)
    }
  })

  ws.on('close', () => {
    console.log(`ℹ️ [ws/cmd] android disconnected: ${deviceToken}`)
    androidCmdByDevice.delete(deviceToken)
  })
})

server.listen(BACKEND_PORT, '0.0.0.0', () => {
  console.log(`Backend listening on ${BACKEND_PORT}`)
})
