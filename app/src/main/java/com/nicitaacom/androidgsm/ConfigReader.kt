package com.nicitaacom.androidgsm

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader

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
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<Config>() {}.type
            return Gson().fromJson(reader, type)
        }
    }
}
