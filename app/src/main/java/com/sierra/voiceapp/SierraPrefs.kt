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

    /** IP/puerto/token del puente de chat (Hermes local en sierra-pc, puerto
     * distinto a Canal A — ver com.sierra.voiceapp.network.SierraApiClient). */
    var chatIp: String
        get() = prefs.getString(KEY_CHAT_IP, DEFAULT_CHAT_IP) ?: DEFAULT_CHAT_IP
        set(value) = prefs.edit().putString(KEY_CHAT_IP, value).apply()

    var chatPort: Int
        get() = prefs.getInt(KEY_CHAT_PORT, DEFAULT_CHAT_PORT)
        set(value) = prefs.edit().putInt(KEY_CHAT_PORT, value).apply()

    var chatToken: String
        get() = prefs.getString(KEY_CHAT_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CHAT_TOKEN, value).apply()

    fun chatBaseUrl(): String = "http://$chatIp:$chatPort"

    /** SHA del commit del APK instalado actualmente, para saber si hay uno más nuevo en apk-latest. */
    var lastInstalledCommitSha: String
        get() = prefs.getString(KEY_LAST_APK_SHA, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_APK_SHA, value).apply()

    /** Si el servicio en primer plano debe avisar con notificación cuando
     * haya una confirmación de Nivel 3 pendiente. Activo por default. */
    var vigilanciaActiva: Boolean
        get() = prefs.getBoolean(KEY_VIGILANCIA, true)
        set(value) = prefs.edit().putBoolean(KEY_VIGILANCIA, value).apply()

    companion object {
        private const val PREFS_NAME = "sierra_prefs"
        private const val KEY_IP = "server_ip"
        private const val KEY_PORT = "server_port"
        private const val KEY_TOKEN = "sierra_token_poco"
        private const val KEY_LAST_APK_SHA = "last_installed_apk_sha"
        private const val KEY_VIGILANCIA = "vigilancia_confirmaciones_activa"
        private const val KEY_CHAT_IP = "chat_server_ip"
        private const val KEY_CHAT_PORT = "chat_server_port"
        private const val KEY_CHAT_TOKEN = "chat_server_token"

        // Canal A (FastAPI + SQLite en sierra-server), no sierra-pc directo.
        const val DEFAULT_IP = "100.71.115.36"
        const val DEFAULT_PORT = 8200

        // Puente /comando: Hermes local (glm-4.7-flash, solo chat) en sierra-pc.
        const val DEFAULT_CHAT_IP = "100.86.158.55"
        const val DEFAULT_CHAT_PORT = 8300
    }
}
