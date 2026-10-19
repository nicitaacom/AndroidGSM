package com.nicitaacom.androidgsm

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the SEND_SMS command (outreach-tool /dbr SMS blast - plan-01-dbr-initial-arch section 3.3):
 *  - resolves the SIM (subscription id first, then the slot the server read from SIM_LIST)
 *  - hands the text to SmsManager (multipart when it does not fit one SMS)
 *  - reports the radio's own result over /ws/cmd: SMS_SENT | SMS_FAILED { requestId, reason }
 * gsm-outreach-tool's POST /api/sms/send waits 30 s for that event. Mirrors CallController.
 *
 * @param context     Service context for SmsManager + the sent-result receiver.
 * @param cmdWsClient Lambda returning the current CommandWebSocketClient (may be null).
 * @param log         GsmLogger — never call MainActivity.log directly inside this class.
 */
class SmsController(
    private val context: Context,
    private val cmdWsClient: () -> CommandWebSocketClient?,
    private val log: GsmLogger
) {
    companion object {
        private const val ACTION_SMS_SENT = "com.nicitaacom.androidgsm.ACTION_SMS_SENT"
        private const val EXTRA_REQUEST_ID = "requestId"
        private const val EXTRA_PART_INDEX = "partIndex"
        // a result that never comes (some OEM radios) is the server's 30 s timeout - this only frees the map
        private const val FORGET_PENDING_AFTER_MS = 60_000L
    }

    // requestId -> parts still waiting for their sent result; the first failed part fails the whole SMS
    private val pendingParts = ConcurrentHashMap<String, AtomicInteger>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isReceiverRegistered = false

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return
            val remaining = pendingParts[requestId] ?: return // already failed, or forgotten
            if (resultCode == Activity.RESULT_OK) {
                if (remaining.decrementAndGet() > 0) return // more parts on the way
                pendingParts.remove(requestId)
                sendResult(requestId, true, null)
            } else {
                pendingParts.remove(requestId)
                sendResult(requestId, false, reasonOf(resultCode))
            }
        }
    }

    fun handleSendSms(data: Map<String, Any>) {
        val requestId = data["requestId"] as? String
        val smsToNumber = data["smsToNumber"] as? String
        val smsBody = data["smsBody"] as? String
        if (requestId.isNullOrBlank()) {
            log("SEND_SMS ignored: missing requestId")
            return
        }
        if (smsToNumber.isNullOrBlank() || smsBody.isNullOrBlank()) {
            sendResult(requestId, false, "missing smsToNumber or smsBody")
            return
        }
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            sendResult(requestId, false, "SEND_SMS permission not granted")
            return
        }
        if (pendingParts.containsKey(requestId)) {
            log("SEND_SMS $requestId ignored: already sending")
            return
        }
        try {
            ensureReceiverRegistered()
            val subscriptionId = resolveSubscriptionId(
                data["simAccountId"] as? String,
                (data["simSlotIndex"] as? Number)?.toInt()
            )
            val smsManager = smsManagerFor(subscriptionId)
            val parts = smsManager.divideMessage(smsBody)
            pendingParts[requestId] = AtomicInteger(parts.size)
            mainHandler.postDelayed({ pendingParts.remove(requestId) }, FORGET_PENDING_AFTER_MS)
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (partIndex in parts.indices) sentIntents.add(sentPendingIntent(requestId, partIndex))
            if (parts.size == 1) smsManager.sendTextMessage(smsToNumber, null, parts[0], sentIntents[0], null)
            else smsManager.sendMultipartTextMessage(smsToNumber, null, parts, sentIntents, null)
            log("SEND_SMS $requestId -> $smsToNumber: ${parts.size} part(s), subscription=${subscriptionId ?: "default"}")
        } catch (e: Exception) {
            pendingParts.remove(requestId)
            log("SEND_SMS $requestId failed before the radio: ${e.message}")
            sendResult(requestId, false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun release() {
        if (!isReceiverRegistered) return
        try { context.unregisterReceiver(sentReceiver) } catch (_: Exception) {}
        isReceiverRegistered = false
        pendingParts.clear()
    }

    private fun sendResult(requestId: String, isSent: Boolean, reason: String?) {
        val type = if (isSent) "SMS_SENT" else "SMS_FAILED"
        val data = mutableMapOf<String, Any>("requestId" to requestId)
        if (reason != null) data["reason"] = reason
        cmdWsClient()?.sendEvent(type, data)
        log("$type $requestId${if (reason != null) ": $reason" else ""}")
    }

    @Synchronized
    private fun ensureReceiverRegistered() {
        if (isReceiverRegistered) return
        val filter = IntentFilter(ACTION_SMS_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(sentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(sentReceiver, filter)
        }
        isReceiverRegistered = true
    }

    // explicit (own package) so the not-exported receiver gets it; one request code per part so the extras stay apart
    private fun sentPendingIntent(requestId: String, partIndex: Int): PendingIntent {
        val intent = Intent(ACTION_SMS_SENT).setPackage(context.packageName)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_PART_INDEX, partIndex)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestId.hashCode() * 31 + partIndex, intent, pendingIntentFlags)
    }

    // the PhoneAccountHandle id (SIM_LIST "id") is the subscription id on most builds and the ICCID on older ones -
    // the slot index is the fallback the server read from the same SIM_LIST entry; null = the phone's default SIM
    @Suppress("MissingPermission")
    private fun resolveSubscriptionId(simAccountId: String?, simSlotIndex: Int?): Int? {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            val subscriptionManager =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val active = subscriptionManager.activeSubscriptionInfoList ?: return null
            if (!simAccountId.isNullOrBlank()) {
                active.firstOrNull { it.subscriptionId.toString() == simAccountId || it.iccId == simAccountId }
                    ?.let { return it.subscriptionId }
            }
            if (simSlotIndex != null) {
                active.firstOrNull { it.simSlotIndex == simSlotIndex }?.let { return it.subscriptionId }
            }
            null
        } catch (e: Exception) {
            log("WARNING: Could not resolve the SIM for SEND_SMS: ${e.message}")
            null
        }
    }

    private fun smsManagerFor(subscriptionId: Int?): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val smsManager = context.getSystemService(SmsManager::class.java)
            return if (subscriptionId != null) smsManager.createForSubscriptionId(subscriptionId) else smsManager
        }
        @Suppress("DEPRECATION")
        return if (subscriptionId != null) SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        else SmsManager.getDefault()
    }

    // Android's own 30 SMS / 30 min limit for a non-default SMS sender answers RESULT_ERROR_LIMIT_EXCEEDED - the root
    // fix is `settings put global sms_outgoing_check_max_count 100000` (plan-01-dbr-initial-arch section 3.3)
    private fun reasonOf(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null pdu"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "limit exceeded - settings put global sms_outgoing_check_max_count 100000"
        else -> "result code $resultCode"
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
