// the VPS between the phone (AndroidGSM) and the browser (outreach-tool /dialer): commands to the phone, the phone's
// events and the call audio to the browser. AndroidGSM/docs/realtime-audio/server.ts is a copy of this file
import express from 'express'
import cors from 'cors'
import bodyParser from 'body-parser'
import http from 'http'
import { WebSocketServer, WebSocket } from 'ws'
import * as dotenv from 'dotenv'

dotenv.config()

const BACKEND_PORT = Number(process.env.BACKEND_PORT || 8080)

// the user's own bearer, typed in outreach-tool's gear menu - his rule: "no validation except min 6 max 64 length"
const BEARER_MIN_LENGTH = 6
const BEARER_MAX_LENGTH = 64

const FRONTEND_PORT = process.env.FRONTEND_PORT || 3000
const FRONTEND_URL = process.env.NODE_ENV === 'production' ? process.env.FRONTEND_URL : `http://localhost:${FRONTEND_PORT}`

interface SimAccount {
  id: string
  componentName: string
  label: string
  simSlotIndex: number
  // the SIM's own number, '' when the phone sends none - the browser shows it under the carrier label
  number: string
  // the SIM's country ('de'), '' when unknown - a German SIM shows the recording consent line (outreach-tool plan-06)
  countryIso: string
}

// the phone's own Telecom state, sent with every heartbeat and at once on every change (CALL_STATE) - the browser's
// status poll reads it, so its card follows the phone even when a CALL_ENDED / CALL_CONNECTED event went missing
interface PhoneCall {
  callState: 'idle' | 'dialing' | 'active' | 'ringing'
  // the number of the call that is on ('' at idle or for a hidden number)
  callNumber: string
  // the browser's own id of that dial / answer ('' for a call started on the phone itself)
  callDialId: string
  // a call rings right now - alone, or waiting next to an active one
  isRinging: boolean
  ringingNumber: string
  // what is paired to the phone - it is the call's output and mic: 'bluetooth' | 'wired' | 'none'
  headset: string
  // where the browser's "Mic vol" stands on this call: 'bluetooth' | 'codec' | 'none' | ''
  micVol: string
}

interface DeviceInfo {
  deviceToken: string
  lastSeen: Date
  sims?: SimAccount[]
  isServiceActive?: boolean
  isTestActive?: boolean
  // undefined = a phone build that sends no call state
  call?: PhoneCall
}

// every map below keys on deviceKey(bearer, deviceToken) - one VPS, many users: a laptop reaches only the phones that
// connected with the same bearer, and 2 users with the same phone model stay apart
const connectedDevices = new Map<string, DeviceInfo>()

// Circular log buffer per bearer — last 200 lines each, available via GET /api/logs with that bearer
const logBuffers = new Map<string, string[]>()
const MAX_LOG_LINES = 200

function serverLog(bearer: string, ...args: any[]) {
  const line = `[${new Date().toISOString()}] ${args.map(a => (typeof a === 'object' ? JSON.stringify(a) : String(a))).join(' ')}`
  const logBuffer = logBuffers.get(bearer) ?? []
  logBuffer.push(line)
  if (logBuffer.length > MAX_LOG_LINES) logBuffer.shift()
  logBuffers.set(bearer, logBuffer)
  console.log(...args)
}

// every browser socket of the phone (2 tabs of the website = 2 sockets) + one android uplink per device
const browserByDevice = new Map<string, Set<WebSocket>>()
const androidByDevice = new Map<string, WebSocket>()
// the one browser socket that gets the call audio - the tab that sent { type: "audio-owner" } on Call / Answer;
// no owner = every browser socket of the phone gets it
const audioOwnerByDevice = new Map<string, WebSocket>()
// the call audio the phone sent while no browser socket of the phone was open (the laptop's Wi-Fi dropped): the newest
// HELD_CHUNKS_MAX chunks (20 ms each = 2 s), sent to the first socket that is back - it plays them at double speed and
// is on live sound again (outreach-tool useInitGSM drainAndSchedule)
type HeldChunk = { frame: string; heldAt: number }
const heldChunksByDevice = new Map<string, HeldChunk[]>()
const HELD_CHUNKS_MAX = 100
// a chunk that waited longer than this is the end of a call that is over, never a drop
const HELD_CHUNK_MAX_AGE_MS = 5000

type BrowserEvent =
  | 'gsm:device-connected'
  | 'gsm:call-started'
  | 'gsm:call-connected'
  | 'gsm:call-ended'
  | 'gsm:incoming-call'
  | 'gsm:incoming-call-ended'
  | 'gsm:call-log-row'
  | 'gsm:call-log'
  | 'gsm:recording-uploaded'
  | 'gsm:recording-failed'
  | 'gsm:test-audio-started'
  | 'gsm:test-audio-stopped'
  | 'gsm:music-stopped'
  | 'gsm:music-drops'
  | 'gsm:sms-opt-out'

