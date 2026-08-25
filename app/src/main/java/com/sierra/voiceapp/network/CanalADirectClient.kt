package com.sierra.voiceapp.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CanalADirectError(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Cliente para POST /tasks en Canal A, exclusivamente acciones de Nivel 1
 * disparadas directo desde la app (sin IA, sin confirmacion) --
 * emitted_by siempre "sierra-app-directo". El Executor revalida el nivel
 * real de cada accion contra su propio catalogo (INVARIANTE #9): si algo
 * ademas fuera Nivel 3, se rechaza solo, esta clase no puede forzarlo.
 */
class CanalADirectClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun crearTareaNivel1(
        action: String,
        params: Map<String, String>,
        onSuccess: () -> Unit,
        onError: (CanalADirectError) -> Unit
    ) {
        val paramsJson = JSONObject().apply { params.forEach { (k, v) -> put(k, v) } }
        val body = JSONObject().apply {
            put("action", action)
            put("params", paramsJson)
            put("permission_level", 1)
            put("emitted_by", "sierra-app-directo")
            put("origin", "pc")
            put("requires_confirmation", false)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/tasks")
            .header("X-Sierra-Token", token)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(CanalADirectError(e.message ?: "Error de red", null))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onError(CanalADirectError("HTTP ${it.code}", it.code))
                        return
                    }
                    onSuccess()
                }
            }
        })
    }
}
