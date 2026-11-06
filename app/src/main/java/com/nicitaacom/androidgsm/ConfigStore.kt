package com.nicitaacom.androidgsm

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

// the config the phone scanned from /dialer's gear menu (plan-05 task 4): BACKEND_URL + BACKEND_BEARER (the userId)
// in SharedPreferences - ConfigReader reads these first, androidgsm.config.json only when nothing was scanned
data class ScannedConfig(val backendUrl: String, val bearer: String)

object ConfigStore {
    private const val PREFS_NAME = "androidgsm_config"
    private const val KEY_BACKEND_URL = "BACKEND_URL"
    private const val KEY_BACKEND_BEARER = "BACKEND_BEARER"

    fun select(context: Context): ScannedConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val backendUrl = prefs.getString(KEY_BACKEND_URL, null)?.trim()?.trimEnd('/') ?: return null
        val bearer = prefs.getString(KEY_BACKEND_BEARER, null)?.trim() ?: return null
        if (backendUrl.isBlank() || bearer.isBlank()) return null
        return ScannedConfig(backendUrl, bearer)
    }

    // the QR's text is the json the gear menu generated: {"BACKEND_URL":"https://...","BACKEND_BEARER":"<userId>"};
    // null = the text is not that json (a QR of something else)
    fun parse(qrText: String): ScannedConfig? {
        val json = runCatching { JSONObject(qrText) }.getOrNull() ?: return null
        val backendUrl = json.optString(KEY_BACKEND_URL).trim().trimEnd('/')
        val bearer = json.optString(KEY_BACKEND_BEARER).trim()
        if (!backendUrl.startsWith("http") || bearer.isBlank()) return null
        return ScannedConfig(backendUrl, bearer)
    }

    fun insert(context: Context, config: ScannedConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_BACKEND_URL, config.backendUrl)
            putString(KEY_BACKEND_BEARER, config.bearer)
        }
    }
}
