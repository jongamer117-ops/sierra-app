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

/** Resultado de /comando ya interpretado, sea cual sea el shape exacto que use el servidor. */
data class ComandoResponse(
    val mensaje: String,
    val matched: Boolean
)

class SierraApiError(message: String, val httpCode: Int? = null) : Exception(message)

/**
 * Cliente para el endpoint /comando de sierra-pc.
 *
 * El contrato final del servidor todavía no está construido, así que el
 * parseo de la respuesta es deliberadamente tolerante: acepta varios
 * nombres de campo comunes para el mensaje ("respuesta", "mensaje",
 * "message", "texto") y detecta "no matcheó nada" tanto por un campo
 * "matched": false explícito como por un campo "error". Si el backend
 * termina usando otros nombres, solo hay que ajustar parseComando().
 */
class SierraApiClient(
    private val baseUrl: String,
    private val token: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // El modelo local puede tardar en cargar en la GPU si estaba
        // descargado por inactividad; 30s se quedaba corto en pruebas reales.
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun enviarComando(
        texto: String,
        onSuccess: (ComandoResponse) -> Unit,
        onError: (SierraApiError) -> Unit
    ) {
        val body = JSONObject().apply { put("texto", texto) }
            .toString()
            .toRequestBody(jsonMediaType)

        val requestBuilder = Request.Builder()
            .url("$baseUrl/comando")
            .post(body)

        if (token.isNotBlank()) {
            requestBuilder.addHeader("X-Sierra-Token", token)
        }

        client.newCall(requestBuilder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(SierraApiError(e.message ?: "Error de red", null))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    val bodyString = it.body?.string().orEmpty()

                    if (!it.isSuccessful) {
                        onError(SierraApiError("HTTP ${it.code}", it.code))
                        return
                    }

                    try {
                        onSuccess(parseComando(bodyString))
                    } catch (e: Exception) {
                        onError(SierraApiError("Respuesta inesperada del servidor: ${e.message}", it.code))
                    }
                }
            }
        })
    }

    private fun parseComando(bodyString: String): ComandoResponse {
        if (bodyString.isBlank()) {
            return ComandoResponse(mensaje = "Sierra no devolvió ningún mensaje.", matched = false)
        }

        val json = try {
            JSONObject(bodyString)
        } catch (e: Exception) {
            // No es JSON: se muestra tal cual llegó.
            return ComandoResponse(mensaje = bodyString, matched = true)
        }

        val mensaje = firstNonEmpty(
            json.optString("respuesta", ""),
            json.optString("mensaje", ""),
            json.optString("message", ""),
            json.optString("texto", ""),
            json.optString("error", "")
        )

        val matched = when {
            json.has("matched") -> json.optBoolean("matched", true)
            json.has("error") -> false
            else -> true
        }

        return ComandoResponse(
            mensaje = mensaje.ifBlank { "Sierra no devolvió ningún mensaje." },
            matched = matched
        )
    }

    private fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() } ?: ""
}
