package com.nicitaacom.androidgsm

import android.content.Context
import org.json.JSONObject

data class Config(
    val BACKEND_URL: String,
    val DEVICE_TOKEN: String,
    val BACKEND_BEARER: String,
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
                BACKEND_URL = json.getString("BACKEND_URL"),
                DEVICE_TOKEN = json.getString("DEVICE_TOKEN"),
                BACKEND_BEARER = json.getString("BACKEND_BEARER"),
                PUSHER_APP_ID = json.getString("PUSHER_APP_ID"),
                PUSHER_KEY = json.getString("PUSHER_KEY"),
                PUSHER_SECRET = json.getString("PUSHER_SECRET"),
                PUSHER_CLUSTER = json.getString("PUSHER_CLUSTER")
            )
        }
    }
}
