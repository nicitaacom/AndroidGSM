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

## Deploy workflow

- APK build command: `adb shell am force-stop com.nicitaacom.androidgsm ; ./gradlew clean assembleDebug && adb install -r $(ls -t app/build/outputs/apk/debug/*.apk | head -n1) && adb shell am start -n com.nicitaacom.androidgsm/.MainActivity`
- Logcat command: `adb logcat -s CmdWS:D GSM:D GsmDialer:D AudioWebSocket:D RootUtils:D *:S`
- **Never list a deploy step the user has already confirmed deployed.** Only mention pending items.
- `docs/` files must be manually copied to their real repos (`gsm-outreach-tool` for server.ts, frontend repo for useInitGSM.ts). Ask the user to confirm after each deploy before marking done.

## What NOT to do

- Do not add `START_SERVICE` / `START_TEST` buttons — service auto-starts on phone boot
- Do not use `getRunningServices()` to check if GsmService is running — broken on Android 8+
- Do not set `isServiceAudioActive = false` in `handleCallEnded` — phone stays in SERVICE mode between calls
- Do not trust `deviceToken` closure in Pusher handlers — always read from `deviceTokenRef.current`
- Do not guard `CALL_ENDED` with only `isCallActive` — also check `audioWsHandler != null` (MIUI may restart the service mid-call resetting the flag)
- Do not route browser mic audio via the cmd WS fallback (`sendCommand AUDIO_CHUNK`) — drop it instead to keep the cmd channel clean
- **Website mic uplink to remote party is not implemented** — no working path exists on this device. `Incall_Music Audio Mixer` slot 1 (VoiceMMode2) is silently ignored by the kernel. pcmC0D19p is the VoiceMMode2 RX playback device (earpiece), NOT a TX injection path — tinyplay to pcmC0D19p plays audio locally only, remote party hears nothing. `VoiceMMode2_Tx Mixer` has no MM/software source entries, only physical mics. MIC: PHONE mode (hardware mic handles TX) is the only working uplink.
- **Do not replace tinymix calls in RootUtils with set_mixer_ctl-only** — `set_mixer_ctl` binary is NOT in assets (not built yet). RootUtils.setMixerElem() has a tinymix fallback for when nativeBinDir is empty. Removing that fallback will silently break all GSM audio (VOC_REC_DL never opens → tinycap captures silence → no audio on frontend). This happened once already. The fix is to BUILD the binary first: `NDK=/home/kali/android-sdk/ndk/27.2.12479018 ./native/set_mixer_ctl/build_arm64.sh` → copy output to `app/src/main/assets/set_mixer_ctl` — only THEN can tinymix fallback be removed.
