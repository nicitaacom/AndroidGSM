package com.nicitaacom.androidgsm

/**
 * Watches the Bluetooth chip's link reports (`BTA_DM_IOT_INFO_EVT` in the system log, root logcat) while
 * MusicPlayer plays. Measured 04.11.2026 on the headset paired to this phone: a song with drops of
 * 50-100 ms on the headphones = 244 reports in 1 minute, spread over the whole song; the same song
 * after a phone reboot = 25 reports in its first 3 s, then none. Reports that keep coming for 20+ s =
 * drops -> onDrops, once per play, so the browser can offer "Experiencing interruptions? [reboot phone]".
 */
class BluetoothDropWatcher(private val log: GsmLogger, private val onDrops: () -> Unit) {
    private var process: Process? = null
    private var reader: Thread? = null

    @Synchronized
    fun start() {
        stop()
        try {
            // -T 1: from the newest line on, not the whole buffer; only the Bluetooth stack's warnings
            val logcat = Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -T 1 bt_btif:W *:S"))
            process = logcat
            reader = Thread {
                val reportTimesMs = ArrayDeque<Long>()
                try {
                    logcat.inputStream.bufferedReader().forEachLine { line ->
                        if (!line.contains(LINK_REPORT)) return@forEachLine
                        val now = System.currentTimeMillis()
                        reportTimesMs.addLast(now)
                        while (now - reportTimesMs.first() > DROP_WINDOW_MS) reportTimesMs.removeFirst()
                        // a burst while the link starts (the song's first seconds, the end of a call) is short;
                        // drops keep the reports coming across the window
                        val isSpread = now - reportTimesMs.first() >= MIN_SPREAD_MS
                        if (reportTimesMs.size >= MIN_REPORTS && isSpread) {
                            log("🎧 BluetoothDropWatcher: ${reportTimesMs.size} link reports in ${DROP_WINDOW_MS / 1000} s - drops")
                            onDrops()
                            logcat.destroy()
                        }
                    }
                } catch (_: Exception) {
                    // destroy() closes the stream - the normal way this thread ends
                }
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            log("🎧 BluetoothDropWatcher: start failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        reader = null
    }

    private companion object {
        const val LINK_REPORT = "BTA_DM_IOT_INFO_EVT"
        const val DROP_WINDOW_MS = 30_000L
        const val MIN_SPREAD_MS = 20_000L
        const val MIN_REPORTS = 30
    }
}
