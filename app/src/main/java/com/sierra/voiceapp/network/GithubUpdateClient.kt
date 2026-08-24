package com.sierra.voiceapp.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class UpdateError(message: String) : Exception(message)

/**
 * Chequea y descarga el APK debug que el workflow de GitHub Actions publica
 * en la rama `apk-latest` (ver .github/workflows/build-apk.yml). No usa la
 * API de Releases porque este repo no publica releases, solo esa rama.
 */
class GithubUpdateClient(
    private val owner: String = "jongamer117-ops",
    private val repo: String = "sierra-app",
    private val apkBranch: String = "apk-latest",
    private val apkFileName: String = "sierra-voice-app-debug.apk"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun fetchLatestCommitSha(
        onSuccess: (String) -> Unit,
        onError: (UpdateError) -> Unit
    ) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/commits/$apkBranch")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(UpdateError(e.message ?: "Error de red consultando GitHub"))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onError(UpdateError("GitHub respondió HTTP ${it.code}"))
                        return
                    }
                    try {
                        val sha = JSONObject(it.body?.string().orEmpty()).getString("sha")
                        onSuccess(sha)
                    } catch (e: Exception) {
                        onError(UpdateError("Respuesta inesperada de GitHub: ${e.message}"))
                    }
                }
            }
        })
    }

    fun downloadApk(
        destino: File,
        onSuccess: (File) -> Unit,
        onError: (UpdateError) -> Unit
    ) {
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/$owner/$repo/$apkBranch/$apkFileName")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(UpdateError(e.message ?: "Error de red descargando el APK"))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        onError(UpdateError("GitHub respondió HTTP ${it.code}"))
                        return
                    }
                    try {
                        destino.outputStream().use { out ->
                            it.body?.byteStream()?.copyTo(out)
                                ?: throw UpdateError("Respuesta sin contenido")
                        }
                        onSuccess(destino)
                    } catch (e: Exception) {
                        onError(UpdateError("No se pudo guardar el APK: ${e.message}"))
                    }
                }
            }
        })
    }
}
