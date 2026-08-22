package com.sierra.voiceapp

import android.content.Context

/**
 * IP/puerto/token son configurables desde Ajustes porque el endpoint
 * del backend (/comando) todavía no está desplegado y puede cambiar.
 */
class SierraPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverIp: String
        get() = prefs.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
        set(value) = prefs.edit().putString(KEY_IP, value).apply()

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    fun baseUrl(): String = "http://$serverIp:$serverPort"

    companion object {
        private const val PREFS_NAME = "sierra_prefs"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
        private const val KEY_TOKEN = "sierra_token_poco"

        const val DEFAULT_IP = "100.86.158.55"
        const val DEFAULT_PORT = 8000
    }
}
