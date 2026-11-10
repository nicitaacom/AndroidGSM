package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellSignalStrengthGsm
import android.telephony.TelephonyManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One answered dial in 2 lines at the hang-up, sent as DIAL_QUALITY -> the VPS log -> the website's Logs box
 * (outreach-tool dialer, 10.11.2026: leads answered "Verbindung ist schlecht"):
 *  - 📶 lead side - what decides how the lead hears him: the dialing SIM's network, dBm and GSM bit errors (0 clean,
 *    7 worst) every 10 s, the call's route and its switches, the Bluetooth link reports (BluetoothDropWatcher), music
 *    playing on the call's route, the phone mic mute
 *  - 🌐 phone internet - what decides how he hears the lead on the laptop: Wi-Fi dBm, band, link speed, the audio
 *    socket's upload backlog and reconnects
 * His voice never touches the internet: headphones mic -> Bluetooth -> phone -> the SIM's network.
 * Each 10 s sample also goes to logcat (GsmGateway) as it is taken.
 */
class DialQualityLog(
    private val context: Context,
    private val cmdWsClient: () -> CommandWebSocketClient?,
    private val audioWsHandlerRef: AudioWsHandlerRef,
    private val log: GsmLogger,
) {
    private class Sample(
        val network: String,
        val dbm: Int?,
        val bitErrors: Int?,
        val isMusicOn: Boolean,
        val isMuted: Boolean,
        val wifiDbm: Int?,
        val wifiMbps: Int?,
    )

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var sampling: ScheduledFuture<*>? = null
    private val samples = mutableListOf<Sample>()
    private val routes = mutableListOf<String>()
    private var subscriptionId: Int? = null
    private var startedAtMs = 0L
    private var wifiMhz: Int? = null
    @Volatile private var bluetoothDropsAtSec: Long? = null
    private val bluetoothWatcher = BluetoothDropWatcher(log) { bluetoothDropsAtSec = elapsedSec() }

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // the answer - subscriptionId is the dialing SIM's (null for a lead who dialed in: the default SIM)
    @Synchronized
    fun start(subscriptionId: Int?) {
        sampling?.cancel(false)
        samples.clear()
        routes.clear()
        this.subscriptionId = subscriptionId
        startedAtMs = System.currentTimeMillis()
        wifiMhz = null
        bluetoothDropsAtSec = null
        onRouteChanged()
        bluetoothWatcher.start()
        sampling = executor.scheduleAtFixedRate({ takeSample() }, 0, SAMPLE_EVERY_SEC, TimeUnit.SECONDS)
    }

    // GsmInCallService.onAudioRouteChanged - "BLUETOOTH 0:00 -> EARPIECE 0:45" shows a headset link that fell off
    @Synchronized
    fun onRouteChanged() {
        if (startedAtMs == 0L) return
        val route = GsmInCallService.currentAudioState?.route ?: return
        val name = GsmInCallService.routeName(route)
        if (routes.lastOrNull()?.startsWith(name) == true) return
        routes.add("$name ${clock(elapsedSec())}")
    }

    // the hang-up - before teardownCall drops the audio socket, its backlog is read here
    @Synchronized
    fun stop() {
        if (startedAtMs == 0L) return
        sampling?.cancel(false)
        sampling = null
        bluetoothWatcher.stop()
        val durationSec = elapsedSec()
        startedAtMs = 0L
        if (samples.isEmpty()) return
        for (line in listOf(leadSideLine(durationSec), phoneInternetLine(durationSec))) {
            log(line)
            cmdWsClient()?.sendEvent("DIAL_QUALITY", mapOf("line" to line))
        }
    }

    @Synchronized
    private fun takeSample() {
        if (startedAtMs == 0L) return
        try {
            val telephony = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                .let { manager -> subscriptionId?.let { manager.createForSubscriptionId(it) } ?: manager }
            val network = try { networkName(telephony.voiceNetworkType) } catch (_: SecurityException) { "?" }
            var dbm: Int? = null
            var bitErrors: Int? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val strengths = telephony.signalStrength?.cellSignalStrengths.orEmpty()
                val gsm = strengths.filterIsInstance<CellSignalStrengthGsm>().firstOrNull()
                dbm = (if (network == "2G" && gsm != null) gsm else strengths.firstOrNull())?.dbm?.takeIf { it != Int.MAX_VALUE }
                bitErrors = gsm?.bitErrorRate?.takeIf { it in 0..7 }
            }
            @Suppress("DEPRECATION")
            val wifi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            val wifiDbm = wifi?.rssi?.takeIf { it > WIFI_NO_SIGNAL_DBM }
            if (wifiDbm != null) wifiMhz = wifi.frequency
            val sample = Sample(
                network = network,
                dbm = dbm,
                bitErrors = bitErrors,
                isMusicOn = audioManager.isMusicActive,
                isMuted = GsmInCallService.isMuted,
                wifiDbm = wifiDbm,
                wifiMbps = wifi?.linkSpeed?.takeIf { wifiDbm != null && it > 0 },
            )
            samples.add(sample)
            log(
                "📶 dial ${clock(elapsedSec())}: ${sample.network} ${sample.dbm ?: "?"} dBm, bit errors ${sample.bitErrors ?: "?"}/7, " +
                    "${routes.lastOrNull() ?: "no route"}, music ${if (sample.isMusicOn) "on" else "off"}, " +
                    "Wi-Fi ${sample.wifiDbm ?: "-"} dBm ${sample.wifiMbps ?: "-"} Mbit/s",
            )
        } catch (e: Exception) {
            log("📶 dial sample error: ${e.message}")
        }
    }

    private fun leadSideLine(durationSec: Long): String {
        val dbms = samples.mapNotNull { it.dbm }
        val every10s = samples.joinToString(" ") { "${it.network}:${it.dbm ?: "?"}/${it.bitErrors ?: "?"}" }
        val drops = bluetoothDropsAtSec?.let { ", drops from ${clock(it)}" } ?: ""
        return "📶 DIAL ${clock(durationSec)} lead side · signal best ${dbms.maxOrNull() ?: "?"} worst ${dbms.minOrNull() ?: "?"} dBm" +
            " · bit errors max ${samples.mapNotNull { it.bitErrors }.maxOrNull() ?: "?"}/7 (0 = clean)" +
            " · every 10 s network:dBm/errors $every10s" +
            " · route ${routes.joinToString(" -> ").ifEmpty { "?" }}" +
            " · Bluetooth link reports ${bluetoothWatcher.reportCount}$drops" +
            " · music on ${samples.count { it.isMusicOn }}/${samples.size} samples" +
            " · phone mic muted ${samples.count { it.isMuted }}/${samples.size} samples"
    }

    private fun phoneInternetLine(durationSec: Long): String {
        val wifiDbms = samples.mapNotNull { it.wifiDbm }
        val handler = audioWsHandlerRef.handler
        return "🌐 DIAL ${clock(durationSec)} phone internet" +
            " · Wi-Fi ${wifiMhz ?: "?"} MHz, best ${wifiDbms.maxOrNull() ?: "?"} worst ${wifiDbms.minOrNull() ?: "?"} dBm" +
            " · link speed worst ${samples.mapNotNull { it.wifiMbps }.minOrNull() ?: "?"} Mbit/s" +
            " · upload backlog max ${(handler?.maxQueuedBytes ?: 0L) / 1024} KB" +
            " · audio socket reconnects ${handler?.wsFailureCount ?: 0}"
    }

    private fun elapsedSec(): Long = (System.currentTimeMillis() - startedAtMs) / 1000

    private fun clock(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

    private fun networkName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        else -> "?"
    }

    private companion object {
        const val SAMPLE_EVERY_SEC = 10L
        // WifiInfo's rssi when the phone has no Wi-Fi link
        const val WIFI_NO_SIGNAL_DBM = -127
    }
}
