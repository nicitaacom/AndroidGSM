# Android GSM Gateway

Android foreground service that exposes **real GSM calls** to a browser via
**WebSocket** (audio + commands) + **Pusher** (browser-side call state events only).

Phone = modem. Backend = brain. Frontend controls service lifecycle.

---

## ⛔ STOP — website-mic uplink on a real GSM call is a FIRMWARE DEAD END (don't go in circles)

> Read this before touching kernel/mixer/tinyplay/host-PCM uplink code again.
> Confirmed on hardware 2026-05-30. Device: Redmi Note 7 (lavender, sdm660, MIUI A10).

**Goal that does NOT work:** feeding the *website's* mic into a live **carrier CS
(circuit-switched) GSM call** so the remote party hears the website. The remote can
only ever hear the **phone's own hardware mic** on a CS call.

**Where the wall actually is:** the closed **ADSP firmware**, one layer *below* the
kernel. It is signed/encrypted — you cannot read, modify, or override it from the OS.

**Two earlier diagnoses were WRONG (both misreads — do not chase them again):**
1. ❌ "slot 1 (VoiceMMode2) write silently dropped" — the control is `SOC_SINGLE_EXT`
   (single-element); the "2 slots" tinymix shows is a display artifact.
2. ❌ "kvaddr=NULL ION timing bug" — `kvaddr=0` in dmesg is `%pK` printing zeros under
   `kptr_restrict=2`, NOT a real NULL. `msm_audio_ion_alloc()` errors out if vaddr is
   truly NULL, so it can't succeed with NULL. ION mapped fine (`mem_handle=0x24`).

**The real, final failure** (kernel injector flashed, live VoiceMMode2 call):
```
vmm2_inj: ADSP memory mapped, mem_handle=0x24            ← kernel side SUCCEEDED
voc_send_cvp_start_vocpcm: DSP returned error[ADSP_EUNSUPPORTED]
vmm2_inj: start_vocpcm failed -38                        ← FIRMWARE refused (-38 = ENOSYS)
```
The only software TX-injection the firmware supports (VSS_IVPCM host-PCM tap) is
accepted **only on a session created as host-PCM** (the dormant
`msm-pcm-host-voice-v2.c` driver). A live carrier CS call set up by the modem/RIL is a
different session and the firmware refuses to retrofit a TX tap onto it. Enabling that
dormant driver would only inject into its *own* host-PCM session, not the carrier call.
**No kernel patch can make the DSP say yes.**

### Why every alternative is also walled (the full map — read this, save months)

The goal "I speak on the laptop, remote hears me" has exactly **two** technical shapes,
and **each hits a hard wall.** Neither wall is something you can code around.

#### Wall 1 — CS (carrier) call → FIRMWARE wall
A normal carrier call's mic is hardware-only, owned by the ADSP firmware (proven above).
Software cannot inject into it. This includes the seductive idea of a **"virtual mic"**:

> ❌ **A virtual / software mic does NOT work for a CS call on this phone.**
> A virtual mic lives in Android's AudioFlinger (software audio layer). A CS call's mic
> comes from the hardware mic → modem voice DSP, **bypassing AudioFlinger entirely**.
> So the call never reads the virtual mic. A virtual mic only works for apps that read
> the mic via AudioFlinger — VoIP apps, recorders, WebRTC — **never a carrier CS call.**
> "Virtual mic" is just "software mic injection" = the exact thing the firmware blocks.

The only way audio enters a CS call's uplink is as a **physical analog mic signal**:
wired headset mic, Bluetooth (HFP) headset mic, or acoustic (speaker → phone mic).

#### Wall 2 — VoIP call → PROVIDER / KYC wall
A VoIP/SIP call's audio DOES go through AudioFlinger, so a virtual mic WOULD work.
But to place a VoIP call to a **real phone number** (PSTN) you need a provider with a
carrier relationship — and that's a regulated, KYC-gated activity. Confirmed dead ends
(months of attempts, 2026):
- **Twilio** — works technically but too expensive for the use case.
- **Telnyx** — KYC via Onfido; rejects every passport tried (own + friends'). No KYC → no service.
- **Dial9 / many UK SIP providers** — require a **UK proof-of-address**.
- **Betamax/Voipbuster-family, misc cheap providers** — registration rejected / sign-up broken.
- General truth: cheap + no-KYC + PSTN-origination basically **does not exist**, because
  the phone network legally won't let an unverified stranger originate calls. The KYC
  *is* the wall, not the software.

#### The asset you already have: your UK SIM passed KYC
You already cleared verification once — when you got the **UK SIM**. That SIM is your
only KYC-free PSTN credential. So the realistic exits all route **through that SIM**,
just in hardware where the call audio is *software-accessible* (not firmware-locked):

| Exit | KYC? | Buy? | Virtual mic works? | Notes |
|---|---|---|---|---|
| **This phone, CS call + physical mic feed** (wired headset / BT / acoustic) | none | cable/host near phone | no — analog only | works today; audio must be physically beside the phone |
| **USB LTE modem (Quectel EC25 / SIMCom A7600) with the SIM** | none (uses your SIM) | ~$30–60 module + host | ✅ yes (call audio over USB-audio) | self-hosted, no firmware wall; needs hardware near the SIM |
| **VoIP↔GSM gateway box (GoIP / Yeastar TG) with the SIM** | none (uses your SIM) | ~$40–100 box | ✅ yes (SIP ↔ GSM) | most plug-and-play; the commercial version of this project |
| Self-hosted Asterisk/FreeSWITCH + **paid/KYC SIP trunk** | YES (the wall) | trunk cost | ✅ yes | blocked by Wall 2 above |

**Bottom line:** on *this phone*, for a *CS call*, there is **no software-only fix** —
not kernel, not mixer, not virtual mic. Either feed analog audio physically into this
phone, or move the SIM into a USB-modem / gateway box where the audio is software. Both
keep your UK number and need **no provider KYC.**

#### ✅ THE EXIT THAT WORKS: Bluetooth HFP mic (proven on hardware 2026-05-30)
When a Bluetooth headset (HFP) connects during a live CS call, the **modem/DSP itself
reroutes the call mic from the phone mic to the BT SCO mic** — a *firmware-blessed*
route (unlike VSS_IVPCM, which the firmware rejects). Confirmed in the live mixer during
an active VoiceMMode2 call:
```
VoiceMMode2_Tx Mixer INT3_MI2S_TX_MMode2   Off Off   ← phone hardware mic: OFF
VoiceMMode2_Tx Mixer SLIM_7_TX_MMode2      On  Off   ← BT HFP/SCO mic: ON
```
A real BT headset's mic was confirmed audible to the remote party. **The audio on that
SCO channel doesn't have to come from a physical headset mic** — it's whatever device
acts as the BT HFP headset.

**Plan (website mic → remote, no KYC, no firmware patch, no root mixer):**
1. Small always-on Linux host **beside the UK phone** (BT is local-range): Pi / mini-PC / old laptop.
2. Host runs BlueZ as a **Bluetooth HFP headset/audio-gateway** the phone routes call audio to → it owns the SCO mic channel (= `SLIM_7_TX` into the call).
3. Website mic (browser, DE) → existing `/ws/audio` → VPS → UK-side host → injected as the BT HFP SCO "mic" → phone → CS uplink → remote hears the laptop.
4. Downlink already works via tinycap (or take it from the same BT SCO speaker channel).

The firmware auto-selects `SLIM_7_TX` when BT HFP is the active call-audio device — **no
manual tinymix/set_mixer_ctl needed.** See memory `bt-hfp-uplink-works.md`.

##### UK-side host: Raspberry Pi (decided 2026-05-30; future build, not done yet)
The "fake headset" that injects WS audio as the BT mic must be a **programmable** BT
device next to the phone — a real headset can't (it only sends its own mic; you can't
feed a stream into it). Options weighed:
- ❌ Dumb BT headset — closed appliance, no audio-in.
- ❌ Spare Android (5.2) — Android won't let an app replace the BT HFP mic stream (closed
  BT stack/HAL, no API, not even rooted). Wired-cable variant could play audio out its
  jack, but 5.2 is too fragile for the WS-receiver app.
