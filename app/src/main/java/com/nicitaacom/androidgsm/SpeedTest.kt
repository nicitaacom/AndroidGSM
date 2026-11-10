package com.nicitaacom.androidgsm

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The phone's own download + upload in Mbit/s, measured when the music stops for slow internet (outreach-tool
 * plan-03 task 12, his words: "it should show internet on a laptop and on a phone" + "I measured and internet speed
 * on speedtest.net was ok"). Download: the song link itself - the same road that stalled, a slow song server shows
 * here and nowhere on speedtest.net. Upload: zeros to the VPS's /api/speed-test - the road her voice takes to the
 * website. Each half stops at MAX_BYTES or MAX_MS, whichever comes first; null = that half failed.
 */
object SpeedTest {
    private val executor = Executors.newSingleThreadExecutor()

    fun measure(songUrl: String?, backendUrl: String, bearer: String, log: GsmLogger, onDone: (Double?, Double?) -> Unit) {
        executor.execute {
            val downloadMbps = songUrl?.let { measureDownload(it, log) }
            val uploadMbps = measureUpload("${backendUrl.trimEnd('/')}/api/speed-test", bearer, log)
            log("📶 SpeedTest: ⬇️ ${downloadMbps ?: "-"} ⬆️ ${uploadMbps ?: "-"} Mbit/s")
            onDone(downloadMbps, uploadMbps)
        }
    }

    // counts from the 1st byte, so the server's own wait before it answers does not pull the number down
    private fun measureDownload(url: String, log: GsmLogger): Double? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = MAX_MS.toInt()
                setRequestProperty("Range", "bytes=0-${MAX_BYTES - 1}")
            }
            connection.inputStream.use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                var firstByteAt = 0L
                var bytes = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    val now = SystemClock.elapsedRealtime()
                    if (firstByteAt == 0L) firstByteAt = now else bytes += read
                    if (bytes >= MAX_BYTES || now - firstByteAt >= MAX_MS) break
                }
                val seconds = (SystemClock.elapsedRealtime() - firstByteAt) / 1000.0
                if (bytes == 0L || seconds <= 0) null else toMbps(bytes, seconds)
            }
        } catch (error: Exception) {
            log("📶 SpeedTest: download failed - ${error.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    // chunked, so the phone stops writing at MAX_MS and the request still ends cleanly
    private fun measureUpload(url: String, bearer: String, log: GsmLogger): Double? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Authorization", "Bearer $bearer")
                setChunkedStreamingMode(CHUNK_BYTES)
            }
            val chunk = ByteArray(CHUNK_BYTES)
            val startedAt = SystemClock.elapsedRealtime()
            var bytes = 0L
            connection.outputStream.use { output ->
                while (bytes < MAX_BYTES && SystemClock.elapsedRealtime() - startedAt < MAX_MS) {
                    output.write(chunk)
                    bytes += chunk.size
                }
            }
            val code = connection.responseCode
            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
            if (code !in 200..299) {
                log("📶 SpeedTest: upload answered HTTP $code")
                null
            } else toMbps(bytes, seconds)
        } catch (error: Exception) {
            log("📶 SpeedTest: upload failed - ${error.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    // 1 decimal - "0.4", "12.7"
    private fun toMbps(bytes: Long, seconds: Double) = Math.round(bytes * 8 / seconds / 1_000_000 * 10) / 10.0

    private const val MAX_BYTES = 2_000_000L
    private const val MAX_MS = 4_000L
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val CHUNK_BYTES = 16 * 1024
}
