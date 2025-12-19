package com.nicitaacom.androidgsm

import android.content.Context
import org.json.JSONObject

data class Config(
    val WS_URL: String,
    val DEVICE_TOKEN: String,
    val BACKEND_AUTH_KEY: String,
    val ICE_SERVERS: List<Map<String, String>>
)

class ConfigReader {
    companion object {
        fun readConfig(context: Context): Config {
            val inputStream = context.assets.open("androidgsm.config.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            val iceServersList = mutableListOf<Map<String, String>>()
            val iceServersArray = json.getJSONArray("ICE_SERVERS")
            for (i in 0 until iceServersArray.length()) {
                val server = iceServersArray.getJSONObject(i)
                val serverMap = mutableMapOf<String, String>()
                server.keys().forEach { key ->
                    serverMap[key] = server.getString(key)
                }
                iceServersList.add(serverMap)
            }

            return Config(
                WS_URL = json.getString("WS_URL"),
                DEVICE_TOKEN = json.getString("DEVICE_TOKEN"),
                BACKEND_AUTH_KEY = json.getString("BACKEND_AUTH_KEY"),
                ICE_SERVERS = iceServersList
            )
        }
    }
}
