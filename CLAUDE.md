# AndroidGSM — CLAUDE.md

## Project overview

Android app (Kotlin) + Node.js backend (server.ts on VPS) + Next.js frontend.
Goal: phone acts as a GSM gateway — browser can initiate calls through the phone over WebSocket audio.

## Architecture

```
Frontend (Next.js, Coolify on the VPS - its own Dockerfile, not Vercel)
  ↕ HTTP polling /api/gsm/status (every 2s)
  ↕ WebSocket /ws/audio (call event frames - forwardToBrowser; no Pusher since 06.11.2026)
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
- Logcat command: `adb logcat -s CmdWS:D GsmGateway:D GsmDialer:D AudioWebSocket:D RootUtils:D *:S` - `GsmGateway` is MainActivity.log's tag (every service line: CallController, MusicPlayer, "Unhandled command"). It was `GSM` until 2026-11-02: MIUI's logd drops that tag outright, so none of those lines ever reached logcat.
- Before a test call: `adb logcat -c && adb logcat -G 32M` - the default 8 MiB buffer prunes the chattiest uid first, and during a call that is this app.
- **Never list a deploy step the user has already confirmed deployed.** Only mention pending items.
- `docs/` files must be manually copied to their real repos (`gsm-outreach-tool` for server.ts, frontend repo for useInitGSM.ts). Ask the user to confirm after each deploy before marking done.

## What NOT to do

- Do not add `START_SERVICE` / `START_TEST` buttons — service auto-starts on phone boot
- Do not use `getRunningServices()` to check if GsmService is running — broken on Android 8+
- Do not set `isServiceAudioActive = false` in `handleCallEnded` — phone stays in SERVICE mode between calls
- Do not trust `deviceToken` closure in Pusher handlers — always read from `deviceTokenRef.current`
- Do not guard `CALL_ENDED` with only `isCallActive` — also check `audioWsHandler != null` (MIUI may restart the service mid-call resetting the flag)
- Do not route browser mic audio via the cmd WS fallback (`sendCommand AUDIO_CHUNK`) — drop it instead to keep the cmd channel clean
- **Do not auto-route call audio to Bluetooth (`GsmInCallService.routeToBluetoothIfAvailable()` / the BT branch of `onCallAudioStateChanged()`) — both are disabled 2026-09-25.** `VOC_REC_DL`/tinycap only taps the on-chip codec's EARPIECE voice path. `STATE_ACTIVE` fires shortly after OFFHOOK (already-known MIUI lag) — by then `onCallConnected()` has already opened `VOC_REC_DL` and started tinycap on EARPIECE. Switching `CallAudioState` to `ROUTE_BLUETOOTH` moves the whole call onto the SCO/HFP link, off that tap — tinycap keeps running but captures only silence. Symptom: browser hears ~2-3s of real downlink audio then silence, even though the call stays live and the operator's own voice (uplink) may still go through fine. Root-caused via code/git-history review (commit `52cda78 "fix: rotate to bluetooth"`), not confirmed on hardware — re-enable only after confirming `VOC_REC_DL` still captures over a live BT SCO route on a real device. Hardware mic (MIC: PHONE) already works fine as uplink without BT, so this isn't a functionality loss, only a mic-quality tradeoff.
- **Website mic uplink to remote party on a real carrier CS call is a FIRMWARE DEAD END — do not attempt kernel/mixer/tinyplay/host-PCM fixes.** Confirmed on hardware 2026-05-30: the ADSP firmware (closed, one layer below the kernel) rejects `VSS_IVPCM_CMD_START_V2` with `ADSP_EUNSUPPORTED` (-38) on a live VoiceMMode2 call. The flashed kernel injector maps ION fine (`mem_handle=0x24`) but the DSP refuses the TX tap. Two prior diagnoses were misreads: "slot 1 blocked" (control is single-element `SOC_SINGLE_EXT`) and "kvaddr=NULL bug" (`kvaddr=0` is `%pK` under `kptr_restrict=2`, not a real NULL). The only firmware-accepted software TX inject (VSS_IVPCM host-PCM tap) works only on a session *created* as host-PCM (dormant `msm-pcm-host-voice-v2.c`), never on a carrier CS call. **Pivot: use a VoIP/SIP leg (AudioRecord/AudioTrack, injectable, no root) or a server-side SIP trunk.** See `readme.md` top "STOP" section, `KERNEL-UPLINK-PLAN.md` banner, memory [[uplink-kernel-block]]. MIC: PHONE (hardware mic) remains the only working CS uplink.
- **Do not rely on the pre-dial `mutePhoneSpeaker()` alone to keep a browser-driven call quiet while it rings.** The HAL rewrites `EAR_S`/`SPK` when it brings up the voice path for the route Telecom picked, a moment after `placeCall()`, so a mute set before the dial is gone by the time the network ringback arrives (2026-11-02: the ringing was heard on the phone itself next to the browser). The quiet state is applied again from `GsmInCallService.onAudioRouteChanged` (the call's first route + every switch) after 0.5 s and 2 s — `CallController.onPhoneRouteChanged()`. A headset paired to the phone (BT / wired) counts as the call's output there: voice volume from the website's "Playback Vol" (`SET_GAIN playbackGain`, 1.0 = max; the website lowers it to its "ringing while music plays" percent while it rings with music on, `CallController.onPlaybackGainChanged()` re-applies it at once), earpiece + loudspeaker muted. The voice volume goes back to the operator's pre-call level in `teardownCall()`, never left at the cue's max. The same headset branch lifts the music stream to its max on the call link (`liftMusicOnCallLink()`): Android keeps a music level per output, the call link's entry was 10/15 and the song came through some 14 dB under the call at "100%" (2026-11-02). That write touches the call link's entry only; the music link between calls keeps its own level.
- **Do not give the phone's music player `USAGE_MEDIA` (or `USAGE_GAME`).** AudioService mutes every player with those usages for the whole call - from Telecom's `MODE_IN_CALL` at the dial until `MODE_NORMAL` (`AudioService.PlaybackActivityMonitor: call: muting piid:<id> uid:10230` in the system log; 2026-11-02: the song went silent the moment a call was placed, and `MUSIC_VOLUME` on the answer did not bring it back). `MusicPlayer.kt` uses `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`: routed like media (A2DP between calls, the call's own route during one), not on that mute list.
- **Do not treat TelephonyManager `OFFHOOK` as "answered".** For an outgoing call Android reports OFFHOOK the moment the call is dialing. It is the trigger for the call audio setup (tinycap, the audio WS - the downlink chunks are held back while the generated ringback plays, since 06.11.2026 one "tyyy" reaches the browser, not the network's own on top), but `CALL_CONNECTED` goes to the browser only from Telecom `Call.STATE_ACTIVE` (`CallController.sendCallConnectedIfAnswered`). Sent on OFFHOOK it lit the website's "answered" at the dial: its ringing phase was 0 s long, so "ringing while music plays" never applied and "keep music on answer at" paused the music at the dial (2026-11-02).
- **Do not set `STREAM_VOICE_CALL` for a Bluetooth headset's call volume.** While a call runs on the Bluetooth call link Android drops every change of that stream (AudioPolicyManager `checkAndSetVolume`: "do not change in call volume if bluetooth is connected") - the website's volume slider changed nothing in the headset until 2026-11-12. The headset's call volume is `STREAM_BLUETOOTH_SCO` (stream 6, hidden in the SDK): `CallController.setHeadsetCallVolume`. A wired headset keeps `STREAM_VOICE_CALL`.
- **Do not send call audio to the website while a headset paired to the phone plays the call** (`AudioWebSocketHandler.isAudioToBrowserOn`, `CallController.applyAudioToBrowser`, 2026-11-12). 2 outputs at once = the operator hears every ringback twice (the headset plays the network's own, the laptop the generated one), and the stream goes over the 2.4 GHz Wi-Fi radio the Bluetooth call link shares. tinycap still has to run - `CallRecorder` takes its chunks from the tee.
- **Do not send `CALL_ENDED` / `CALL_CONNECTED` without the call's `dialId`** (`GsmInCallService.dialIdByCall`, 2026-11-12). The laptop ends its card only for the dial the frame names; an end with no id falls back to a timing rule that a late end of the call before can pass. The id belongs to the Telecom `Call` object, never to "the call that is current right now" - `teardownCall(endedDialId)` gets it from `onCallRemoved`.
- **Do not report a ring the operator ignored as ringing** (`GsmInCallService.ignoredCall`): for Telecom it rings on (the caller keeps hearing it), the laptop's status poll would show its Incoming line again every 4 s.
- **"Mic vol" has nothing to act on for a Bluetooth headset.** Its mic goes from the Bluetooth radio straight into the voice DSP (`VoiceMMode2_Tx Mixer SLIM_7_TX_MMode2`), no mixer volume sits on that road, and Android has no API for the headset's own mic gain. `RootUtils.updateMicVolume` (the codec's `DEC1..5 Volume`) covers the phone's own mic and a wired headset's mic only; `micVolStatus` reports which case it is, the website shows it. Do not add a slider path that pretends otherwise.
- **Do not replace tinymix calls in RootUtils with set_mixer_ctl-only** — `set_mixer_ctl` binary is NOT in assets (not built yet). RootUtils.setMixerElem() has a tinymix fallback for when nativeBinDir is empty. Removing that fallback will silently break all GSM audio (VOC_REC_DL never opens → tinycap captures silence → no audio on frontend). This happened once already. The fix is to BUILD the binary first: `NDK=/home/kali/android-sdk/ndk/27.2.12479018 ./native/set_mixer_ctl/build_arm64.sh` → copy output to `app/src/main/assets/set_mixer_ctl` — only THEN can tinymix fallback be removed.
