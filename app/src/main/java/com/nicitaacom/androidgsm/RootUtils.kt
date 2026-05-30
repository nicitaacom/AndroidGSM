package com.nicitaacom.androidgsm

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

object RootUtils {
    private const val TAG = "RootUtils"

    // Path to the unpacked set_mixer_ctl binary. Set by GsmService.onCreate() after unpacking asset.
    @Volatile var nativeBinDir: String = ""

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

    // Resolves the `su` binary path for exec — falls back to just "su" (PATH lookup).
    private val suBin: String by lazy {
        suPaths.firstOrNull { File(it).exists() } ?: "su"
    }

    private fun runSuCommand(command: String): Pair<Int, String> {
        val process = Runtime.getRuntime().exec(arrayOf(suBin, "-c", command))
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

    // WARNING: set_mixer_ctl binary is NOT in assets — it has never been built.
    // The tinymix fallback below (slot 0 only) is what keeps audio working.
    // DO NOT remove the fallback until the binary is built and placed in assets:
    //   NDK=/home/kali/android-sdk/ndk/27.2.12479018 ./native/set_mixer_ctl/build_arm64.sh
    //   cp <output> app/src/main/assets/set_mixer_ctl
    // Removing the fallback without the binary = VOC_REC_DL never opens = tinycap silence = no audio.
    private fun setMixerElem(control: String, slot: Int, value: Int): Boolean {
        return if (nativeBinDir.isNotEmpty()) {
            val bin = "$nativeBinDir/set_mixer_ctl"
            val (exit, output) = runSuCommand("$bin 0 '$control' $slot $value")
            (exit == 0).also { ok ->
                if (ok) Log.d(TAG, "✅ set_mixer_ctl '$control'[$slot]=$value")
                else Log.e(TAG, "❌ set_mixer_ctl '$control'[$slot]=$value failed (exit=$exit): $output")
            }
        } else {
            // Fallback: tinymix sets only slot 0, slot 1 (VoiceMMode2) may be missed on MIUI sdm660.
            // Build set_mixer_ctl to fix: NDK=/path/to/ndk ./native/set_mixer_ctl/build_arm64.sh
            if (slot > 0) {
                Log.w(TAG, "⚠️ set_mixer_ctl not available — skipping slot $slot for '$control' (tinymix fallback only handles slot 0)")
                return true // don't fail the whole operation
            }
            val (exit, output) = runSuCommand("tinymix '$control' $value")
            (exit == 0).also { ok ->
                if (ok) Log.d(TAG, "✅ tinymix '$control'=$value (slot 0 only)")
                else Log.e(TAG, "❌ tinymix '$control'=$value failed (exit=$exit): $output")
            }
        }
    }

    private fun logRootState(rooted: Boolean, reason: String): Boolean {
        Log.d(TAG, if (rooted) "✅ Root detected: $reason" else "❌ Root not detected: $reason")
        return rooted
    }

    // Cached — only runs the su subprocess once per process lifetime.
    // isRooted() was being called on every onCreate, triggering a Magisk grant dialog each time.
    val isRooted: Boolean by lazy { detectRooted() }

    private fun detectRooted(): Boolean {
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

    // Tap GSM call downlink into MultiMedia1 capture stream via set_mixer_ctl.
    // 'MultiMedia1 Mixer VOC_REC_DL' is a 2-slot BOOL control (slot 0 = VoiceMMode1,
    // slot 1 = VoiceMMode2). tinymix cannot set slot 1 on MIUI sdm660 due to a broken
    // mixer_ctl_get_array — we use set_mixer_ctl (SNDRV_CTL_IOCTL_ELEM_WRITE) directly.
    fun enableIncallMusicCapture(): Boolean {
        val s0 = setMixerElem("MultiMedia1 Mixer VOC_REC_DL", 0, 1)
        val s1 = setMixerElem("MultiMedia1 Mixer VOC_REC_DL", 1, 1)
        return (s0 && s1).also { ok ->
            if (ok) Log.d(TAG, "✅ VOC_REC_DL slots 0+1 enabled (GSM downlink capture open)")
            else Log.e(TAG, "❌ VOC_REC_DL enable partial: slot0=$s0 slot1=$s1")
        }
    }

    fun disableIncallMusicCapture(): Boolean {
        val s0 = setMixerElem("MultiMedia1 Mixer VOC_REC_DL", 0, 0)
        val s1 = setMixerElem("MultiMedia1 Mixer VOC_REC_DL", 1, 0)
        return (s0 && s1).also { ok ->
            if (ok) Log.d(TAG, "✅ VOC_REC_DL slots 0+1 disabled")
            else Log.e(TAG, "❌ VOC_REC_DL disable partial: slot0=$s0 slot1=$s1")
        }
    }

    fun dumpMixerControls(): String {
        val (_, output) = runSuCommand("tinymix")
        return output
    }

    // Route MultiMedia1 AudioTrack playback into the GSM voice uplink (TX path).
    // 'Incall_Music Audio Mixer MultiMedia1/5' are 2-slot BOOL controls. Same MIUI tinymix
    // bug applies — must use set_mixer_ctl for each slot separately.
    fun enableIncallMusicInjection(): Boolean {
        var ok = true
        for (ctl in listOf(
            "Incall_Music Audio Mixer MultiMedia1",
            "Incall_Music Audio Mixer MultiMedia5"
        )) {
            if (!setMixerElem(ctl, 0, 1)) ok = false
            if (!setMixerElem(ctl, 1, 1)) ok = false
        }
        // Mute hardware mic TX so phone mic doesn't bleed into the uplink
        setMixerElem("VoiceMMode1_Tx Mute", 0, 1)
        setMixerElem("VoiceMMode2_Tx Mute", 0, 1)
        return ok.also { Log.d(TAG, if (it) "✅ Incall injection enabled" else "❌ Incall injection partial") }
    }

    fun disableIncallMusicInjection() {
        for (ctl in listOf(
            "Incall_Music Audio Mixer MultiMedia1",
            "Incall_Music Audio Mixer MultiMedia5"
        )) {
            setMixerElem(ctl, 0, 0)
            setMixerElem(ctl, 1, 0)
        }
        // Restore hardware mic TX
        setMixerElem("VoiceMMode1_Tx Mute", 0, 0)
        setMixerElem("VoiceMMode2_Tx Mute", 0, 0)
        Log.d(TAG, "✅ Incall injection disabled")
    }

    // Mute earpiece + speaker output so call audio is inaudible on the phone.
    fun mutePhoneSpeaker(): Boolean {
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
