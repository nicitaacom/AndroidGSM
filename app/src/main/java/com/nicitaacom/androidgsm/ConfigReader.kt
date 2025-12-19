package com.nicitaacom.androidgsm

import android.content.Context
import org.json.JSONObject

data class Config(
    val WS_URL: String,
    val DEVICE_TOKEN: String,
    val BACKEND_AUTH_KEY: String,
    val PUSHER_APP_ID: String,
    val PUSHER_KEY: String,
    val PUSHER_SECRET: String,
    val PUSHER_CLUSTER: String
)

class ConfigReader {
    companion object {
        fun readConfig(context: Context): Config {
            val inputStream = context.assets.open("androidgsm.config.json")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            return Config(
                WS_URL = json.getString("WS_URL"),
                DEVICE_TOKEN = json.getString("DEVICE_TOKEN"),
                BACKEND_AUTH_KEY = json.getString("BACKEND_AUTH_KEY"),
                PUSHER_APP_ID = json.optString("PUSHER_APP_ID", ""),
                PUSHER_KEY = json.optString("PUSHER_KEY", ""),
                PUSHER_SECRET = json.optString("PUSHER_SECRET", ""),
                PUSHER_CLUSTER = json.optString("PUSHER_CLUSTER", "")
            )
        }
    }
}
