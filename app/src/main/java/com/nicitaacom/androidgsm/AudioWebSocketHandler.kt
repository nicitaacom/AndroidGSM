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
    private val onAudioReceived: (ShortArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
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

    private val isRooted: Boolean by lazy { RootUtils.isRooted() }

    companion object {
        private const val TAG = "AudioWebSocket"
        private const val SAMPLE_RATE = 16000
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
        wsConnection = WebSocketAudioClient(wsUrl, bearerToken, deviceToken) { packet -> handleAudioPacket(packet) }
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
                if (seq < seqRx) { Log.w(TAG, "⚠️ Out of order: got seq=$seq, expected > $seqRx"); return }
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
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            MainActivity.log("❌ ERROR: RECORD_AUDIO permission not granted")
            return
        }

        // 2. Explicitly start RECORD_AUDIO app op before capturing — required on Android 12+ or system kills the process
        try {
            val packageName = context.packageName
            val uid = context.applicationInfo.uid
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val result = appOpsManager.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_RECORD_AUDIO, uid, packageName
                )
                if (result != android.app.AppOpsManager.MODE_ALLOWED) {
                    MainActivity.log("❌ RECORD_AUDIO app op not allowed (result=$result) - mic blocked by system")
                    return
                }
            }
            MainActivity.log("✅ RECORD_AUDIO app op allowed by system")
        } catch (exception: Exception) {
            MainActivity.log("⚠️ AppOps check warning: ${exception.message}")
        }

        try {
            MainActivity.log("🎤 WebSocket: Starting capture...")
            // Always use MODE_IN_COMMUNICATION + earpiece so hardware AEC suppresses feedback.
            // Speakerphone causes a mic→speaker→mic loop regardless of mode.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
            if (bufferSize <= 0) { MainActivity.log("❌ ERROR: Invalid buffer size"); return }

            // Always attempt REMOTE_SUBMIX path first.
            // Root detection can be false-negative on some devices/ROMs.
            audioRecord = buildRootedAudioRecord(bufferSize)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                MainActivity.log("❌ ERROR: AudioRecord not initialized")
                return
            }

            // 3. Recreate scope and channel if previous session was cancelled
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            playbackChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            hpfPrev = 0f
            hpfPrevIn = 0f

            isRecording = true
            audioRecord?.startRecording()
            scope.launch { captureAndStreamAudio(bufferSize) }
            MainActivity.log("✅ Capture started (WS) - source: ${resolveCaptureSourceLabel()}")
        } catch (exception: SecurityException) {
            MainActivity.log("❌ ERROR: Permission rejected: ${exception.message}")
        } catch (exception: Exception) {
            MainActivity.log("❌ ERROR starting capture: ${exception.message}")
        }
    }

    fun stopAudioCapture() {
        try {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false

            MainActivity.log("🎤 Capture stopped")
        } catch (exception: Exception) {
            Log.e(TAG, "❌ Error stopping capture", exception)
        }
    }

    // 6. Build AudioRecord using REMOTE_SUBMIX (requires root + CAPTURE_AUDIO_OUTPUT permission)
    private fun buildRootedAudioRecord(bufferSize: Int): AudioRecord? {
        if (!isRooted) {
            MainActivity.log("⚠️ Root not detected by app checks; still attempting REMOTE_SUBMIX capture")
        }

        val hasCapture = ContextCompat.checkSelfPermission(context, "android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED
        if (!hasCapture) {
            val granted = RootUtils.grantAudioOutputCapture(context)
            if (!granted) {
                MainActivity.log("❌ Failed to grant CAPTURE_AUDIO_OUTPUT via root - falling back to mic")
                return buildMicAudioRecord(bufferSize)
            }
        }

        return try {
            val record = AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX, SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, bufferSize)
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                MainActivity.log("✅ REMOTE_SUBMIX initialized - capturing device audio output")
                record
            } else {
                record.release()
                MainActivity.log("❌ REMOTE_SUBMIX failed to initialize - falling back to mic")
                buildMicAudioRecord(bufferSize)
            }
        } catch (exception: SecurityException) {
            MainActivity.log("❌ REMOTE_SUBMIX permission denied: ${exception.message} - falling back to mic")
            buildMicAudioRecord(bufferSize)
        } catch (exception: Exception) {
            MainActivity.log("❌ REMOTE_SUBMIX exception: ${exception.message} - falling back to mic")
            buildMicAudioRecord(bufferSize)
        }
    }

    private fun resolveCaptureSourceLabel(): String {
        val source = try { audioRecord?.audioSource } catch (_: Exception) { null }
        return if (source == MediaRecorder.AudioSource.REMOTE_SUBMIX) "REMOTE_SUBMIX" else "MIC/VOICE_COMMUNICATION"
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
    private val NOISE_GATE_RMS_THRESHOLD = 0.008f

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

                    // 2. Noise gate — skip chunk if RMS is below threshold
                    if (rms(buffer, read) < NOISE_GATE_RMS_THRESHOLD) {
                        gatedCount++
                        if (gatedCount % 50 == 0) Log.d(TAG, "🔇 Noise gate: $gatedCount chunks suppressed")
                        continue
                    }

                    val byteBuffer = ByteArray(read * 2)
                    ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read)
                    val base64Audio = Base64.encodeToString(byteBuffer, Base64.NO_WRAP)
                    val seq = seqTx++
                    wsConnection?.sendAudioChunk(audio = base64Audio, seq = seq, sampleRate = SAMPLE_RATE, codec = "pcm16")
                    if (seq % 50L == 0L) Log.d(TAG, "WS-SEND audio chunk seq=$seq")
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

            // Earpiece in all modes — speakerphone causes mic→speaker→mic feedback loop.
            // Hardware AEC on VOICE_COMMUNICATION stream handles echo suppression.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
            if (bufferSize <= 0) { MainActivity.log("❌ ERROR: Invalid playback buffer size"); return }

            // 1. Both modes use VOICE_COMMUNICATION so hardware AEC activates
            val audioUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(audioUsage)
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

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) { MainActivity.log("❌ ERROR: AudioTrack not initialized"); return }

            isPlaying = true
            audioTrack?.play()
            startPlaybackConsumer()
            MainActivity.log("✅ Playback started - earpiece/AEC mode")
        } catch (exception: Exception) {
            MainActivity.log("❌ ERROR starting playback: ${exception.message}")
        }
    }

    fun setCallActive(active: Boolean) {
        isCallActive = active
        Log.d(TAG, "WebSocket call state: $active")
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

        val shortBuffer = ShortArray(audioBytes.size / 2)
        ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)

        val gain = 0.7f
        for (i in shortBuffer.indices) {
            shortBuffer[i] = (shortBuffer[i] * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
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
            // Close channel to unblock consumer coroutine, then recreate for next session
            playbackChannel.close()
            playbackChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            track?.stop()
            track?.release()
            scope.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
