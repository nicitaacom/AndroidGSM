package com.nicitaacom.androidgsm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * A customer's STOP reply (plan-01-dbr-initial-arch section 3.4): the number goes on the phone's own list at once
 * (SmsOptOutList), then the running service reports SMS_OPT_OUT over /ws/cmd so outreach-tool's user DB learns it
 * too. A manifest receiver - it fires with the service down as well, the list is what matters then.
 */
class SmsReceiver : BroadcastReceiver() {
    companion object {
        // the CTIA / TCPA keywords - the whole message, or its first word ("STOP please"), punctuation ignored
        private val STOP_WORDS = setOf("STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT")
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // a long SMS arrives as several PDUs of one sender - one text before matching
        val sender = messages.firstOrNull()?.originatingAddress ?: return
        val text = messages.joinToString("") { it.messageBody ?: "" }.trim()
        if (!isStopReply(text)) return

        SmsOptOutList.add(context, sender)
        MainActivity.log("STOP from $sender - on the phone's opt-out list")
        GsmService.notifySmsOptOut(sender, text)
    }

    private fun isStopReply(text: String): Boolean {
        val letters = text.uppercase().replace(Regex("[^A-Z ]"), "").trim()
        if (letters.isEmpty()) return false
        if (letters in STOP_WORDS) return true
        return letters.substringBefore(' ') in STOP_WORDS
    }
}