// the VPS's part of a dial's quality log (outreach-tool dialer, 10.11.2026): the lead's voice from the answer to the
// hang-up - a long gap between 2 phone chunks = the phone's upload stalled, a browser socket backlog = the laptop's
// download lags. The phone's 📶 / 🌐 lines (DIAL_QUALITY) and the laptop's 💻 line (dial-quality) land next to it
type RelayStats = { startedAt: number; lastChunkAt: number; chunks: number; maxGapMs: number; slowGaps: number; maxBacklogBytes: number }
const relayStatsByDevice = new Map<string, RelayStats>()
const SLOW_GAP_MS = 300
// the phone sends 20 ms chunks - 50 a second is real time
const CHUNKS_PER_SECOND = 50
const MAX_DIAL_QUALITY_LINE = 2000

function countRelayedChunk(key: string, receivers: WebSocket[]) {
  const stats = relayStatsByDevice.get(key)
  if (!stats) return
  const now = Date.now()
  if (stats.lastChunkAt) {
    const gapMs = now - stats.lastChunkAt
    stats.maxGapMs = Math.max(stats.maxGapMs, gapMs)
    if (gapMs > SLOW_GAP_MS) stats.slowGaps++
  }
  stats.lastChunkAt = now
  stats.chunks++
  for (const receiver of receivers) stats.maxBacklogBytes = Math.max(stats.maxBacklogBytes, receiver.bufferedAmount)
}

function logRelayStats(bearer: string, key: string) {
  const stats = relayStatsByDevice.get(key)
  if (!stats) return
  relayStatsByDevice.delete(key)
  const durationSec = Math.max(1, Math.round((Date.now() - stats.startedAt) / 1000))
  const clock = `${Math.floor(durationSec / 60)}:${String(durationSec % 60).padStart(2, '0')}`
  serverLog(
    bearer,
    `🌐 DIAL ${clock} VPS relay · ${stats.chunks} chunks from the phone, ${(stats.chunks / durationSec).toFixed(1)}/s (${CHUNKS_PER_SECOND} = real time)` +
      ` · phone->VPS gaps over ${SLOW_GAP_MS} ms: ${stats.slowGaps}, longest ${stats.maxGapMs} ms` +
      ` · VPS->laptop backlog max ${Math.round(stats.maxBacklogBytes / 1024)} KB`,
  )
}

