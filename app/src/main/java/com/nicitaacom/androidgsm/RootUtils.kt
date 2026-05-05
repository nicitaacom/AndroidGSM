package com.nicitaacom.androidgsm

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object RootUtils {
    private const val TAG = "RootUtils"

    private val suPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/system/sd/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/apex/com.android.runtime/bin/su"
    )

    private fun runSuCommand(command: String): Pair<Int, String> {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        // Drain streams on separate threads to avoid blocking waitFor()
        var out = ""
        var err = ""
        val outThread = Thread { try { out = process.inputStream.bufferedReader().readText() } catch (_: Exception) {} }.also { it.start() }
        val errThread = Thread { try { err = process.errorStream.bufferedReader().readText() } catch (_: Exception) {} }.also { it.start() }
        val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            outThread.interrupt()
            errThread.interrupt()
            return -1 to "timeout"
        }
        outThread.join(500)
        errThread.join(500)
        return process.exitValue() to (out + "\n" + err).trim()
    }

    private fun logRootState(rooted: Boolean, reason: String): Boolean {
        Log.d(TAG, if (rooted) "✅ Root detected: $reason" else "❌ Root not detected: $reason")
        return rooted
    }

    fun isRooted(): Boolean {
        // 1) su execution test (most reliable for this app use-case)
        runCatching {
            val (exit, output) = runSuCommand("id")
            if (exit == 0 && output.contains("uid=0")) {
                return logRootState(true, "su -c id returned uid=0")
            }
        }

        // 2) Known su binary paths
        if (suPaths.any { File(it).exists() }) {
            return logRootState(true, "su binary exists in known path")
        }

        // 3) Build tags fallback (often test-keys on rooted/custom images)
        if (Build.TAGS?.contains("test-keys") == true) {
            return logRootState(true, "Build.TAGS contains test-keys")
        }

        return logRootState(false, "no su execution/path/build-tags evidence")
    }

    fun grantPermission(packageName: String, permission: String): Boolean = try {
        val (exitCode, output) = runSuCommand("pm grant $packageName $permission")
        (exitCode == 0).also { granted ->
            if (granted) Log.d(TAG, "✅ Granted $permission")
            else Log.e(TAG, "❌ Failed to grant $permission (exit=$exitCode, output=$output)")
        }
    } catch (exception: Exception) {
        Log.e(TAG, "❌ Grant permission error: ${exception.message}")
        false
    }

    fun grantAudioOutputCapture(context: Context): Boolean =
        grantPermission(context.packageName, "android.permission.CAPTURE_AUDIO_OUTPUT")

    // Force audio routing to speaker at the AudioFlinger/HAL level via root.
    // AudioManager.setSpeakerphoneOn() is overridden by the system dialer when it owns the call;
    // this binder call bypasses that by setting FORCE_SPEAKER on FORCE_FOR_COMMUNICATION directly.
    // Equivalent to: AudioSystem.setForceUse(FOR_COMMUNICATION, FORCE_SPEAKER)
    fun forceSpeakerForCapture(): Boolean {
        // service call audio 102 i32 <usage=2=FOR_COMMUNICATION> i32 <config=1=FORCE_SPEAKER>
        val (exit, output) = runSuCommand("service call audio 102 i32 2 i32 1")
        return (exit == 0).also { ok ->
            if (ok) Log.d(TAG, "✅ Root force-speaker: FOR_COMMUNICATION -> FORCE_SPEAKER")
            else Log.e(TAG, "❌ Root force-speaker failed (exit=$exit): $output")
        }
    }

    // Restore to default routing (FORCE_NONE) after call ends
    fun restoreAudioRouting(): Boolean {
        val (exit, output) = runSuCommand("service call audio 102 i32 2 i32 0")
        return (exit == 0).also { ok ->
            if (ok) Log.d(TAG, "✅ Root restore audio routing: FOR_COMMUNICATION -> FORCE_NONE")
            else Log.e(TAG, "❌ Root restore routing failed (exit=$exit): $output")
        }
    }
}
