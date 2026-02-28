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
    private val pusherClient: PusherClient,
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
                    val record = AudioRecord(
                        source,
                        SAMPLE_RATE,
                        CHANNEL_IN,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        MainActivity.log("✅ Audio capture source selected: $source (bufferSize: $bufferSize)")
                        selectedRecord = record
                        break
                    }
                    record.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize AudioRecord with source $source: ${e.message}")
                }
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
                        pusherClient.sendEvent("AUDIO_CHUNK", mapOf("audio" to base64Audio))
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
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Reset audio manager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL

            MainActivity.log("🎤 Audio capture stopped")
            Log.d(TAG, "Audio capture stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio capture", e)
        }
    }

    fun startAudioPlayback() {
        if (isPlaying) return

        try {
            MainActivity.log("🔊 Starting audio playback...")

            // Route playback to default media output device (speaker/headphones).
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = true

            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0
            )

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
        if (!isPlaying) {
            startAudioPlayback()
        }

        scope.launch {
            try {
                // 1. Validate base64 string
                if (base64Audio.isBlank()) {
                    Log.w(TAG, "⚠️ DEBUG: Received empty base64 audio chunk")
                    MainActivity.log("⚠️ Empty audio chunk (Pusher fallback)")
                    return@launch
                }

                // 2. Decode base64 with error handling
                val audioBytes = try {
                    Base64.decode(base64Audio, Base64.NO_WRAP)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "❌ DEBUG: Failed to decode base64: ${e.message}")
                    Log.e(TAG, "  Base64 length: ${base64Audio.length}")
                    Log.e(TAG, "  First 50 chars: ${base64Audio.take(50)}")
                    MainActivity.log("ERROR: Invalid base64 - check logs")
                    return@launch
                }

                Log.d(TAG, "✅ DEBUG: Decoded base64 → ${audioBytes.size} bytes")

                // 3. Validate decoded bytes
                if (audioBytes.isEmpty()) {
                    Log.w(TAG, "⚠️ DEBUG: Decoded audio bytes are empty")
                    return@launch
                }

                // 4. Check if size is valid (must be even number of bytes for 16-bit samples)
                if (audioBytes.size % 2 != 0) {
                    Log.e(TAG, "❌ DEBUG: Odd bytes (${audioBytes.size}) - expected multiple of 2")
                    Log.e(TAG, "  This means corrupted PCM data")
                    MainActivity.log("ERROR: Audio chunk odd size - corrupted")
                    return@launch
                }

                // 5. Convert bytes to shorts (16-bit PCM samples)
                // Use proper byte order handling
                val shortBuffer = ShortArray(audioBytes.size / 2)
                val byteBuffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN)
                byteBuffer.asShortBuffer().get(shortBuffer)

                Log.d(TAG, "✅ DEBUG: Converted to ${shortBuffer.size} samples")
                Log.d(TAG, "  Sample values (first 10): ${shortBuffer.take(10).joinToString(",")}")

                // 6. Verify AudioTrack is initialized before writing
                if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    Log.w(TAG, "⚠️ DEBUG: AudioTrack state=${audioTrack?.state}, restarting")
                    startAudioPlayback()
                }

                // 7. Write audio with error handling
                val written = audioTrack?.write(shortBuffer, 0, shortBuffer.size) ?: -1
                
                Log.d(TAG, "DEBUG: AudioTrack.write() returned: $written")
                
                if (written == AudioTrack.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "❌ DEBUG: AudioTrack.ERROR_INVALID_OPERATION")
                    Log.e(TAG, "  AudioTrack state: ${audioTrack?.state}")
                    Log.e(TAG, "  isPlaying: $isPlaying")
                    isPlaying = false
                    MainActivity.log("ERROR: AudioTrack invalid operation")
                } else if (written == AudioTrack.ERROR_BAD_VALUE) {
                    Log.e(TAG, "❌ DEBUG: AudioTrack.ERROR_BAD_VALUE")
                    Log.e(TAG, "  Tried to write ${shortBuffer.size} samples")
                    isPlaying = false
                    MainActivity.log("ERROR: AudioTrack bad value")
                } else if (written < 0) {
                    Log.e(TAG, "❌ DEBUG: AudioTrack unknown error: $written")
                    isPlaying = false
                } else if (written != shortBuffer.size) {
                    Log.w(TAG, "⚠️ DEBUG: Partial write: $written / ${shortBuffer.size} samples")
                } else {
                    Log.d(TAG, "✅ DEBUG: Successfully wrote ${shortBuffer.size} samples")
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ DEBUG: Exception in playAudioChunk")
                Log.e(TAG, "  Message: ${e.message}")
                Log.e(TAG, "  Class: ${e::class.simpleName}")
                e.printStackTrace()
                MainActivity.log("ERROR: Play failed - check logcat")
            }
        }
    }

    fun stopAudioPlayback() {
        try {
            isPlaying = false
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            MainActivity.log("🔊 Audio playback stopped")
            Log.d(TAG, "Audio playback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio playback", e)
        }
    }

    fun cleanup() {
        stopAudioCapture()
        stopAudioPlayback()
        scope.cancel()
        Log.d(TAG, "AudioStreamHandler cleaned up")
    }
}
