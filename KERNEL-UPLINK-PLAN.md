# Kernel Uplink Fix — Handoff Plan

> Goal: make the **remote GSM party hear the website's mic** on Xiaomi Redmi Note 7
> (lavender, sdm660, MIUI Android 10).

---

## ⛔ FINAL CONCLUSION (2026-05-30): KERNEL PATH IS A DEAD END — PIVOT TO VoIP/SIP

Injecting the website mic into a **live carrier CS (circuit-switched) GSM call** is
**blocked in the closed ADSP firmware**, one layer below the kernel. No kernel patch
can fix it. Hardware-proven this session. Do NOT spend more time on kernel uplink.
(Full reasoning also at the top of `readme.md` under "STOP".)

### The airtight proof (flashed injector kernel, live VoiceMMode2 call)
```
vmm2_inj: ion paddr=0x110039000 kvaddr=0000000000000000   ← RED HERRING (%pK + kptr_restrict=2)
vmm2_inj: ADSP memory mapped, mem_handle=0x24             ← kernel side SUCCEEDED
voc_send_cvp_start_vocpcm: DSP returned error[ADSP_EUNSUPPORTED]
vmm2_inj: start_vocpcm failed -38                         ← FIRMWARE refused
```
1. `kvaddr=0` is NOT a NULL bug — it's `%pK` under `kptr_restrict=2`. ION mapped fine.
   The prior "move ION alloc to init" theory would NOT have fixed anything.
2. Real failure = `ADSP_EUNSUPPORTED` (-38) on `VSS_IVPCM_CMD_START_V2`.
3. VSS_IVPCM host-PCM tap is firmware-accepted only on a session CREATED as host-PCM
   (`msm-pcm-host-voice-v2.c`, dormant — no "VoiceMMode2 HOST TX" in `/proc/asound/pcm`).
4. Enabling that driver injects into its OWN session, not the carrier CS call. Dead end.

### ✅ PIVOT — realistic working architecture
Use a leg through AudioFlinger (injectable): **VoIP/SIP call leg** (AudioRecord/AudioTrack,
no root/kernel) or **server-side SIP trunk** (provider dials the GSM number). Downlink
(tinycap) and `placeCall` (no handle) unaffected.

**Everything below is the historical investigation that led here — kept for the record.**

---

## TL;DR of where we are

- **Call connects** ✅ (placeCall with NO PhoneAccountHandle — see [[placecall-no-handle]]).
- **Downlink** (remote → website): tinycap runs but captures silence (rms≈0). Separate issue.
- **Uplink** (website mic → remote): **NOT working.** Browser mic PCM reaches Android over
  `/ws/audio` (WS-RX chunks confirmed in logcat) but never reaches the GSM TX path.
- We were about to investigate a **kernel patch** because every userspace mixer attempt failed.
- **MAJOR DISCOVERY (this session):** the long-held assumption that the kernel "silently drops
  slot-1 (VoiceMMode2) writes" is **WRONG**. The kernel control is single-element. See below.

## The kernel discovery (read this carefully)

Kernel source cloned to: `/home/kali/Documents/GitHub/lavender-kernel`
(branch `lavender-q-oss`, MiCode/Xiaomi_Kernel_OpenSource).

Routing driver: `sound/soc/msm/qdsp6v2/msm-pcm-routing-v2.c`

### What the controls actually are

```c
// line ~6099 — incall_music_delivery_mixer_controls
SOC_SINGLE_EXT("MultiMedia1", MSM_BACKEND_DAI_VOICE_PLAYBACK_TX,  MSM_FRONTEND_DAI_MULTIMEDIA1, 1, 0, get, put)
SOC_SINGLE_EXT("MultiMedia2", MSM_BACKEND_DAI_VOICE_PLAYBACK_TX,  ...)
SOC_SINGLE_EXT("MultiMedia5", MSM_BACKEND_DAI_VOICE_PLAYBACK_TX,  ...)
SOC_SINGLE_EXT("MultiMedia9", MSM_BACKEND_DAI_VOICE_PLAYBACK_TX,  ...)

// line ~6114 — incall_music2_delivery_mixer_controls
SOC_SINGLE_EXT("MultiMedia1", MSM_BACKEND_DAI_VOICE2_PLAYBACK_TX, ...)
...
```

