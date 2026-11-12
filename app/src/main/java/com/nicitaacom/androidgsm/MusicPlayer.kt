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
class MusicPlayer(
    private val log: GsmLogger,
    // songUrl: the link that was playing - slow_internet measures the phone's download on it (SpeedTest).
    // why: slow_internet's own line for the toast - the waits and the phone's radios at the last one, null otherwise
    private val onStopped: (reason: String, songUrl: String?, why: String?) -> Unit,
    onDrops: () -> Unit,
    // PhoneRadioState.describe - Wi-Fi band / dBm / link, Bluetooth, a call, the screen
    private val radioState: () -> String,
) {
    private var player: MediaPlayer? = null
    private var songUrl: String? = null
    private val dropWatcher = BluetoothDropWatcher(log, onDrops)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stallTimesMs = ArrayDeque<Long>()
    private var startTimeout: Runnable? = null
    private var volume = 1f
    // ms of the last seek - the buffering right after it is the seek itself, not slow internet
    private var seekAtMs = 0L

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
        this.songUrl = songUrl
        volume = volumePercent.coerceIn(0, 100) / 100f
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attributes)
            mp.setDataSource(songUrl)
            mp.setOnPreparedListener { prepared ->
                cancelStartTimeout()
                val startMs = (positionSec * 1000).toInt()
                if (startMs > 0) {
                    seekAtMs = System.currentTimeMillis()
                    prepared.seekTo(startMs)
                }
                prepared.setVolume(volume, volume)
                prepared.start()
                dropWatcher.start()
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
            dropWatcher.stop()
            log("🎵 MusicPlayer: paused")
        } catch (e: IllegalStateException) {
            log("🎵 MusicPlayer: pause ignored: ${e.message}")
        }
    }

    @Synchronized
    fun seek(positionSec: Double) {
        try {
            seekAtMs = System.currentTimeMillis()
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
    fun stop(reason: String?, why: String? = null) {
        releasePlayer()
        if (reason == null) {
            log("🎵 MusicPlayer: stopped by the browser")
            return
        }
        log("🎵 MusicPlayer: stopped - $reason${why?.let { " · $it" } ?: ""}")
        onStopped(reason, songUrl, why)
    }

    private fun onStall() {
        val now = System.currentTimeMillis()
        // A seek empties the player's buffer, so it buffers once right after - counted as a stall, 3 clicks on a
        // recording's waveform within a minute stop it as "slow internet" on a fast line
        if (now - seekAtMs < SEEK_BUFFERING_MS) {
            log("🎵 MusicPlayer: buffering after a seek - no stall")
            return
        }
        stallTimesMs.addLast(now)
        while (stallTimesMs.isNotEmpty() && now - stallTimesMs.first() > STALL_WINDOW_MS) stallTimesMs.removeFirst()
        // every wait with the radios at that moment - 3 waits with the same "Wi-Fi 2.4 GHz · Bluetooth music on"
        // point at the shared radio, 3 different ones at the line
        val radio = radioState()
        log("🎵 MusicPlayer: buffering stall ${stallTimesMs.size}/$MAX_STALLS in the last ${STALL_WINDOW_MS / 1000} s · $radio")
        if (stallTimesMs.size < MAX_STALLS) return
        val waitsSec = (now - stallTimesMs.first()) / 1000
        stop("slow_internet", "$MAX_STALLS waits for the song in $waitsSec s · at the last one: $radio")
    }

    private fun scheduleStartTimeout() {
        cancelStartTimeout()
        val timeout = Runnable {
            log("🎵 MusicPlayer: no start within ${START_TIMEOUT_MS / 1000} s")
            stop("slow_internet", "the song did not start within ${START_TIMEOUT_MS / 1000} s · ${radioState()}")
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
        dropWatcher.stop()
        stallTimesMs.clear()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private companion object {
        const val START_TIMEOUT_MS = 5_000L
        const val STALL_WINDOW_MS = 60_000L
        const val MAX_STALLS = 3
        const val SEEK_BUFFERING_MS = 3_000L
    }
}
