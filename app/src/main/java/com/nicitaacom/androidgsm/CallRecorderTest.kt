package com.nicitaacom.androidgsm

import android.content.Context
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * plan-06 task 1 - the recording test: can the phone record BOTH sides of an answered call while the
 * headset is paired over Bluetooth? Runs on the answer, only while the marker file RUN_TEST sits in
 * <external files>/recordings/ (adb shell touch ...) - one shot, the marker is deleted at the start.
 * Two ways, 30 s each, one after the other, on their own thread:
 *   A. MediaRecorder with AudioSource.VOICE_CALL                      -> recordings/test-voice-call.m4a
 *   B. the running tinycap stream + VOC_REC_UL on top of VOC_REC_DL  -> recordings/test-tinycap.wav
 * The result is in logcat (GsmGateway, "🧪" lines) and in the 2 files; nothing else of the call changes.
 */
class CallRecorderTest(
    private val context: Context,
    private val audioWsHandlerRef: AudioWsHandlerRef,
    private val log: GsmLogger
) {
    private val dir: File
        get() = File(context.getExternalFilesDir(null), "recordings").also { it.mkdirs() }

    private val marker: File
        get() = File(dir, "RUN_TEST")

    // Called on STATE_ACTIVE (the answer). Blocks its own thread for ~20 s, never the caller's.
    fun runOnce() {
        if (!marker.exists()) return
        marker.delete()
        Thread {
            log("🧪 recording test: way A (MediaRecorder VOICE_CALL) $TEST_SECONDS s")
            recordVoiceCall(File(dir, "test-voice-call.m4a"))
            log("🧪 recording test: way B (tinycap + VOC_REC_UL) $TEST_SECONDS s")
            recordTinycapTee(File(dir, "test-tinycap.wav"))
            log("🧪 recording test done - adb pull ${dir.absolutePath}")
        }.start()
    }

    private fun recordVoiceCall(file: File) {
        val recorder = MediaRecorder()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(SAMPLE_RATE)
            recorder.setAudioEncodingBitRate(32_000)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            Thread.sleep(TEST_SECONDS * 1000L)
            recorder.stop()
            log("🧪 way A: ${file.name} ${file.length()} bytes")
        } catch (error: Exception) {
            log("🧪 way A failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { recorder.release() }
        }
    }

    // The browser's own downlink stream (tinycap on MultiMedia1) is teed into a file; VOC_REC_UL adds
    // the uplink (his voice) to that same stream for these 30 s, so the browser hears him too meanwhile.
    private fun recordTinycapTee(file: File) {
        val handler = audioWsHandlerRef.handler
        if (handler == null) {
            log("🧪 way B skipped: no audio handler, tinycap is not running")
            return
        }
        val isUplinkOn = RootUtils.enableIncallUplinkCapture()
        try {
            FileOutputStream(file).use { out ->
                out.write(ByteArray(WAV_HEADER_BYTES))
                handler.teeSink = out
                Thread.sleep(TEST_SECONDS * 1000L)
                handler.teeSink = null
                Thread.sleep(100)
            }
            writeWavHeader(file)
            log("🧪 way B: ${file.name} ${file.length()} bytes (VOC_REC_UL ${if (isUplinkOn) "on" else "FAILED"})")
        } catch (error: Exception) {
            handler.teeSink = null
            log("🧪 way B failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            RootUtils.disableIncallUplinkCapture()
        }
    }

    // 16 kHz mono PCM16 - the same stream the browser gets
    private fun writeWavHeader(file: File) {
        val dataBytes = (file.length() - WAV_HEADER_BYTES).toInt().coerceAtLeast(0)
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(dataBytes)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header.array())
        }
    }

    companion object {
        private const val TEST_SECONDS = 30
        private const val SAMPLE_RATE = 16000
        private const val WAV_HEADER_BYTES = 44
    }
}
