package com.nicitaacom.androidgsm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telecom.Connection
import android.telecom.DisconnectCause

class GsmConnection : Connection() {
    override fun onAnswer() {
        try {
            MainActivity.log("GsmConnection: onAnswer called - setting active and broadcasting CONNECTED")
            
            val audioManager = AppContextHolder.ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isSpeakerphoneOn = false
            
            setActive()
            val intent = Intent(GsmService.ACTION_CALL_CONNECTED_BROADCAST)
            AppContextHolder.ctx?.sendBroadcast(intent)
        } catch (e: Exception) {
            MainActivity.log("GsmConnection onAnswer error: ${e.message}")
        }
    }

    override fun onDisconnect() {
        try {
            MainActivity.log("GsmConnection: onDisconnect called - broadcasting DISCONNECTED")
            
            val audioManager = AppContextHolder.ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.mode = AudioManager.MODE_NORMAL
            
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            val intent = Intent(GsmService.ACTION_CALL_DISCONNECTED_BROADCAST)
            AppContextHolder.ctx?.sendBroadcast(intent)
            destroy()
        } catch (e: Exception) {
            MainActivity.log("GsmConnection onDisconnect error: ${e.message}")
        }
    }

    override fun onHold() {
        setOnHold()
    }

    override fun onUnhold() {
        setActive()
    }
}
