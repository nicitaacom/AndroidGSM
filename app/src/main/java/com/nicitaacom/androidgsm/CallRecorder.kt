package com.nicitaacom.androidgsm

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.edit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * plan-06 task 2 - every answered call is recorded on the phone while "record every call" is on (SET_RECORDING from
 * the website's gear menu). The way task 1 proved on hardware (07.11.2026, headset paired over Bluetooth): the tinycap
 * stream the browser gets (her voice) + VOC_REC_UL on top of it (his voice), teed into recordings/pending.pcm from the
 * answer to the hang-up, then encoded to AAC (16 kHz mono 32 kbps) as recordings/pending.m4a. reportCall renames it
 * <callLogId>.m4a once the phone's own call log row is known.
 */
class CallRecorder(
    private val context: Context,
    private val audioWsHandlerRef: AudioWsHandlerRef,
    private val log: GsmLogger
) {
    private val dir: File
        get() = File(context.filesDir, "recordings").also { it.mkdirs() }

    private val pendingPcm: File
        get() = File(dir, "pending.pcm")

    private val pendingM4a: File
        get() = File(dir, "pending.m4a")

    @Volatile private var pcmOut: FileOutputStream? = null
    @Volatile private var encodeThread: Thread? = null
    // true from the hang-up of a recorded call until reportCall picked its file up - a call that was not recorded
    // never takes the file of the one before it
    @Volatile private var isWaitingForCallLogId = false

    init {
        // the phone restarted mid-call: a pending file without an end helps nobody
        listOf(pendingPcm, pendingM4a)
            .filter { it.exists() && System.currentTimeMillis() - it.lastModified() > PENDING_MAX_AGE_MS }
            .forEach { it.delete() }
    }

    // the answer (STATE_ACTIVE) - the switch is read here only, turning it off mid-call keeps this call recording
    fun start() {
        if (!selectIsRecordEveryCall(context)) return
        if (pcmOut != null) return
        val handler = audioWsHandlerRef.handler
        if (handler == null) {
            log("🎙 recording skipped: tinycap is not running for this call")
            return
        }
        try {
            pendingPcm.delete()
            pendingM4a.delete()
            val isUplinkOn = RootUtils.enableIncallUplinkCapture()
            val out = FileOutputStream(pendingPcm)
            pcmOut = out
            handler.teeSink = out
            log("🎙 recording started (VOC_REC_UL ${if (isUplinkOn) "on" else "FAILED - her voice only"})")
        } catch (error: Exception) {
            pcmOut = null
            log("🎙 recording start failed: ${error.message}")
        }
    }

    // the hang-up: the tee comes off first, VOC_REC_UL closes, the AAC encode runs on its own thread
    fun stop() {
        val out = pcmOut ?: return
        pcmOut = null
        audioWsHandlerRef.handler?.teeSink = null
        RootUtils.disableIncallUplinkCapture()
        runCatching { out.close() }
        isWaitingForCallLogId = true
        encodeThread = Thread {
            try {
                encodeToM4a(pendingPcm, pendingM4a)
                log("🎙 recording encoded: ${pendingM4a.length() / 1024} KB")
            } catch (error: Exception) {
                pendingM4a.delete()
                log("🎙 recording encode failed: ${error.message}")
            } finally {
                pendingPcm.delete()
            }
        }.also { it.start() }
    }

    // reportCall, once the call log row is known: waits for the encode, renames pending.m4a to <callLogId>.m4a -
    // true when this call has a recording (CALL_REPORT's hasRecording)
    fun finish(callLogId: Long): Boolean {
        if (!isWaitingForCallLogId) return false
        isWaitingForCallLogId = false
        encodeThread?.join(ENCODE_WAIT_MS)
        encodeThread = null
        if (!pendingM4a.exists()) return false
        val file = File(dir, "$callLogId.m4a")
        val isRenamed = pendingM4a.renameTo(file)
        if (isRenamed) log("🎙 recording kept: ${file.name}")
        return isRenamed
    }

    private fun encodeToM4a(pcm: File, m4a: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, READ_BYTES)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(m4a.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        val bytes = ByteArray(READ_BYTES)
        var presentationUs = 0L
        var isInputDone = false
        var isOutputDone = false
        try {
            FileInputStream(pcm).use { input ->
                while (!isOutputDone) {
                    if (!isInputDone) {
                        val inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inBuffer = codec.getInputBuffer(inIndex) ?: error("no input buffer")
                            inBuffer.clear()
                            // whole 16-bit samples only - a half sample would shift every one after it
                            val n = input.read(bytes, 0, minOf(bytes.size, inBuffer.capacity()) and 1.inv())
                            if (n <= 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputDone = true
                            } else {
                                inBuffer.put(bytes, 0, n)
                                codec.queueInputBuffer(inIndex, 0, n, presentationUs, 0)
                                presentationUs += n / 2 * 1_000_000L / SAMPLE_RATE
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    } else if (outIndex >= 0) {
                        val outBuffer = codec.getOutputBuffer(outIndex) ?: error("no output buffer")
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) {
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, outBuffer, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isOutputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (track >= 0) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    companion object {
        private const val PREFS_NAME = "androidgsm_recording"
        private const val KEY_IS_RECORD_EVERY_CALL = "isRecordEveryCall"
        private const val SAMPLE_RATE = 16000
        private const val BIT_RATE = 32000
        private const val READ_BYTES = 8192
        private const val CODEC_TIMEOUT_US = 10_000L
        // a 30-minute call encodes in a few seconds on the Redmi Note 7 - reportCall waits this long at most
        private const val ENCODE_WAIT_MS = 30_000L
        private const val PENDING_MAX_AGE_MS = 60 * 60 * 1000L

        // "record every call" from the website's gear menu (dialerConfig.isRecordEveryCall), kept so a phone
        // restart does not turn it off
        fun selectIsRecordEveryCall(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_IS_RECORD_EVERY_CALL, false)

        fun insertIsRecordEveryCall(context: Context, isRecordEveryCall: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_IS_RECORD_EVERY_CALL, isRecordEveryCall)
            }
        }
    }
}
