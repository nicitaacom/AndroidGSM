package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder


class AudioWebSocketHandler(
    private val context: Context,
    private val config: Config,
    private val log: GsmLogger = { MainActivity.log(it) },
    private val onAudioReceived: (ShortArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var tinycapProcess: Process? = null
    private var uplinkPcmProcess: Process? = null
    @Volatile private var ringbackJob: Job? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    private var isRecording = false
    private var isCallActive = false
    // 1. Use SupervisorJob so child coroutine crashes don't cancel siblings
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager

    private var wsConnection: WebSocketAudioClient? = null
    private var seqTx = 0L
    private var seqRx = -1L

    // Single consumer drains this channel — prevents flooding DefaultDispatcher with 50 coroutines/sec
    private var playbackChannel = Channel<ShortArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val isRooted: Boolean get() = RootUtils.isRooted

    companion object {
        private const val TAG = "AudioWebSocket"
        private const val SAMPLE_RATE = 16000
        private const val PLAYBACK_SAMPLE_RATE = 8000 // browser sends 8kHz for GSM uplink
        // European ringback (ITU-T 425Hz) shifted 2 semitones down: 425 × 2^(-2/12) ≈ 379 Hz.
        // 1s tone / 4s silence. Full cycle (tone + silence as zeroed PCM) burst-sent in one
        // pass per cycle; browser schedules end-to-end with no gaps.
        private const val RINGBACK_FREQ = 320.0  // ~3 semitones below EU 425Hz standard
        private const val RINGBACK_AMPLITUDE = 0.20
        private const val RINGBACK_TONE_MS = 1000
        private const val RINGBACK_GAP_MS = 4000
        private const val RINGBACK_EDGE_MS = 5
        @Volatile var micGain: Float = 1.0f
        @Volatile var playbackGain: Float = 0.7f
        // true = browser mic is uplink source (default); false = phone mic handles uplink natively
        @Volatile var useBrowserMicUplink: Boolean = true
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
        private val MIC_CAPTURE_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
    }

    val isWsConnected: Boolean get() = wsConnection?.isConnected ?: false

    fun connect(wsUrl: String, bearerToken: String, deviceToken: String) {
        log("🔌 WebSocket Audio: Connecting to $wsUrl")
        seqRx = -1L
        wsConnection?.close()
        wsConnection = WebSocketAudioClient(wsUrl, bearerToken, deviceToken, onAudioPacket = { packet -> handleAudioPacket(packet) })
    }

    fun disconnect() {
        stopRingback()
        wsConnection?.close()
        wsConnection = null
        stopAudioCapture()
        stopAudioPlayback()
    }

    // Streams a single sustained "tyyyyymmm" ringback to the WEBSITE over /ws/audio
    // (dir=toBrowser) while the call is DIALING — phone plays nothing.
    // Stopped on OFFHOOK (remote answers) or call end.
    fun startRingback() {
        if (ringbackJob?.isActive == true) return
        val tone = buildRingbackTone()
        val toneSamples = tone.size
        val chunkSamples = 320
        // delay() must cover tone + gap, not just gap. The burst is sent near-instantly,
        // but the browser spends RINGBACK_TONE_MS playing it before the silence begins.
        // Using only RINGBACK_GAP_MS caused the next burst to arrive while the tone was
        // still playing, producing a double-beep with no real gap between cycles.
        val cycleMs = (RINGBACK_TONE_MS + RINGBACK_GAP_MS).toLong()
        log("📞 Ringback → website (${RINGBACK_FREQ}Hz, ${RINGBACK_TONE_MS}ms tone, ${RINGBACK_GAP_MS}ms gap, ${cycleMs}ms cycle)")
        ringbackJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    var off = 0
                    while (off + chunkSamples <= toneSamples) {
                        sendPcmToBrowser(tone.copyOfRange(off, off + chunkSamples))
                        off += chunkSamples
                    }
                    kotlinx.coroutines.delay(cycleMs)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                Log.e(TAG, "❌ Ringback error: ${e.message}")
            }
        }
    }

    // Builds only the tone portion as PCM16 (RINGBACK_TONE_MS). Silence is NOT included —
    // the gap is handled by delay() in the loop so the browser queue goes empty and
    // nextPlayTimeRef expires, letting it re-anchor cleanly for each new tone burst.
    private fun buildRingbackTone(): ShortArray {
        val toneSamples = (RINGBACK_TONE_MS * SAMPLE_RATE / 1000 / 320) * 320
        val out = ShortArray(toneSamples)
        val amplitude = Short.MAX_VALUE * RINGBACK_AMPLITUDE
        val edge = (RINGBACK_EDGE_MS * SAMPLE_RATE / 1000).coerceAtLeast(1)
        val w = 2.0 * Math.PI * RINGBACK_FREQ / SAMPLE_RATE
        var phase = 0.0
        for (i in 0 until toneSamples) {
            val env = when {
                i < edge -> i.toDouble() / edge
                i > toneSamples - edge -> (toneSamples - i).toDouble() / edge
                else -> 1.0
            }
            out[i] = (Math.sin(phase) * env * amplitude)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            phase += w; if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
        }
        return out
    }

    fun stopRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
    }

    private fun sendPcmToBrowser(pcm: ShortArray) {
        val bytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        wsConnection?.sendAudioChunk(audio = base64, seq = seqTx++, sampleRate = SAMPLE_RATE, codec = "pcm16")
    }

    private fun handleAudioPacket(packet: org.json.JSONObject) {
        try {
            val dir = packet.optString("dir", "")
            val role = packet.optString("role", "")
            val audio = packet.optString("audio", "")
            val seq = packet.optLong("seq", -1)

            if (dir != "toAndroid" || audio.isEmpty()) {
                Log.d(TAG, "WS-RX skip dir=$dir role=$role hasAudio=${audio.isNotEmpty()}")
                return
            }

            if (seq != -1L) {
                if (seq < seqRx && seqRx - seq < 10000) { Log.w(TAG, "⚠️ Out of order: got seq=$seq, expected > $seqRx"); return }
            if (seq < seqRx) seqRx = seq  // seq wrapped/reset (new session) — accept and resync
                seqRx = seq
            }

            if (seq % 50L == 0L) Log.d(TAG, "WS-RX audio chunk seq=$seq")
            playAudioChunk(audio, seq)
        } catch (exception: Exception) {
            Log.e(TAG, "❌ Error handling audio packet: ${exception.message}")
        }
    }

    fun startAudioCapture() {
        if (isRecording) return

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        playbackChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        hpfPrev = 0f
        hpfPrevIn = 0f
        isRecording = true

        if (isCallActive) {
            // GSM call mode: Java AudioRecord cannot access VOICE_CALL audio without
            // CAPTURE_AUDIO_OUTPUT (signature permission, ungratable at runtime).
            // Run tinycap as root — reads directly from ALSA MultiMedia1 kernel device,
            // bypassing all Java permission checks. VOC_REC_DL mixer must already be open.
            scope.launch { captureViaTinycap() }
            log("✅ Capture started (tinycap/root) — GSM call downlink")
            return
        }

        // TEST mode: use standard AudioRecord with mic
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("❌ ERROR: RECORD_AUDIO permission not granted")
            isRecording = false
            return
        }
        try {
            log("🎤 WebSocket: Starting mic capture...")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
            if (bufferSize <= 0) { log("❌ ERROR: Invalid buffer size"); isRecording = false; return }

            audioRecord = buildMicAudioRecord(bufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                log("❌ ERROR: AudioRecord not initialized")
                isRecording = false
                return
            }

            audioRecord?.startRecording()
            scope.launch { captureAndStreamAudio(bufferSize) }
            log("✅ Capture started (WS) - source: ${resolveCaptureSourceLabel()}")
        } catch (exception: SecurityException) {
            log("❌ ERROR: Permission rejected: ${exception.message}")
            isRecording = false
        } catch (exception: Exception) {
            log("❌ ERROR starting capture: ${exception.message}")
            isRecording = false
        }
    }

    fun stopAudioCapture() {
        try {
            isRecording = false
            tinycapProcess?.destroy()
            tinycapProcess = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            if (!isCallActive) {
                audioManager.mode = AudioManager.MODE_NORMAL
                audioManager.isSpeakerphoneOn = false
            }
            log("🎤 Capture stopped")
        } catch (exception: Exception) {
            Log.e(TAG, "❌ Error stopping capture", exception)
        }
    }

    // Capture GSM call downlink via tinycap as root.
    // tinycap reads from ALSA MultiMedia1 (card 0 device 0) at kernel level — no Java
    // permission check applies. VOC_REC_DL mixer must be open before this is called.
    private suspend fun captureViaTinycap() = withContext(Dispatchers.IO) {
        val chunkSamples = 320 // 20ms at 16kHz
        val chunkBytes = chunkSamples * 2 // PCM16 = 2 bytes/sample
        // tinycap writes a 44-byte WAV header before PCM data — skip it
        val WAV_HEADER_BYTES = 44

        try {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "/system/bin/tinycap /proc/self/fd/1 -D 0 -d 0 -c 1 -r 16000 -b 16"
            ))
            tinycapProcess = proc

            val input = proc.inputStream
            val headerBuf = ByteArray(WAV_HEADER_BYTES)
            var headerRead = 0
            while (headerRead < WAV_HEADER_BYTES && isRecording) {
                val n = input.read(headerBuf, headerRead, WAV_HEADER_BYTES - headerRead)
                if (n < 0) break
                headerRead += n
            }
            log("✅ tinycap: WAV header consumed ($headerRead bytes), streaming PCM...")

            val buffer = ByteArray(chunkBytes)
            var seq = seqTx
            var chunkCount = 0

            while (isRecording) {
                var offset = 0
                while (offset < chunkBytes && isRecording) {
                    val n = input.read(buffer, offset, chunkBytes - offset)
                    if (n < 0) { isRecording = false; break }
                    offset += n
                }
                if (offset == 0) continue

                // Send raw PCM bytes — no HPF/gain processing for downlink voice
                val base64Audio = Base64.encodeToString(buffer, 0, offset, Base64.NO_WRAP)
                wsConnection?.sendAudioChunk(audio = base64Audio, seq = seq, sampleRate = SAMPLE_RATE, codec = "pcm16")
                if (seq % 50L == 0L) {
                    val shorts = ShortArray(offset / 2)
                    ByteBuffer.wrap(buffer, 0, offset).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                    Log.d(TAG, "tinycap-SEND seq=$seq rms=${String.format("%.4f", rms(shorts, shorts.size))}")
                }
                seq++
                chunkCount++
            }
            seqTx = seq
            log("✅ tinycap capture ended: $chunkCount chunks sent")
        } catch (e: Exception) {
            log("❌ tinycap capture error: ${e.message}")
        } finally {
            tinycapProcess?.destroy()
            tinycapProcess = null
        }
    }

    private fun resolveCaptureSourceLabel(): String {
        val source = try { audioRecord?.audioSource } catch (_: Exception) { null }
        return when (source) {
            MediaRecorder.AudioSource.REMOTE_SUBMIX -> "REMOTE_SUBMIX (GSM via speakerphone)"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION (mic)"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> "source=$source"
        }
    }

    // 7. Build AudioRecord using MIC sources as fallback
    private fun buildMicAudioRecord(bufferSize: Int): AudioRecord? {
        for (source in MIC_CAPTURE_SOURCES) {
            try {
                val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, bufferSize)
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    log("✅ Capture source: $source (mic fallback)")
                    return record
                }
                record.release()
            } catch (exception: SecurityException) {
                log("❌ Source $source permission denied: ${exception.message}")
            } catch (exception: Exception) {
                Log.w(TAG, "Failed source $source: ${exception.message}")
            }
        }
        log("❌ All mic capture sources failed")
        return null
    }

    // High-pass filter state (single-pole IIR, cutoff ~80Hz at 16kHz)
    // y[n] = α * (y[n-1] + x[n] - x[n-1])
    // α = RC / (RC + dt), RC = 1/(2π*fc), dt = 1/fs
    private var hpfPrev: Float = 0f
    private var hpfPrevIn: Float = 0f
    private val hpfAlpha: Float = run {
        val fc = 80.0
        val rc = 1.0 / (2.0 * Math.PI * fc)
        val dt = 1.0 / SAMPLE_RATE
        (rc / (rc + dt)).toFloat()
    }

    // Noise gate: RMS threshold below which the chunk is dropped (not transmitted)
    // 0.008f ≈ -42 dBFS — enough to kill background hiss, won't cut normal speech
    private val NOISE_GATE_RMS_THRESHOLD = 0.002f

    private fun applyHighPassFilter(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            val x = samples[i].toFloat() / Short.MAX_VALUE
            val y = hpfAlpha * (hpfPrev + x - hpfPrevIn)
            hpfPrevIn = x
            hpfPrev = y
            samples[i] = (y * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun rms(samples: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) sum += (samples[i].toDouble() / Short.MAX_VALUE).let { it * it }
        return Math.sqrt(sum / count).toFloat()
    }

    private suspend fun captureAndStreamAudio(bufferSize: Int) = withContext(Dispatchers.IO) {
        val buffer = ShortArray(bufferSize / 2)
        var chunkCount = 0
        var gatedCount = 0

        while (isRecording) {
            try {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (read > 0) {
                    // 1. High-pass filter — remove rumble/hum below ~80Hz
                    applyHighPassFilter(buffer, read)

                    // Apply mic gain
                    if (micGain != 1.0f) {
                        for (i in 0 until read) {
                            buffer[i] = (buffer[i] * micGain).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                    }

                    // Noise gate disabled — threshold was too aggressive, cutting speech
                    // if (rms(buffer, read) < NOISE_GATE_RMS_THRESHOLD) { gatedCount++; continue }

                    val byteBuffer = ByteArray(read * 2)
                    ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read)
                    val base64Audio = Base64.encodeToString(byteBuffer, Base64.NO_WRAP)
                    val seq = seqTx++
                    wsConnection?.sendAudioChunk(audio = base64Audio, seq = seq, sampleRate = SAMPLE_RATE, codec = "pcm16")
                    if (seq % 50L == 0L) Log.d(TAG, "WS-SEND audio chunk seq=$seq rms=${String.format("%.6f", rms(buffer, read))}")
                    chunkCount++
                } else if (read < 0) {
                    Log.e(TAG, "❌ Read error: $read")
                    break
                }
            } catch (exception: Exception) {
                Log.e(TAG, "❌ Error in capture loop: ${exception.message}")
                break
            }
        }
        Log.d(TAG, "Capture ended: $chunkCount chunks sent, $gatedCount gated")
    }

    fun startAudioPlayback() {
        if (isPlaying) return
        try {
            log("🔊 WebSocket: Starting playback...")

            if (isCallActive) {
                // Call mode: write browser mic PCM directly to /dev/snd/pcmC0D19p (VoiceMMode2).
                // The Incall_Music Audio Mixer injection (MultiMedia1 → voice TX) is blocked by
                // the MIUI CAF kernel — slot 1 (VoiceMMode2) ELEM_WRITE is silently ignored.
                // Direct PCM write to pcmC0D19p bypasses the mixer entirely and injects audio
                // straight into the active VoiceMMode2 TX uplink. Confirmed working via tinyplay.
                startCallUplinkPcmWriter()
                return
            }

            // TEST mode: standard AudioTrack to earpiece/AEC
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
            if (bufferSize <= 0) { log("❌ ERROR: Invalid playback buffer size"); return }

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) { log("❌ ERROR: AudioTrack not initialized"); return }

            isPlaying = true
            audioTrack?.play()
            startPlaybackConsumer()
            log("✅ Playback started (TEST mode, earpiece/AEC)")
        } catch (exception: Exception) {
            log("❌ ERROR starting playback: ${exception.message}")
        }
    }

    // Writes browser mic PCM directly to /dev/snd/pcmC0D19p (VoiceMMode2 TX uplink).
    // Spawns a root subprocess that reads from stdin and writes to the PCM device.
    // VoiceMMode2 format: S16_LE, mono, 8000 Hz, period=1024 samples.
    // Browser sends 8kHz PCM (PLAYBACK_SAMPLE_RATE=8000) which matches exactly.
    private fun startCallUplinkPcmWriter() {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "tinyplay /proc/self/fd/0 -D 0 -d 19 -c 1 -r 8000 -b 16 2>/dev/null"
            ))
            uplinkPcmProcess = proc
            isPlaying = true
            log("✅ Uplink PCM writer started (VoiceMMode2 pcmC0D19p, 8kHz mono S16_LE)")
            // startPlaybackConsumer will drain the channel and write to proc.outputStream
            startCallUplinkConsumer(proc)
        } catch (e: Exception) {
            log("❌ ERROR starting uplink PCM writer: ${e.message}")
        }
    }

    private fun startCallUplinkConsumer(proc: Process) {
        scope.launch(Dispatchers.IO) {
            val out = proc.outputStream
            try {
                // tinyplay requires a WAV header — send a streaming WAV header with max size fields
                // so it doesn't try to seek. sampleRate=8000, mono, 16-bit PCM.
                val sr = PLAYBACK_SAMPLE_RATE
                val header = buildWavHeader(sr)
                out.write(header)
                out.flush()
                log("📤 WAV header sent to tinyplay stdin")
            } catch (t: Throwable) {
                Log.e(TAG, "❌ Failed to write WAV header: ${t.message}")
                return@launch
            }
            for (samples in playbackChannel) {
                if (!isPlaying) break
                try {
                    val bytes = ByteArray(samples.size * 2)
                    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
                    out.write(bytes)
                    out.flush()
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) Log.e(TAG, "❌ Uplink PCM write error: ${t.message}")
                    break
                }
            }
            Log.d(TAG, "Uplink PCM consumer exited")
        }
    }

    // Streaming WAV header: size fields set to 0xFFFFFFFF so tinyplay doesn't seek.
    private fun buildWavHeader(sampleRate: Int): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * 2 // mono 16-bit
        buf.put("RIFF".toByteArray())
        buf.putInt(0x7FFFFFFF)           // chunk size — max for streaming
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)                   // PCM subchunk size
        buf.putShort(1)                  // PCM format
        buf.putShort(1)                  // mono
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(2)                  // block align
        buf.putShort(16)                 // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(0x7FFFFFFF)           // data size — max for streaming
        return buf.array()
    }

    fun setCallActive(active: Boolean) {
        isCallActive = active
        if (active) stopRingback()
        Log.d(TAG, "WebSocket call state: $active")
    }

    private fun playAudioChunk(base64Audio: String, seq: Long = -1) {
        if (!isPlaying) return
        if (base64Audio.isBlank()) return
        // In call mode with phone mic selected: drop browser audio — phone mic handles uplink natively
        if (isCallActive && !useBrowserMicUplink) return

        val audioBytes = try {
            Base64.decode(base64Audio, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "❌ Base64 decode failed: ${e.message}")
            return
        }
        if (audioBytes.isEmpty() || audioBytes.size % 2 != 0) return

        val shortBuffer = ShortArray(audioBytes.size / 2)
        ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)

        val gain = playbackGain
        if (gain != 1.0f) {
            for (i in shortBuffer.indices) {
                shortBuffer[i] = (shortBuffer[i] * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        // Non-blocking offer — channel drops oldest if full (brief network burst), no coroutine launched
        playbackChannel.trySend(shortBuffer)
    }

    private fun startPlaybackConsumer() {
        scope.launch(Dispatchers.IO) {
            for (samples in playbackChannel) {
                if (!isPlaying) break
                try {
                    val track = audioTrack ?: break
                    if (track.state != AudioTrack.STATE_INITIALIZED) break
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) continue
                    val written = track.write(samples, 0, samples.size)
                    if (written < 0) Log.e(TAG, "❌ AudioTrack write error: $written")
                } catch (t: Throwable) {
                    if (t !is kotlinx.coroutines.CancellationException) Log.e(TAG, "❌ Playback consumer error: ${t.message}")
                    break
                }
            }
            Log.d(TAG, "Playback consumer exited")
        }
    }

    fun stopAudioPlayback() {
        try {
            isPlaying = false
            val track = audioTrack
            audioTrack = null
            uplinkPcmProcess?.destroy()
            uplinkPcmProcess = null
            // Close channel to unblock consumer coroutine, then recreate for next session
            playbackChannel.close()
            playbackChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            track?.stop()
            track?.release()
            scope.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            log("🔊 Playback stopped")
        } catch (exception: Exception) {
            Log.e(TAG, "❌ Error stopping playback", exception)
        }
    }
    
    fun cleanup() {
        stopAudioCapture()
        stopAudioPlayback()
        disconnect()
        scope.cancel()
        Log.d(TAG, "Cleaned up")
    }
}
