// Note: this is from backend gsm-outreach-tool github repository - this file exist only for context for AI
// this code hosted on VPS which allows bypass Vercel 60s API request timeout limit
import express from 'express'
import cors from 'cors'
import bodyParser from 'body-parser'
import http from 'http'
import { WebSocketServer, WebSocket } from 'ws'
import * as dotenv from 'dotenv'

dotenv.config()

const BACKEND_AUTH_KEY = process.env.BACKEND_BEARER
const BACKEND_PORT = Number(process.env.BACKEND_PORT || 8080)

const FRONTEND_PORT = process.env.FRONTEND_PORT || 3000
const FRONTEND_URL = process.env.NODE_ENV === 'production' ? process.env.FRONTEND_URL : `http://localhost:${FRONTEND_PORT}`

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

// every browser socket of the phone (2 tabs of the website = 2 sockets) + one android uplink per device
const browserByDevice = new Map<string, Set<WebSocket>>()
const androidByDevice = new Map<string, WebSocket>()

type BrowserEvent =
  | 'gsm:device-connected'
  | 'gsm:call-started'
  | 'gsm:call-connected'
  | 'gsm:call-ended'
  | 'gsm:test-audio-started'
  | 'gsm:test-audio-stopped'
  | 'gsm:music-stopped'
  | 'gsm:music-drops'
  | 'gsm:sms-opt-out'

// one frame to every open browser socket of the phone - returns how many got it
function sendToBrowsers(deviceToken: string, frame: string) {
  let sent = 0
  for (const peer of browserByDevice.get(deviceToken) ?? []) {
    if (peer.readyState !== WebSocket.OPEN) continue
    try {
      peer.send(frame)
      sent++
    } catch (err) {
      console.error('❌ [ws/audio] failed to send to browser peer', { deviceToken, err: String(err) })
    }
  }
  return sent
}

// a phone -> browser event over the browser's own /ws/audio socket - the one road, the audio already runs on it
function forwardToBrowser(deviceToken: string, type: BrowserEvent, data: object = {}) {
  const sent = sendToBrowsers(deviceToken, JSON.stringify({ type, deviceToken, ...data, timestamp: new Date().toISOString() }))
  serverLog(`[ws/audio] ${type} forwarded to ${sent} browser socket(s) for ${deviceToken}`)
}

// SMS blast (outreach-tool /dbr): POST /api/sms/send waits for the phone's own SMS_SENT / SMS_FAILED for its requestId
const SMS_SEND_TIMEOUT_MS = 30_000
type SmsSendResult = { sent: true } | { sent: false; reason: string }
const pendingSms = new Map<string, { resolve: (result: SmsSendResult) => void; timer: NodeJS.Timeout }>()

function resolvePendingSms(requestId: unknown, result: SmsSendResult) {
  if (typeof requestId !== 'string') return
  const pending = pendingSms.get(requestId)
  if (!pending) return
  clearTimeout(pending.timer)
  pendingSms.delete(requestId)
  pending.resolve(result)
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
    wsBrowserPeers: [...browserByDevice.values()].reduce((n, peers) => n + peers.size, 0),
    wsAndroidPeers: androidByDevice.size,
    uptime: process.uptime(),
    timestamp: new Date().toISOString(),
  })
})

