package com.nicitaacom.androidgsm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.telecom.*

class GsmConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        MainActivity.log("GsmConnectionService: onCreateOutgoingConnection called")
        
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false

        val conn = GsmConnection()
        conn.setInitializing()
        conn.setAddress(request?.address, 1)
        conn.setDialing()
        conn.setActive()
        
        val intent = Intent(GsmService.ACTION_CALL_CONNECTED_BROADCAST)
        sendBroadcast(intent)

        return conn
    }

    override fun onCreateIncomingConnection(connectionManagerPhoneAccount: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        MainActivity.log("GsmConnectionService: onCreateIncomingConnection called")
        val conn = GsmConnection()
        conn.setRinging()
        return conn
    }
}
