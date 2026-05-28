package com.nicitaacom.androidgsm

import android.telecom.Connection
import android.telecom.DisconnectCause

class GsmConnection : Connection() {
    override fun onAnswer() {
        try {
            setActive()
            MainActivity.log("GsmConnection: onAnswer — setActive()")
        } catch (e: Exception) {
            MainActivity.log("GsmConnection onAnswer error: ${e.message}")
        }
    }

    override fun onDisconnect() {
        try {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            MainActivity.log("GsmConnection: onDisconnect — destroyed")
        } catch (e: Exception) {
            MainActivity.log("GsmConnection onDisconnect error: ${e.message}")
        }
    }

    override fun onHold() { setOnHold() }
    override fun onUnhold() { setActive() }
}
