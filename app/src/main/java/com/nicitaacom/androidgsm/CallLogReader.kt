package com.nicitaacom.androidgsm

import android.content.Context
import android.provider.CallLog

// one row of the phone's own call log = one "Recent Calls" row on the laptop (plan-05 task 8): type incoming |
// outgoing | missed | rejected, startedAt in ms, talkSec the talk time (0 for a missed one), number "" when hidden
data class CallLogRow(val id: Long, val number: String, val type: String, val startedAt: Long, val talkSec: Int) {
    fun toMap(): Map<String, Any> =
        mapOf("id" to id, "number" to number, "type" to type, "startedAt" to startedAt, "talkSec" to talkSec)
}

object CallLogReader {
    private val PROJECTION = arrayOf(
        CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION
    )

    // newest first; sinceMs > 0 keeps the rows that started at or after it. Throws SecurityException without
    // READ_CALL_LOG (the default-dialer role grants it, MainActivity asks for it too)
    fun select(context: Context, limit: Int, sinceMs: Long = 0): List<CallLogRow> {
        val selection = if (sinceMs > 0) "${CallLog.Calls.DATE} >= ?" else null
        val selectionArgs = if (sinceMs > 0) arrayOf(sinceMs.toString()) else null
        val rows = mutableListOf<CallLogRow>()
        context.contentResolver
            .query(CallLog.Calls.CONTENT_URI, PROJECTION, selection, selectionArgs, "${CallLog.Calls.DATE} DESC")
            ?.use { cursor ->
                while (cursor.moveToNext() && rows.size < limit) {
                    rows += CallLogRow(
                        id = cursor.getLong(0),
                        number = cursor.getString(1) ?: "",
                        type = typeName(cursor.getInt(2)),
                        startedAt = cursor.getLong(3),
                        talkSec = cursor.getInt(4)
                    )
                }
            }
        return rows
    }

    private fun typeName(type: Int): String = when (type) {
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> "incoming"
    }
}
