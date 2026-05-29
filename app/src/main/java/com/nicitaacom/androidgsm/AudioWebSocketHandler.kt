package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
    private val onAudioReceived: (ShortArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var tinycapProcess: Process? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var tinyplayProcess: Process? = null
    @Volatile private var lastDestroyedTinyplay: Process? = null
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
    // Call mode: 8 chunks × 32ms = 256ms max queue before dropping oldest (keeps lag bounded).
    // Non-call: 64 chunks for smooth playback over variable network.
    private var playbackChannel = Channel<ShortArray>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val isRooted: Boolean by lazy { RootUtils.isRooted() }

    companion object {
        private const val TAG = "AudioWebSocket"
        private const val SAMPLE_RATE = 16000
        @Volatile var micGain: Float = 1.0f
        @Volatile var playbackGain: Float = 0.7f
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
        private val MIC_CAPTURE_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
    }

    fun connect(wsUrl: String, bearerToken: String, deviceToken: String) {
        MainActivity.log("🔌 WebSocket Audio: Connecting to $wsUrl")
        seqRx = -1L
        wsConnection = WebSocketAudioClient(wsUrl, bearerToken, deviceToken, onAudioPacket = { packet -> handleAudioPacket(packet) })
    }

    fun disconnect() {
        wsConnection?.close()
        wsConnection = null
        stopAudioCapture()
        stopAudioPlayback()
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

            if (seq % 500L == 0L) Log.d(TAG, "WS-RX audio chunk seq=$seq")
            playAudioChunk(audio, seq)
        } catch (exception: Exception) {
            Log.e(TAG, "❌ Error handling audio packet: ${exception.message}")
        }
    }

    fun startAudioCapture() {
        if (isRecording) return

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        playbackChannel = Channel(capacity = if (isCallActive) 8 else 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        hpfPrev = 0f
        hpfPrevIn = 0f
        isRecording = true

        if (isCallActive) {
            // GSM call mode: Java AudioRecord cannot access VOICE_CALL audio without
            // CAPTURE_AUDIO_OUTPUT (signature permission, ungratable at runtime).
            // Run tinycap as root — reads directly from ALSA MultiMedia1 kernel device,
            // bypassing all Java permission checks. VOC_REC_DL mixer must already be open.
            scope.launch { captureViaTinycap() }
            MainActivity.log("✅ Capture started (tinycap/root) — GSM call downlink")
            return
        }

        // TEST mode: use standard AudioRecord with mic
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            MainActivity.log("❌ ERROR: RECORD_AUDIO permission not granted")
            isRecording = false
            return
        }
        try {
            MainActivity.log("🎤 WebSocket: Starting mic capture...")
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
            if (bufferSize <= 0) { MainActivity.log("❌ ERROR: Invalid buffer size"); isRecording = false; return }

            audioRecord = buildMicAudioRecord(bufferSize)
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                MainActivity.log("❌ ERROR: AudioRecord not initialized")
                isRecording = false
                return
            }

            audioRecord?.startRecording()
            scope.launch { captureAndStreamAudio(bufferSize) }
            MainActivity.log("✅ Capture started (WS) - source: ${resolveCaptureSourceLabel()}")
        } catch (exception: SecurityException) {
            MainActivity.log("❌ ERROR: Permission rejected: ${exception.message}")
            isRecording = false
        } catch (exception: Exception) {
            MainActivity.log("❌ ERROR starting capture: ${exception.message}")
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
            MainActivity.log("🎤 Capture stopped")
        } catch (exception: Exception) {
            Log.e(TAG, "❌ Error stopping capture", exception)
        }
    }

    // Capture GSM call downlink via tinycap on MultiMedia1 (device 0, 16kHz).
    // VOC_REC_DL must be set BEFORE the call connects (during dialing) — the kernel blocks
    // slot 1 (VoiceMMode2) writes once the call session is active.
    // primed in handleCallStarted → enableIncallMusicCapture() before gsmDialer.startCall().
    private suspend fun captureViaTinycap() = withContext(Dispatchers.IO) {
        val chunkSamples = 320 // 20ms at 16kHz
        val chunkBytes = chunkSamples * 2
        val CAPTURE_SAMPLE_RATE = SAMPLE_RATE // 16000
        val WAV_HEADER_BYTES = 44

        try {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "exec /system/bin/tinycap /proc/self/fd/1 -D 0 -d 0 -c 1 -r 16000 -b 16"
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
            MainActivity.log("✅ tinycap: WAV header consumed ($headerRead bytes), streaming PCM...")

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
            MainActivity.log("✅ tinycap capture ended: $chunkCount chunks sent")
        } catch (e: Exception) {
            MainActivity.log("❌ tinycap capture error: ${e.message}")
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
                    MainActivity.log("✅ Capture source: $source (mic fallback)")
                    return record
                }
                record.release()
            } catch (exception: SecurityException) {
                MainActivity.log("❌ Source $source permission denied: ${exception.message}")
            } catch (exception: Exception) {
                Log.w(TAG, "Failed source $source: ${exception.message}")
            }
        }
        MainActivity.log("❌ All mic capture sources failed")
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
            MainActivity.log("🔊 WebSocket: Starting playback...")

            if (!isCallActive) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.isSpeakerphoneOn = false
            }

            isPlaying = true

            if (isCallActive) {
                // GSM call mode: write PCM directly to pcmC0D0p (MultiMedia1) via native binary
                // so the Incall_Music DSP mixer can route it into the GSM voice TX uplink.
                // AudioTrack cannot open this path — AudioFlinger keeps pcm0p in standby during calls.
                startPcmPlayProcess()
                startPlaybackConsumerPcmPlay()
            } else {
                val playRate = SAMPLE_RATE
                val channelOut = CHANNEL_OUT
                val bufferSize = AudioTrack.getMinBufferSize(playRate, channelOut, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
                if (bufferSize <= 0) { MainActivity.log("❌ ERROR: Invalid playback buffer size"); isPlaying = false; return }

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(playRate)
                        .setChannelMask(channelOut)
                        .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    MainActivity.log("❌ ERROR: AudioTrack not initialized"); isPlaying = false; return
                }
                audioTrack?.play()
                startPlaybackConsumer()
            }

            MainActivity.log("✅ Playback started (isCallActive=$isCallActive)")
        } catch (exception: Exception) {
            MainActivity.log("❌ ERROR starting playback: ${exception.message}")
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

    private fun startPlaybackConsumerPcmPlay() {
        scope.launch(Dispatchers.IO) {
            var chunks = 0L
            // PREPARE failed means the voice DSP path isn't live yet (call still ringing/dialing).
            // Retry launching pcm_play every 600ms for up to 20 seconds until it stays alive.
            val deadline = System.currentTimeMillis() + 20_000L
            var proc = tinyplayProcess
            while (isPlaying && proc != null) {
                // Wait for pcm_play to either succeed (start writing) or fail fast (PREPARE failed).
                // If it exits within 400ms it failed — don't consume audio yet.
                val exited = proc.waitFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (exited) {
                    val exitCode = try { proc.exitValue() } catch (_: Exception) { -1 }
                    Log.d(TAG, "pcm_play: PREPARE failed (exit=$exitCode) — flushing queue, retrying in 600ms")
                    // Discard queued audio — it's stale (from before voice path was live)
                    while (playbackChannel.tryReceive().isSuccess) {}
                    if (!isPlaying) break
                    if (System.currentTimeMillis() > deadline) { Log.e(TAG, "pcm_play: deadline exceeded"); break }
                    Thread.sleep(600)
                    startPcmPlayProcess()
                    proc = tinyplayProcess
                    continue
                }

                // pcm_play is alive — flush stale audio queued during retry wait, then start piping
                while (playbackChannel.tryReceive().isSuccess) {}
                val out = proc.outputStream.buffered(4096)
                try {
                    for (samples in playbackChannel) {
                        if (!isPlaying) break
                        // Browser sends 16kHz; pcm_play opens pcm0p at 8kHz (voice HAL rate).
                        // Downsample 2:1 by averaging pairs of samples.
                        val outSamples = ShortArray(samples.size / 2)
                        for (i in outSamples.indices) {
                            outSamples[i] = ((samples[i * 2].toInt() + samples[i * 2 + 1].toInt()) / 2).toShort()
                        }
                        val bytes = ByteArray(outSamples.size * 2)
                        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outSamples)
                        out.write(bytes)
                        chunks++
                        if (chunks % 500L == 0L) { out.flush(); Log.d(TAG, "pcm_play: piped $chunks chunks") }
                    }
                    break // channel closed cleanly — we're done
                } catch (_: Exception) {
                    // pipe broke — pcm_play died mid-call (xrun or call ended)
                }
                try { out.close() } catch (_: Exception) {}
                if (!isPlaying) break
                Log.d(TAG, "pcm_play: pipe broke after $chunks chunks — retrying")
                if (System.currentTimeMillis() > deadline) { Log.e(TAG, "pcm_play: deadline exceeded"); break }
                Thread.sleep(600)
                startPcmPlayProcess()
                proc = tinyplayProcess
            }
            Log.d(TAG, "pcm_play consumer exited after $chunks chunks")
        }
    }

    private fun describeAudioDevice(device: AudioDeviceInfo): String =
        "id=${device.id},type=${device.type},product=${device.productName}"

    fun setCallActive(active: Boolean) {
        isCallActive = active
        Log.d(TAG, "WebSocket call state: $active")
    }

    fun flushPlaybackQueue() {
        var dropped = 0
        while (playbackChannel.tryReceive().isSuccess) { dropped++ }
        Log.d(TAG, "Playback queue flushed: dropped $dropped chunks")
    }

    private fun playAudioChunk(base64Audio: String, seq: Long = -1) {
        if (!isPlaying) return
        if (base64Audio.isBlank()) return

        val audioBytes = try {
            Base64.decode(base64Audio, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "❌ Base64 decode failed: ${e.message}")
            return
        }
        if (audioBytes.isEmpty() || audioBytes.size % 2 != 0) return

        var shortBuffer = ShortArray(audioBytes.size / 2)
        ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)

        val gain = playbackGain
        if (gain != 1.0f) {
            for (i in shortBuffer.indices) {
                shortBuffer[i] = (shortBuffer[i] * gain).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        // Browser sends 16kHz mono — AudioTrack in call mode is also 16kHz mono. No conversion needed.

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
            val tinyplay = tinyplayProcess
            tinyplayProcess = null
            // Close channel to unblock consumer coroutine, then recreate for next session
            playbackChannel.close()
            playbackChannel = Channel(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            track?.stop()
            track?.release()
            scope.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            if (tinyplay != null) {
                tinyplay.destroyForcibly()
                lastDestroyedTinyplay = tinyplay
            }
            MainActivity.log("🔊 Playback stopped")
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
