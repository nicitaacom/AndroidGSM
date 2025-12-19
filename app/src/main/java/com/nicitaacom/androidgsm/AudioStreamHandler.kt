package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
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

    companion object {
        private const val TAG = "AudioStreamHandler"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
    }

    fun startAudioCapture() {
        if (isRecording) return

        try {
            MainActivity.log("🎤 Starting audio capture...")

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            if (bufferSize <= 0) {
                MainActivity.log("ERROR: Invalid buffer size")
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                MainActivity.log("ERROR: AudioRecord not initialized")
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            scope.launch {
                captureAndStreamAudio(bufferSize)
            }

            MainActivity.log("✅ Audio capture started")
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

                delay(20) // ~50 chunks per second
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

            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_OUT,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            if (bufferSize <= 0) {
                MainActivity.log("ERROR: Invalid playback buffer size")
                return
            }

            audioTrack = AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_OUT,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )

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
