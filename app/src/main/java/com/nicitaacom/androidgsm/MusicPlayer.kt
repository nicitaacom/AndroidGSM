package com.nicitaacom.androidgsm

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

/**
 * Plays the song link the browser's music panel sends (outreach-tool plan-03): the phone streams the
 * file itself and plays it on the headphones paired to it. During a live call it is mixed into the
 * call's own route at whatever volume the browser sets, and the call-audio rules in CallController stay
 * untouched. The browser is the remote: MUSIC_PLAY / MUSIC_PAUSE / MUSIC_SEEK / MUSIC_VOLUME /
 * MUSIC_STOP. The player never resumes on its own after a stop or a service restart.
 *
 * The slow-internet rule (his words: "if internet speed is low not allow to use this feature and use
 * internet for calls instead of music and calls"): no start within START_TIMEOUT_MS, or MAX_STALLS
 * buffering stalls within STALL_WINDOW_MS -> stop and report MUSIC_STOPPED { reason } to the browser,
 * which pauses the music site and shows the toast. He presses play again when he wants.
 */
class MusicPlayer(private val log: GsmLogger, private val onStopped: (reason: String) -> Unit) {
    private var player: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stallTimesMs = ArrayDeque<Long>()
    private var startTimeout: Runnable? = null
    private var volume = 1f

    // Not USAGE_MEDIA: AudioService mutes every MEDIA / GAME player for the whole call, from Telecom's
    // MODE_IN_CALL at the dial until MODE_NORMAL ("PlaybackActivityMonitor: call: muting piid" in the
    // system log, 2026-11-02 - the song went silent the moment a call was placed, and MUSIC_VOLUME on
    // the answer did not bring it back). Navigation guidance is routed like media (A2DP between calls,
    // the call's own route during one) and is not on that mute list.
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** A new song or a refreshed link: the old player is dropped, the new one prepares and starts at positionSec. */
    @Synchronized
    fun play(songUrl: String, positionSec: Double, volumePercent: Int, title: String) {
        releasePlayer()
        volume = volumePercent.coerceIn(0, 100) / 100f
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attributes)
            mp.setDataSource(songUrl)
            mp.setOnPreparedListener { prepared ->
                cancelStartTimeout()
                val startMs = (positionSec * 1000).toInt()
                if (startMs > 0) prepared.seekTo(startMs)
                prepared.setVolume(volume, volume)
                prepared.start()
                log("🎵 MusicPlayer: playing \"$title\" from ${positionSec.toInt()} s at $volumePercent%")
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) onStall()
                false
            }
            mp.setOnErrorListener { _, what, extra ->
                log("🎵 MusicPlayer: error what=$what extra=$extra")
                stop("error")
                true
            }
            // the song ended - the browser's music site picks the next one and sends a new MUSIC_PLAY
            mp.setOnCompletionListener { log("🎵 MusicPlayer: song ended, waiting for the browser's next song") }
            player = mp
            scheduleStartTimeout()
            mp.prepareAsync()
        } catch (e: Exception) {
            log("🎵 MusicPlayer: play failed: ${e.message}")
            stop("error")
        }
    }

    @Synchronized
    fun pause() {
        try {
            val mp = player ?: return
            if (mp.isPlaying) mp.pause()
            log("🎵 MusicPlayer: paused")
        } catch (e: IllegalStateException) {
            log("🎵 MusicPlayer: pause ignored: ${e.message}")
        }
    }

    @Synchronized
    fun seek(positionSec: Double) {
        try {
            player?.seekTo((positionSec * 1000).toInt())
        } catch (e: IllegalStateException) {
            log("🎵 MusicPlayer: seek ignored: ${e.message}")
        }
    }

    @Synchronized
    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f
        try {
            player?.setVolume(volume, volume)
            log("🎵 MusicPlayer: volume $percent%")
        } catch (e: IllegalStateException) {
            log("🎵 MusicPlayer: volume ignored: ${e.message}")
        }
    }

    /** Drops the player. A reason means the phone stopped on its own and the browser must hear about it. */
    @Synchronized
    fun stop(reason: String?) {
        releasePlayer()
        if (reason == null) {
            log("🎵 MusicPlayer: stopped by the browser")
            return
        }
        log("🎵 MusicPlayer: stopped - $reason")
        onStopped(reason)
    }

    private fun onStall() {
        val now = System.currentTimeMillis()
        stallTimesMs.addLast(now)
        while (stallTimesMs.isNotEmpty() && now - stallTimesMs.first() > STALL_WINDOW_MS) stallTimesMs.removeFirst()
        log("🎵 MusicPlayer: buffering stall ${stallTimesMs.size}/$MAX_STALLS in the last ${STALL_WINDOW_MS / 1000} s")
        if (stallTimesMs.size >= MAX_STALLS) stop("slow_internet")
    }

    private fun scheduleStartTimeout() {
        cancelStartTimeout()
        val timeout = Runnable {
            log("🎵 MusicPlayer: no start within ${START_TIMEOUT_MS / 1000} s")
            stop("slow_internet")
        }
        startTimeout = timeout
        mainHandler.postDelayed(timeout, START_TIMEOUT_MS)
    }

    private fun cancelStartTimeout() {
        startTimeout?.let { mainHandler.removeCallbacks(it) }
        startTimeout = null
    }

    private fun releasePlayer() {
        cancelStartTimeout()
        stallTimesMs.clear()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private companion object {
        const val START_TIMEOUT_MS = 5_000L
        const val STALL_WINDOW_MS = 60_000L
        const val MAX_STALLS = 3
    }
}
