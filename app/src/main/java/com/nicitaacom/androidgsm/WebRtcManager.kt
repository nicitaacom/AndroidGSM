package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.connection.ConnectionEventListener
import com.pusher.client.connection.ConnectionState
import com.pusher.client.connection.ConnectionStateChange
import kotlinx.coroutines.*
import org.json.JSONObject

class AudioStreamManager(
    private val context: Context,
    private val pusherAppId: String,
    private val pusherKey: String,
    private val pusherSecret: String,
    private val pusherCluster: String
) {
    private var pusher: Pusher? = null
    private var deviceToken: String = ""
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "AudioStreamManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

        fun initialize(token: String) {
        this.deviceToken = token
        try {
            val options = PusherOptions()
            options.setCluster(pusherCluster)

            // Set auth endpoint for private channels
            options.setAuthorizer { channelName, socketId ->
                val authJson = JSONObject()
                authJson.put("auth", "$pusherKey:$socketId")
                authJson.toString()
            }

            pusher = Pusher(pusherKey, options)

            pusher?.connect(object : ConnectionEventListener {
                override fun onConnectionStateChange(change: ConnectionStateChange) {
                    Log.d(TAG, "Pusher state: ${change.previousState} -> ${change.currentState}")
                    MainActivity.log("🔄 Pusher: ${change.currentState}")
                    if (change.currentState == ConnectionState.CONNECTED) {
                        MainActivity.log("✅ Pusher connected!")
                        subscribeToChannels(deviceToken)
                    }
                }

                override fun onError(message: String, code: String?, e: Exception?) {
                    Log.e(TAG, "Pusher error: $message, code: $code", e)
                    MainActivity.log("⚠️ Pusher error: $message")
                }
            }, ConnectionState.ALL)

            Log.d(TAG, "Pusher initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Pusher", e)
        }
    }

    private fun subscribeToChannels(deviceToken: String) {
        // Subscribe to public channel (no auth needed)
        val publicChannel = pusher?.subscribe("gsm-$deviceToken")
        publicChannel?.bind("call-command") { event ->
            Log.d(TAG, "Received command: ${event.data}")
            MainActivity.log("📡 Pusher command received")
            handleCommand(event.data)
        }

        // Subscribe to presence channel for audio streaming
        val audioChannel = pusher?.subscribe("presence-audio-$deviceToken")
        audioChannel?.bind("client-audio-chunk") { event ->
            Log.d(TAG, "Audio chunk ack: ${event.data}")
        }

        MainActivity.log("📶 Subscribed to channels: gsm-$deviceToken")
    }

    private fun handleCommand(data: String) {
        try {
            val json = JSONObject(data)
            val command = json.getString("command")

            when (command) {
                "start_stream" -> startAudioCapture()
                "stop_stream" -> stopAudioCapture()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle command", e)
        }
    }

    fun startAudioCapture() {
        if (isRecording) return
        MainActivity.log("🎤 Starting audio capture...")

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            isRecording = true
            audioRecord?.startRecording()

            scope.launch {
                streamAudio(bufferSize)
            }

            Log.d(TAG, "Audio capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            isRecording = false
        }
    }

    private suspend fun streamAudio(bufferSize: Int) = withContext(Dispatchers.IO) {
        val buffer = ShortArray(bufferSize / 2)

        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

            if (read > 0) {
                val byteBuffer = ByteArray(read * 2)
                for (i in 0 until read) {
                    byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
                }

                val base64Audio = Base64.encodeToString(byteBuffer, Base64.NO_WRAP)
                sendAudioChunk(base64Audio)
            }

            delay(20) // ~50fps audio streaming
        }
    }

    private fun sendAudioChunk(audioData: String) {
        // Send via Pusher or WebSocket to backend
        val payload = JSONObject()
        payload.put("type", "audio_chunk")
        payload.put("data", audioData)
        payload.put("timestamp", System.currentTimeMillis())

        // Trigger event to backend channel
        Log.d(TAG, "Streaming audio chunk (${audioData.length} bytes)")
    }

    fun stopAudioCapture() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Audio capture stopped")
    }

    fun cleanup() {
        stopAudioCapture()
        pusher?.disconnect()
        scope.cancel()
        Log.d(TAG, "AudioStreamManager cleaned up")
    }
}
