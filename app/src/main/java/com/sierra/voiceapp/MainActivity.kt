package com.sierra.voiceapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sierra.voiceapp.databinding.ActivityMainBinding
import com.sierra.voiceapp.network.CanalATaskError
import com.sierra.voiceapp.network.CanalATasksClient
import com.sierra.voiceapp.network.ComandoResponse
import com.sierra.voiceapp.network.SierraApiClient
import com.sierra.voiceapp.network.SierraApiError
import java.text.Normalizer
import java.util.Locale

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SierraPrefs

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var isListening = false

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                Toast.makeText(this, R.string.error_no_mic_permission, Toast.LENGTH_LONG).show()
            }
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Se pidio o no, arranca (o no) el servicio segun quedo el permiso --
            // no hay nada mas que hacer con el resultado aqui.
            iniciarVigilanciaSiCorresponde()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                ttsReady = true
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(this@MainActivity)
            }
        }

        binding.hablarButton.setOnClickListener { onMicPressed() }
        binding.enviarButton.setOnClickListener { enviarTexto(binding.transcripcionEditText.text.toString()) }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.confirmacionesButton.setOnClickListener {
            startActivity(Intent(this, ConfirmacionesActivity::class.java))
        }

        binding.verHoraButton.setOnClickListener {
            ejecutarAccionDirecta("get_time", emptyMap(), resumen = getString(R.string.btn_ver_hora))
        }
        binding.abrirFirefoxButton.setOnClickListener {
            ejecutarAccionDirecta(
                "open_app", mapOf("app_name" to "firefox"),
                resumen = getString(R.string.btn_abrir_firefox)
            )
        }
        binding.buscarYoutubeButton.setOnClickListener {
            val query = binding.busquedaEditText.text.toString().trim()
            if (query.isEmpty()) {
                Toast.makeText(this, R.string.error_texto_vacio, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ejecutarAccionDirecta(
                "search_youtube", mapOf("query" to query),
                resumen = getString(R.string.acciones_resumen_busqueda, query)
            )
        }
        binding.verArchivoButton.setOnClickListener {
            val nombre = binding.archivoEditText.text.toString().trim()
            if (nombre.isEmpty()) {
                Toast.makeText(this, R.string.error_texto_vacio, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ejecutarAccionDirecta(
                "view_file", mapOf("path" to "$RUTA_DESCARGAS/$nombre"),
                resumen = getString(R.string.acciones_resumen_archivo, nombre)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evalua cada vez que se vuelve a esta pantalla, por si el token
        // recien se configuro en Ajustes o se activo/desactivo la vigilancia.
        iniciarVigilanciaSiCorresponde()
    }

    private fun iniciarVigilanciaSiCorresponde() {
        if (!prefs.hasToken() || !prefs.vigilanciaActiva) {
            stopService(Intent(this, ConfirmacionesForegroundService::class.java))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, ConfirmacionesForegroundService::class.java))
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
        binding.hablarButton.contentDescription =
            getString(if (listening) R.string.btn_escuchando else R.string.btn_hablar)
    }

    // --- Comando rapido (Nivel 1, sin IA) vs. chat conversacional (/comando) ---

    private fun procesarTextoReconocido(textoOriginal: String) {
        binding.transcripcionEditText.setText(textoOriginal)

        val comando = interpretarComandoRapido(textoOriginal)
        if (comando != null) {
            ejecutarAccionDirecta(comando.action, comando.params, comando.resumen)
        } else {
            enviarTexto(textoOriginal)
        }
    }

    private data class ComandoRapido(val action: String, val params: Map<String, String>, val resumen: String)

    private fun interpretarComandoRapido(textoOriginal: String): ComandoRapido? {
        val texto = quitarAcentos(textoOriginal.lowercase(Locale.getDefault()))

        return when {
            texto.contains("hora") ->
                ComandoRapido("get_time", emptyMap(), getString(R.string.btn_ver_hora))

            texto.contains("firefox") ->
                ComandoRapido("open_app", mapOf("app_name" to "firefox"), getString(R.string.btn_abrir_firefox))

            texto.contains("youtube") || texto.contains("busca") || texto.contains("buscar") -> {
                val query = extraerConsultaBusqueda(texto)
                if (query.isBlank()) null
                else ComandoRapido(
                    "search_youtube", mapOf("query" to query),
                    getString(R.string.acciones_resumen_busqueda, query)
                )
            }

            else -> null
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

    private fun ejecutarAccionDirecta(action: String, params: Map<String, String>, resumen: String) {
        if (!prefs.hasToken()) {
            Toast.makeText(this, R.string.error_falta_token, Toast.LENGTH_LONG).show()
            return
        }
        binding.respuestaTextView.text = getString(R.string.acciones_enviando, resumen)

        val level = if (action == "open_app") 2 else 1
        if (level != 1) {
            // Nivel 2 requiere confirmation_token local, que esta pantalla no
            // emite -- por ahora solo se disparan acciones Nivel 1 directo.
            binding.respuestaTextView.text = getString(R.string.acciones_no_entendido, resumen)
            return
        }

        val client = CanalATasksClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.crearTarea(
            action = action,
            params = params,
            level = level,
            onSuccess = {
                runOnUiThread {
                    binding.respuestaTextView.text = getString(R.string.acciones_enviado, resumen)
                    if (binding.leerSwitch.isChecked && ttsReady) {
                        textToSpeech?.speak(resumen, TextToSpeech.QUEUE_FLUSH, null, "sierra_accion")
                    }
                }
            },
            onError = { error -> runOnUiThread { mostrarErrorTarea(error) } }
        )
    }

    private fun mostrarErrorTarea(error: CanalATaskError) {
        val mensaje = if (error.httpCode != null) {
            getString(R.string.error_servidor, error.httpCode)
        } else {
            getString(R.string.error_conexion, error.message ?: "")
        }
        binding.respuestaTextView.text = mensaje
    }

    // --- Chat conversacional (/comando) ---

    private fun enviarTexto(texto: String) {
        val textoLimpio = texto.trim()
        if (textoLimpio.isEmpty()) {
            Toast.makeText(this, R.string.error_texto_vacio, Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.enviarButton.isEnabled = false
        binding.respuestaTextView.text = getString(R.string.enviando)

        val client = SierraApiClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.enviarComando(
            texto = textoLimpio,
            onSuccess = { respuesta -> runOnUiThread { mostrarRespuesta(respuesta) } },
            onError = { error -> runOnUiThread { mostrarError(error) } }
        )
    }

    private fun mostrarRespuesta(respuesta: ComandoResponse) {
        binding.progressBar.visibility = android.view.View.GONE
        binding.enviarButton.isEnabled = true

        val texto = if (respuesta.matched) respuesta.mensaje
        else "${getString(R.string.error_sin_match)}\n\n${respuesta.mensaje}"

        binding.respuestaTextView.text = texto

        if (binding.leerSwitch.isChecked && ttsReady) {
            textToSpeech?.speak(respuesta.mensaje, TextToSpeech.QUEUE_FLUSH, null, "sierra_respuesta")
        }
    }

    private fun mostrarError(error: SierraApiError) {
        binding.progressBar.visibility = android.view.View.GONE
        binding.enviarButton.isEnabled = true

        val mensaje = if (error.httpCode != null) {
            getString(R.string.error_servidor, error.httpCode)
        } else {
            getString(R.string.error_conexion, error.message ?: "")
        }
        binding.respuestaTextView.text = mensaje
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
            procesarTextoReconocido(texto)
        }
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val RUTA_DESCARGAS = "/home/jonathanf/Downloads"
    }
}
