package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * The phone's radios right now in 1 line - MusicPlayer logs it at every wait for the song and sends it with
 * MUSIC_STOPPED, so the website's toast names what the phone was doing (outreach-tool plan-09: the toast came 3 times
 * in 1 hour with the phone 2 cm from the router, a fast speed test after each one).
 * Wi-Fi 2.4 GHz and Bluetooth share 1 radio on the phone - "2.4 GHz" + "Bluetooth ... on" at every wait points at that;
 * "5 GHz" points away from it, at the song's own server or the router.
 */
object PhoneRadioState {
    // WifiInfo's rssi when the phone has no Wi-Fi link
    private const val WIFI_NO_SIGNAL_DBM = -127

    fun describe(context: Context): String {
        val appContext = context.applicationContext
        @Suppress("DEPRECATION")
        val wifi = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        val wifiDbm = wifi?.rssi?.takeIf { it > WIFI_NO_SIGNAL_DBM }
        val wifiText = if (wifi == null || wifiDbm == null) "no Wi-Fi link"
            else "Wi-Fi ${bandText(wifi.frequency)} $wifiDbm dBm, link ${wifi.linkSpeed} Mbit/s"
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        val bluetoothText = when {
            audioManager.isBluetoothScoOn -> "Bluetooth call audio on"
            audioManager.isBluetoothA2dpOn -> "Bluetooth music on"
            else -> "no Bluetooth audio"
        }
        val isCallOn = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        val isScreenOn = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        return "$wifiText · $bluetoothText · ${if (isCallOn) "a call on" else "no call"} · screen ${if (isScreenOn) "on" else "off"}"
    }

    private fun bandText(mhz: Int): String = when (mhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "$mhz MHz"
    }
}