**These are `SOC_SINGLE_EXT` — single-element controls.** The "2 slots" that `tinymix` prints
(`BOOL 2 ... On Off`) is a tinymix display artifact (channel count from another layer), NOT two
writable voice sessions. There is no per-slot kernel logic to patch. Our entire "slot 1 blocked"
theory was based on misreading tinymix output.

### How injection is wired (DAPM routes, line ~14951)

```
{"Incall_Music Audio Mixer",  "MultiMedia1", "MM_DL1"}  →  {"VOICE_PLAYBACK_TX",  NULL, "Incall_Music Audio Mixer"}
{"Incall_Music_2 Audio Mixer","MultiMedia1", "MM_DL1"}  →  {"VOICE2_PLAYBACK_TX", NULL, "Incall_Music_2 Audio Mixer"}
```

So:
- `Incall_Music Audio Mixer MultiMedia1`   → backend **VOICE_PLAYBACK_TX**  ("Voice Farend Playback")
- `Incall_Music_2 Audio Mixer MultiMedia1` → backend **VOICE2_PLAYBACK_TX** ("Voice2 Farend Playback")

### The put handler HAS NO BLOCK (line 1983 `msm_routing_put_audio_mixer`)

```c
if (ucontrol->value.integer.value[0] && route_is_set(...) == false) {
    msm_pcm_routing_process_audio(mc->reg, mc->shift, 1);   // <- just commits it
    snd_soc_dapm_mixer_update_power(...);
}
return 1;
```

No slot check, no silent drop. The write IS committed.

### The REAL mechanism (line 1817-1823 in `msm_pcm_routing_process_audio`)

```c
if (set) {
    if (!test_bit(val, &msm_bedais[reg].fe_sessions[0]) &&
        ((msm_bedais[reg].port_id == VOICE_PLAYBACK_TX) ||
         (msm_bedais[reg].port_id == VOICE2_PLAYBACK_TX)))
        voc_start_playback(set, msm_bedais[reg].port_id);   // <- starts farend playback into the voice session
    ...
}
```

`voc_start_playback(port_id)` tells the ADSP voice driver to mix the farend-playback stream
into the **active voice session** bound to that backend. The question that decides everything:

**Which voice session does VOICE_PLAYBACK_TX vs VOICE2_PLAYBACK_TX attach to, and is our active
GSM call (VoiceMMode2) bound to it?**

The call on this device uses **VoiceMMode2** (confirmed: `INT0_MI2S_RX_Voice Mixer VoiceMMode2 On`).
`voc_start_playback` routes farend playback to whatever sessions are active via `voice_get_session`
matching. Need to trace `voc_start_playback` in `sound/soc/msm/qdsp6v2/q6voice.c` to see if it
iterates ALL active voice sessions (incl. VoiceMMode2) or only legacy CS Voice / Voice2.

## Why the userspace attempts failed (re-interpretation)

Setting `Incall_Music Audio Mixer MultiMedia1` On via set_mixer_ctl DID commit in the kernel.
But the remote heard nothing because **nothing was playing to the MultiMedia1 PCM device** at the
right moment — our tinyplay was writing to `pcmC0D19p` (VoiceMMode2 RX device, wrong device), and
when we wrote to `pcmC0D0p` (MultiMedia1) the `voc_start_playback` may not have attached to the
VoiceMMode2 session, OR the mixer was set but no MM1 frontend stream was actually open/feeding.

**We never did the clean combined test:**
1. `Incall_Music Audio Mixer MultiMedia1` = On (or Incall_Music_2)
2. AudioTrack(USAGE_VOICE_COMMUNICATION or MEDIA) playing to MultiMedia1 frontend (pcmC0D0p)
   via the *normal Android AudioTrack path* (NOT tinyplay to a voice device)
3. while a VoiceMMode2 call is active

That is the actual original design in `RootUtils.enableIncallMusicInjection()` +
`AudioWebSocketHandler` AudioTrack(USAGE_MEDIA)→MM1. It may have only ever failed because of the
WRONG-SLOT theory leading us to add tinyplay-to-pcmC0D19p instead.

