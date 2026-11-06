package com.nicitaacom.androidgsm

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Config(
    val BACKEND_URL: String,
    val DEVICE_TOKEN: String,
    val BACKEND_BEARER: String
)

class ConfigReader {
    companion object {
        // the scanned config first (ConfigStore, plan-05 task 4), the json in assets only when nothing was scanned
        fun readConfig(context: Context): Config {
            val deviceName = "${Build.MANUFACTURER}_${Build.MODEL}"
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")

            val scanned = ConfigStore.select(context)
            val config = if (scanned != null) {
                MainActivity.log("Config: scanned from /dialer (bearer ${scanned.bearer.take(8)}…)")
                Config(BACKEND_URL = scanned.backendUrl, DEVICE_TOKEN = deviceName, BACKEND_BEARER = scanned.bearer)
            } else {
                MainActivity.log("Config: androidgsm.config.json (nothing scanned yet - [SCAN CONFIG] reads the QR from /dialer's gear menu)")
                readJsonConfig(context, deviceName)
            }

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

        private fun readJsonConfig(context: Context, deviceName: String): Config {
            val inputStream = try {
                context.assets.open("androidgsm.config.json")
            } catch (e: Exception) {
                val msg = "CONFIG ERROR: androidgsm.config.json not found in assets.\n" +
                    "Scan the QR from /dialer's gear menu, or copy androidgsm.config.example.json → androidgsm.config.json and rebuild."
                MainActivity.log(msg)
                throw e
            }

            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)

            val missing = listOf("BACKEND_URL", "BACKEND_BEARER")
                .filter { key -> json.optString(key).isBlank() || json.optString(key).startsWith("your-") || json.optString(key) == "xxxx" }

            if (missing.isNotEmpty()) {
                val msg = "CONFIG ERROR: missing in androidgsm.config.json: ${missing.joinToString()}\n" +
                    "Scan the QR from /dialer's gear menu ([SCAN CONFIG] above) - it holds both."
                MainActivity.log(msg)
                throw IllegalStateException(msg)
            }

            return Config(
                BACKEND_URL = json.getString("BACKEND_URL").trimEnd('/'),
                DEVICE_TOKEN = deviceName,
                BACKEND_BEARER = json.getString("BACKEND_BEARER")
            )
        }
    }
}
