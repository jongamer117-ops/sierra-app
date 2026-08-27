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

class CanalATaskError(message: String, val httpCode: Int? = null) : Exception(message)

data class EstadoTarea(
    val taskId: String,
    val status: String,
    val result: String?,
    val resultDetail: String?
)

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
            put("origin", "app")
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

    fun consultarEstado(
        taskId: String,
        onResult: (EstadoTarea) -> Unit,
        onError: (CanalATaskError) -> Unit
    ) {
        val request = Request.Builder()
            .url("$baseUrl/tasks?limit=20")
            .header("X-Sierra-Token", token)
            .get()
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
                        val raw = it.body?.string().orEmpty()
                        val encontrada = parseLista(raw).firstOrNull { t -> t.taskId == taskId }
                        if (encontrada == null) {
                            onError(CanalATaskError("task_id no está en las últimas 20"))
                        } else {
                            onResult(encontrada)
                        }
                    } catch (e: Exception) {
                        onError(CanalATaskError("Respuesta inesperada del servidor: ${e.message}", it.code))
                    }
                }
            }
        })
    }

    private fun parseLista(raw: String): List<EstadoTarea> {
        val array = when {
            raw.isBlank() -> JSONArray()
            raw.trim().startsWith("[") -> JSONArray(raw)
            else -> {
                val obj = JSONObject(raw)
                when {
                    obj.has("tasks") -> obj.getJSONArray("tasks")
                    obj.has("items") -> obj.getJSONArray("items")
                    obj.has("data") -> obj.getJSONArray("data")
                    else -> JSONArray()
                }
            }
        }
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            EstadoTarea(
                taskId = o.optString("task_id"),
                status = o.optString("status"),
                result = o.optString("result", null).takeIf { !it.isNullOrBlank() && it != "null" },
                resultDetail = o.optString("result_detail", null).takeIf { !it.isNullOrBlank() && it != "null" }
            )
        }
    }
}