app.get('/api/devices', (_req, res) => {
  const now = Date.now()

  for (const [token, info] of [...connectedDevices.entries()]) {
    if (now - info.lastSeen.getTime() > 30000) {
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

  if (!device || now - device.lastSeen.getTime() > 30000) {
    if (device) {
      connectedDevices.delete(deviceToken)
      console.log('ℹ️ [api/device-status] removed stale device', { deviceToken })
    }
    return res.json({ isAuthorized: false, lastSeen: null })
  }

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

  if (type !== 'AUDIO_CHUNK' && type !== 'CONNECTED') serverLog(`📱 [api/events] ${type}`, { deviceToken })

  connectedDevices.set(deviceToken, { deviceToken, lastSeen: new Date() })

  switch (type) {
    case 'CONNECTED':
      // heartbeat — lastSeen already updated above, nothing goes to the browser
      break
    case 'CONNECTED_EXPLICIT':
      serverLog(`✅ [api/events] device-connected: ${deviceToken}`)
      forwardToBrowser(deviceToken, 'gsm:device-connected')
      break
    case 'DISCONNECTED':
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
      forwardToBrowser(deviceToken, 'gsm:call-started', { number: data?.number })
      break
    case 'CALL_CONNECTED':
      serverLog(`✅ [api/events] CALL_CONNECTED - call is being answered`)
      forwardToBrowser(deviceToken, 'gsm:call-connected')
      break
    case 'CALL_ENDED':
      serverLog(`❌ [api/events] CALL_ENDED`)
      forwardToBrowser(deviceToken, 'gsm:call-ended')
      break
    case 'DTMF_SENT':
      console.log(`🔢 [api/events] DTMF sent: ${data?.digit}`)
      sendCommand(deviceToken, 'SEND_DTMF', data)
      break
    case 'TEST_AUDIO_STARTED':
      serverLog(`🎧 [api/events] TEST_AUDIO_STARTED device=${deviceToken}`)
      forwardToBrowser(deviceToken, 'gsm:test-audio-started')
      break
    case 'TEST_AUDIO_STOPPED':
      serverLog(`🛑 [api/events] TEST_AUDIO_STOPPED device=${deviceToken}`)
      forwardToBrowser(deviceToken, 'gsm:test-audio-stopped')
      break
    case 'SMS_SENT':
      resolvePendingSms(data?.requestId, { sent: true })
      break
    case 'SMS_FAILED':
      resolvePendingSms(data?.requestId, { sent: false, reason: String(data?.reason ?? 'failed') })
      break
    case 'SMS_OPT_OUT':
      // the phone's STOP receiver - outreach-tool /dbr (the browser) runs its opt-out route from this event
      serverLog(`🛑 [api/events] SMS_OPT_OUT from ${data?.smsToNumber}`)
      forwardToBrowser(deviceToken, 'gsm:sms-opt-out', { smsToNumber: data?.smsToNumber, replyText: data?.replyText ?? '' })
      break
    case 'AUDIO_CHUNK': {
      const sent = sendToBrowsers(
        deviceToken,
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
      if (!sent) console.warn(`⚠️ [ws/audio] no browser peer, dropping chunk`)
      break
    }
    default:
      console.warn(`⚠️ [api/events] unknown event type: ${type}`)
      break
  }

  res.json({ success: true })
})

app.post('/api/commands', async (req, res) => {
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

  if (commands.type !== 'SET_GAIN' && commands.type !== 'AUDIO_CHUNK') serverLog('ℹ️ [api/commands] accepted', { deviceToken, type: commands.type })

  // the call state reaches the browser at once - no wait for the phone to echo the event back over /ws/cmd
  if (commands.type === 'CALL_ENDED') {
    forwardToBrowser(deviceToken, 'gsm:call-ended')
  } else if (commands.type === 'CALL_STARTED' || commands.type === 'MAKE_CALL') {
    forwardToBrowser(deviceToken, 'gsm:call-started', { number: commands.data?.number })
  }

  sendCommand(deviceToken, commands.type, commands.data || {})
  res.json({ success: true })
})

// One SMS through the phone's SIM - the VM-sendScheduledSMS lambda calls this per scheduled_sms row (requestId = its
// idName) and marks the row sent / failed from the answer. The phone's result is the radio's own sent-intent result.
app.post('/api/sms/send', async (req, res) => {
  if (!authOk(req.headers.authorization)) return res.status(401).json({ error: 'Unauthorized' })

  const { deviceToken, simAccountId, simComponentName, smsToNumber, smsBody, requestId } = req.body ?? {}
  if (!deviceToken || !smsToNumber || !smsBody || !requestId) {
    return res.status(400).json({ error: 'Missing deviceToken, smsToNumber, smsBody or requestId' })
  }

  const cmdWs = androidCmdByDevice.get(deviceToken)
  if (cmdWs?.readyState !== WebSocket.OPEN) {
    serverLog(`📴 [api/sms/send] ${requestId}: phone offline (${deviceToken})`)
    const offline: SmsSendResult = { sent: false, reason: 'phone offline' }
    return res.json(offline)
  }
  if (pendingSms.has(requestId)) {
    const repeated: SmsSendResult = { sent: false, reason: 'already sending' }
    return res.json(repeated)
  }

  // the phone resolves the SIM by subscription id first, then by this slot - read from its own SIM_LIST entry
  const simSlotIndex = connectedDevices.get(deviceToken)?.sims?.find(sim => sim.id === simAccountId)?.simSlotIndex ?? null

  const result = await new Promise<SmsSendResult>(resolve => {
    const timer = setTimeout(() => {
      pendingSms.delete(requestId)
      resolve({ sent: false, reason: 'timeout' })
    }, SMS_SEND_TIMEOUT_MS)
    pendingSms.set(requestId, { resolve, timer })
    sendCommand(deviceToken, 'SEND_SMS', { requestId, smsToNumber, smsBody, simAccountId, simComponentName, simSlotIndex })
  })
  serverLog(`📲 [api/sms/send] ${requestId} -> ${smsToNumber}: ${result.sent ? 'sent' : result.reason}`)
  res.json(result)
})

// Android command WebSocket peers: deviceToken -> WebSocket (/ws/cmd)
const androidCmdByDevice = new Map<string, WebSocket>()

// Deliver a command to the Android device over /ws/cmd - no cmd socket = the phone is offline, the command is dropped
function sendCommand(deviceToken: string, type: string, data: any = {}) {
  const isLogged = type !== 'AUDIO_CHUNK' && type !== 'SET_GAIN'
  const cmdWs = androidCmdByDevice.get(deviceToken)
  if (cmdWs?.readyState !== WebSocket.OPEN) {
    if (isLogged) serverLog(`📴 [ws/cmd] no cmd socket for ${deviceToken}, ${type} dropped`)
    return
  }
  try {
    cmdWs.send(JSON.stringify({ type: 'command', cmdType: type, data, timestamp: new Date().toISOString() }))
    if (isLogged) serverLog(`✅ [ws/cmd] command sent to android: ${type}`)
  } catch (err) {
    console.error(`❌ [ws/cmd] failed to send command: ${err}`)
  }
}

const server = http.createServer(app)

// Single WebSocket server on /ws — reverse proxy only needs to forward one path.
// role=android-cmd → command channel; everything else → audio channel.
const wss = new WebSocketServer({ noServer: true })
const wsCmd = new WebSocketServer({ noServer: true })

server.on('upgrade', (req: http.IncomingMessage, socket: any, head: any) => {
  const url = new URL(req.url || '', `http://${req.headers.host}`)
  if (!url.pathname.startsWith('/ws/')) {
    socket.destroy()
    return
  }
  const role = url.searchParams.get('role')
  if (role === 'android-cmd') {
    wsCmd.handleUpgrade(req, socket, head, (ws: WebSocket) => wsCmd.emit('connection', ws, req))
  } else {
    wss.handleUpgrade(req, socket, head, (ws: WebSocket) => wss.emit('connection', ws, req))
  }
})

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

      // Log only registration packets (no audio data). Per-chunk relay is silent.
      if (msg.audio === undefined && msg.seq === undefined) {
        console.log('ℹ️ [ws/audio] register', { role, deviceToken, dir })
      }

      if (role === 'browser') {
        const peers = browserByDevice.get(deviceToken) ?? new Set<WebSocket>()
        peers.add(ws)
        browserByDevice.set(deviceToken, peers)
      }
      if (role === 'android') androidByDevice.set(deviceToken, ws)

      // Relay with sequence/timestamp untouched (receiver jitter buffer uses these)
      if (dir === 'toAndroid') {
        const peer = androidByDevice.get(deviceToken)
        if (peer?.readyState === WebSocket.OPEN) {
          try {
            peer.send(JSON.stringify(msg))
          } catch (err) {
            console.error('❌ [ws/audio] failed to send to android peer', { deviceToken, err: String(err) })
          }
        }
        // No android peer → silently drop. Browser is told via gsm:call-ended to stop the mic.
      } else if (dir === 'toBrowser') {
        // the audio reaches every browser socket of the phone too - only /dialer opens one today, so that is 1 tab
        sendToBrowsers(deviceToken, JSON.stringify(msg))
      }
    } catch (err) {
      console.error('❌ [ws/audio] unexpected error processing message', { err: String(err), raw: msg })
    }
  })

  ws.on('close', () => {
    console.log('ℹ️ [ws/audio] disconnected')
    for (const [k, peers] of browserByDevice) {
      peers.delete(ws)
      if (!peers.size) browserByDevice.delete(k)
    }
    // When the Android audio peer drops, the call audio path is gone — the browser gets gsm:call-ended
    // so the UI exits the in-call state even if the cmd-WS CALL_ENDED event was lost
    // (MIUI stopping the cmd thread, etc).
    for (const [k, v] of androidByDevice) {
      if (v === ws) {
        androidByDevice.delete(k)
        serverLog(`[ws/audio] android audio peer dropped, firing gsm:call-ended for ${k}`)
        forwardToBrowser(k, 'gsm:call-ended')
      }
    }
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
  connectedDevices.set(deviceToken, { ...connectedDevices.get(deviceToken), deviceToken, lastSeen: new Date() })
  serverLog(`✅ [ws/cmd] android connected: ${deviceToken}`)

  ws.on('message', (buf) => {
    try {
      const msg = JSON.parse(buf.toString('utf8'))
      if (msg.type !== 'event') return

      const { eventType, deviceToken: dt, data } = msg
      const tok = dt || deviceToken
      // Update lastSeen on every event, preserving all existing fields
      const existing = connectedDevices.get(tok)
      const updated: DeviceInfo = { ...existing, deviceToken: tok, lastSeen: new Date() }
      // Heartbeat and explicit-connect carry current service/test state from Android
      if (data?.isServiceActive !== undefined) updated.isServiceActive = !!data.isServiceActive
      if (data?.isTestActive !== undefined) updated.isTestActive = !!data.isTestActive
      connectedDevices.set(tok, updated)

      // CONNECTED is the 8s heartbeat — silence it to keep logs readable
      if (eventType !== 'CONNECTED') serverLog(`📱 [ws/cmd] event: ${eventType} from ${tok}`)

      // only state-change events go to the browser — not heartbeats
      switch (eventType) {
        case 'MUSIC_STOPPED': {
          // the phone stopped the song on its own (slow_internet / error / service_restart) -
          // outreach-tool's useSubscribeToMusicStopped pauses the music site and shows the toast (plan-03)
          const reason = typeof data?.reason === 'string' ? data.reason : 'unknown'
          serverLog(`🎵 [ws/cmd] MUSIC_STOPPED: ${tok} (${reason})`)
          forwardToBrowser(tok, 'gsm:music-stopped', { reason })
          break
        }
        case 'MUSIC_DROPS':
          // the phone's Bluetooth keeps reporting link trouble while the song plays = 50-100 ms pauses on the
          // headphones; outreach-tool's /dialer shows "Experiencing interruptions? [reboot phone]" - over the audio WS only
          serverLog(`🎧 [ws/cmd] MUSIC_DROPS: ${tok}`)
          forwardToBrowser(tok, 'gsm:music-drops')
          break
        case 'CONNECTED_EXPLICIT':
          serverLog(`✅ [ws/cmd] device-connected: ${tok}`)
          forwardToBrowser(tok, 'gsm:device-connected')
          break
        case 'CALL_CONNECTED':
          serverLog(`📞 [ws/cmd] CALL_CONNECTED: ${tok}`)
          forwardToBrowser(tok, 'gsm:call-connected')
          break
        case 'CALL_ENDED':
          serverLog(`❌ [ws/cmd] CALL_ENDED: ${tok}`)
          forwardToBrowser(tok, 'gsm:call-ended')
          break
        case 'TEST_AUDIO_STARTED': {
          serverLog(`🎧 [ws/cmd] TEST_AUDIO_STARTED: ${tok}`)
          const devTas = connectedDevices.get(tok); if (devTas) { devTas.isTestActive = true; devTas.isServiceActive = false }
          forwardToBrowser(tok, 'gsm:test-audio-started')
          break
        }
        case 'TEST_AUDIO_STOPPED': {
          serverLog(`🛑 [ws/cmd] TEST_AUDIO_STOPPED: ${tok}`)
          const devTasp = connectedDevices.get(tok); if (devTasp) devTasp.isTestActive = false
          forwardToBrowser(tok, 'gsm:test-audio-stopped')
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
        case 'SMS_SENT':
          resolvePendingSms(data?.requestId, { sent: true })
          break
        case 'SMS_FAILED':
          resolvePendingSms(data?.requestId, { sent: false, reason: String(data?.reason ?? 'failed') })
          break
        case 'SMS_OPT_OUT':
          serverLog(`🛑 [ws/cmd] SMS_OPT_OUT from ${data?.smsToNumber}`)
          forwardToBrowser(tok, 'gsm:sms-opt-out', { smsToNumber: data?.smsToNumber, replyText: data?.replyText ?? '' })
          break
        case 'CONNECTED':
          // heartbeat — lastSeen already updated above, nothing goes to the browser
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
    // The phone reconnects with the same deviceToken (a service restart, a reinstall over adb) and the NEW socket
    // registers before the OLD one's close arrives here. Deleting by token alone threw the new socket away:
    // heartbeats on it kept the device "Ready" on the website, while every command found no cmd socket and was
    // dropped - no call, no music (2026-11-02, 17:37). Only the socket that is still registered may unregister.
    if (androidCmdByDevice.get(deviceToken) !== ws) {
      console.log(`ℹ️ [ws/cmd] stale socket closed, the newer one stays registered: ${deviceToken}`)
      return
    }
    androidCmdByDevice.delete(deviceToken)
    connectedDevices.delete(deviceToken)
  })
})

server.listen(BACKEND_PORT, '0.0.0.0', () => {
  console.log(`Backend listening on ${BACKEND_PORT}`)
})
