package com.sierra.voiceapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sierra.voiceapp.databinding.ActivityAccionesBinding
import com.sierra.voiceapp.network.CanalATaskError
import com.sierra.voiceapp.network.CanalATasksClient
import java.text.Normalizer
import java.util.Locale

/**
 * Acciones de Nivel 1 disparadas directo por el usuario (voz o boton),
 * sin ningun decisor de IA en el medio -- por eso solo cubre acciones
 * automaticas y ya fijas del catalogo del Executor.
 */
class AccionesActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityAccionesBinding
    private lateinit var prefs: SierraPrefs
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                Toast.makeText(this, R.string.error_no_mic_permission, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccionesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@AccionesActivity)
            }
        }

        binding.hablarButton.setOnClickListener { onMicPressed() }

        binding.verHoraButton.setOnClickListener {
            ejecutar("get_time", emptyMap(), level = 1, resumen = getString(R.string.btn_ver_hora))
        }
        binding.abrirFirefoxButton.setOnClickListener {
            ejecutar(
                "open_app", mapOf("app_name" to "firefox"), level = 1,
                resumen = getString(R.string.btn_abrir_firefox)
            )
        }
        binding.buscarYoutubeButton.setOnClickListener {
            val query = binding.busquedaEditText.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, R.string.error_texto_vacio, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ejecutar(
                "search_youtube", mapOf("query" to query), level = 1,
                resumen = getString(R.string.acciones_resumen_busqueda, query)
            )
        }
        binding.verArchivoButton.setOnClickListener {
            val nombre = binding.archivoEditText.text.toString().trim()
            if (nombre.isEmpty()) {
                Toast.makeText(this, R.string.error_texto_vacio, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ejecutar(
                "view_file", mapOf("path" to "$RUTA_DESCARGAS/$nombre"), level = 1,
                resumen = getString(R.string.acciones_resumen_archivo, nombre)
            )
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    private fun onMicPressed() {
        if (isListening) {
            speechRecognizer?.stopListening()
            return
        }
        if (speechRecognizer == null) {
            Toast.makeText(this, R.string.error_no_recognition, Toast.LENGTH_LONG).show()
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun setListeningState(listening: Boolean) {
        isListening = listening
        binding.hablarButton.isActivated = listening
    }

    private fun interpretarYEjecutar(textoOriginal: String) {
        val texto = quitarAcentos(textoOriginal.lowercase(Locale.getDefault()))

        when {
            texto.contains("hora") ->
                ejecutar("get_time", emptyMap(), level = 1, resumen = getString(R.string.btn_ver_hora))

            texto.contains("firefox") ->
                ejecutar(
                    "open_app", mapOf("app_name" to "firefox"), level = 1,
                    resumen = getString(R.string.btn_abrir_firefox)
                )

            texto.contains("youtube") || texto.contains("busca") || texto.contains("buscar") -> {
                val query = extraerConsultaBusqueda(texto)
                if (query.isBlank()) {
                    mostrarNoEntendido(textoOriginal)
                } else {
                    ejecutar(
                        "search_youtube", mapOf("query" to query), level = 1,
                        resumen = getString(R.string.acciones_resumen_busqueda, query)
                    )
                }
            }

            else -> mostrarNoEntendido(textoOriginal)
        }
    }

    private fun extraerConsultaBusqueda(texto: String): String {
        var resultado = texto
        listOf(
            "busca en youtube", "buscá en youtube", "en youtube", "buscar", "busca",
            "buscá", "youtube", "pon", "poné", "reproduce"
        ).forEach { resultado = resultado.replace(it, "") }
        return resultado.trim()
    }

    private fun quitarAcentos(texto: String): String {
        val normalizado = Normalizer.normalize(texto, Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{Mn}"), "")
    }

    private fun mostrarNoEntendido(texto: String) {
        binding.resultadoTextView.text = getString(R.string.acciones_no_entendido, texto)
    }

    private fun ejecutar(action: String, params: Map<String, String>, level: Int, resumen: String) {
        if (!prefs.hasToken()) {
            Toast.makeText(this, R.string.error_falta_token, Toast.LENGTH_LONG).show()
            return
        }
        binding.resultadoTextView.text = getString(R.string.acciones_enviando, resumen)

        val client = CanalATasksClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.crearTarea(
            action = action,
            params = params,
            level = level,
            onSuccess = {
                runOnUiThread {
                    binding.resultadoTextView.text = getString(R.string.acciones_enviado, resumen)
                }
            },
            onError = { error ->
                runOnUiThread {
                    val mensaje = if (error.httpCode != null) {
                        getString(R.string.error_servidor, error.httpCode)
                    } else {
                        getString(R.string.error_conexion, error.message ?: "")
                    }
                    binding.resultadoTextView.text = mensaje
                }
            }
        )
    }

    // --- RecognitionListener ---

    override fun onReadyForSpeech(params: Bundle?) {
        setListeningState(true)
    }

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) = Unit

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        setListeningState(false)
    }

    override fun onError(error: Int) {
        setListeningState(false)
    }

    override fun onResults(results: Bundle?) {
        setListeningState(false)
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val texto = matches?.firstOrNull().orEmpty()
        if (texto.isNotBlank()) {
            binding.transcripcionTextView.text = texto
            interpretarYEjecutar(texto)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val RUTA_DESCARGAS = "/home/jonathanf/Downloads"
    }
}