// one frame to every open browser socket of the phone - returns how many got it
function sendToBrowsers(bearer: string, deviceToken: string, frame: string) {
  let sent = 0
  for (const peer of browserByDevice.get(deviceKey(bearer, deviceToken)) ?? []) {
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

// a phone -> browser event over the browser's own /ws/audio socket - the one road, the audio already runs on it.
// The frame's own keys come last: an event's data with a `type` of its own (CALL_REPORT's "outgoing") in front of them
// takes the frame type's place, and the browser finds no handler for the frame
function forwardToBrowser(bearer: string, deviceToken: string, type: BrowserEvent, data: object = {}) {
  const sent = sendToBrowsers(bearer, deviceToken, JSON.stringify({ ...data, type, deviceToken, timestamp: new Date().toISOString() }))
  serverLog(bearer, `[ws/audio] ${type} forwarded to ${sent} browser socket(s) for ${deviceToken}`)
}

function holdChunk(key: string, frame: string) {
  const heldChunks = heldChunksByDevice.get(key) ?? []
  heldChunks.push({ frame, heldAt: Date.now() })
  if (heldChunks.length > HELD_CHUNKS_MAX) heldChunks.shift()
  heldChunksByDevice.set(key, heldChunks)
}

// the browser socket that is back gets what it missed, in the order the phone sent it
function sendHeldChunks(bearer: string, key: string, receiver: WebSocket) {
  const heldChunks = heldChunksByDevice.get(key)
  if (!heldChunks) return
  heldChunksByDevice.delete(key)
  const now = Date.now()
  const frames = heldChunks.filter(({ heldAt }) => now - heldAt <= HELD_CHUNK_MAX_AGE_MS).map(({ frame }) => frame)
  if (!frames.length) return
  try {
    for (const frame of frames) receiver.send(frame)
    const heldMs = Math.round((frames.length * 1000) / CHUNKS_PER_SECOND)
    serverLog(bearer, `⏩ [ws/audio] ${frames.length} held chunks (${heldMs} ms) sent to the browser socket that is back`)
  } catch (err) {
    console.error('❌ [ws/audio] failed to send the held chunks', { err: String(err) })
  }
}

// SMS blast (outreach-tool /dbr): POST /api/sms/send waits for the phone's own SMS_SENT / SMS_FAILED for its requestId
const SMS_SEND_TIMEOUT_MS = 30_000
type SmsSendResult = { sent: true } | { sent: false; reason: string }
// keyed `${deviceKey}:${requestId}` - 2 users' schedules may share an idName
const pendingSms = new Map<string, { resolve: (result: SmsSendResult) => void; timer: NodeJS.Timeout }>()

function resolvePendingSms(key: string, requestId: unknown, result: SmsSendResult) {
  if (typeof requestId !== 'string') return
  const pending = pendingSms.get(`${key}:${requestId}`)
  if (!pending) return
  clearTimeout(pending.timer)
  pendingSms.delete(`${key}:${requestId}`)
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

// the request's own bearer, or null - any value of 6 to 64 chars is a user's bearer, the maps below split by it
function authOk(auth?: string): string | null {
  const incoming = extractBearerToken(auth)
  return incoming.length >= BEARER_MIN_LENGTH && incoming.length <= BEARER_MAX_LENGTH ? incoming : null
}

// one VPS, many users: a phone and its laptops meet on `${bearer}:${deviceToken}`
function deviceKey(bearer: string, deviceToken: string) {
  return `${bearer}:${deviceToken}`
}

function deviceTokenOf(key: string, bearer: string) {
  return key.slice(bearer.length + 1)
}

// sendCommand is defined below after androidCmdByDevice is initialized

app.get('/api/logs', (req, res) => {
  const bearer = authOk(req.headers.authorization)
  if (!bearer) return res.status(401).json({ error: 'Unauthorized' })
  const n = Math.min(Number((req.query as any).n ?? 50), 200)
  res.json({ logs: (logBuffers.get(bearer) ?? []).slice(-n) })
})

// the phone's upload test when its music stops for slow internet (outreach-tool plan-03 task 12, his words: "it should
// show internet on a laptop and on a phone") - the body is octet-stream zeros, so bodyParser.json leaves it alone and the
// stream is counted here as it arrives
app.post('/api/speed-test', (req, res) => {
  const bearer = authOk(req.headers.authorization)
  if (!bearer) return res.status(401).json({ error: 'Unauthorized' })
  let bytes = 0
  req.on('data', (chunk: Buffer) => {
    bytes += chunk.length
  })
  req.on('end', () => res.json({ bytes }))
  req.on('error', error => res.status(400).json({ error: error.message }))
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

app.get('/api/devices', (req, res) => {
  const bearer = authOk(req.headers.authorization)
  if (!bearer) return res.status(401).json({ error: 'Unauthorized' })
  const now = Date.now()

  for (const [key, info] of [...connectedDevices.entries()]) {
    if (now - info.lastSeen.getTime() > 30000) {
      connectedDevices.delete(key)
      console.log('ℹ️ [api/devices] removed stale device', { deviceToken: info.deviceToken })
    }
  }

  // only the devices that connected with this request's bearer
  const devices = [...connectedDevices.entries()]
    .filter(([key]) => key.startsWith(`${bearer}:`))
    .map(([, d]) => d)
    .sort((a, b) => a.lastSeen.getTime() - b.lastSeen.getTime())
    .map((d) => ({
      deviceToken: d.deviceToken,
      lastSeen: d.lastSeen,
    }))

  res.json({ devices })
})

app.get('/api/device-status/:deviceToken', (req, res) => {
  const bearer = authOk(req.headers.authorization)
  if (!bearer) {
    console.log('❌ [api/device-status] unauthorized', { incomingLen: extractBearerToken(req.headers.authorization).length })
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken } = req.params
  const key = deviceKey(bearer, deviceToken)
  const now = Date.now()
  const device = connectedDevices.get(key)

  if (!device || now - device.lastSeen.getTime() > 30000) {
    if (device) {
      connectedDevices.delete(key)
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
    // null = this phone's build sends no call state yet
    call: device.call ?? null,
  })
})

app.post('/api/events', async (req, res) => {
  const bearer = authOk(req.headers.authorization)
  if (!bearer) {
    console.error('❌ [api/events] unauthorized', { incomingLen: extractBearerToken(req.headers.authorization).length })
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken, type, data } = req.body
  if (!deviceToken || !type) {
    console.error('❌ [api/events] missing deviceToken/type')
    return res.status(400).json({ error: 'Missing deviceToken or type' })
  }
  const key = deviceKey(bearer, deviceToken)

  if (type !== 'AUDIO_CHUNK' && type !== 'CONNECTED') serverLog(bearer, `📱 [api/events] ${type}`, { deviceToken })

  connectedDevices.set(key, { deviceToken, lastSeen: new Date() })

  switch (type) {
    case 'CONNECTED':
      // heartbeat — lastSeen already updated above, nothing goes to the browser
      break
    case 'CONNECTED_EXPLICIT':
      serverLog(bearer, `✅ [api/events] device-connected: ${deviceToken}`)
      forwardToBrowser(bearer, deviceToken, 'gsm:device-connected')
      break
    case 'DISCONNECTED':
      connectedDevices.delete(key)
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
            number: pairs.number ?? '',
            countryIso: pairs.countryIso ?? '',
          }
        })
        const device = connectedDevices.get(key)
        if (device) device.sims = sims
        console.log(`📱 [api/events] SIM_LIST for ${deviceToken}: ${sims.length} accounts`, sims.map(s => s.label))
      } catch (e) {
        console.error(`❌ [api/events] SIM_LIST parse error: ${e}`)
      }
      break
    }
    case 'CALL_STARTED':
      serverLog(bearer, `📞 [api/events] CALL_STARTED to ${data?.number}`)
      forwardToBrowser(bearer, deviceToken, 'gsm:call-started', { number: data?.number })
      break
    case 'CALL_CONNECTED':
      serverLog(bearer, `✅ [api/events] CALL_CONNECTED - call is being answered`)
      forwardToBrowser(bearer, deviceToken, 'gsm:call-connected')
      break
    case 'CALL_ENDED':
      serverLog(bearer, `❌ [api/events] CALL_ENDED`)
      forwardToBrowser(bearer, deviceToken, 'gsm:call-ended')
      break
    case 'DTMF_SENT':
      console.log(`🔢 [api/events] DTMF sent: ${data?.digit}`)
      sendCommand(bearer, deviceToken, 'SEND_DTMF', data)
      break
    case 'TEST_AUDIO_STARTED':
      serverLog(bearer, `🎧 [api/events] TEST_AUDIO_STARTED device=${deviceToken}`)
      forwardToBrowser(bearer, deviceToken, 'gsm:test-audio-started')
      break
    case 'TEST_AUDIO_STOPPED':
      serverLog(bearer, `🛑 [api/events] TEST_AUDIO_STOPPED device=${deviceToken}`)
      forwardToBrowser(bearer, deviceToken, 'gsm:test-audio-stopped')
      break
    case 'SMS_SENT':
      resolvePendingSms(key, data?.requestId, { sent: true })
      break
    case 'SMS_FAILED':
      resolvePendingSms(key, data?.requestId, { sent: false, reason: String(data?.reason ?? 'failed') })
      break
    case 'SMS_OPT_OUT':
      // the phone's STOP receiver - outreach-tool /dbr (the browser) runs its opt-out route from this event
      serverLog(bearer, `🛑 [api/events] SMS_OPT_OUT from ${data?.smsToNumber}`)
      forwardToBrowser(bearer, deviceToken, 'gsm:sms-opt-out', { smsToNumber: data?.smsToNumber, replyText: data?.replyText ?? '' })
      break
    case 'AUDIO_CHUNK': {
      const sent = sendToBrowsers(
        bearer,
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
  const bearer = authOk(req.headers.authorization)
  if (!bearer) {
    console.log('❌ [api/commands] unauthorized', { incomingLen: extractBearerToken(req.headers.authorization).length })
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken, commands } = req.body
  if (!deviceToken || !commands?.type) {
    console.log('❌ [api/commands] missing payload', { deviceToken, type: commands?.type })
    return res.status(400).json({ error: 'Missing deviceToken or commands.type' })
  }

  if (commands.type !== 'SET_GAIN' && commands.type !== 'AUDIO_CHUNK') serverLog(bearer, 'ℹ️ [api/commands] accepted', { deviceToken, type: commands.type })

  // the call state reaches the browser at once - no wait for the phone to echo the event back over /ws/cmd
  // dialId = the browser's own id of the dial it starts / ends - a frame of the dial before never ends the new one
  if (commands.type === 'CALL_ENDED') {
    forwardToBrowser(bearer, deviceToken, 'gsm:call-ended', { dialId: commands.data?.dialId })
  } else if (commands.type === 'CALL_STARTED' || commands.type === 'MAKE_CALL') {
    forwardToBrowser(bearer, deviceToken, 'gsm:call-started', { number: commands.data?.number, dialId: commands.data?.dialId })
  }

  sendCommand(bearer, deviceToken, commands.type, commands.data || {})
  res.json({ success: true })
})

// One SMS through the phone's SIM - the VM-sendScheduledSMS lambda calls this per scheduled_sms row (requestId = its
// idName) and marks the row sent / failed from the answer. The phone's result is the radio's own sent-intent result.
app.post('/api/sms/send', async (req, res) => {
  const bearer = authOk(req.headers.authorization)
  if (!bearer) return res.status(401).json({ error: 'Unauthorized' })

  const { deviceToken, simAccountId, simComponentName, smsToNumber, smsBody, requestId } = req.body ?? {}
  if (!deviceToken || !smsToNumber || !smsBody || !requestId) {
    return res.status(400).json({ error: 'Missing deviceToken, smsToNumber, smsBody or requestId' })
  }
  const key = deviceKey(bearer, deviceToken)
  const pendingKey = `${key}:${requestId}`

  const cmdWs = androidCmdByDevice.get(key)
  if (cmdWs?.readyState !== WebSocket.OPEN) {
    serverLog(bearer, `📴 [api/sms/send] ${requestId}: phone offline (${deviceToken})`)
    const offline: SmsSendResult = { sent: false, reason: 'phone offline' }
    return res.json(offline)
  }
  if (pendingSms.has(pendingKey)) {
    const repeated: SmsSendResult = { sent: false, reason: 'already sending' }
    return res.json(repeated)
  }

  // the phone resolves the SIM by subscription id first, then by this slot - read from its own SIM_LIST entry
  const simSlotIndex = connectedDevices.get(key)?.sims?.find(sim => sim.id === simAccountId)?.simSlotIndex ?? null

  const result = await new Promise<SmsSendResult>(resolve => {
    const timer = setTimeout(() => {
      pendingSms.delete(pendingKey)
      resolve({ sent: false, reason: 'timeout' })
    }, SMS_SEND_TIMEOUT_MS)
    pendingSms.set(pendingKey, { resolve, timer })
    sendCommand(bearer, deviceToken, 'SEND_SMS', { requestId, smsToNumber, smsBody, simAccountId, simComponentName, simSlotIndex })
  })
  serverLog(bearer, `📲 [api/sms/send] ${requestId} -> ${smsToNumber}: ${result.sent ? 'sent' : result.reason}`)
  res.json(result)
})

// Android command WebSocket peers: deviceKey -> WebSocket (/ws/cmd)
const androidCmdByDevice = new Map<string, WebSocket>()

// Deliver a command to the Android device over /ws/cmd - no cmd socket = the phone is offline, the command is dropped
function sendCommand(bearer: string, deviceToken: string, type: string, data: any = {}) {
  const isLogged = type !== 'AUDIO_CHUNK' && type !== 'SET_GAIN'
  const cmdWs = androidCmdByDevice.get(deviceKey(bearer, deviceToken))
  if (cmdWs?.readyState !== WebSocket.OPEN) {
    if (isLogged) serverLog(bearer, `📴 [ws/cmd] no cmd socket for ${deviceToken}, ${type} dropped`)
    return
  }
  try {
    cmdWs.send(JSON.stringify({ type: 'command', cmdType: type, data, timestamp: new Date().toISOString() }))
    if (isLogged) serverLog(bearer, `✅ [ws/cmd] command sent to android: ${type}`)
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
  const bearer = authOk(token ? `Bearer ${token}` : undefined)
  if (!bearer) {
    console.log('❌ [ws/audio] unauthorized connection', { incomingLen: (token || "").trim().length })
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
      // the tab that pressed Call / Answer claims the call audio of this phone
      if (msg.type === 'audio-owner' && msg.deviceToken) {
        audioOwnerByDevice.set(deviceKey(bearer, msg.deviceToken), ws)
        console.log('ℹ️ [ws/audio] audio-owner', { deviceToken: msg.deviceToken })
        return
      }

      // the laptop's 💻 line of a dial that just ended (outreach-tool useInitGSM) - into the Logs box
      if (msg.type === 'dial-quality' && typeof msg.line === 'string') {
        serverLog(bearer, msg.line.slice(0, MAX_DIAL_QUALITY_LINE))
        return
      }

      const { role, deviceToken, dir } = msg
      if (!role || !deviceToken || !dir) return
      const key = deviceKey(bearer, deviceToken)

      // Log only registration packets (no audio data). Per-chunk relay is silent.
      if (msg.audio === undefined && msg.seq === undefined) {
        console.log('ℹ️ [ws/audio] register', { role, deviceToken, dir })
      }

      if (role === 'browser') {
        const peers = browserByDevice.get(key) ?? new Set<WebSocket>()
        peers.add(ws)
        browserByDevice.set(key, peers)
        sendHeldChunks(bearer, key, ws)
      }
      if (role === 'android') androidByDevice.set(key, ws)

      // Relay with sequence/timestamp untouched (receiver jitter buffer uses these)
      if (dir === 'toAndroid') {
        const peer = androidByDevice.get(key)
        if (peer?.readyState === WebSocket.OPEN) {
          try {
            peer.send(JSON.stringify(msg))
          } catch (err) {
            console.error('❌ [ws/audio] failed to send to android peer', { deviceToken, err: String(err) })
          }
        }
        // No android peer → silently drop. Browser is told via gsm:call-ended to stop the mic.
      } else if (dir === 'toBrowser') {
        // the tab that claimed the audio gets it; no claim = every browser socket of the phone (1 tab on /dialer today)
        const frame = JSON.stringify(msg)
        const owner = audioOwnerByDevice.get(key)
        if (owner?.readyState === WebSocket.OPEN) {
          try {
            owner.send(frame)
          } catch (err) {
            console.error('❌ [ws/audio] failed to send to the audio-owner peer', { deviceToken, err: String(err) })
          }
          countRelayedChunk(key, [owner])
        } else {
          // no open browser socket = the laptop's internet dropped - the audio waits for the socket that comes back
          const sentCount = sendToBrowsers(bearer, deviceToken, frame)
          if (!sentCount && msg.audio !== undefined) holdChunk(key, frame)
          countRelayedChunk(key, [...(browserByDevice.get(key) ?? [])])
        }
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
    for (const [k, owner] of audioOwnerByDevice) if (owner === ws) audioOwnerByDevice.delete(k)
    // The Android audio peer dropped. The audio socket runs over the phone's internet, the call over the SIM - a
    // Wi-Fi drop ends the socket and never the call, and gsm:call-ended here leaves the browser on "Ready" with the
    // lead still on the line. A phone that reports its call state ends its calls itself (CALL_ENDED with the dialId,
    // callState idle in the status poll); only a build that reports none still gets this frame
    for (const [k, v] of androidByDevice) {
      if (v === ws) {
        androidByDevice.delete(k)
        const deviceToken = deviceTokenOf(k, bearer)
        logRelayStats(bearer, k)
        const call = connectedDevices.get(k)?.call
        if (call) {
          serverLog(bearer, `[ws/audio] android audio peer dropped for ${deviceToken}, the phone's call state: ${call.callState}`)
          continue
        }
        serverLog(bearer, `[ws/audio] android audio peer dropped, firing gsm:call-ended for ${deviceToken}`)
        forwardToBrowser(bearer, deviceToken, 'gsm:call-ended')
      }
    }
  })
})

wsCmd.on('connection', (ws, req) => {
  const url = new URL(req.url || '', `http://${req.headers.host}`)
  const token = url.searchParams.get('token')
  const deviceToken = url.searchParams.get('deviceToken') || ''
  const bearer = authOk(token ? `Bearer ${token}` : undefined)
  if (!bearer || !deviceToken) {
    ws.close(1008, 'Unauthorized')
    return
  }
  const key = deviceKey(bearer, deviceToken)

  androidCmdByDevice.set(key, ws)
  connectedDevices.set(key, { ...connectedDevices.get(key), deviceToken, lastSeen: new Date() })
  serverLog(bearer, `✅ [ws/cmd] android connected: ${deviceToken}`)

  ws.on('message', (buf) => {
    try {
      const msg = JSON.parse(buf.toString('utf8'))
      if (msg.type !== 'event') return

      const { eventType, deviceToken: dt, data } = msg
      const tok = dt || deviceToken
      const tokKey = deviceKey(bearer, tok)
      // Update lastSeen on every event, preserving all existing fields
      const existing = connectedDevices.get(tokKey)
      const updated: DeviceInfo = { ...existing, deviceToken: tok, lastSeen: new Date() }
      // Heartbeat and explicit-connect hold the current service/test state from Android
      if (data?.isServiceActive !== undefined) updated.isServiceActive = !!data.isServiceActive
      if (data?.isTestActive !== undefined) updated.isTestActive = !!data.isTestActive
      // the heartbeat, the explicit connect and CALL_STATE all hold the phone's own call state
      if (typeof data?.callState === 'string') {
        updated.call = {
          callState: data.callState,
          callNumber: typeof data.callNumber === 'string' ? data.callNumber : '',
          callDialId: typeof data.callDialId === 'string' ? data.callDialId : '',
          isRinging: data.isRinging === true,
          ringingNumber: typeof data.ringingNumber === 'string' ? data.ringingNumber : '',
          headset: typeof data.headset === 'string' ? data.headset : 'none',
          micVol: typeof data.micVol === 'string' ? data.micVol : '',
        }
      }
      connectedDevices.set(tokKey, updated)

      // CONNECTED is the 8s heartbeat — silence it to keep logs readable; DIAL_QUALITY and CALL_STATE have their own line
      if (eventType !== 'CONNECTED' && eventType !== 'DIAL_QUALITY' && eventType !== 'CALL_STATE') {
        serverLog(bearer, `📱 [ws/cmd] event: ${eventType} from ${tok}`)
      }

      // only state-change events go to the browser — not heartbeats
      switch (eventType) {
        case 'MUSIC_STOPPED': {
          // the phone stopped the song on its own (slow_internet / error / service_restart) -
          // outreach-tool's useSubscribeToMusicStopped pauses the music site and shows the toast (plan-03)
          const reason = typeof data?.reason === 'string' ? data.reason : 'unknown'
          // slow_internet holds the phone's own speed in Mbit/s (plan-03 task 12) - a half the phone could not
          // measure is left out, the toast shows "not measured" for it
          const downloadMbps = typeof data?.downloadMbps === 'number' ? data.downloadMbps : undefined
          const uploadMbps = typeof data?.uploadMbps === 'number' ? data.uploadMbps : undefined
          serverLog(bearer, `🎵 [ws/cmd] MUSIC_STOPPED: ${tok} (${reason}, ⬇️ ${downloadMbps ?? '-'} ⬆️ ${uploadMbps ?? '-'} Mbit/s)`)
          forwardToBrowser(bearer, tok, 'gsm:music-stopped', { reason, downloadMbps, uploadMbps })
          break
        }
        case 'MUSIC_DROPS':
          // the phone's Bluetooth keeps reporting link trouble while the song plays = 50-100 ms pauses on the
          // headphones; outreach-tool's /dialer shows "Experiencing interruptions? [reboot phone]" - over the audio WS only
          serverLog(bearer, `🎧 [ws/cmd] MUSIC_DROPS: ${tok}`)
          forwardToBrowser(bearer, tok, 'gsm:music-drops')
          break
        case 'CONNECTED_EXPLICIT':
          serverLog(bearer, `✅ [ws/cmd] device-connected: ${tok}`)
          forwardToBrowser(bearer, tok, 'gsm:device-connected')
          break
        case 'CALL_STATE':
          // kept above (updated.call) - the browser reads it with its status poll; one line per change in the Logs box
          serverLog(bearer, `📱 [ws/cmd] CALL_STATE ${data?.callState ?? '?'}${data?.isRinging ? ' + a ring' : ''}: ${tok}`)
          break
        case 'CALL_CONNECTED':
          serverLog(bearer, `📞 [ws/cmd] CALL_CONNECTED: ${tok}`)
          relayStatsByDevice.set(tokKey, { startedAt: Date.now(), lastChunkAt: 0, chunks: 0, maxGapMs: 0, slowGaps: 0, maxBacklogBytes: 0 })
          forwardToBrowser(bearer, tok, 'gsm:call-connected', { dialId: data?.dialId })
          break
        case 'CALL_ENDED':
          serverLog(bearer, `❌ [ws/cmd] CALL_ENDED: ${tok}`)
          heldChunksByDevice.delete(tokKey)
          logRelayStats(bearer, tokKey)
          forwardToBrowser(bearer, tok, 'gsm:call-ended', { dialId: data?.dialId })
          break
        case 'DIAL_QUALITY':
          // the phone's 📶 lead side + 🌐 phone internet lines of the dial that just ended (DialQualityLog.kt)
          if (typeof data?.line === 'string') serverLog(bearer, data.line.slice(0, MAX_DIAL_QUALITY_LINE))
          break
        case 'INCOMING_CALL':
          // she rings on the phone (plan-05 task 7) - every tab of the user shows "Incoming · <number>" with
          // [Ignore] [Answer]; an empty number is a hidden one. The laptop answers with ANSWER_CALL / IGNORE_CALL
          serverLog(bearer, `📲 [ws/cmd] INCOMING_CALL from ${data?.number || 'hidden number'}: ${tok}`)
          forwardToBrowser(bearer, tok, 'gsm:incoming-call', { number: typeof data?.number === 'string' ? data.number : '' })
          break
        case 'INCOMING_CALL_ENDED':
          // the call that rang next to an active one went away - the active call stays
          serverLog(bearer, `📲 [ws/cmd] INCOMING_CALL_ENDED: ${tok}`)
          forwardToBrowser(bearer, tok, 'gsm:incoming-call-ended')
          break
        case 'CALL_REPORT':
          // the row of the call that just ended, from the phone's own call log (plan-05 task 8):
          // { id, number, type, startedAt, talkSec, hasRecording } - or { error } when the phone has no call log access.
          // The row goes under `row`, so its own `type` ("outgoing") stays apart from the frame's type
          serverLog(bearer, `📒 [ws/cmd] CALL_REPORT ${data?.type ?? ''} ${data?.number || data?.error || ''}: ${tok}`)
          forwardToBrowser(bearer, tok, 'gsm:call-log-row', typeof data?.error === 'string' ? { error: data.error } : { row: data ?? {} })
          break
        case 'CALL_LOG':
          // the phone's newest call log rows, the answer to the laptop's GET_CALL_LOG (plan-05 task 9): { rows } or { error }
          serverLog(bearer, `📒 [ws/cmd] CALL_LOG ${Array.isArray(data?.rows) ? data.rows.length + ' rows' : data?.error ?? ''}: ${tok}`)
          forwardToBrowser(bearer, tok, 'gsm:call-log', data ?? {})
          break
        case 'RECORDING_UPLOADED':
          // the phone PUT <callLogId>.m4a into the user's own bucket (outreach-tool dialer plan-06 task 3) - the file
          // went straight to Supabase, only this answer passes here
          serverLog(bearer, `🎙 [ws/cmd] RECORDING_UPLOADED ${data?.callLogId ?? ''}: ${tok}`)
          forwardToBrowser(bearer, tok, 'gsm:recording-uploaded', { callLogId: data?.callLogId })
          break
        case 'RECORDING_FAILED':
          serverLog(bearer, `🎙 [ws/cmd] RECORDING_FAILED ${data?.callLogId ?? ''} ${data?.reason ?? ''}: ${tok}`)
          forwardToBrowser(bearer, tok, 'gsm:recording-failed', { callLogId: data?.callLogId, reason: String(data?.reason ?? 'failed') })
          break
        case 'TEST_AUDIO_STARTED': {
          serverLog(bearer, `🎧 [ws/cmd] TEST_AUDIO_STARTED: ${tok}`)
          const devTas = connectedDevices.get(tokKey); if (devTas) { devTas.isTestActive = true; devTas.isServiceActive = false }
          forwardToBrowser(bearer, tok, 'gsm:test-audio-started')
          break
        }
        case 'TEST_AUDIO_STOPPED': {
          serverLog(bearer, `🛑 [ws/cmd] TEST_AUDIO_STOPPED: ${tok}`)
          const devTasp = connectedDevices.get(tokKey); if (devTasp) devTasp.isTestActive = false
          forwardToBrowser(bearer, tok, 'gsm:test-audio-stopped')
          break
        }
        case 'SERVICE_STARTED': {
          serverLog(bearer, `▶️ [ws/cmd] SERVICE_STARTED: ${tok}`)
          const devSs = connectedDevices.get(tokKey); if (devSs) { devSs.isServiceActive = true; devSs.isTestActive = false }
          break
        }
        case 'SERVICE_STOPPED': {
          serverLog(bearer, `⏹️ [ws/cmd] SERVICE_STOPPED: ${tok}`)
          const devSsp = connectedDevices.get(tokKey); if (devSsp) devSsp.isServiceActive = false
          break
        }
        case 'SIM_LIST': {
          try {
            const simsRaw: string = data?.sims ?? ''
            const matches = [...simsRaw.matchAll(/\{([^}]+)\}/g)]
            const sims = matches.map(m => {
              const pairs: Record<string, string> = {}
              m[1].split(', ').forEach((p: string) => { const eq = p.indexOf('='); if (eq > 0) pairs[p.slice(0, eq).trim()] = p.slice(eq + 1).trim() })
              return {
                id: pairs.id ?? '',
                componentName: pairs.componentName ?? '',
                label: pairs.label ?? 'SIM',
                simSlotIndex: Number(pairs.simSlotIndex ?? 0),
                number: pairs.number ?? '',
                countryIso: pairs.countryIso ?? '',
              }
            })
            const dev = connectedDevices.get(tokKey)
            if (dev) dev.sims = sims
          } catch (e) { console.error('SIM_LIST parse error', e) }
          break
        }
        case 'SMS_SENT':
          resolvePendingSms(tokKey, data?.requestId, { sent: true })
          break
        case 'SMS_FAILED':
          resolvePendingSms(tokKey, data?.requestId, { sent: false, reason: String(data?.reason ?? 'failed') })
          break
        case 'SMS_OPT_OUT':
          serverLog(bearer, `🛑 [ws/cmd] SMS_OPT_OUT from ${data?.smsToNumber}`)
          forwardToBrowser(bearer, tok, 'gsm:sms-opt-out', { smsToNumber: data?.smsToNumber, replyText: data?.replyText ?? '' })
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
    if (androidCmdByDevice.get(key) !== ws) {
      console.log(`ℹ️ [ws/cmd] stale socket closed, the newer one stays registered: ${deviceToken}`)
      return
    }
    androidCmdByDevice.delete(key)
    connectedDevices.delete(key)
  })
})

server.listen(BACKEND_PORT, '0.0.0.0', () => {
  console.log(`Backend listening on ${BACKEND_PORT}`)
})
