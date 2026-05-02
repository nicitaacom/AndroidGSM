package com.nicitaacom.androidgsm

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
            val inputStream = try {
                context.assets.open("androidgsm.config.json")
            } catch (e: Exception) {
                val msg = "CONFIG ERROR: androidgsm.config.json not found in assets.\n" +
                    "Copy androidgsm.config.example.json → androidgsm.config.json and fill in your values, then rebuild."
                MainActivity.log(msg)
                throw e
            }

            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            val missing = listOf("BACKEND_URL", "BACKEND_BEARER", "PUSHER_APP_ID", "PUSHER_KEY", "PUSHER_SECRET", "PUSHER_CLUSTER")
                .filter { key -> json.optString(key).isBlank() || json.optString(key).startsWith("your-") || json.optString(key) == "xxxx" }

            if (missing.isNotEmpty()) {
                val msg = "CONFIG ERROR: placeholder values detected for: ${missing.joinToString()}\n" +
                    "Edit androidgsm.config.json with real credentials and rebuild."
                MainActivity.log(msg)
                throw IllegalStateException(msg)
            }

            val deviceName = "${Build.MANUFACTURER}_${Build.MODEL}"
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")

            val config = Config(
                BACKEND_URL = json.getString("BACKEND_URL").trimEnd('/'),
                DEVICE_TOKEN = deviceName,
                BACKEND_BEARER = json.getString("BACKEND_BEARER"),
                PUSHER_APP_ID = json.getString("PUSHER_APP_ID"),
                PUSHER_KEY = json.getString("PUSHER_KEY"),
                PUSHER_SECRET = json.getString("PUSHER_SECRET"),
                PUSHER_CLUSTER = json.getString("PUSHER_CLUSTER")
            )

            // Ping backend once to verify URL is reachable
            Thread {
                try {
                    val url = URL("${config.BACKEND_URL}/health")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("Authorization", "Bearer ${config.BACKEND_BEARER}")
                    val code = conn.responseCode
                    if (code in 200..299) {
                        MainActivity.log("✅ Backend reachable: ${config.BACKEND_URL} (HTTP $code)")
                    } else {
                        MainActivity.log("⚠️ Backend responded HTTP $code — check BACKEND_URL and BACKEND_BEARER")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    MainActivity.log("❌ Backend unreachable: ${config.BACKEND_URL} — ${e.message}")
                }
            }.start()

            return config
        }
    }
}
