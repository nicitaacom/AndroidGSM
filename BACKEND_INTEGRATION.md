# Backend Integration Guide - Pusher with Bi-Directional Audio

## 📡 Architecture Overview

```
┌─────────────────┐          ┌──────────────┐          ┌──────────────────┐
│   Android App   │◄────────►│    Pusher    │◄────────►│  Backend Server  │
│                 │          │              │          │                  │
│  • GsmService   │          │  Channels:   │          │  • Express API   │
│  • PusherClient │          │  - Commands  │          │  • Pusher Trigger│
│  • AudioHandler │          │  - Audio     │          │  • Audio Process │
└─────────────────┘          └──────────────┘          └──────────────────┘
```

## 🔄 Communication Flow

### 1. **Android → Backend (Events & Audio)**
   - Endpoint: `POST /api/events`
   - Auth: `Bearer YOUR_BACKEND_BEARER`
   - Content: JSON events + audio chunks

### 2. **Backend → Android (Commands)**
   - Channel: `private-device-{deviceToken}`
   - Event: `command`
   - Auth: `/pusher/auth` endpoint

server.ts
```ts

// 2. list devices
app.get('/api/devices', (req, res) => {
  const devices = Array.from(connectedDevices.values()).map(d => ({
    deviceToken: d.deviceToken,
    lastSeen: d.lastSeen,
  }))

  res.json({ devices })
})

// 3. receive events from android app
app.post('/api/events', (req, res) => {
  const auth = req.headers.authorization

  if (!auth || auth !== `Bearer ${BACKEND_AUTH_KEY}`) {
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken, type, data } = req.body

  if (!deviceToken || !type) {
    return res.status(400).json({ error: 'Missing deviceToken or type' })
  }

  // update last seen
  connectedDevices.set(deviceToken, {
    deviceToken,
    lastSeen: new Date(),
  })

  console.log(`← Event from ${deviceToken}: ${type}`, data || '')

  // forward to pusher channels
  switch (type) {
    case 'CONNECTED':
      pusher.trigger('gsm-devices', 'device-connected', {
        deviceToken,
        timestamp: new Date().toISOString(),
      })
      break

    case 'CALL_STARTED':
      pusher.trigger('gsm-calls', 'call-started', {
        deviceToken,
        number: data?.number,
        timestamp: new Date().toISOString(),
      })
      pusher.trigger(`device-${deviceToken}`, 'status-update', {
        type: 'CALL_STARTED',
        data,
        timestamp: new Date().toISOString(),
      })
      break

    case 'CALL_ENDED':
      pusher.trigger('gsm-calls', 'call-ended', {
        deviceToken,
        timestamp: new Date().toISOString(),
      })
      pusher.trigger(`device-${deviceToken}`, 'status-update', {
        type: 'CALL_ENDED',
        data,
        timestamp: new Date().toISOString(),
      })
      break

    case 'DTMF_SENT':
      pusher.trigger(`device-${deviceToken}`, 'dtmf-sent', {
        digit: data?.digit,
        timestamp: new Date().toISOString(),
      })
      break

    case 'STATUS':
      pusher.trigger(`device-${deviceToken}`, 'status-update', {
        type: 'STATUS',
        data,
        timestamp: new Date().toISOString(),
      })
      break
  }

  res.json({ success: true })
})

// 4. receive commands from web dashboard
app.post('/api/commands', (req, res) => {
  const auth = req.headers.authorization

  if (!auth || auth !== `Bearer ${BACKEND_AUTH_KEY}`) {
    return res.status(401).json({ error: 'Unauthorized' })
  }

  const { deviceToken, command } = req.body

  if (!deviceToken || !command?.type) {
    return res.status(400).json({ error: 'Missing deviceToken or command.type' })
  }

  console.log(`→ Command to ${deviceToken}: ${command.type}`, command.data || '')

  // trigger on private device channel (android app must be subscribed to private-device-{token})
  pusher.trigger(`private-device-${deviceToken}`, 'command', {
    ...command,
    timestamp: new Date().toISOString(),
  })

  res.json({ success: true })
})

// 5. pusher auth endpoint (for private channels)
app.post('/pusher/auth', (req, res) => {
  const { socket_id, channel_name } = req.body

  if (!socket_id || !channel_name) {
    return res.status(400).json({ error: 'Missing params' })
  }

  const authResponse = pusher.authorizeChannel(socket_id, channel_name)
  res.json(authResponse)
})

app.listen(BACKEND_PORT, '0.0.0.0', () => {
  startupInfo(BACKEND_URL);
});

process.on('SIGTERM', () => {
  console.log('SIGTERM received, shutting down...')
  process.exit(0)
})
```