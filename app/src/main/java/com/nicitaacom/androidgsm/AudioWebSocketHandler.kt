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
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioWebSocketHandler(
    private val context: Context,
    private val config: Config,
    private val onAudioReceived: (ShortArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private var wsConnection: WebSocketAudioClient? = null
    private var seqTx = 0L
    private var seqRx = -1L

    companion object {
        private const val TAG = "AudioWebSocket"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4
        private val CAPTURE_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_DOWNLINK,
        )
    }

    fun connect(wsUrl: String, bearerToken: String, deviceToken: String) {
        MainActivity.log("🔌 WebSocket Audio: Connecting to $wsUrl")
        seqRx = -1L

        wsConnection = WebSocketAudioClient(wsUrl, bearerToken, deviceToken) { packet ->
            handleAudioPacket(packet)
        }
    }

    fun disconnect() {
        wsConnection?.close()
        wsConnection = null
        stopAudioCapture()
        stopAudioPlayback()
    }

    private fun handleAudioPacket(packet: JSONObject) {
        try {
            val dir = packet.optString("dir", "")
            val role = packet.optString("role", "")
            val audio = packet.optString("audio", "")
            val seq = packet.optLong("seq", -1)
            
            if (dir != "toAndroid" || audio.isEmpty()) {
                Log.d(TAG, "Skipping packet: dir=$dir, role=$role, hasAudio=${audio.isNotEmpty()}")
                return
            }

            // Validate sequence
            if (seq != -1L) {
                if (seq < seqRx) {
                    Log.w(TAG, "⚠️ Out of order: got seq=$seq, expected > $seqRx")
                    return
                }
                seqRx = seq
            }

            // Decode and play audio
            playAudioChunk(audio)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error handling audio packet: ${e.message}")
        }
    }

    fun startAudioCapture() {
        if (isRecording) return

        try {
            MainActivity.log("🎤 WebSocket: Starting capture...")

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_IN,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            if (bufferSize <= 0) {
                MainActivity.log("ERROR: Invalid buffer size")
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
                        MainActivity.log("✅ Capture source: $source")
                        selectedRecord = record
                        break
                    }
                    record.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed source $source: ${e.message}")
                }
            }

            audioRecord = selectedRecord
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                MainActivity.log("ERROR: AudioRecord not initialized")
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            scope.launch {
                captureAndStreamAudio(bufferSize)
            }

            MainActivity.log("✅ Capture started (WS)")
            
        } catch (e: Exception) {
            MainActivity.log("ERROR starting capture: ${e.message}")
            Log.e(TAG, "Error", e)
        }
    }

    private suspend fun captureAndStreamAudio(bufferSize: Int) = withContext(Dispatchers.IO) {
        val buffer = ShortArray(bufferSize / 2)
        var chunkCount = 0

        while (isRecording) {
            try {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (read > 0) {
                    // Convert to bytes with LITTLE_ENDIAN
                    val byteBuffer = ByteArray(read * 2)
                    ByteBuffer.wrap(byteBuffer)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .put(buffer, 0, read)

                    // Encode to base64
                    val base64Audio = Base64.encodeToString(byteBuffer, Base64.NO_WRAP)

                    // Send via WebSocket
                    wsConnection?.sendAudioChunk(
                        audio = base64Audio,
                        seq = seqTx++,
                        sampleRate = SAMPLE_RATE,
                        codec = "pcm16"
                    )

                    chunkCount++
                    if (chunkCount % 50 == 0) {
                        Log.d(TAG, "✅ Sent $chunkCount chunks")
                    }
                } else if (read < 0) {
                    Log.e(TAG, "❌ Read error: $read")
                    break
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in capture loop: ${e.message}")
                break
            }
        }
        Log.d(TAG, "Capture ended: $chunkCount chunks")
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
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture", e)
        }
    }

    fun startAudioPlayback() {
        if (isPlaying) return

        try {
            MainActivity.log("🔊 WebSocket: Starting playback...")

            // Use default media route/output device for reliable speaker playback outside telephony call context.
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

            MainActivity.log("✅ Playback started (WS, default media output)")
            
        } catch (e: Exception) {
            MainActivity.log("ERROR starting playback: ${e.message}")
            Log.e(TAG, "Error", e)
        }
    }

    private fun playAudioChunk(base64Audio: String) {
        if (!isPlaying) {
            startAudioPlayback()
        }

        scope.launch {
            try {
                // ✅ DEBUG: Log packet details
                Log.d(TAG, "Received audio chunk: size=${base64Audio.length}")

                // Validate
                if (base64Audio.isBlank()) {
                    Log.w(TAG, "⚠️ Empty audio chunk")
                    return@launch
                }

                // Decode base64
                val audioBytes = try {
                    Base64.decode(base64Audio, Base64.NO_WRAP)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "❌ Base64 decode failed: ${e.message}")
                    MainActivity.log("ERROR: Invalid base64 audio")
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Unexpected decode error: ${e.message}")
                    return@launch
                }

                Log.d(TAG, "Decoded: ${audioBytes.size} bytes")

                // Validate size
                if (audioBytes.isEmpty() || audioBytes.size % 2 != 0) {
                    Log.e(TAG, "❌ Invalid audio size: ${audioBytes.size}")
                    return@launch
                }

                // ✅ CRITICAL: Use explicit byte order
                val shortBuffer = ShortArray(audioBytes.size / 2)
                try {
                    ByteBuffer.wrap(audioBytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asShortBuffer()
                        .get(shortBuffer)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Buffer conversion error: ${e.message}")
                    return@launch
                }

                // Verify AudioTrack exists and is initialized
                val track = audioTrack
                if (track == null) {
                    Log.w(TAG, "⚠️ AudioTrack is null, restarting")
                    startAudioPlayback()
                    return@launch
                }

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    Log.w(TAG, "⚠️ AudioTrack not initialized (state=${track.state}), restarting")
                    startAudioPlayback()
                    return@launch
                }

                // Write audio with bounds checking
                try {
                    val written = track.write(shortBuffer, 0, shortBuffer.size)

                    when {
                        written == AudioTrack.ERROR_INVALID_OPERATION -> {
                            Log.e(TAG, "❌ AudioTrack ERROR_INVALID_OPERATION")
                            isPlaying = false
                        }
                        written == AudioTrack.ERROR_BAD_VALUE -> {
                            Log.e(TAG, "❌ AudioTrack ERROR_BAD_VALUE")
                            isPlaying = false
                        }
                        written < 0 -> {
                            Log.e(TAG, "❌ AudioTrack write error: $written")
                        }
                        written != shortBuffer.size -> {
                            Log.w(TAG, "⚠️ Partial write: $written/${shortBuffer.size}")
                        }
                        else -> {
                            Log.d(TAG, "✅ Wrote ${shortBuffer.size} samples")
                        }
                    }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "❌ AudioTrack IllegalStateException (released?): ${e.message}")
                    isPlaying = false
                } catch (e: Exception) {
                    Log.e(TAG, "❌ AudioTrack write exception: ${e.message}")
                    e.printStackTrace()
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error playing chunk: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun stopAudioPlayback() {
        try {
            isPlaying = false
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            MainActivity.log("🔊 Playback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping playback", e)
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
