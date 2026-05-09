package com.nicitaacom.androidgsm

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object RootUtils {
    private const val TAG = "RootUtils"

    // Path where GsmService unpacks set_mixer_ctl from assets at startup.
    var nativeBinDir: String = "/data/local/tmp"

    private fun setMixerCtlBin() = "$nativeBinDir/set_mixer_ctl"

    // Set one element of a multi-element ALSA BOOL/INTEGER control by index.
    // Uses the native set_mixer_ctl binary to bypass broken mixer_ctl_get_array
    // in this device's tinyalsa build.
    private fun setMixerElem(controlName: String, elemIdx: Int, value: Int): Boolean {
        val bin = setMixerCtlBin()
        val (exit, output) = runSuCommand("$bin 0 '$controlName' $elemIdx $value")
        return (exit == 0).also { ok ->
            if (ok) Log.d(TAG, "✅ setMixerElem '$controlName'[$elemIdx]=$value")
            else Log.w(TAG, "⚠️ setMixerElem '$controlName'[$elemIdx]=$value failed (exit=$exit): $output")
        }
    }

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

    // Tap GSM call downlink (the audio you hear from the other party) into MultiMedia1's
    // capture stream via tinymix. 'MultiMedia1 Mixer VOC_REC_DL' (index 1190 on sdm660) is
    // the Qualcomm Voice-Call-Record DownLink mixer — once enabled, AudioRecord on
    // REMOTE_SUBMIX (which taps MultiMedia1) sees the GSM downlink PCM.
    //
    // NOTE: The previously used 'Incall_Music Audio Mixer MultiMedia1' is the OPPOSITE
    // direction — it injects MultiMedia1 playback INTO the call uplink. Wrong control.
    fun enableIncallMusicCapture(): Boolean {
        // slot 0 = VoiceMMode1, slot 1 = VoiceMMode2 — set both via native binary
        val ok0 = setMixerElem("MultiMedia1 Mixer VOC_REC_DL", 0, 1)
        val ok1 = setMixerElem("MultiMedia1 Mixer VOC_REC_DL", 1, 1)
        return (ok0 || ok1).also { ok ->
            if (ok) Log.d(TAG, "✅ VOC_REC_DL -> MultiMedia1 enabled (slot0=$ok0 slot1=$ok1)")
            else Log.e(TAG, "❌ VOC_REC_DL enable failed both slots")
        }
    }

    fun disableIncallMusicCapture(): Boolean {
        setMixerElem("MultiMedia1 Mixer VOC_REC_DL", 0, 0)
        setMixerElem("MultiMedia1 Mixer VOC_REC_DL", 1, 0)
        Log.d(TAG, "✅ VOC_REC_DL -> MultiMedia1 disabled")
        return true
    }

    fun dumpMixerControls(): String {
        val (_, output) = runSuCommand("tinymix")
        return output
    }

    // Route MultiMedia1 AudioTrack playback into the GSM voice uplink (TX path).
    // tinymix on this device cannot set slot 1 (VoiceMMode2) of these BOOL controls
    // due to a broken mixer_ctl_get_array. We use set_mixer_ctl (native ioctl binary)
    // to write each element index individually via SNDRV_CTL_IOCTL_ELEM_WRITE.
    fun enableIncallMusicInjection(): Boolean {
        var any = false
        for (mm in listOf("MultiMedia1", "MultiMedia2", "MultiMedia5")) {
            for (slot in 0..1) {
                if (setMixerElem("Incall_Music Audio Mixer $mm", slot, 1)) any = true
                setMixerElem("Incall_Music_2 Audio Mixer $mm", slot, 1)
            }
        }
        return any
    }

    fun disableIncallMusicInjection() {
        for (mm in listOf("MultiMedia1", "MultiMedia2", "MultiMedia5")) {
            for (slot in 0..1) {
                setMixerElem("Incall_Music Audio Mixer $mm", slot, 0)
                setMixerElem("Incall_Music_2 Audio Mixer $mm", slot, 0)
            }
        }
        Log.d(TAG, "✅ Incall_Music injection disabled")
    }

    // Route AFE-PROXY RX (pcmC0D6p) into VoiceMMode2 TX uplink.
    // This is the confirmed working injection path on sdm660/MIUI — slot 0 sticks
    // unlike Incall_Music slot 1 (VoiceMMode2) which the HAL immediately resets.
    fun enableAfeProxyInjection(): Boolean {
        val ok = setMixerElem("VoiceMMode2_Tx Mixer AFE_PCM_TX_MMode2", 0, 1)
        Log.d(TAG, if (ok) "✅ AFE-PROXY injection enabled" else "❌ AFE-PROXY injection failed")
        return ok
    }

    fun disableAfeProxyInjection() {
        setMixerElem("VoiceMMode2_Tx Mixer AFE_PCM_TX_MMode2", 0, 0)
        Log.d(TAG, "✅ AFE-PROXY injection disabled")
    }

    // Mute earpiece + speaker output controls so call audio is inaudible on the phone.
    // VOC_REC_DL capture path remains open — REMOTE_SUBMIX still taps the mixer.
    fun mutePhoneSpeaker(): Boolean {
        // EAR_S = earpiece output, SPK = speaker output on sdm660/Redmi Note 7.
        // Setting to ZERO disconnects the voice-call downlink from the physical output
        // while leaving VOC_REC_DL capture path open for REMOTE_SUBMIX.
        var ok = true
        for (ctl in listOf("EAR_S", "SPK")) {
            val (exit, output) = runSuCommand("tinymix '$ctl' ZERO")
            if (exit == 0) Log.d(TAG, "✅ muted $ctl")
            else { Log.w(TAG, "⚠️ could not mute $ctl (exit=$exit): $output"); ok = false }
        }
        return ok
    }

    fun unmutePhoneSpeaker() {
        for (ctl in listOf("EAR_S", "SPK")) {
            val (exit, output) = runSuCommand("tinymix '$ctl' Switch")
            if (exit == 0) Log.d(TAG, "✅ unmuted $ctl")
            else Log.w(TAG, "⚠️ could not unmute $ctl (exit=$exit): $output")
        }
    }
}
