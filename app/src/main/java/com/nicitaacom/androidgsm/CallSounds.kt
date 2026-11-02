package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.annotation.RawRes

/**
 * Plays the short start/end call clips (res/raw/start_call.mp3, end_call.mp3) on the phone.
 * USAGE_VOICE_COMMUNICATION_SIGNALLING is what in-call DTMF tones use, so the clip follows the
 * live call route (earpiece / loudspeaker / headset) instead of the media speaker.
 * One clip at a time — a new play() cuts the previous one.
 *
 * The clips are byte-identical copies of outreach-tool/public/start-call.mp3 and end-call.mp3.
 * Never re-encode or "normalize" them: the first copies were pushed ~3 dB hotter and decoded
 * above full scale, which the 16-bit playback path clipped into a metallic click.
 *
 * Callers that flip the EAR_S/SPK mixer around a clip must wait for awaitFinished() first.
 * A fixed sleep is not enough: in-call playback starts a few hundred ms late on this HAL, and
 * muting the mixer while the clip is still sounding is what turned it into a short "tick".
 */
class CallSounds(private val context: Context, private val log: GsmLogger) {
    private var player: MediaPlayer? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Starts the clip and returns at once. */
    @Synchronized
    fun play(@RawRes resId: Int) {
        release()
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mp = MediaPlayer.create(context, resId, attributes, am.generateAudioSessionId()) ?: run {
                log("🔈 CallSounds: could not create a player for res $resId")
                return
            }
            // Listeners and awaiters release only THEIR player: a late completion message for the
            // start cue, or an awaiter woken by a cut, must not tear down an end cue started since.
            mp.setOnCompletionListener { releaseIfCurrent(mp) }
            mp.setOnErrorListener { _, what, extra ->
                log("🔈 CallSounds: playback error what=$what extra=$extra")
                releaseIfCurrent(mp)
                true
            }
            player = mp
            mp.start()
            log("🔈 CallSounds: playing ${context.resources.getResourceEntryName(resId)}")
        } catch (e: Exception) {
            log("🔈 CallSounds: play failed: ${e.message}")
            release()
        }
    }

    /**
     * Blocks until the clip from the last play() has ended, then a short drain so the HAL output
     * buffer is empty before the caller flips a mixer switch. Polls the player instead of waiting
     * on its completion callback: that callback is posted to the main looper, and teardownCall()
     * runs ON the main thread (Telecom's onCallRemoved), so waiting for it there would deadlock.
     * Returns false (and cuts the clip) on timeout. Returns at once when nothing is playing.
     */
    fun awaitFinished(timeoutMs: Long = CLIP_TIMEOUT_MS): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val mp = synchronized(this) { player } ?: return true
            val playing = try { mp.isPlaying } catch (_: IllegalStateException) { false }
            if (!playing) {
                releaseIfCurrent(mp)
                Thread.sleep(DRAIN_MS)
                return true
            }
            Thread.sleep(POLL_MS)
        }
        log("🔈 CallSounds: clip did not finish within $timeoutMs ms — cutting it")
        release()
        return false
    }

    @Synchronized
    private fun releaseIfCurrent(mp: MediaPlayer) {
        if (player === mp) release()
    }

    /** play() + awaitFinished(): returns once the whole clip has been heard (or the timeout hit). */
    fun playBlocking(@RawRes resId: Int, timeoutMs: Long = CLIP_TIMEOUT_MS): Boolean {
        play(resId)
        return awaitFinished(timeoutMs)
    }

    @Synchronized
    fun release() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private companion object {
        // Both clips are under 0.6 s; in-call start latency adds a few hundred ms on top.
        const val CLIP_TIMEOUT_MS = 1500L
        const val POLL_MS = 20L
        const val DRAIN_MS = 120L
    }
}
