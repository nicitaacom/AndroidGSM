package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean

// the SIM's own number over USSD: his 2 Telekom SIMs report number=NULL in the phone's SIM table, so the laptop's SIM
// pickers showed "slot 1 / slot 2". The carrier's own-number code is dialed once per SIM, the digits in the reply go to
// SharedPreferences by the SIM_LIST "id" (the PhoneAccount id: the subscription id on Android 11+, the ICCID on
// Android 10), GsmDialer.getSimAccounts reads them from there on every SIM_LIST
object SimNumberReader {
    private const val PREFS_NAME = "androidgsm_sim_numbers"
    private const val USSD_TIMEOUT_MS = 20_000L

    // the SIM's label (lower case, contains) -> the USSD code the carrier answers with the SIM's own number
    private val OWN_NUMBER_USSD_BY_CARRIER = mapOf(
        "telekom" to "*135#",
        "vodafone" to "*#100#",
    )
    private val NUMBER_IN_REPLY = Regex("""\+?\d[\d ]{7,}\d""")
    // one read at a time: onConnected runs on every socket open, and a 2nd *135# while the 1st session is open fails
    // with -1 (06.11.2026: 2 opens 14 ms apart, 2 reads)
    private val isReading = AtomicBoolean(false)

    fun select(context: Context, simAccountId: String): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(simAccountId, "") ?: ""

    private fun insert(context: Context, simAccountId: String, number: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { putString(simAccountId, number) }
    }

    // dials the own-number USSD on every SIM of GsmDialer.getSimAccounts with number "" - one SIM at a time, the modem
    // holds one USSD session. Those SIMs come from Telecom's call-capable accounts: MIUI hands this app an empty
    // SubscriptionManager.activeSubscriptionInfoList. onNumber runs after each reply that held a number
    fun readMissing(context: Context, log: GsmLogger, sims: List<Map<String, Any>>, onNumber: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
        val missing = sims.filter { (it["number"] as? String).isNullOrBlank() }
        log("SimNumberReader: ${sims.size} SIMs, ${missing.size} without a number")
        if (missing.isEmpty()) return
        if (!isReading.compareAndSet(false, true)) {
            log("SimNumberReader: a read already runs")
            return
        }
        // the account id is the subscription id on Android 11+ and the ICCID on Android 10 (his Redmi Note 7) - one su
        // read of the SIM table covers the ICCID case
        val subscriptionIdByIccid = if (missing.all { (it["id"] as? String)?.toIntOrNull() != null }) emptyMap()
        else RootUtils.selectSubscriptionIdByIccid()
        readNext(context, log, missing, 0, subscriptionIdByIccid, onNumber)
    }

    @Suppress("MissingPermission")
    private fun readNext(
        context: Context,
        log: GsmLogger,
        sims: List<Map<String, Any>>,
        index: Int,
        subscriptionIdByIccid: Map<String, Int>,
        onNumber: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || index >= sims.size) {
            isReading.set(false)
            return
        }
        val sim = sims[index]
        val simAccountId = sim["id"] as? String ?: ""
        val slot = sim["simSlotIndex"]
        val carrier = (sim["label"] as? String)?.lowercase() ?: ""
        val code = OWN_NUMBER_USSD_BY_CARRIER.entries.firstOrNull { carrier.contains(it.key) }?.value
        val subscriptionId = simAccountId.toIntOrNull() ?: subscriptionIdByIccid[simAccountId]
        if (code == null || subscriptionId == null) {
            log("SimNumberReader: no own-number USSD for \"$carrier\" id $simAccountId (slot $slot)")
            readNext(context, log, sims, index + 1, subscriptionIdByIccid, onNumber)
            return
        }
        val telephonyManager = (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .createForSubscriptionId(subscriptionId)
        val handler = Handler(Looper.getMainLooper())
        // a modem that answers neither way must not hold the next SIM forever - `done` keeps a late reply from running
        // the next SIM a second time
        var done = false
        val next = Runnable {
            if (done) return@Runnable
            done = true
            readNext(context, log, sims, index + 1, subscriptionIdByIccid, onNumber)
        }
        handler.postDelayed(next, USSD_TIMEOUT_MS)
        log("SimNumberReader: USSD $code on slot $slot ($carrier)")
        telephonyManager.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                handler.removeCallbacks(next)
                val number = NUMBER_IN_REPLY.find(response)?.value?.replace(" ", "")
                if (number == null) {
                    log("SimNumberReader: no number in the USSD reply on slot $slot: $response")
                } else {
                    insert(context, simAccountId, number)
                    log("SimNumberReader: slot $slot = $number")
                    onNumber()
                }
                next.run()
            }

            override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                handler.removeCallbacks(next)
                log("SimNumberReader: USSD $code failed on slot $slot: code $failureCode")
                next.run()
            }
        }, handler)
    }
}
