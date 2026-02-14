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

class AudioStreamHandler(
    private val context: Context,
    private val pusherClient: PusherClient
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
        private val CAPTURE_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
        )
    }

    fun startAudioCapture() {
        if (isRecording) return

        try {
            MainActivity.log("🎤 Starting audio capture...")

            // 1. IMPORTANT: VOICE_DOWNLINK only captures audio during ACTIVE call (OFFHOOK state)
            // Dialing tones (beeps) during RINGING state are NOT captured - this is an Android limitation
            // Audio capture will work once call connects (when far end picks up)

            // 2. keep call routing managed by system (BT/wired/earpiece), do not force loudspeaker
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                }?.let { audioManager.setCommunicationDevice(it) }
            }

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            if (bufferSize <= 0) {
                MainActivity.log("ERROR: Invalid buffer size")
                return
            }

            audioRecord = CAPTURE_SOURCES.firstNotNullOfOrNull { source ->
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    MainActivity.log("✅ Audio capture source selected: $source")
                    record
                } else {
                    record.release()
                    null
                }
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                MainActivity.log("ERROR: AudioRecord not initialized - no supported capture source")
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            scope.launch {
                captureAndStreamAudio(bufferSize)
            }

            MainActivity.log("✅ Audio capture started (active when call connects)")
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
                    // Convert to bytes
                    val byteBuffer = ByteArray(read * 2)
                    ByteBuffer.wrap(byteBuffer).asShortBuffer().put(buffer, 0, read)

                    // Encode to base64
                    val base64Audio = Base64.encodeToString(byteBuffer, Base64.NO_WRAP)

                    // Send to backend
                    pusherClient.sendEvent("AUDIO_CHUNK", mapOf("audio" to base64Audio))

                    chunkCount++
                    if (chunkCount % 50 == 0) {
                        Log.d(TAG, "Sent $chunkCount audio chunks")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop", e)
                break
            }
        }
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

            // Keep route on BT/headset/earpiece instead of forcing loudspeaker.
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = false

            // Set max volume for voice call stream
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
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
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                MainActivity.log("ERROR: AudioTrack not initialized")
                return
            }

            isPlaying = true
            audioTrack?.play()

            MainActivity.log("✅ Audio playback started")
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
                // Decode base64
                val audioBytes = Base64.decode(base64Audio, Base64.NO_WRAP)

                // Convert to shorts
                val shortBuffer = ShortArray(audioBytes.size / 2)
                ByteBuffer.wrap(audioBytes).asShortBuffer().get(shortBuffer)

                // Play audio
                audioTrack?.write(shortBuffer, 0, shortBuffer.size)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio chunk", e)
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