## SUB-TASKS for the new chat (in order)

### Task 1 — Trace voc_start_playback ✅ DONE 2026-05-29

**RESULT:** `voc_start_playback` (q6voice.c:5476) iterates ALL active sessions and attaches
farend playback by "sub" class (q6voice.c:517-549):
- `Incall_Music`   = VOICE_PLAYBACK_TX  → `is_sub1_vsid` → VOICE/VOLTE/VOWLAN/**VoiceMMode1** → reaches VoiceMMode2: **NO**
- `Incall_Music_2` = VOICE2_PLAYBACK_TX → `is_sub2_vsid` → VOICE2/**VoiceMMode2**          → reaches VoiceMMode2: **YES**

Our call = VoiceMMode2 = sub2 ⇒ **use `Incall_Music_2 Audio Mixer`**. All prior userspace
attempts used the wrong mixer (`Incall_Music`). No kernel block, no kernel patch needed — go to Task 2.

### Task 1 (original brief) — Trace voc_start_playback (READ-ONLY, do first, no flashing)
- File: `/home/kali/Documents/GitHub/lavender-kernel/sound/soc/msm/qdsp6v2/q6voice.c`
- Find `voc_start_playback(u32 set, int port_id)`.
- Determine whether it loops over ALL active voice sessions (so VoiceMMode2 gets the farend mix)
  or only a fixed CS-voice/Voice2 session.
- Also check `voice_get_session_by_idx` / `voice_session_id` mapping and how VoiceMMode2 session
  id relates to VOICE_PLAYBACK_TX vs VOICE2_PLAYBACK_TX.
- OUTPUT: a definitive answer — "Incall_Music (VOICE_PLAYBACK_TX) reaches VoiceMMode2: YES/NO",
  "Incall_Music_2 (VOICE2_PLAYBACK_TX) reaches VoiceMMode2: YES/NO".

### Task 2 — Clean userspace re-test (NO kernel flash needed)
Based on Task 1, on a LIVE call, do the proper combined test:
- Set the correct Incall_Music control On via:
  `adb shell su -c '/data/data/com.nicitaacom.androidgsm/files/set_mixer_ctl 0 "Incall_Music Audio Mixer MultiMedia1" 0 1'`
  (and also try `Incall_Music_2`).
- Play a 1kHz tone to MultiMedia1 the **AudioTrack way** — write a tiny test: AudioTrack
  USAGE_VOICE_COMMUNICATION / MEDIA, 8kHz or 48kHz, streaming a sine. NOT tinyplay-to-voice-device.
- Ask user if remote party hears the tone.
- If YES → uplink is solvable in USERSPACE after all; wire it into AudioWebSocketHandler and
  we are DONE, no kernel patch, no bootloader unlock.
- If NO → proceed to Task 3.

### Task 3 — Kernel patch ✅ IN PROGRESS 2026-05-29

**Mechanism (decided):** Enable the existing `msm-pcm-host-voice-v2` ALSA driver for VoiceMMode2 TX.
The driver is compiled in but dormant — no DT node, no FE DAI entries, no machine dai_links.
Once enabled it exposes `VoiceMMode2 HOST TX PLAYBACK` (pcmC0DXXp), a standard ALSA PCM device
that pushes userspace PCM into the VoiceMMode2 TX uplink via `VSS_IVPCM_CMD_START_V2` /
`VSS_IVPCM_EVT_PUSH_BUFFER_V2` (host-PCM tap point). This is a documented ADSP-supported path,
not a hack. Writing 16kHz PCM to that device goes directly into the modem TX path.

**Task 2 re-run:** failed (same result as readme Iteration 7 — the Incall_Music farend path does
not work on this device; remote heard silence).

**CRITICAL: Use Predator Stormbreaker source, NOT MiCode lavender-q-oss.**
- MiCode source (lavender-kernel/) = 4.4.192. Building it breaks Wi-Fi (confirmed by user's
  prior rooting attempt — Predator kernel was specifically required to fix Wi-Fi after flashing).
- Correct source: `github.com/stormbreaker-project/kernel_xiaomi_lavender` branch `oldcam-eas`
  (4.4.291, same author sohamxda7/sohamsen, same lineage as running 4.4.205 Predator kernel).
- Cloned to: `/home/kali/Documents/GitHub/predator-kernel`

**Build env:**
- Source: `/home/kali/Documents/GitHub/predator-kernel`
- NDK clang: `/home/kali/android-sdk/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin`
- Cross-compile: `aarch64-linux-gnu-` (gcc-aarch64-linux-gnu installed)
- Build command:
  ```
  make -j$(nproc) O=out ARCH=arm64 CC=clang CLANG_TRIPLE=aarch64-linux-gnu- \
    CROSS_COMPILE=aarch64-linux-gnu- HOSTCFLAGS="-fcommon -Wno-error" Image.gz-dtb
  ```
- Defconfig: `lavender_defconfig`
- Output: `out/arch/arm64/boot/Image.gz-dtb`
- Repack: magiskboot (from Magisk on device) or AnyKernel3 from `sohamxda7/AnyKernel3`
- Boot backup: `/home/kali/Documents/GitHub/boot-predator-backup.img`
- Rollback: `fastboot flash boot boot-predator-backup.img`

**Patch plan (4 files):**
1. `arch/arm64/boot/dts/qcom/sdm660-audio.dtsi` — add `qcom,msm-voice-host-pcm` DT node
2. `sound/soc/msm/qdsp6v2/msm-dai-q6-v2.c` — declare FE DAIs for VoiceMMode2 HOST TX/RX
3. `sound/soc/msm/sdm660-common.c` — add dai_links for VoiceMMode2 HOST TX PLAYBACK/CAPTURE
4. `sound/soc/msm/qdsp6v2/q6voice.c` — add pr_info instrumentation in voc_start_playback,
   voice_cvs_start_playback, and VSS_IVPCM_EVT_NOTIFY_V2 handler

**Flashing prereq:** confirm `fastboot flashing get_unlock_ability`. User asserts OEM unlock on.
**SAFETY:** boot backup at `/home/kali/Documents/GitHub/boot-predator-backup.img`. Restore via
`fastboot flash boot boot-predator-backup.img` on bootloop.

## Hard facts / gotchas (don't re-learn these)

- **placeCall MUST pass empty Bundle, NO PhoneAccountHandle** — any handle → "Mobile network not
  available". See memory [[placecall-no-handle]]. Dual-SIM frontend selection is disabled as a result.
- **GsmConnectionService + GsmConnection were DELETED** — they intercepted placeCall and broke it.
  Only GsmInCallService (InCallService, UI=true) remains, needed for Call object (DTMF) + CallActivity.
- Active call session = **VoiceMMode2** (slot index 1). Confirmed via `INT0_MI2S_RX_Voice Mixer
  VoiceMMode2 On`.
- `pcmC0D19p` = VoiceMMode2 **RX** (earpiece playback), **NOT** a TX injection path. tinyplay to it
  plays locally only. STOP using it for uplink.
- Downlink VOC_REC_DL capture is rms≈0 — separate bug, also possibly the same "we misread which
  device/route" class of error. Worth re-checking after uplink is solved.
- set_mixer_ctl binary lives at `/data/data/com.nicitaacom.androidgsm/files/set_mixer_ctl` on device.
- Build/deploy (ALWAYS clean — INSTALL_PARSE_FAILED otherwise):
  `adb shell am force-stop com.nicitaacom.androidgsm ; ./gradlew clean assembleDebug && adb install -r -d $(ls -t app/build/outputs/apk/debug/*.apk | head -n1) && adb shell am start -n com.nicitaacom.androidgsm/.MainActivity`
- Logcat: `adb logcat -s GSM:D AudioWebSocket:D WebSocketAudio:D CmdWS:D RootUtils:D GsmDialer:D`
- GSM-tagged logs (MainActivity.log) sometimes don't appear in a rotated buffer during a live call;
  use `adb logcat -c` BEFORE the call, then dump after.

## Full iteration history
See `readme.md` sections "placeCall() Mobile network not available — iterations" and
"Browser mic uplink". Memory files: [[uplink-kernel-block]] (note: its "slot 1 blocked" conclusion
is now SUPERSEDED by the SOC_SINGLE_EXT discovery above), [[placecall-no-handle]].
