package com.sierra.voiceapp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * IP/puerto/token son configurables desde Ajustes. El token se guarda cifrado
 * (EncryptedSharedPreferences) porque autentica contra Canal A, un servicio
 * que controla acciones reales del mundo real.
 */
class SierraPrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var serverIp: String
        get() = prefs.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
        set(value) = prefs.edit().putString(KEY_IP, value).apply()

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    fun hasToken(): Boolean = token.isNotBlank()

    fun baseUrl(): String = "http://$serverIp:$serverPort"

    /** SHA del commit del APK instalado actualmente, para saber si hay uno más nuevo en apk-latest. */
    var lastInstalledCommitSha: String
        get() = prefs.getString(KEY_LAST_APK_SHA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_APK_SHA, value).apply()

    companion object {
        private const val PREFS_NAME = "sierra_prefs"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
        private const val KEY_TOKEN = "sierra_token_poco"
        private const val KEY_LAST_APK_SHA = "last_installed_apk_sha"

        // Canal A (FastAPI + SQLite en sierra-server), no sierra-pc directo.
        const val DEFAULT_IP = "100.71.115.36"
        const val DEFAULT_PORT = 8200
    }
}
