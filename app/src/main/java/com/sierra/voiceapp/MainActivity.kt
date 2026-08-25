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
import com.sierra.voiceapp.network.CanalADirectClient
import com.sierra.voiceapp.network.CanalADirectError
import com.sierra.voiceapp.network.ComandoResponse
import com.sierra.voiceapp.network.SierraApiClient
import com.sierra.voiceapp.network.SierraApiError
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

        actualizarChipBackend()
        binding.backendChipButton.setOnClickListener { alternarBackend() }

        binding.generarImagenButton.setOnClickListener { generarImagen() }
    }

    private fun generarImagen() {
        val descripcion = binding.imagenPromptEditText.text.toString().trim()
        if (descripcion.isEmpty()) {
            Toast.makeText(this, R.string.error_prompt_imagen_vacio, Toast.LENGTH_SHORT).show()
            return
        }
        if (!prefs.hasToken()) {
            Toast.makeText(this, R.string.error_falta_token, Toast.LENGTH_LONG).show()
            return
        }

        binding.respuestaTextView.text = getString(R.string.generando_imagen)

        // generate_image es Nivel 1 (reclasificado 2026-08-25: rapido, local,
        // bajo riesgo -- a diferencia de generate_video, que sigue en Nivel 3
        // y sigue yendo por Cortana + confirmacion). Directo a Canal A, sin
        // IA en el medio.
        val client = CanalADirectClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.crearTareaNivel1(
            action = "generate_image",
            params = mapOf("prompt" to descripcion),
            onSuccess = {
                runOnUiThread {
                    binding.respuestaTextView.text = getString(R.string.imagen_generada)
                    if (binding.leerSwitch.isChecked && ttsReady) {
                        textToSpeech?.speak(
                            getString(R.string.imagen_generada), TextToSpeech.QUEUE_FLUSH, null, "sierra_imagen"
                        )
                    }
                }
            },
            onError = { error -> runOnUiThread { mostrarErrorImagen(error) } }
        )
    }

    private fun mostrarErrorImagen(error: CanalADirectError) {
        binding.respuestaTextView.text = if (error.httpCode != null) {
            getString(R.string.error_servidor, error.httpCode)
        } else {
            getString(R.string.error_conexion, error.message ?: "")
        }
    }

    private fun actualizarChipBackend() {
        binding.backendChipButton.text = getString(
            if (prefs.usarCortana) R.string.chip_backend_cortana else R.string.chip_backend_local
        )
    }

    private fun alternarBackend() {
        val usarCortana = !prefs.usarCortana
        prefs.usarCortana = usarCortana
        if (usarCortana) {
            prefs.chatIp = SierraPrefs.CORTANA_CHAT_IP
            prefs.chatPort = SierraPrefs.CORTANA_CHAT_PORT
            prefs.chatToken = SierraPrefs.CORTANA_CHAT_TOKEN
        } else {
            prefs.chatIp = SierraPrefs.HERMES_LOCAL_CHAT_IP
            prefs.chatPort = SierraPrefs.HERMES_LOCAL_CHAT_PORT
            prefs.chatToken = SierraPrefs.HERMES_LOCAL_CHAT_TOKEN
        }
        actualizarChipBackend()
        Toast.makeText(
            this,
            if (usarCortana) R.string.backend_cambiado_cortana else R.string.backend_cambiado_local,
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onResume() {
        super.onResume()
        // Re-evalua cada vez que se vuelve a esta pantalla, por si el token
        // recien se configuro en Ajustes o se activo/desactivo la vigilancia.
        iniciarVigilanciaSiCorresponde()
        actualizarChipBackend()
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

    // --- Chat conversacional (/comando) -- todo pasa por Cortana ---

    private fun procesarTextoReconocido(textoOriginal: String) {
        binding.transcripcionEditText.setText(textoOriginal)
        enviarTexto(textoOriginal)
    }

    private fun enviarTexto(texto: String) {
        val textoLimpio = texto.trim()
        if (textoLimpio.isEmpty()) {
            Toast.makeText(this, R.string.error_texto_vacio, Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.enviarButton.isEnabled = false
        binding.respuestaTextView.text = getString(R.string.enviando)

        val client = SierraApiClient(baseUrl = prefs.chatBaseUrl(), token = prefs.chatToken)
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
}
