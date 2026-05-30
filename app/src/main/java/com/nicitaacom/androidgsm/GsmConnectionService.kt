package com.nicitaacom.androidgsm

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.telecom.*

class GsmConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        MainActivity.log("GsmConnectionService: onCreateOutgoingConnection called")

        val conn = GsmConnection()
        conn.setInitializing()
        conn.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        conn.setDialing()
        // Do NOT broadcast CALL_CONNECTED here — the real GSM call has not connected yet.
        // GsmDialer's TelephonyCallback fires OFFHOOK when the carrier connects, triggering audio start.
        return conn
    }

    override fun onCreateIncomingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        MainActivity.log("GsmConnectionService: onCreateIncomingConnection called")
        val conn = GsmConnection()
        conn.setRinging()
        return conn
    }
}
