package com.sierra.voiceapp

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sierra.voiceapp.databinding.ActivityMainBinding
import com.sierra.voiceapp.network.CanalAConfirmationsClient
import com.sierra.voiceapp.network.CanalATaskError
import com.sierra.voiceapp.network.CanalATasksClient
import com.sierra.voiceapp.network.ComandoResponse
import com.sierra.voiceapp.network.SierraApiClient
import com.sierra.voiceapp.network.SierraApiError
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SierraPrefs
    private lateinit var voz: VozSierra

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var accionesExpandidas = false

    private var pulseAnimator: ObjectAnimator? = null
    private var lastSpokenKey: String? = null

    private var taskIdActivo: String? = null
    private var pollEnCurso = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pollTareaRunnable = object : Runnable {
        override fun run() {
            val id = taskIdActivo ?: return
            consultarTarea(id)
            if (taskIdActivo != null) {
                mainHandler.postDelayed(this, POLL_TAREA_MS)
            }
        }
    }

    private val cuentaAtrasRunnable = object : Runnable {
        override fun run() {
            if (SierraPresence.snapshotActual().estado == EstadoSierra.ESPERANDO_SI) {
                SierraPresence.tickConfirmacion()
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val pollConfirmacionesForeground = object : Runnable {
        override fun run() {
            sondearConfirmacionesDesdeActivity()
            mainHandler.postDelayed(this, POLL_CONFIRMACIONES_MS)
        }
    }

    private val presenceListener: (SierraPresence.Snapshot) -> Unit = { snap ->
        aplicarSnapshot(snap)
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else Toast.makeText(this, R.string.error_no_mic_permission, Toast.LENGTH_LONG).show()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            iniciarVigilanciaSiCorresponde()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)
        voz = VozSierra(this)
        SierraPresence.inicializar(applicationContext)

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
        binding.accionesHeader.setOnClickListener { toggleAcciones() }

        binding.verHoraButton.setOnClickListener {
            ejecutarAccionDirecta("get_time", emptyMap(), getString(R.string.acuse_hora), "voz_hora")
        }
        binding.abrirFirefoxButton.setOnClickListener {
            ejecutarAccionDirecta(
                "open_app", mapOf("app_name" to "firefox"),
                getString(R.string.acuse_firefox), "voz_firefox"
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
                getString(R.string.acuse_youtube, query),
                clipFijo = null,
                textoLibreVoz = getString(R.string.acuse_youtube, query)
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
                getString(R.string.acuse_archivo, nombre),
                clipFijo = null,
                textoLibreVoz = getString(R.string.acuse_archivo, nombre)
            )
        }

        evaluarCorteInicial()
    }

    override fun onResume() {
        super.onResume()
        SierraPresence.observar(presenceListener)
        iniciarVigilanciaSiCorresponde()
        if (!prefs.vigilanciaActiva || !prefs.hasToken()) {
            mainHandler.removeCallbacks(pollConfirmacionesForeground)
            mainHandler.post(pollConfirmacionesForeground)
        }
        if (taskIdActivo != null && !pollEnCurso) {
            iniciarPollTarea()
        }
        mainHandler.removeCallbacks(cuentaAtrasRunnable)
        mainHandler.post(cuentaAtrasRunnable)
    }

    override fun onPause() {
        SierraPresence.dejarDeObservar(presenceListener)
        detenerPollTarea(soloPausa = true)
        mainHandler.removeCallbacks(pollConfirmacionesForeground)
        mainHandler.removeCallbacks(cuentaAtrasRunnable)
        super.onPause()
    }

    private fun evaluarCorteInicial() {
        if (!prefs.hasToken()) {
            SierraPresence.degradar(MotivoCorte.SIN_TOKEN)
        } else {
            SierraPresence.limpiarCorte()
            SierraPresence.entrar(EstadoSierra.QUIETA)
        }
    }

    private fun toggleAcciones() {
        accionesExpandidas = !accionesExpandidas
        binding.accionesManualesContainer.visibility = if (accionesExpandidas) View.VISIBLE else View.GONE
        binding.accionesChevron.text = if (accionesExpandidas) "▾" else "▸"
    }

    private fun aplicarSnapshot(snap: SierraPresence.Snapshot) {
        binding.estadoTextView.text = snap.linea
        if (!snap.detalle.isNullOrBlank() && snap.estado == EstadoSierra.LISTA) {
            binding.respuestaTextView.text = snap.detalle
        }
        pintarAnillo(snap.estado)
        actualizarPulso(snap.estado == EstadoSierra.PENSANDO)
        binding.confirmacionesBadge.apply {
            if (snap.confirmaciones <= 0) visibility = View.GONE
            else {
                visibility = View.VISIBLE
                text = snap.confirmaciones.toString()
            }
        }
        hablarSiCorresponde(snap)
    }

    private fun pintarAnillo(estado: EstadoSierra) {
        val colorRes = when (estado) {
            EstadoSierra.QUIETA -> R.color.sierra_accent_dim
            EstadoSierra.ESCUCHANDO -> R.color.sierra_listening
            EstadoSierra.PENSANDO -> R.color.sierra_accent
            EstadoSierra.EN_COLA -> R.color.sierra_primary
            EstadoSierra.ESPERANDO_SI -> R.color.sierra_error
            EstadoSierra.LISTA -> R.color.sierra_accent
            EstadoSierra.CORTA -> R.color.sierra_text_secondary
        }
        binding.estadoAnillo.background.mutate().setTint(ContextCompat.getColor(this, colorRes))
    }

    private fun actualizarPulso(activo: Boolean) {
        if (activo) {
            if (pulseAnimator == null) {
                pulseAnimator = ObjectAnimator.ofFloat(binding.estadoAnillo, View.ALPHA, 1f, 0.25f).apply {
                    duration = 900
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            binding.estadoAnillo.alpha = 1f
        }
    }

    private fun hablarSiCorresponde(snap: SierraPresence.Snapshot) {
        if (!binding.leerSwitch.isChecked) return
        val key = "${snap.estado}:${snap.linea}"
        if (key == lastSpokenKey) return
        lastSpokenKey = key
        if (snap.estado == EstadoSierra.QUIETA && key.endsWith(getString(R.string.presencia_quieta))) return
        val clip = VozSierra.clipParaEstado(snap.estado)
        if (clip != null && voz.clipDisponible(clip)) voz.decir(clip)
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

    private fun sondearConfirmacionesDesdeActivity() {
        if (!prefs.hasToken()) {
            SierraPresence.confirmacionesVivas(0, null)
            SierraPresence.degradar(MotivoCorte.SIN_TOKEN)
            return
        }
        val client = CanalAConfirmationsClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.fetchPending(
            onSuccess = { pendientes ->
                runOnUiThread {
                    val proxima = pendientes.minByOrNull { it.expiresAt }
                    SierraPresence.confirmacionesVivas(
                        cantidad = pendientes.size,
                        expiraEnSegundos = proxima?.let { segundosHasta(it.expiresAt) }
                    )
                }
            },
            onError = { }
        )
    }

    private fun segundosHasta(expiresAt: String): Long {
        return try {
            val exp = Instant.parse(expiresAt)
            (exp.epochSecond - Instant.now().epochSecond).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
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
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) startListening() else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
        binding.hablarButton.contentDescription = getString(if (listening) R.string.btn_escuchando else R.string.btn_hablar)
        if (listening) SierraPresence.entrar(EstadoSierra.ESCUCHANDO)
        else if (SierraPresence.snapshotActual().estado == EstadoSierra.ESCUCHANDO) SierraPresence.entrar(EstadoSierra.QUIETA)
    }

    private fun procesarTextoReconocido(textoOriginal: String) {
        binding.transcripcionEditText.setText(textoOriginal)
        val comando = interpretarComandoRapido(textoOriginal)
        if (comando != null) ejecutarAccionDirecta(comando.action, comando.params, comando.acuse, comando.clip, comando.textoLibre)
        else enviarTexto(textoOriginal)
    }

    private data class ComandoRapido(
        val action: String,
        val params: Map<String, String>,
        val acuse: String,
        val clip: String?,
        val textoLibre: String?
    )

    private fun interpretarComandoRapido(textoOriginal: String): ComandoRapido? {
        val texto = quitarAcentos(textoOriginal.lowercase(Locale.getDefault()))
        return when {
            texto.contains("hora") ->
                ComandoRapido("get_time", emptyMap(), getString(R.string.acuse_hora), "voz_hora", null)
            texto.contains("firefox") ->
                ComandoRapido("open_app", mapOf("app_name" to "firefox"), getString(R.string.acuse_firefox), "voz_firefox", null)
            texto.contains("youtube") || texto.contains("busca") || texto.contains("buscar") -> {
                val query = extraerConsultaBusqueda(texto)
                if (query.isBlank()) null
                else ComandoRapido("search_youtube", mapOf("query" to query), getString(R.string.acuse_youtube, query), null, getString(R.string.acuse_youtube, query))
            }
            else -> null
        }
    }

    private fun extraerConsultaBusqueda(texto: String): String {
        var resultado = texto
        listOf("busca en youtube", "buscá en youtube", "en youtube", "buscar", "busca", "buscá", "youtube", "pon", "poné", "reproduce").forEach { resultado = resultado.replace(it, "") }
        return resultado.trim()
    }

    private fun quitarAcentos(texto: String): String {
        val normalizado = Normalizer.normalize(texto, Normalizer.Form.NFD)
        return normalizado.replace(Regex("\\p{Mn}"), "")
    }

    private fun ejecutarAccionDirecta(
        action: String,
        params: Map<String, String>,
        acuse: String,
        clipFijo: String? = null,
        textoLibreVoz: String? = null
    ) {
        if (!prefs.hasToken()) {
            SierraPresence.degradar(MotivoCorte.SIN_TOKEN)
            Toast.makeText(this, R.string.error_falta_token, Toast.LENGTH_LONG).show()
            return
        }
        SierraPresence.limpiarCorte()
        SierraPresence.entrar(EstadoSierra.PENSANDO, linea = acuse)
        if (binding.leerSwitch.isChecked) {
            when {
                clipFijo != null && voz.clipDisponible(clipFijo) -> voz.decir(clipFijo)
                textoLibreVoz != null -> voz.decirTextoLibre(textoLibreVoz)
            }
        }
        val level = if (action == "open_app") 2 else 1
        if (level != 1) {
            SierraPresence.entrar(
                EstadoSierra.LISTA,
                detalle = getString(R.string.acciones_no_entendido, acuse),
                linea = getString(R.string.acuse_fallo, getString(R.string.acciones_no_entendido, acuse))
            )
            return
        }
        val client = CanalATasksClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.crearTarea(
            action = action,
            params = params,
            level = level,
            onSuccess = { taskId ->
                runOnUiThread {
                    taskIdActivo = taskId
                    SierraPresence.entrar(EstadoSierra.EN_COLA, linea = getString(R.string.acuse_encolado))
                    if (binding.leerSwitch.isChecked && voz.clipDisponible("voz_encolado")) voz.decir("voz_encolado")
                    iniciarPollTarea()
                }
            },
            onError = { error -> runOnUiThread { mostrarErrorTarea(error) } }
        )
    }

    private fun iniciarPollTarea() {
        pollEnCurso = true
        mainHandler.removeCallbacks(pollTareaRunnable)
        mainHandler.post(pollTareaRunnable)
    }

    private fun detenerPollTarea(soloPausa: Boolean) {
        mainHandler.removeCallbacks(pollTareaRunnable)
        pollEnCurso = false
        if (!soloPausa) taskIdActivo = null
    }

    private fun consultarTarea(taskId: String) {
        val client = CanalATasksClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.consultarEstado(
            taskId = taskId,
            onResult = { estado ->
                runOnUiThread {
                    if (taskIdActivo != taskId) return@runOnUiThread
                    if (estado.status != "done") return@runOnUiThread
                    val ok = estado.result == "success"
                    val linea = if (ok) getString(R.string.acuse_ejecutado) else getString(R.string.acuse_fallo, estado.resultDetail ?: "")
                    SierraPresence.entrar(EstadoSierra.LISTA, detalle = estado.resultDetail, linea = linea)
                    if (binding.leerSwitch.isChecked) {
                        val clip = if (ok) "voz_ejecutado" else "voz_fallo"
                        if (voz.clipDisponible(clip)) voz.decir(clip)
                        else if (!ok) voz.decirTextoLibre(linea)
                    }
                    detenerPollTarea(soloPausa = false)
                }
            },
            onError = { }
        )
    }

    private fun mostrarErrorTarea(error: CanalATaskError) {
        val mensaje = if (error.httpCode != null) getString(R.string.error_servidor, error.httpCode)
        else getString(R.string.error_conexion, error.message ?: "")
        SierraPresence.degradar(MotivoCorte.SIN_PC, mensaje)
        binding.respuestaTextView.text = mensaje
        detenerPollTarea(soloPausa = false)
    }

    private fun enviarTexto(texto: String) {
        val textoLimpio = texto.trim()
        if (textoLimpio.isEmpty()) {
            Toast.makeText(this, R.string.error_texto_vacio, Toast.LENGTH_SHORT).show()
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        binding.enviarButton.isEnabled = false
        SierraPresence.entrar(EstadoSierra.PENSANDO)
        val client = SierraApiClient(baseUrl = prefs.chatBaseUrl(), token = prefs.chatToken)
        client.enviarComando(
            texto = textoLimpio,
            onSuccess = { respuesta -> runOnUiThread { mostrarRespuesta(respuesta) } },
            onError = { error -> runOnUiThread { mostrarError(error) } }
        )
    }

    private fun mostrarRespuesta(respuesta: ComandoResponse) {
        binding.progressBar.visibility = View.GONE
        binding.enviarButton.isEnabled = true
        val texto = if (respuesta.matched) respuesta.mensaje else "${getString(R.string.error_sin_match)}\n\n${respuesta.mensaje}"
        binding.respuestaTextView.text = texto
        SierraPresence.entrar(EstadoSierra.LISTA, detalle = texto)
        if (binding.leerSwitch.isChecked) voz.decirTextoLibre(respuesta.mensaje)
    }

    private fun mostrarError(error: SierraApiError) {
        binding.progressBar.visibility = View.GONE
        binding.enviarButton.isEnabled = true
        val mensaje = if (error.httpCode != null) getString(R.string.error_servidor, error.httpCode)
        else getString(R.string.error_conexion, error.message ?: "")
        binding.respuestaTextView.text = mensaje
        SierraPresence.degradar(MotivoCorte.SIN_PC, mensaje)
    }

    override fun onReadyForSpeech(params: Bundle?) { setListeningState(true) }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { setListeningState(false) }
    override fun onError(error: Int) { setListeningState(false) }
    override fun onResults(results: Bundle?) {
        setListeningState(false)
        val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if (texto.isNotBlank()) procesarTextoReconocido(texto)
    }
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onDestroy() {
        speechRecognizer?.destroy()
        pulseAnimator?.cancel()
        voz.liberar()
        SierraPresence.dejarDeObservar(presenceListener)
        super.onDestroy()
    }

    companion object {
        private const val RUTA_DESCARGAS = "/home/jonathanf/Downloads"
        private const val POLL_TAREA_MS = 3000L
        private const val POLL_CONFIRMACIONES_MS = 4000L
    }
}
