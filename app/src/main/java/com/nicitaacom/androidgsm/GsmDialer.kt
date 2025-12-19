package com.nicitaacom.androidgsm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.ActivityCompat

class GsmDialer(private val context: Context) {

    fun startCall(number: String) {
        try {
            MainActivity.log("GsmDialer: Initiating call to $number")

            // Check permission
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
                MainActivity.log("ERROR: CALL_PHONE permission not granted")
                return
            }

            val intent = Intent(Intent.ACTION_CALL)
            intent.data = Uri.parse("tel:$number")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)

            MainActivity.log("GsmDialer: Call started to $number")
        } catch (e: Exception) {
            MainActivity.log("ERROR starting call: ${e.message}")
            Log.e("GsmDialer", "Failed to start call", e)
        }
    }

        fun endCall() {
        MainActivity.log("GsmDialer: End call requested")
        MainActivity.log("WARNING: Ending calls programmatically requires system permissions")
        MainActivity.log("User must end call manually from dialer")
        // Note: Ending calls programmatically is restricted on Android
        // This would require ANSWER_PHONE_CALLS permission (API 26+) or being a system app
        Log.d("GsmDialer", "End call - user action required")
    }

    fun sendDtmf(digit: Char) {
        MainActivity.log("WARNING: DTMF tones require active call connection")
        MainActivity.log("DTMF support limited on this Android version")
        Log.d("GsmDialer", "DTMF requested: $digit (not implemented)")
    }
}
