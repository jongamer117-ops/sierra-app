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

class CanalADirectError(message: String, val httpCode: Int? = null) : Exception(message)

/** Fila de GET /tasks. status: "pending" | "done". result: "success" |
 * "error" | "rejected" (null mientras sigue pendiente). */
data class EstadoTarea(
    val taskId: String,
    val status: String,
    val result: String?,
    val resultDetail: String?
)

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

    /** onSuccess recibe el task_id: sin el, la app no puede seguir la tarea y
     * solo sabe que Canal A la guardo -- que no es lo mismo que ejecutada. */
    fun crearTareaNivel1(
        action: String,
        params: Map<String, Any>,
        onSuccess: (String) -> Unit,
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
                    try {
                        onSuccess(JSONObject(it.body?.string().orEmpty()).getString("task_id"))
                    } catch (e: Exception) {
                        onError(CanalADirectError("Respuesta inesperada del servidor: ${e.message}", it.code))
                    }
                }
            }
        })
    }

    /**
     * Estado de una tarea ya creada. Canal A no expone GET /tasks/{id}, asi
     * que se pide la lista corta y se filtra por task_id -- por posicion no
     * sirve: /tasks trae las de todos los origenes, no solo las de la app.
     */
    fun consultarEstado(
        taskId: String,
        onResult: (EstadoTarea) -> Unit,
        onError: (CanalADirectError) -> Unit
    ) {
        val request = Request.Builder()
            .url("$baseUrl/tasks?limit=20")
            .header("X-Sierra-Token", token)
            .get()
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
                    try {
                        val encontrada = parseLista(it.body?.string().orEmpty())
                            .firstOrNull { t -> t.taskId == taskId }
                        if (encontrada == null) {
                            onError(CanalADirectError("task_id no esta en las ultimas 20", it.code))
                        } else {
                            onResult(encontrada)
                        }
                    } catch (e: Exception) {
                        onError(CanalADirectError("Respuesta inesperada del servidor: ${e.message}", it.code))
                    }
                }
            }
        })
    }

    /**
     * Igual que consultarEstado pero para varias tareas del mismo lote
     * (variaciones de imagen) en un solo GET, en vez de una por tarea cada
     * tick. limit mas alto que consultarEstado: un lote de 4 necesita mas
     * margen para que ninguna se caiga de la ventana antes de terminar.
     *
     * Un task_id que no aparece en la ventana no es error -- se trata igual
     * que "sigue pendiente" (nunca se afirma terminado sin verlo con
     * status=done). onResult devuelve solo lo que encontro, puede venir
     * incompleto.
     */
    fun consultarEstados(
        taskIds: Set<String>,
        onResult: (Map<String, EstadoTarea>) -> Unit,
        onError: (CanalADirectError) -> Unit
    ) {
        val request = Request.Builder()
            .url("$baseUrl/tasks?limit=40")
            .header("X-Sierra-Token", token)
            .get()
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
                    try {
                        val encontradas = parseLista(it.body?.string().orEmpty())
                            .filter { t -> t.taskId in taskIds }
                            .associateBy { t -> t.taskId }
                        onResult(encontradas)
                    } catch (e: Exception) {
                        onError(CanalADirectError("Respuesta inesperada del servidor: ${e.message}", it.code))
                    }
                }
            }
        })
    }

    private fun parseLista(raw: String): List<EstadoTarea> {
        if (raw.isBlank()) return emptyList()
        val array = if (raw.trim().startsWith("[")) JSONArray(raw) else {
            val obj = JSONObject(raw)
            when {
                obj.has("tasks") -> obj.getJSONArray("tasks")
                obj.has("items") -> obj.getJSONArray("items")
                else -> JSONArray()
            }
        }
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            EstadoTarea(
                taskId = o.optString("task_id"),
                status = o.optString("status"),
                // optString devuelve la cadena "null" para un null de JSON, no
                // null -- de ahi el filtro, si no "No salio. null" seria posible.
                result = o.optString("result").takeIf { s -> s.isNotBlank() && s != "null" },
                resultDetail = o.optString("result_detail").takeIf { s -> s.isNotBlank() && s != "null" }
            )
        }
    }
}