- ✅ **Raspberry Pi** — full BlueZ/audio control, no vendor lock. The right host.

**Cost case (Pi beats every PSTN provider, and isn't KYC-blocked):**

| | One-time | Ongoing | Year 1 | Then/yr |
|---|---|---|---|---|
| **Phone + Raspberry Pi setup** | ~$50 phone + ~$40 Pi + ~$10 ship ≈ **$100** | ~$15/mo SIM | **≈ $250** | **≈ $150** |
| Twilio / Telnyx / etc. | — | — | **> $500** | > $500 |
| | | | | *and KYC-blocked (Onfido rejects, UK address required)* |

Plus: keeps your UK number, your already-verified SIM, and needs no provider sign-up.
Build steps (BlueZ HFP role, SCO codec, audio injection) are open for the next session.

##### Works-today fallback (phone local in DE, no Pi yet)
Until the Pi-in-UK setup is built, you can already use this **right now** with the phone
in front of you in DE. The goal "remote hears me, not just the phone mic" is met by
making *your* voice the phone's mic directly — two ways:
1. **Wired USB-C mic:** plug a **USB-C mic** (or a USB mic via a USB-C adapter) into the
   phone and talk into that instead of the laptop. It registers as a USB Audio Class
   (UAC) input and the firmware routes it via `USB_AUDIO_TX_MMode2` (seen in the mixer
   dump) — same firmware-blessed external-mic path. Skips the laptop entirely. (Not the
   3.5mm jack — a USB-C / USB mic.)
2. **Bluetooth (no Frankenstein phone-holder rig):** connect the small **Lenovo
   thinkplus** BT earbuds to the phone and **talk into the RIGHT earbud's mic** — proven
   above to route into the call uplink (`SLIM_7_TX`). Lets you talk hands-free without
   holding the phone to your face.

These are the same firmware-blessed mic path the Pi plan uses — just with you physically
next to the phone instead of a Pi bridging your laptop audio over the internet.

**What still works as-is:** downlink (remote → website via tinycap on MultiMedia1),
and `placeCall` with `EXTRA_PHONE_ACCOUNT_HANDLE` in Bundle. Full history below under
"Browser mic uplink" / "Iterations to use website's mic" (kept for the record).

---

## First-time setup (Kali / no Android SDK)

```bash
chmod +x install-android-sdk.sh
./install-android-sdk.sh
source ~/.zshrc
```

## Config (required before first build)

```bash
cp app/src/main/assets/androidgsm.config.example.json app/src/main/assets/androidgsm.config.json
```

Edit `app/src/main/assets/androidgsm.config.json`:

```json
{
  "BACKEND_URL": "https://your-backend.com",
  "BACKEND_BEARER": "your-secret-token",
  "PUSHER_KEY": "your-pusher-key",
  "PUSHER_CLUSTER": "eu",
  "DEVICE_TOKEN": ""
}
```

> `androidgsm.config.json` is gitignored — never committed.
> `DEVICE_TOKEN` is auto-generated from `{manufacturer}_{model}` at runtime.

## Build & run on phone

```bash
adb devices   # must show device, not unauthorized

# Always clean to avoid INSTALL_PARSE_FAILED
adb shell am force-stop com.nicitaacom.androidgsm \
  ; ./gradlew clean assembleDebug \
  && adb install -r $(ls -t app/build/outputs/apk/debug/*.apk | head -n1) \
  && adb shell am start -n com.nicitaacom.androidgsm/.MainActivity

# Live logs
adb logcat -s GSM:D AudioWebSocket:D WebSocketAudio:D CmdWS:D RootUtils:D
```

## To take a screenshot (so you don't transfer it via USB)

```bash
adb exec-out screencap -p 2>/dev/null | convert - -resize 360x -quality 70 /tmp/gsm_small.jpg && echo "jpg: $(wc -c < /tmp/gsm_small.jpg) bytes"

```

## In-app setup (first run)

1. Grant all permissions when prompted
2. Phone connects to backend via `/ws/cmd` — shows "Status: Not Active"
3. Use the **website** to start SERVICE or TEST mode — phone UI updates accordingly
4. Use the website to make/end calls

---

## Architecture

### Pusher usage (minimal — browser only)

Pusher is used **only** to push call state events to the browser (5–10 messages per call).
Android does NOT subscribe to Pusher. All Android↔backend communication goes over WebSocket.

| What | Where | Cost |
|------|-------|------|
| Call state (started/connected/ended) | Pusher → browser | ~6 msgs/call |
| Device ready state | Pusher → browser | 1 msg/connect (debounced 5min) |
| Android commands (CALL_STARTED, CALL_ENDED, START_SERVICE, etc.) | `/ws/cmd` WebSocket | 0 Pusher msgs |
| Android heartbeats | `/ws/cmd` WebSocket | 0 Pusher msgs |
| Audio | `/ws/audio` WebSocket | 0 Pusher msgs |

### Service lifecycle

The phone app **never auto-starts SERVICE or TEST mode** on launch. GsmService starts as a
foreground process for WebSocket connectivity only. SERVICE/TEST audio modes are started and
stopped exclusively from the frontend via commands over `/ws/cmd`.

State sync is dual-path:
1. **Immediate**: Android sends `SERVICE_STARTED`/`SERVICE_STOPPED` events over `/ws/cmd` with a retry loop (10s deadline, 300ms poll) to handle WS not yet open
2. **Periodic fallback**: Heartbeat every 15s carries `isServiceActive` and `isTestActive` via `stateProvider` lambda

Frontend polls `/api/gsm/status` every 2s — returns `isServiceActive` and `isTestActive` so the UI reflects real phone state.

### System diagram

```
┌──────────────────────────────────┐
│        Next.js Frontend          │
│  useInitGSM hook                 │
│  ┌──────────────────────────┐    │
│  │ startService/stopService │ ── POST /api/gsm/commands ─────────────────────────┐
│  │ startTest/stopTest       │ ── POST /api/gsm/commands ─────────────────────────┤
│  │ setMicGain/setPlayback   │ ── POST /api/gsm/commands ─────────────────────────┤
│  │ call() / hungUp()        │ ── POST /api/gsm/call-started|ended ───────────────┤
│  │ sendDTMF()               │ ── POST /api/gsm/send-dtmf ────────────────────────┤
│  │ fetchLogs()              │ ── GET  /api/gsm/logs ─────────────────────────────┤
│  │ mic audio (PCM16)        │ ── WS /ws/audio (dir=toAndroid) ──────────────┐    │
│  │ speaker playback ◄───────│─── WS /ws/audio (dir=toBrowser) ◄────────────┤    │
│  └──────────────────────────┘    │                                          │    │
│  Pusher subscriptions:           │                                          │    │
│  gsm-calls ◄─────────────────────┤─ call-started/connected/ended            │    │
│  gsm-devices ◄───────────────────┤─ device-connected                        │    │
└──────────────────────────────────┘                                          │    │
                                                                              │    │
                    ┌─────────────────────────────────────────────────────────┘    │
                    │   server.ts (VPS / Express)                                   │
                    │  WS /ws/cmd  ◄──── Android persistent cmd WS ───────────────┐│
                    │    heartbeat → update lastSeen + isServiceActive/isTestActive ││
                    │    SERVICE_STARTED/STOPPED → update connectedDevices          ││
                    │    commands → deliver to Android over this WS                 ││
                    │  WS /ws/audio ◄──── Android audio WS ────────────────────────┘│
                    │    relay browser ↔ android PCM16 audio                        │
                    │  POST /api/commands → sends command over /ws/cmd              │
                    │  GET  /api/logs    → returns last 200 server log lines        │
                    │  POST /api/gsm/call-started → send CALL_STARTED over /ws/cmd  │
                    │  POST /api/gsm/call-ended   → send CALL_ENDED over /ws/cmd    │
                    │  POST /api/gsm/send-dtmf    → send SEND_DTMF over /ws/cmd     │
                    │  GET  /api/gsm/status       → { isAuthorized, deviceToken,    │
                    │                                  sims[], isServiceActive,      │
                    │                                  isTestActive }                │
                    └────────────────────────────────────────────────────────────────
                                         ↕ WS /ws/cmd  (persistent)
                                 ┌──────────────────────┐
                                 │   Android App        │
                                 │                      │
                                 │  GsmService          │
                                 │  GsmDialer           │
                                 │  CommandWebSocketClient
                                 │  AudioWebSocketHandler
                                 └──────────────────────┘
```

### Android components

```
GsmService              Foreground service; owns all state, dispatches commands.
                        Starts on launch for /ws/cmd connectivity only.
                        SERVICE/TEST audio modes require explicit frontend commands.
                        Does NOT use PusherClient — all backend comms via WS.

CommandWebSocketClient  Persistent WS to /ws/cmd. Stays connected always.
                        Receives CALL_STARTED/CALL_ENDED/START_SERVICE/STOP_SERVICE/
                          START_TEST/STOP_TEST/SET_GAIN commands.
                        Sends CONNECTED heartbeats every 15s with isServiceActive +
                          isTestActive state (no Pusher cost).
                        Sends SERVICE_STARTED/STOPPED/CALL_CONNECTED/CALL_ENDED events.
                        Auto-reconnects on failure.
                        stateProvider lambda: injected by GsmService so heartbeats
                          always carry current audio mode state.

GsmDialer               Registers TelephonyCallback (API 31+) or PhoneStateListener.
                        Places calls via TelecomManager.placeCall(uri, bundle) where bundle
                        contains EXTRA_PHONE_ACCOUNT_HANDLE — resolves to simAccountId match,
                        then default outgoing account, then first available SIM. The 3-arg
                        placeCall form and empty Bundle both fail on this MIUI build.
                        OFFHOOK → starts audio; IDLE → stops audio + sends CALL_ENDED.

AudioWebSocketHandler   WS duplex audio (/ws/audio).
                        Call mode: tinycap subprocess (root) reads ALSA MultiMedia1 kernel
                          device directly, streams raw PCM16 to server.
                        TEST mode: standard AudioRecord from mic.
                        Playback: Channel<ShortArray>(capacity=64) single-consumer coroutine.
                        AudioManager mode changes are gated on !isCallActive to avoid
                          overriding GsmService's MODE_IN_CALL+speakerphone setup.

WebSocketAudioClient    OkHttp WS; sends registration packet on open; routes command
                        messages (type="command") to onCommand callback.

RootUtils               su -c with 3s timeout + stream-draining threads.
                        Uses set_mixer_ctl native binary (assets/set_mixer_ctl, arm64 ELF)
                          to write individual BOOL element indices via SNDRV_CTL_IOCTL_ELEM_WRITE,
                          bypassing broken mixer_ctl_get_array in MIUI's tinyalsa.
                        enableIncallMusicCapture(): sets MultiMedia1 Mixer VOC_REC_DL slots 0+1
                        disableIncallMusicCapture(): clears both slots.
                        enableIncallMusicInjection(): sets Incall_Music Audio Mixer MM1/MM5
                          slots 0+1 (VoiceMMode1+VoiceMMode2), mutes hardware mic TX.
                        disableIncallMusicInjection(): clears above, restores mic TX.
                        nativeBinDir: set by GsmService.onCreate() after unpacking asset.

AudioStreamHandler      LEGACY — not used in call path. Kept for reference.
```

---

## How GSM call audio gets to the browser

This was the hardest problem. Here's the full path and why each approach was needed.

### The problem

GSM call audio on Android routes through the **modem voice DSP**, completely bypassing
Android's AudioFlinger (the normal Java audio system). This means:

- `AudioRecord(VOICE_CALL)` — needs `CAPTURE_AUDIO_OUTPUT` (signature-level, cannot be granted via `pm grant` even with root on MIUI)
- `AudioRecord(REMOTE_SUBMIX)` — taps AudioFlinger output, but GSM audio never goes through AudioFlinger during a call → pure silence (RMS 0.00004)
- `AudioManager.setSpeakerphoneOn(true)` during a call — Telecom overrides it back to earpiece

### The solution: tinycap + set_mixer_ctl as root subprocesses

The GSM downlink audio IS available at the ALSA kernel level via a Qualcomm mixer route.
The uplink (browser mic → remote party) requires enabling a second mixer route.

Both routes have **2-slot BOOL controls** (slot 0 = VoiceMMode1, slot 1 = VoiceMMode2).
The device's `tinymix` binary cannot set slot 1 due to a broken `mixer_ctl_get_array` in
MIUI's tinyalsa build. We ship a native `set_mixer_ctl` binary (compiled with NDK r27 against
the kernel's `sound/asound.h`) that calls `SNDRV_CTL_IOCTL_ELEM_WRITE` directly per element index.

**Downlink (remote party → browser):**
```
set_mixer_ctl 0 'MultiMedia1 Mixer VOC_REC_DL' 0 1   # VoiceMMode1
set_mixer_ctl 0 'MultiMedia1 Mixer VOC_REC_DL' 1 1   # VoiceMMode2
tinycap /proc/self/fd/1 -D 0 -d 0 -c 1 -r 16000 -b 16
```
`VOC_REC_DL` taps the GSM call's receive path into MultiMedia1's ALSA capture device.
tinycap writes a 44-byte WAV header then raw PCM16 mono 16kHz to stdout.
The app reads stdout, skips the WAV header, and streams 320-sample (20ms) chunks.

**Uplink (browser mic → remote party):**
```
set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia1' 0 1   # VoiceMMode1
set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia1' 1 1   # VoiceMMode2
set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia5' 0 1   # (MIUI may route to MM5)
set_mixer_ctl 0 'Incall_Music Audio Mixer MultiMedia5' 1 1
# also Incall_Music_2 variants for both MMode slots
```
`Incall_Music Audio Mixer` routes MultiMedia1/5 AudioTrack playback into the GSM TX uplink.
Browser mic PCM arrives via WebSocket → Android `AudioTrack` (USAGE_MEDIA) → MultiMedia1 →
voice DSP TX path → remote party.
Hardware mic TX is muted while injection is active.

**Step 3 — Stream to browser:**
```
raw PCM16 → base64 → WebSocket JSON → server.ts → browser WebSocket → AudioContext
```

**Why not `REMOTE_SUBMIX`?**
On Qualcomm sdm660 (Xiaomi Redmi Note 7), GSM audio goes:
```
modem → voice DSP → ALSA kernel → earpiece/speaker
```
It never enters AudioFlinger, so REMOTE_SUBMIX sees nothing. Confirmed via RMS logging
(consistently 0.000044 regardless of tinymix settings).

**Why not speakerphone?**
`AudioManager.setSpeakerphoneOn(true)` sets route momentarily, but `TelephonyManager`
immediately overrides it back to earpiece during an active call. Not reliable.

**Why not standard tinymix?**
The 2-slot BOOL controls (`Incall_Music Audio Mixer`, `VOC_REC_DL`) require writing each
element index separately. MIUI's tinyalsa `tinymix` calls `mixer_ctl_get_array` which fails
on this device with "Failed to mixer_ctl_get_array" — slot 1 (VoiceMMode2) is always left Off.
The `set_mixer_ctl` binary bypasses this by using the raw `SNDRV_CTL_IOCTL_ELEM_WRITE` ioctl.

### Browser playback quality fix

The browser creates an `AudioContext` at its default sample rate (48kHz).
`createBuffer(1, chunk.length, 16000)` inside a 48kHz context forces the browser to
upsample 3x using its internal resampler — this caused robotic/crackling artifacts.

Fix: create `AudioContext({ sampleRate: 16000 })` to match the incoming PCM exactly.
No resampling occurs, audio plays cleanly.

### Lookahead scheduler (gapless playback)

`setInterval`-based playback fires late due to browser tab throttling, causing gaps.
Instead, incoming chunks are scheduled end-to-end into AudioContext time:

```
nextPlayTime = max(nextPlayTime, ctx.currentTime + BUFFER_S)
src.start(nextPlayTime)
nextPlayTime += chunkDuration
```

150ms initial buffer absorbs network jitter. 250ms lookahead keeps the schedule filled.

---

## Call flow (SERVICE mode)

```
1. Phone opens app → GsmService starts for WS connectivity → CommandWebSocketClient connects to /ws/cmd
   Phone UI shows "Status: Not Active"

2. Frontend clicks START SERVICE:
   → POST /api/gsm/commands { type: "START_SERVICE" }
   → server sends { type:"command", cmdType:"START_SERVICE" } over /ws/cmd
   → GsmService.handleWsCommand("START_SERVICE")
   → startServiceDuplexOutputToServerAndServerToInput()
   → isServiceAudioActive = true
   → Phone UI shows "Status: Service Active" (green)
   → retry loop sends SERVICE_STARTED event until WS confirms isConnected
   → server updates connectedDevices.isServiceActive = true
   → status poll returns isServiceActive: true → frontend updates UI

3. Frontend call() → POST /api/gsm/call-started { num, deviceToken, simAccountId, simComponentName }
   → server sends { type:"command", cmdType:"CALL_STARTED", data:{number, simAccountId} } over /ws/cmd
   → GsmService.handleCommand("CALL_STARTED")
   → RootUtils.enableIncallMusicCapture()  (opens VOC_REC_DL mixer)
   → GsmDialer.startCall(number, simAccountId, simComponentName)
   → TelecomManager.placeCall() with correct PhoneAccountHandle (dual-SIM aware)
   → Pusher: gsm:call-started → browser (shows "dialing" state)

4. Carrier connects → TelephonyCallback fires OFFHOOK
   → GsmService sets MODE_IN_CALL + speakerphoneOn=true + STREAM_VOICE_CALL=max
   → AudioWebSocketHandler.connect() → /ws/audio
   → startAudioCapture() → launches tinycap subprocess as root
   → startAudioPlayback() → AudioTrack (USAGE_MEDIA) ready on MultiMedia1
   → Thread.sleep(200) → RootUtils.enableIncallMusicInjection()
        set_mixer_ctl: Incall_Music Audio Mixer MM1/MM5 slots 0+1 = On
        mute hardware mic TX (VoiceMMode1_Tx/VoiceMMode2_Tx)
   → CommandWebSocketClient.sendEvent("CALL_CONNECTED")
   → server: Pusher gsm:call-connected → browser (starts audio playout)

5. Audio flows:
   DOWNLINK: tinycap → raw PCM16 → base64 → /ws/audio → server → browser AudioContext
   UPLINK:   Browser mic → PCM16 → /ws/audio → server → Android AudioTrack → MultiMedia1
             → Incall_Music mixer → voice DSP TX → remote party

6. Call ends (either side):
   → TelephonyCallback fires IDLE (via GsmDialer.setCallEndedCallback)
   → RootUtils.disableIncallMusicCapture()  (VOC_REC_DL slots → Off)
   → RootUtils.disableIncallMusicInjection() (Incall_Music slots → Off, mic TX restored)
   → RootUtils.unmutePhoneSpeaker()
   → AudioWebSocketHandler stops + disconnects
   → CommandWebSocketClient.sendEvent("CALL_ENDED")
   → server: Pusher gsm:call-ended → browser (stops playout)
```

## TEST mode flow

```
Frontend clicks START TEST:
  → POST /api/gsm/commands { type: "START_TEST" }
  → server sends { type:"command", cmdType:"START_TEST" } over /ws/cmd
  → GsmService.handleWsCommand("START_TEST")
  → AudioWebSocketHandler (callActive=false) → /ws/audio
  → startAudioPlayback() + startAudioCapture() (uses mic, not tinycap)
  → CommandWebSocketClient.sendEvent("TEST_AUDIO_STARTED")
    → server: Pusher gsm:test-audio-started → browser
      → browser starts mic capture + audio playout
        → bidirectional PCM16 audio over /ws/audio

Frontend clicks STOP TEST:
  → POST /api/gsm/commands { type: "STOP_TEST" }
  → GsmService stops test audio, sends TEST_AUDIO_STOPPED
  → server: Pusher gsm:test-audio-stopped → browser
```

---

## Gain control

Mic gain and playback volume are controlled from the frontend — no phone UI sliders.

```
Frontend setMicGain(value) / setPlaybackGain(value):
  → POST /api/gsm/commands { type: "SET_GAIN", data: { micGain: value } }
  → server sends SET_GAIN command over /ws/cmd
  → GsmService.handleWsCommand("SET_GAIN")
  → AudioWebSocketHandler applies gain to capture/playback path
```

---

## Server logs

Server keeps a circular buffer of the last 200 log lines (meaningful events only, not audio chunks).

```
Frontend fetchLogs():
  → GET /api/gsm/logs
  → server: GET /api/logs?n=50
  → returns { logs: string[] }  (last 50 lines)
```

---

## Dual-SIM support

On startup, `GsmDialer.getSimAccounts()` queries `TelecomManager.callCapablePhoneAccounts`
and sends the list to the backend via `CommandWebSocketClient.sendEvent("SIM_LIST", ...)`.

The server stores SIMs per device and returns them in `/api/gsm/status`.
The frontend (`useInitGSM.ts`) auto-selects the first SIM and exposes `simsRef`,
`selectedSimRef`, `selectSim()` for a SIM picker UI.

When `call()` is invoked, `simAccountId` and `simComponentName` are sent to the backend
and forwarded to Android, which passes the correct `PhoneAccountHandle` to `placeCall()`.

---

## placeCall() "Mobile network not available" — iterations

All failures share the same root cause: `TelephonyConnectionService.getPhoneForAccount` returns
null → Telecom immediately disconnects the call with `DisconnectCause(ERROR, "Phone is null, OUT_OF_SERVICE")`.

**Device context:** Xiaomi Redmi Note 7 (sdm660, MIUI Android 10), dual-SIM slot.
Slot 0: ABSENT (subId=1, iccId=...674563). Slot 1: LOADED/active (subId=2, iccId=...622364).
`gsm.sim.state = ABSENT,LOADED`. Default subId resolves to -1 (no default set).

**Iteration 1 — old simState check**
`telephonyManager.simState` checks the default subscription (slot 0 = ABSENT) → call rejected
before `placeCall()` is even called. Fixed: replaced with `callCapablePhoneAccounts.isNotEmpty()`.

**Iteration 2 — GsmConnectionService intercepting placeCall()**
`GsmConnectionService` was registered with `BIND_TELECOM_CONNECTION_SERVICE` intent filter.
When our app is the default dialer, Telecom routed `placeCall()` through our `ConnectionService`
first instead of `TelephonyConnectionService`. Our `GsmConnection.setDialing()` returned a dead
connection with no real modem. Fixed: removed `GsmConnectionService` and `GsmConnection` entirely
from the manifest — they are not needed for the DIALER role. Only `GsmInCallService` is needed.

**Iteration 3 — no PhoneAccountHandle → default subId=-1**
Without `EXTRA_PHONE_ACCOUNT_HANDLE`, Telecom picks the default voice subscription (subId=-1 on
this device) → `getPhoneForAccount` → `chosenPhone=null`. Fixed: always pass an explicit handle.

**Iteration 4 — handle built from callCapablePhoneAccounts has id=ICCID, not subId**
`callCapablePhoneAccounts.firstOrNull()` returns a handle with `id=89490240001956622364` (ICCID).
`TelephonyConnectionService.getPhoneForAccount` on this MIUI build resolves Phone objects by
subId string, not ICCID → still `chosenPhone=null`. Fix attempt: build handle manually with
`id=activeSubId.toString()` using `TelephonyConnectionService` component.

**Iteration 5 — activeSubscriptionInfoList returned wrong SIM (current)**
`activeSubscriptionInfoList.firstOrNull { it.simSlotIndex >= 0 }` returned the absent SIM
(subId=1, simSlotIndex from DB = -1 but list returned it with slot ≥ 0 on this MIUI build).
Result: `handle.id="1"` → wrong SIM → `chosenPhone=null`. Fix: `maxByOrNull { it.simSlotIndex }`
to pick the SIM in the highest slot index (slot 1 = active).

**Iteration 6 — no PhoneAccountHandle at all**
Passing ANY handle causes `TelephonyConnectionService.getPhoneForAccount` to fail on this MIUI
build regardless of what id is used. Solution: pass empty `Bundle()` with no handle — MIUI
resolves the SIM itself. Call connects for single-SIM scenario. ✅ (single SIM) ❌ (dual-SIM)

**Dual-SIM problem with iteration 6:** on a 2-SIM device, an empty Bundle causes Telecom to
enter `SELECT_PHONE_ACCOUNT` state (waiting for a SIM picker). Our `InCallService` never
answers the picker, so the call sits in `SELECT_PHONE_ACCOUNT` for the full "call duration"
then is CANCELED. `mCallState` stays 0 (IDLE), no ADSP voice path is created, tinycap
captures silence. Confirmed via `dumpsys telecom`: call stuck at `SELECT_PHONE_ACCOUNT → DISCONNECTED`.

**Iteration 7 — embed handle via `EXTRA_PHONE_ACCOUNT_HANDLE` in Bundle (WORKING)** ✅
The 3-arg `placeCall(uri, Bundle, PhoneAccountHandle)` form fails (iteration 4–5). But putting
the handle *inside* the Bundle as `EXTRA_PHONE_ACCOUNT_HANDLE` is different — it uses the same
2-arg `placeCall(uri, extras)` form and MIUI resolves it correctly.
`resolvePhoneAccountHandle()` priority: matching `simAccountId`/`simComponentName` → user's
default outgoing account → first available account. Call reaches the modem, ADSP voice path
is created, downlink audio flows.

**Current status: call connects. Dual-SIM selection from frontend works.**

---

## Browser mic uplink — why remote party hears nothing (confirmed dead end)

tinyplay writing to pcmC0D19p is the VoiceMMode2 **RX** playback device — it plays audio
to the phone's earpiece/speaker, NOT into the GSM TX uplink. The `VoiceMMode2_Tx Mixer`
has no entry for any MM/VOIP/pcmC0D19 source — only physical mic interfaces (INT3_MI2S_TX etc.).

Full mixer dump during active call confirms: all `VoiceMMode2_Tx Mixer` entries are Off.
The only active route is `INT0_MI2S_RX_Voice Mixer VoiceMMode2 On Off` (earpiece downlink).

The ONLY path into VoiceMMode2 TX from software is `Incall_Music Audio Mixer MultiMedia*/MultiMedia5`
slot 1 (VoiceMMode2) — which the MIUI CAF kernel silently ignores on ELEM_WRITE (confirmed by
brute-force ioctl scan). Slot 0 (VoiceMMode1) writes succeed but the active call uses VoiceMMode2.

**Known dead ends for browser mic → remote party TX:**
1. `AudioTrack(USAGE_MEDIA)` + `Incall_Music Audio Mixer` slot 0 → routes to VoiceMMode1 (wrong session)
2. `Incall_Music Audio Mixer` slot 1 → kernel ignores ELEM_WRITE silently
3. tinyplay to pcmC0D19p → RX playback device, not TX; remote party hears nothing
4. `VoiceMMode2_Tx Mixer` has no MM/software source — only physical mics

**Iteration 7 — Incall_Music_2 Audio Mixer + tinyplay to MultiMedia1/MultiMedia5/VoiceMMode1/VoIP devices**
Set `Incall_Music_2 Audio Mixer MultiMedia1` slot 0 = On (succeeds, kernel does not block it).
Played white noise and 1kHz tone via tinyplay to pcmC0D0p (MultiMedia1), pcmC0D13p (MultiMedia5),
pcmC0D2p (VoiceMMode1), pcmC0D3p (VoIP), pcmC0D19p (VoiceMMode2). Remote party heard silence on
all devices. Full mixer dump during active call: `VoiceMMode2_Tx Mixer` has zero active entries.
`Incall_Music` controls are set but MultiMedia1 PCM is closed — no audio routed into TX.
Conclusion: `Incall_Music` DSP path does not connect to VoiceMMode2 TX on this MIUI CAF build
regardless of which PCM device or mixer control is used.

**Iteration 8 — confirmed the slot-1 block is systemic (all 2-slot voice TX controls)**
Tested `VoiceMMode2_Tx Mixer INT_BT_SCO_TX_MMode2` (numid 2014):
- slot 0 write → `On` (kernel accepts)
- slot 1 write → stays `Off` (kernel silently drops, same as Incall_Music)

This proves the slot-1 (VoiceMMode2) block is NOT specific to Incall_Music — it affects EVERY
2-element BOOL voice-TX kcontrol. The active call uses VoiceMMode2 = slot 1, so no userspace
write can ever route into it. Single root cause in the kernel ASoC platform driver.

**CONFIRMED DEAD END (userspace): No software TX injection path exists on this device
(sdm660, MIUI Android 10).** Every 2-slot voice TX control has its slot-1 (VoiceMMode2) write
silently dropped by the kernel. The only working uplink is MIC: PHONE (hardware mic via
`INT3_MI2S_TX_MMode2`). Website mic → remote party is not achievable with userspace tools.

### Kernel patch route (the real fix)

The slot-1 ELEM_WRITE is dropped in the MSM ASoC routing driver
(`sound/soc/msm/msm-pcm-routing-v2.c`, function `msm_routing_put_audio_mixer` /
`msm_pcm_routing_process_voice` or the matrix update path). The kcontrol write returns success
but the ADM (Audio Device Manager) matrix connection for session index 1 (VoiceMMode2) is never
committed to the ADSP.

To fix permanently:
1. Obtain kernel source for this exact build (Xiaomi lavender / sdm660, kernel 4.x CAF).
2. In `msm-pcm-routing-v2.c`, find where the 2-slot voice mixer writes iterate session indices
   and locate the guard that skips/no-ops index 1 (VoiceMMode2) — likely a session-state or
   `is_custom_stereo`/`voc_session` validity check that fails for the second mode.
3. Force the `adm_matrix_map` / `voice_set_route` call for the VoiceMMode2 session.
4. Build, package as a custom boot image (needs unlocked bootloader), flash.

Once slot 1 commits, the existing `RootUtils.enableIncallMusicInjection()` +
`AudioTrack(USAGE_MEDIA) → MultiMedia1` path works as originally designed — no app changes needed.

**Status: userspace exhausted. Uplink requires a kernel patch + custom boot image.**

---

## Known issues & decisions

**REMOTE_SUBMIX silence**
GSM audio on sdm660 never enters AudioFlinger during a call. REMOTE_SUBMIX consistently
returns RMS ~0.000044 (silence). Only tinycap via ALSA MultiMedia1 works.

**Wrong tinymix control (fixed)**
`Incall_Music Audio Mixer MultiMedia1` was tried first for downlink — it injects MM1 playback
INTO the call uplink TX, not the reverse. The correct downlink control is `MultiMedia1 Mixer VOC_REC_DL`.
`Incall_Music Audio Mixer` IS the correct uplink injection control (browser mic → remote party).

**tinymix broken on MIUI sdm660 for 2-slot BOOL controls (fixed)**
`Incall_Music Audio Mixer` and `VOC_REC_DL` are 2-slot controls (slot0=VoiceMMode1, slot1=VoiceMMode2).
MIUI's tinyalsa `tinymix` calls `mixer_ctl_get_array` before writing, which fails on these controls
with "Failed to mixer_ctl_get_array". Result: slot 1 (VoiceMMode2) is never set — always stays Off.
Since the active call uses VoiceMMode2, neither capture nor injection worked.
Fixed: native `set_mixer_ctl` binary in `assets/` uses `SNDRV_CTL_IOCTL_ELEM_WRITE` directly,
setting each slot by element index without any read-first step. Compiled with NDK r27 against
`sound/asound.h` from NDK sysroot. GsmService unpacks it to `filesDir` on first run.

**AudioManager mode conflict (fixed)**
`startAudioCapture()` was setting `MODE_IN_COMMUNICATION + speakerphoneOn=false`,
overriding GsmService's `MODE_IN_CALL + speakerphoneOn=true` set just before capture.
Fixed: AudioManager mode changes in AudioWebSocketHandler are gated on `!isCallActive`.

**STREAM_VOICE_CALL volume=0 silences capture (fixed)**
REMOTE_SUBMIX taps the post-volume mixer output. Volume=0 yields silence even with the
correct mixer path open. Fixed: set STREAM_VOICE_CALL to max before starting capture.

**AudioContext sample rate mismatch (fixed)**
Browser default AudioContext is 48kHz. `createBuffer(..., 16000)` inside 48kHz context
triggers browser 3x upsampler → robotic/crackling audio. Fixed: `AudioContext({ sampleRate: 16000 })`.

**WS onclose setting isReady=false (fixed)**
`ws.onclose` was calling `setIsReady(false)` on every reconnect (alt+tab, etc.).
Fixed: removed it. The 2s status poll is the only authoritative source for device readiness.

**Audio replay after call ends (fixed)**
`onCallEnded` was not calling `stopPlayoutLoop()`. Buffer kept draining after hangup.
Fixed: `stopPlayoutLoop()` + `resetInboundAudioState()` called in `onCallEnded`.

**Pusher quota (fixed)**
Two heartbeats firing every 15s via Pusher (11,520+ Pusher messages/day from heartbeats alone).
Fixed: Android no longer uses Pusher at all. `CommandWebSocketClient` handles all
Android↔backend communication. Pusher is now used only for browser-side call state
events (~6 messages per call).

**SIGABRT in DefaultDispatcher**
Was caused by launching a new coroutine per audio chunk at 50/sec.
Fixed: playback uses `Channel<ShortArray>(capacity=64)` with a single consumer coroutine.
Do NOT revert to `scope.launch` per chunk.

**INSTALL_PARSE_FAILED**
Always use `./gradlew clean assembleDebug`. Do not put inline XML comments after
self-closing tags in `AndroidManifest.xml` — breaks Android 10 manifest parser.

**Log spam from audio loop**
Never call `MainActivity.log()` from the capture/playback loop. Use `Log.d()` only.
`MainActivity.log()` posts to UI thread at 50/sec which locks the ScrollView.

**Phone showing wrong status (fixed)**
`requestPermissionsOnLaunchIfNeeded` was auto-dispatching SERVICE start on every launch,
causing phone to always show "Service Active" regardless of frontend state.
Fixed: removed auto-dispatch. GsmService process starts (for WS cmd) but no audio mode
is activated until frontend sends START_SERVICE.

**SERVICE_STARTED event dropped silently (fixed)**
`cmdWsClient?.sendEvent()` returns early when `!isConnected`. WS not yet open when
service starts. Fixed: retry loop (10s deadline, 300ms poll) waits for `isConnected`.

**connectedDevices dropping isServiceActive on reconnect (fixed)**
Device entry was reconstructed with only `{ deviceToken, lastSeen, sims }` on reconnect,
dropping `isServiceActive`/`isTestActive`. Fixed: spread existing entry first.

**Kotlin trailing lambda bug (fixed)**
`WebSocketAudioClient(... , { packet -> handleAudioPacket(packet) })` — trailing lambda
attaches to the LAST parameter, which was `onCommand`, not `onAudioPacket`.
Fixed: use named argument: `onAudioPacket = { packet -> handleAudioPacket(packet) }`.

---

## WebSocket message schemas

### /ws/audio (audio relay)
```json
{
  "role": "browser | android",
  "deviceToken": "Xiaomi_2109119DG",
  "dir": "toAndroid | toBrowser",
  "codec": "pcm16",
  "seq": 42,
  "ts": 1234567890,
  "sampleRate": 16000,
  "audio": "<base64 PCM16 little-endian>"
}
```

### /ws/cmd (command channel, Android only)

Android → server:
```json
{ "type": "event", "eventType": "CONNECTED|SERVICE_STARTED|SERVICE_STOPPED|CALL_CONNECTED|CALL_ENDED|...", "deviceToken": "...", "data": { "isServiceActive": true, "isTestActive": false } }
```

Server → Android:
```json
{ "type": "command", "cmdType": "CALL_STARTED|CALL_ENDED|SEND_DTMF|START_SERVICE|STOP_SERVICE|START_TEST|STOP_TEST|SET_GAIN", "data": { "number": "...", "simAccountId": "...", "micGain": 1.0 } }
```

## Audio format

```
Encoding   : PCM 16-bit little-endian
Sample rate: 16kHz
Channels   : Mono
Transport  : Base64 inside WebSocket JSON frames
Chunk size : 320 samples (20ms)
```

---

## Iterations to use website's mic (browser mic → remote party uplink)

This is the hardest unsolved problem. Summary of every approach tried.

### What we know for certain (from live `tinymix` dump during an active call)

```
1689  BOOL 2  Incall_Music Audio Mixer MultiMedia1   On  Off   ← slot 0 = VoiceMMode1 (ON), slot 1 = VoiceMMode2 (OFF)
1691  BOOL 2  Incall_Music Audio Mixer MultiMedia5   On  Off   ← same
1877  BOOL 2  INT0_MI2S_RX_Voice Mixer VoiceMMode2   Off On    ← this device's call is on VoiceMMode2 (slot 1)
2021  BOOL 2  VoiceMMode2_Tx Mixer INT3_MI2S_TX_MMode2  On Off ← TX mic path is INT3_MI2S_TX
```

**The call always uses VoiceMMode2 (slot 1) on this device.**
`Incall_Music Audio Mixer` slot 0 routes to VoiceMMode1 — which is **not the active call**.
Slot 1 routes to VoiceMMode2 — which IS the active call. Slot 1 is what we need to set ON.

### Why tinymix can't set slot 1

`tinymix` calls `mixer_ctl_get_array()` before writing. MIUI's tinyalsa build has a broken
`mixer_ctl_get_array` that fails on 2-slot BOOL controls — returns error, leaves slot 1 Off.
Confirmed: `tinymix 1689 1 1`, `tinymix 1689 On On`, `tinymix 'Incall_Music...' 1 1` — all fail.

### The only real fix: set_mixer_ctl binary

Must use `SNDRV_CTL_IOCTL_ELEM_WRITE` directly via ioctl — reads current value, sets target slot,
writes back. Bypasses `mixer_ctl_get_array` entirely. Source: `native/set_mixer_ctl/set_mixer_ctl.c`.

**Current status: binary NOT built.** `nativeBinDir` is empty → `RootUtils.setMixerElem()` falls
back to tinymix slot 0 only → slot 1 (VoiceMMode2) never set → injection goes to wrong voice session
→ remote party hears nothing from browser mic.

To fix permanently:
```bash
NDK=/home/kali/android-sdk/ndk/27.2.12479018 ./native/set_mixer_ctl/build_arm64.sh
# then rebuild APK
```

### Approaches tried and failed

1. **`AudioTrack(USAGE_MEDIA)` + tinymix slot 0** — routes to VoiceMMode1, call is on VoiceMMode2.
   Injection is active but goes to the wrong voice session. Remote party hears nothing.

2. **`VoiceMMode1_Tx Mute` / `VoiceMMode2_Tx Mute` via tinymix** — these control names don't exist
   on this device. `Voice Tx Mute` and `Voice Tx Device Mute` exist but are INT type, not BOOL.
   Hardware mic muting was failing silently — phone mic may still bleed.

3. **tinymix with multiple value syntax** — `tinymix 1689 1 1`, `tinymix 1689 On On` — all rejected.
   The broken `mixer_ctl_get_array` is called before any write attempt regardless of syntax.

4. **Objective-C / low-level binary patching** — explored writing raw ARM64 shellcode to call ioctl
   directly. Rejected: no compiler available on device, no NDK installed at the time, no busybox.

5. **set_mixer_ctl ioctl binary built and tested** — slot 0 writes work. Slot 1 writes return
   OK but value never changes in tinymix. Confirmed: `ELEM_WRITE` for slot 1 is silently ignored
   by this kernel on ALL 2-slot BOOL controls (`Incall_Music`, `VOC_REC_DL`, `VoiceMMode2_Tx Mixer`).
   This is NOT limited to active-call state — tested outside a call too. The kernel simply never
   applies slot 1 writes for these controls on this MIUI CAF build.

6. **Direct PCM write to pcmC0D19p (VoiceMMode2 TX) — uplink working** ✓
   Device 19 = VoiceMMode2 (playback+capture). Format: S16_LE, mono, 8kHz.
   `exec tinyplay /proc/self/fd/0 -D 0 -d 19` writes browser mic PCM to the call TX uplink.
   Pipe kept alive with `exec` (replaces su shell so Java app's outputStream stays connected).
   Hardware mic muted via `VoiceMMode2_Tx Mixer INT3_MI2S_TX_MMode2` slot 0 = 0.
   CALL_CONNECTED sent after tinyplay starts → browser receives it → browser starts mic capture.
   **Status: uplink pipeline works end-to-end. Remote party hears silence instead of browser mic
   because browser mic audio is not arriving at Android over /ws/audio.**

7. **Downlink (remote party → browser) still broken** — tinycap on device 0 captures
   MultiMedia1, which requires VOC_REC_DL slot 1 (VoiceMMode2) to be set. Slot 1 is always Off
   (kernel ignores writes). tinycap runs but captures silence (RMS ~0.000).
   pcmC0D19c (VoiceMMode2 capture) is exclusively locked by the modem — can't open from userspace.
   Pre-priming VOC_REC_DL before dialing also doesn't work — slot 1 never sticks regardless.

8. **Browser mic audio not arriving at Android** — CALL_CONNECTED is sent, browser receives
   `gsm:call-connected` via Pusher, sets `isCallActive=true`. `startMicCapture()` fires when
   the first downlink audio packet arrives in `ws.onmessage`. But downlink is silence (see #7),
   so `startMicCapture()` never triggers. Chicken-and-egg: browser waits for downlink audio to
   start mic, but downlink is broken. Fix: `onCallConnected` in useInitGSM.ts must call
   `startMicCapture()` directly instead of waiting for the first audio packet.

### What needs to happen to fully fix browser mic uplink

1. **Frontend fix** (useInitGSM.ts): `onCallConnected` must call `startMicCapture()` directly —
   not wait for first downlink audio packet. The browser currently only auto-starts mic when
   it receives a WS audio packet with `isCallActiveRef.current=true`.

2. **Downlink fix** — tinycap on device 0 captures silence because VOC_REC_DL slot 1 is Off.
   The kernel won't let us set slot 1. The only remaining option not yet tried:
   reading directly from the ALSA PCM device that VoiceMMode2 RX writes to, without going
   through the MultiMedia1 mixer route. Need to find which PCM device (not pcmC0D19c which is
   capture-exclusive) carries the VoiceMMode2 downlink in a readable way.

### Correct TX mute control names (confirmed from live dump)

```
VoiceMMode2_Tx Mixer INT3_MI2S_TX_MMode2  (numid 2022) BOOL slot 0 — hardware mic TX
```
Setting slot 0 = 0 disconnects phone mic from VoiceMMode2 TX uplink.
Restore on call end: set slot 0 = 1.

---

## Native binaries

```
native/set_mixer_ctl/
  set_mixer_ctl.c      Source. Uses sound/asound.h SNDRV_CTL_IOCTL_ELEM_WRITE directly.
  build_arm64.sh       NDK r27 build script. Output → app/src/main/assets/set_mixer_ctl.
                       Run: NDK=/path/to/ndk ./native/set_mixer_ctl/build_arm64.sh
app/src/main/assets/
  set_mixer_ctl        Pre-built arm64 PIE ELF. Bundled in APK. GsmService unpacks to
                       filesDir on first run and sets RootUtils.nativeBinDir.
```

Usage (manual test):
```bash
adb shell "su -c '/data/data/com.nicitaacom.androidgsm/files/set_mixer_ctl 0 \"Incall_Music Audio Mixer MultiMedia1\" 1 1'"
# Expected: OK: 'Incall_Music Audio Mixer MultiMedia1'[1] = 1  (numid=1690 count=2)
```

---

## Docs (frontend integration)

```
docs/realtime-audio/
  server.ts              Express + WebSocket server (VPS). Handles /ws/audio, /ws/cmd, Pusher triggers.
                         Circular log buffer (200 lines). GET /api/logs, POST /api/commands.
  useInitGSM.ts          React hook: device discovery, Pusher events, WebSocket audio,
                         call/hangup/DTMF, SIM picker, service/test control, gain, logs.
  nextjs-api-routes/
    call-started.ts      POST /api/gsm/call-started → sends CALL_STARTED command over /ws/cmd
    call-ended.ts        POST /api/gsm/call-ended   → sends CALL_ENDED command over /ws/cmd
    send-dtmf.ts         POST /api/gsm/send-dtmf    → sends SEND_DTMF command over /ws/cmd
    status.ts            GET  /api/gsm/status        → polls device liveness, sims[], isServiceActive, isTestActive
    commands.ts          POST /api/gsm/commands      → proxies START_SERVICE/STOP_SERVICE/START_TEST/STOP_TEST/SET_GAIN
    logs.ts              GET  /api/gsm/logs           → proxies last 50 server log lines
```

---

## AI instructions

- Do not create doc files
- Update only the changed parts of code, not full files
- Reply concisely
- Always use `./gradlew clean assembleDebug` for builds targeting a device
- All `MainActivity.log()` calls go to logcat tag `GSM` — use `adb logcat -s GSM:D` to debug
- High-frequency events (audio chunks) must use `Log.d()` not `MainActivity.log()` — the latter posts to UI thread
- Primary audio path: `AudioWebSocketHandler` via `/ws/audio`. `AudioStreamHandler` is legacy — do not use.
- Android commands come via `CommandWebSocketClient` over `/ws/cmd`, not Pusher
- `callEndedCallback` is set once in `GsmService.onCreate` — do not override it in `handleCallStarted`
- Playback channel (`Channel<ShortArray>`) must remain single-consumer — no `scope.launch` per chunk
- AudioManager mode changes in `AudioWebSocketHandler` must be gated on `!isCallActive`
- tinycap WAV header is 44 bytes — always skip before reading PCM
- Never call `tinymix` for 2-slot BOOL controls — use `RootUtils.setMixerElem()` (calls set_mixer_ctl)
- To rebuild set_mixer_ctl: `NDK=/home/kali/android-sdk/ndk/27.2.12479018 ./native/set_mixer_ctl/build_arm64.sh`
- set_mixer_ctl is unpacked from assets by GsmService.onCreate() — do not hardcode /data/local/tmp paths
- SERVICE/TEST audio modes are started only by frontend commands — never auto-start on launch
- `SERVICE_STARTED` event uses retry loop (10s, 300ms poll) — do not remove it
- `stateProvider` lambda in `CommandWebSocketClient` carries live `isServiceActive`/`isTestActive`
- Trailing lambda in Kotlin attaches to the LAST parameter — always use named args for non-last lambdas
