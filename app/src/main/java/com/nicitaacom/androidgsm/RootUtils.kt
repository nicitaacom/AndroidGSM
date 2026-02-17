package com.nicitaacom.androidgsm

import android.content.Context
import android.util.Log

object RootUtils {
    private const val TAG = "RootUtils"

    // 1. Check if device is rooted by running `su -c id` and checking uid=0
    fun isRooted(): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val result = process.inputStream.bufferedReader().readLine() ?: ""
        process.waitFor()
        result.contains("uid=0").also { rooted ->
            Log.d(TAG, if (rooted) "✅ Device is rooted" else "❌ Device is NOT rooted")
        }
    } catch (exception: Exception) {
        Log.e(TAG, "❌ Root check failed: ${exception.message}")
        false
    }

    // 2. Grant a system permission via root shell
    fun grantPermission(packageName: String, permission: String): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm grant $packageName $permission"))
        val exitCode = process.waitFor()
        (exitCode == 0).also { granted ->
            if (granted) Log.d(TAG, "✅ Granted $permission")
            else Log.e(TAG, "❌ Failed to grant $permission (exit=$exitCode)")
        }
    } catch (exception: Exception) {
        Log.e(TAG, "❌ Grant permission error: ${exception.message}")
        false
    }

    // 3. Grant CAPTURE_AUDIO_OUTPUT needed for REMOTE_SUBMIX (audio output capture)
    fun grantAudioOutputCapture(context: Context): Boolean =
        grantPermission(context.packageName, "android.permission.CAPTURE_AUDIO_OUTPUT")
}