package com.sierra.voiceapp.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ConfirmacionPendiente(
    val confirmationId: String,
    val taskId: String,
    val confirmationDetail: String,
    val level: Int,
    val createdAt: String,
    val expiresAt: String
)

class CanalAApiError(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Cliente para los endpoints /confirmations/* de Canal A. Usa el header
 * X-Sierra-Token, el mismo que espera el resto de la API de Canal A.
 */
class CanalAConfirmationsClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun fetchPending(
        onSuccess: (List<ConfirmacionPendiente>) -> Unit,
        onError: (CanalAApiError) -> Unit
    ) {
        val request = Request.Builder()
            .url("$baseUrl/confirmations/pending")
            .header("X-Sierra-Token", token)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(CanalAApiError(e.message ?: "Error de red"))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onError(CanalAApiError("HTTP ${it.code}", it.code))
                        return
                    }
                    try {
                        onSuccess(parsePendingList(it.body?.string().orEmpty()))
                    } catch (e: Exception) {
                        onError(CanalAApiError("Respuesta inesperada del servidor: ${e.message}", it.code))
                    }
                }
            }
        })
    }

    fun approve(
        confirmationId: String,
        onSuccess: () -> Unit,
        onError: (CanalAApiError) -> Unit
    ) = postDecision("$baseUrl/confirmations/$confirmationId/approve", onSuccess, onError)

    fun reject(
        confirmationId: String,
        onSuccess: () -> Unit,
        onError: (CanalAApiError) -> Unit
    ) = postDecision("$baseUrl/confirmations/$confirmationId/reject", onSuccess, onError)

    private fun postDecision(
        url: String,
        onSuccess: () -> Unit,
        onError: (CanalAApiError) -> Unit
    ) {
        val body = JSONObject().apply { put("confirmed_by", "sierra-app") }
            .toString()
            .toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .header("X-Sierra-Token", token)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(CanalAApiError(e.message ?: "Error de red"))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onError(CanalAApiError("HTTP ${it.code}", it.code))
                        return
                    }
                    onSuccess()
                }
            }
        })
    }

    private fun parsePendingList(bodyString: String): List<ConfirmacionPendiente> {
        if (bodyString.isBlank()) return emptyList()
        val array = JSONArray(bodyString)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            ConfirmacionPendiente(
                confirmationId = obj.getString("confirmation_id"),
                taskId = obj.getString("task_id"),
                confirmationDetail = obj.getString("confirmation_detail"),
                level = obj.getInt("level"),
                createdAt = obj.getString("created_at"),
                expiresAt = obj.getString("expires_at")
            )
        }
    }
}
