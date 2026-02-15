package com.nicitaacom.androidgsm

import android.content.Intent
import android.os.Bundle
import android.telecom.*

class GsmConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        MainActivity.log("GsmConnectionService: onCreateOutgoingConnection called")

        val conn = GsmConnection()
        conn.setInitializing()
        // Use numeric presentation allowed constant (1) to avoid API-level symbol issues
        conn.setAddress(request?.address, 1)
        conn.setDialing()

        // Promote to active immediately for our simpler flow; Telecom will manage state.
        conn.setActive()
        // Notify app that the call is connected (so audio capture can start)
        val intent = Intent(GsmService.ACTION_CALL_CONNECTED_BROADCAST)
        sendBroadcast(intent)

        return conn
    }

    override fun onCreateIncomingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        MainActivity.log("GsmConnectionService: onCreateIncomingConnection called")
        val conn = GsmConnection()
        conn.setRinging()

        // When answered, the GsmConnection implementation will notify the app
        return conn
    }
}
