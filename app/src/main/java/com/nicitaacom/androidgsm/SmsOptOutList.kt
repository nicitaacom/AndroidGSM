package com.nicitaacom.androidgsm

import android.content.Context

/**
 * The phone's own STOP list (plan-01-dbr-initial-arch section 3.4) - immune memory in the tissue that was exposed:
 * a later SEND_SMS to one of these numbers answers SMS_FAILED "opted out" before the radio, with the browser and
 * outreach-tool's user DB open or not. SmsReceiver adds, SmsController asks.
 */
object SmsOptOutList {
    private const val PREFS = "sms_opt_out"
    private const val KEY_NUMBERS = "numbers"
    // a national-format sender ("0176...") and the E.164 number the blast used ("+49176...") are one number when the
    // shorter one is the tail of the longer one - 8 digits keep an area code from matching every number in it
    private const val MIN_TAIL_DIGITS = 8

    fun add(context: Context, number: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val numbers = HashSet(prefs.getStringSet(KEY_NUMBERS, emptySet()) ?: emptySet())
        numbers.add(normalize(number))
        prefs.edit().putStringSet(KEY_NUMBERS, numbers).apply()
    }

    fun contains(context: Context, number: String): Boolean {
        val wanted = normalize(number)
        if (wanted.isEmpty()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val numbers = prefs.getStringSet(KEY_NUMBERS, emptySet()) ?: return false
        return numbers.any { isSameNumber(it, wanted) }
    }

    // digits only, no leading zeros - "+49 176 874 091 01", "0049176..." and "+49176..." read the same
    private fun normalize(number: String): String = number.filter { it.isDigit() }.trimStart('0')

    private fun isSameNumber(a: String, b: String): Boolean {
        if (a == b) return true
        if (minOf(a.length, b.length) < MIN_TAIL_DIGITS) return false
        return a.endsWith(b) || b.endsWith(a)
    }
}
