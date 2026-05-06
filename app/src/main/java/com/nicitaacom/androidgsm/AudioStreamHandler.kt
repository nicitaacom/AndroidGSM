package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import android.os.Build
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioStreamHandler(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") pusherClient: Any? = null,
    private val onAudioCaptured: ((String) -> Unit)? = null
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "AudioStreamHandler"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
        private const val AUDIO_CHUNK_SIZE_MS = 20  // 20ms chunks at 16kHz = 320 samples
        private const val EXPECTED_CHUNK_SIZE = SAMPLE_RATE * AUDIO_CHUNK_SIZE_MS / 1000 * 2  // in bytes
        private val CAPTURE_SOURCES = intArrayOf(
        // 1. REMOTE_SUBMIX captures both sides of call — requires root or CAPTURE_AUDIO_OUTPUT
        MediaRecorder.AudioSource.REMOTE_SUBMIX,
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC,
    )
    }

    fun startAudioCapture() {
        if (isRecording) return

        try {
            MainActivity.log("🎤 Starting audio capture...")

            // 1. Set audio mode for voice communication (BEFORE creating AudioRecord)
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            
            // Log routing info
            MainActivity.log("Audio mode set to MODE_IN_COMMUNICATION, speaker: OFF")

            // 2. Try to route to BT/wired/earpiece if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                }?.let { device ->
                    audioManager.setCommunicationDevice(device)
                    MainActivity.log("Using audio device: ${device.type}")
                }
            }

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            if (bufferSize <= 0) {
                MainActivity.log("ERROR: Invalid buffer size: $bufferSize")
                return
            }

            var selectedRecord: AudioRecord? = null
            for (source in CAPTURE_SOURCES) {
                try {
                    val record = AudioRecord(source, SAMPLE_RATE, CHANNEL_IN, AUDIO_FORMAT, bufferSize)
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        MainActivity.log("✅ Audio source selected: $source")
                        selectedRecord = record
                        break
                    }
                    record.release()
                } catch (error: Exception) {
                    // 1. Don't crash — just try next source
                    MainActivity.log("⚠️ Source $source failed: ${error.message}, trying next")
                }
            }

            if (selectedRecord == null) {
                MainActivity.log("❌ No audio source available — capture aborted")
                isRecording = false
                return
            }
            audioRecord = selectedRecord

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                MainActivity.log("ERROR: AudioRecord not initialized - no supported capture source")
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            scope.launch {
                captureAndStreamAudio(bufferSize)
            }

            MainActivity.log("✅ Audio capture started (16kHz PCM, MONO, 16-bit)")
        } catch (e: SecurityException) {
            MainActivity.log("ERROR: RECORD_AUDIO permission not granted")
            Log.e(TAG, "Security exception", e)
        } catch (e: Exception) {
            MainActivity.log("ERROR starting audio: ${e.message}")
            Log.e(TAG, "Failed to start audio capture", e)
        }
    }


    private suspend fun captureAndStreamAudio(bufferSize: Int) = withContext(Dispatchers.IO) {
        val buffer = ShortArray(bufferSize / 2)
        var chunkCount = 0

        while (isRecording) {
            try {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (read > 0) {
                    // 1. Only process valid chunk sizes (at least one sample = 2 bytes)
                    if (read < 1) continue

                    // 2. Convert to bytes - ensure we only convert the actual samples read
                    val byteBuffer = ByteArray(read * 2)
                    ByteBuffer.wrap(byteBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read)

                    // 3. Encode to base64
                    val base64Audio = try {
                        Base64.encodeToString(byteBuffer, Base64.NO_WRAP)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error encoding audio to base64", e)
                        continue
                    }

                    // 4. Validate base64 before sending
                    if (base64Audio.isBlank()) {
                        Log.w(TAG, "⚠️ Generated empty base64 audio chunk")
                        continue
                    }

                    // 5. Send to backend with error handling
                    try {
                        onAudioCaptured?.invoke(base64Audio)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending audio chunk via Pusher", e)
                    }

                    chunkCount++
                    if (chunkCount % 50 == 0) {
                        Log.d(TAG, "✅ Sent $chunkCount audio chunks (${read} samples each)")
                    }
                } else if (read < 0) {
                    // Negative read indicates error
                    Log.e(TAG, "❌ AudioRecord.read() returned error: $read")
                    break
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop", e)
                break
            }
        }
        Log.d(TAG, "Audio capture loop ended after $chunkCount chunks")
    }

    fun stopAudioCapture() {
        try {
            isRecording = false
            val record = audioRecord
            audioRecord = null // 1. Null first — prevents read() after release() race
            record?.stop()
            record?.release()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { audioManager.clearCommunicationDevice() } catch (_: Exception) {}
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            MainActivity.log("🎤 Audio capture stopped")
        } catch (error: Exception) {
            MainActivity.log("ERROR stopping capture: ${error.message}")
        }
    }

    fun startAudioPlayback() {
        if (isPlaying) return

        try {
            MainActivity.log("🔊 Starting audio playback...")

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_OUT,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            if (bufferSize <= 0) {
                MainActivity.log("ERROR: Invalid playback buffer size")
                return
            }

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                MainActivity.log("ERROR: AudioTrack not initialized")
                return
            }

            isPlaying = true
            audioTrack?.play()

            MainActivity.log("✅ Audio playback started (default media output)")
        } catch (e: Exception) {
            MainActivity.log("ERROR starting playback: ${e.message}")
            Log.e(TAG, "Failed to start audio playback", e)
        }
    }

    fun playAudioChunk(base64Audio: String) {
        if (!isPlaying) return  // never auto-start — caller must explicitly start playback
        
        scope.launch {
            // 1. Wrap entire coroutine — any exception here must never crash the process
            runCatching {
                if (base64Audio.isBlank()) return@launch

                val audioBytes = try {
                    Base64.decode(base64Audio, Base64.NO_WRAP)
                } catch (error: Exception) {
                    MainActivity.log("❌ Audio decode failed: ${error.message}")
                    return@launch
                }

                if (audioBytes.isEmpty() || audioBytes.size % 2 != 0) return@launch

                val shortBuffer = ShortArray(audioBytes.size / 2)
                ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)

                // 2. Check state before every write — audioTrack can be released mid-flight
                val track = audioTrack ?: run {
                    MainActivity.log("⚠️ AudioTrack null — skipping chunk")
                    return@launch
                }
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    isPlaying = false
                    return@launch
                }
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try { track.play() } catch (_: Exception) { return@launch }
                }

                // 3. Write with result check — never throw
                val written = try {
                    track.write(shortBuffer, 0, shortBuffer.size)
                } catch (error: Exception) {
                    MainActivity.log("❌ AudioTrack.write exception: ${error.message}")
                    isPlaying = false
                    return@launch
                }

                if (written < 0) {
                    MainActivity.log("❌ AudioTrack.write error code: $written")
                    isPlaying = false
                }
            }.onFailure { error ->
                // 4. Last resort — log and survive
                MainActivity.log("❌ playAudioChunk unhandled: ${error.message}")
                isPlaying = false
            }
        }
    }

    fun stopAudioPlayback() {
        try {
            isPlaying = false
            val track = audioTrack
            audioTrack = null // 1. Null first — prevents write() after release() race
            track?.stop()
            track?.release()
            MainActivity.log("🔊 Audio playback stopped")
        } catch (error: Exception) {
            MainActivity.log("ERROR stopping playback: ${error.message}")
        }
    }

    fun cleanup() {
        stopAudioCapture()
        stopAudioPlayback()
        scope.cancel()
        Log.d(TAG, "AudioStreamHandler cleaned up")
    }
}
