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

class CanalATaskError(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Cliente para POST /tasks en Canal A. Usado por acciones Nivel 1
 * disparadas directo desde la app (boton o voz, sin decisor de IA en
 * el medio) -- emitted_by siempre "sierra-app-directo".
 */
class CanalATasksClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun crearTarea(
        action: String,
        params: Map<String, String>,
        level: Int,
        onSuccess: (String) -> Unit,
        onError: (CanalATaskError) -> Unit
    ) {
        val paramsJson = JSONObject().apply { params.forEach { (k, v) -> put(k, v) } }
        val body = JSONObject().apply {
            put("action", action)
            put("params", paramsJson)
            put("permission_level", level)
            put("emitted_by", "sierra-app-directo")
            // "origin" es el host que debe ejecutar la tarea, no quien la emitio
            // (eso ya lo dice emitted_by) -- el Executor de sierra-pc solo pide
            // tareas con origin="pc" (ver executor.py: ORIGIN). Con origin="app"
            // el Executor nunca las recogia: quedaban en pending para siempre,
            // sin error visible. Bug real encontrado en produccion 2026-08-24.
            put("origin", "pc")
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/tasks")
            .header("X-Sierra-Token", token)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(CanalATaskError(e.message ?: "Error de red"))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onError(CanalATaskError("HTTP ${it.code}", it.code))
                        return
                    }
                    try {
                        val taskId = JSONObject(it.body?.string().orEmpty()).getString("task_id")
                        onSuccess(taskId)
                    } catch (e: Exception) {
                        onError(CanalATaskError("Respuesta inesperada del servidor: ${e.message}", it.code))
                    }
                }
            }
        })
    }
}
