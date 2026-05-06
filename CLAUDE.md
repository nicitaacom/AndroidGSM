# AndroidGSM — CLAUDE.md

## Project overview

Android app (Kotlin) + Node.js backend (server.ts on VPS) + Next.js frontend.
Goal: phone acts as a GSM gateway — browser can initiate calls through the phone over WebSocket audio.

## Architecture

```
Frontend (Next.js/Vercel)
  ↕ HTTP polling /api/gsm/status (every 2s)
  ↕ Pusher (call events)
  ↕ WebSocket /ws/audio (audio stream)
        ↕
Backend (Node.js on VPS, Docker)
  server.ts — single process, in-memory state (connectedDevices map)
        ↕ /ws/audio (role=android-cmd → cmd channel, else → audio channel)
        ↕ /ws/audio (audio)
Phone (Android, Kotlin)
  GsmService — foreground service, always running
  CommandWebSocketClient — persistent WS cmd connection
```

## Key files

- `app/src/main/java/com/nicitaacom/androidgsm/GsmService.kt` — main service, handles commands, audio
- `app/src/main/java/com/nicitaacom/androidgsm/CommandWebSocketClient.kt` — persistent WS, heartbeat 8s
- `app/src/main/java/com/nicitaacom/androidgsm/MainActivity.kt` — UI, status display
- `docs/realtime-audio/server.ts` — VPS backend (context-only copy; real file lives in gsm-outreach-tool repo)
- `docs/realtime-audio/useInitGSM.ts` — frontend hook (context-only copy)
- `docs/realtime-audio/nextjs-api-routes/status.ts` — Next.js status route (context-only copy)

> **docs/ files are AI context copies** — changes here must be manually deployed to their real repos.

## Critical timings

| Thing | Value |
|---|---|
| Android heartbeat | 8s |
| Server staleness timeout | 30s |
| Frontend status poll | 2s |

## WebSocket routing (server.ts)

Both cmd and audio share `/ws/audio` path (reverse proxy only forwards `/ws/*`).
Routed by `role` query param:
- `role=android-cmd` → `wsCmd` handler (command channel)
- anything else → `wss` handler (audio channel)

## Phone boot sequence

1. `GsmService.onCreate()` → `ensureRealtimeClientsInitialized()`
2. `CommandWebSocketClient.connect()` → WS handshake
3. `onOpen` → sends `CONNECTED_EXPLICIT` → calls `onConnected` callback
4. `onConnected` → `startServiceDuplexOutputToServerAndServerToInput()` → sends `SERVICE_STARTED`
5. Server sets `isServiceActive = true` in `connectedDevices`
6. Frontend polls `/api/gsm/status` → sees `isServiceActive: true`

## Pending deploy steps 🚨

1. **Deploy `server.ts`** to VPS — unified `/ws/` upgrade handler (noServer mode).
   The reverse proxy was blocking `/ws/cmd`; fix merges both onto `/ws/audio`.
2. **Rebuild + reinstall APK** — Android now connects to `/ws/audio` with `role=android-cmd`.
   Both must be deployed together or the cmd channel won't work.

## What NOT to do

- Do not add `START_SERVICE` / `START_TEST` buttons — service auto-starts on phone boot
- Do not use `getRunningServices()` to check if GsmService is running — broken on Android 8+
- Do not set `isServiceAudioActive = false` in `handleCallEnded` — phone stays in SERVICE mode between calls
- Do not trust `deviceToken` closure in `sendCommand` — always read from `deviceTokenRef.current`
