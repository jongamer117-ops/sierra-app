package com.sierra.voiceapp

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sierra.voiceapp.databinding.ActivityMainBinding
import com.sierra.voiceapp.network.CanalAConfirmationsClient
import com.sierra.voiceapp.network.CanalADirectClient
import com.sierra.voiceapp.network.CanalADirectError
import com.sierra.voiceapp.network.ComandoResponse
import com.sierra.voiceapp.network.EstadoTarea
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

    private lateinit var voz: VozSierra
    private var pulso: ObjectAnimator? = null

    private val poller = Handler(Looper.getMainLooper())

    /** ids del lote de imagen que la pantalla esta siguiendo ahora (1 si es
     * una sola, hasta 4 si son variaciones). Le da identidad al lote: un
     * poll de un lote viejo no puede pisar el estado del actual. Se achica
     * a medida que cada tarea reporta done. */
    private var loteActivo: MutableSet<String> = mutableSetOf()
    private var loteTotal = 0
    private var loteListasCount = 0
    private var loteErrorCount = 0
    private var ultimoDetalleTarea: String? = null
    private var pollsRestantesLote = 0

    private var variacionesSeleccionadas = 1

    private var pollConfirmacionesActivo = false

    private val listenerPresencia: (SierraPresence.Snapshot) -> Unit = { snap -> pintarEstado(snap) }

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

        configurarSpinnersImagen()
        binding.generarImagenButton.setOnClickListener { generarImagen() }
        binding.imagenHeader.setOnClickListener { alternarSeccionImagen() }
        binding.borrarPromptButton.setOnClickListener { binding.imagenPromptEditText.setText("") }
        binding.variacionesMenosButton.setOnClickListener { ajustarVariaciones(-1) }
        binding.variacionesMasButton.setOnClickListener { ajustarVariaciones(1) }

        voz = VozSierra(this)
        SierraPresence.inicializar(this)
    }

    // --- Capa de presencia ---

    private fun alternarSeccionImagen() {
        val abierto = binding.imagenContenedor.visibility == android.view.View.VISIBLE
        binding.imagenContenedor.visibility =
            if (abierto) android.view.View.GONE else android.view.View.VISIBLE
        binding.imagenChevron.text = if (abierto) "▸" else "▾"
    }

    private fun pintarEstado(snap: SierraPresence.Snapshot) {
        binding.estadoTextView.text = snap.linea

        val color = ContextCompat.getColor(
            this,
            when (snap.estado) {
                EstadoSierra.QUIETA -> R.color.sierra_accent_dim
                EstadoSierra.ESCUCHANDO -> R.color.sierra_listening
                EstadoSierra.PENSANDO -> R.color.sierra_accent
                EstadoSierra.EN_COLA -> R.color.sierra_primary
                EstadoSierra.ESPERANDO_SI -> R.color.sierra_error
                EstadoSierra.LISTA -> R.color.sierra_accent
                EstadoSierra.CORTA -> R.color.sierra_text_secondary
            }
        )
        binding.estadoAnillo.background?.setColorFilter(color, PorterDuff.Mode.SRC_IN)

        if (snap.confirmaciones > 0) {
            binding.confirmacionesBadge.text = snap.confirmaciones.toString()
            binding.confirmacionesBadge.visibility = android.view.View.VISIBLE
        } else {
            binding.confirmacionesBadge.visibility = android.view.View.GONE
        }

        if (snap.estado == EstadoSierra.PENSANDO) arrancarPulso() else pararPulso()
    }

    private fun arrancarPulso() {
        if (pulso?.isRunning == true) return
        pulso = ObjectAnimator.ofFloat(binding.estadoAnillo, "alpha", 1f, 0.25f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /** Un pulso que sigue corriendo invisible come bateria para nada. */
    private fun pararPulso() {
        pulso?.cancel()
        pulso = null
        binding.estadoAnillo.alpha = 1f
    }

    private fun hablar(clip: String?, textoLibre: String? = null) {
        if (!binding.leerSwitch.isChecked) return
        when {
            clip != null && voz.clipDisponible(clip) -> voz.decir(clip)
            textoLibre != null -> voz.decirTextoLibre(textoLibre)
        }
    }

    // Mismas opciones que allowed_samplers/allowed_schedulers en catalog.py --
    // si se agregan mas ahi, agregar aca tambien.
    private val resoluciones = listOf(
        Triple("Retrato alto (768×1344)", 768, 1344), // preferido de Jon, queda primero (default)
        Triple("Cuadrada (1024×1024)", 1024, 1024),
        Triple("Retrato (768×1152)", 768, 1152),
        Triple("Paisaje (1152×768)", 1152, 768),
        Triple("Retrato compacto (832×1216)", 832, 1216),
        Triple("Panorámica (1344×768)", 1344, 768),
    )
    private val samplers = listOf(
        "euler", "euler_cfg_pp", "euler_ancestral", "euler_ancestral_cfg_pp",
        "heun", "heunpp2", "dpm_2", "dpm_2_ancestral", "lms", "dpm_fast",
        "dpm_adaptive", "dpmpp_2s_ancestral", "dpmpp_sde", "dpmpp_2m",
        "dpmpp_2m_sde", "dpmpp_3m_sde", "ddpm", "lcm", "ipndm", "deis",
        "res_multistep", "ddim", "uni_pc", "uni_pc_bh2"
    )
    private val schedulers = listOf(
        "simple", "sgm_uniform", "karras", "exponential", "ddim_uniform",
        "beta", "normal", "linear_quadratic", "kl_optimal"
    )

    private fun configurarSpinnersImagen() {
        // Config guardada de Jon: retrato alto, 9 steps, cfg 1.0, euler --
        // resolucion y sampler ya quedan primeros en sus listas (default de
        // Spinner es posicion 0), esto precarga los campos de texto.
        binding.stepsEditText.setText("9")
        binding.cfgEditText.setText("1.0")

        binding.resolucionSpinner.adapter = crearAdapterSpinner(resoluciones.map { it.first })
        binding.samplerSpinner.adapter = crearAdapterSpinner(samplers)
        binding.schedulerSpinner.adapter = crearAdapterSpinner(schedulers)
    }

    // El texto por default de simple_spinner_dropdown_item queda casi
    // invisible sobre el fondo oscuro de la app (nunca tenia color propio) --
    // layout custom con sierra_text_primary para el item cerrado y la lista.
    private fun crearAdapterSpinner(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, R.layout.spinner_item, items).apply {
            setDropDownViewResource(R.layout.spinner_item)
        }
    }

    private fun ajustarVariaciones(delta: Int) {
        variacionesSeleccionadas = (variacionesSeleccionadas + delta).coerceIn(1, MAX_VARIACIONES)
        binding.variacionesValorText.text = variacionesSeleccionadas.toString()
    }

    private fun generarImagen() {
        val descripcion = binding.imagenPromptEditText.text.toString().trim()
        if (descripcion.isEmpty()) {
            Toast.makeText(this, R.string.error_prompt_imagen_vacio, Toast.LENGTH_SHORT).show()
            return
        }
        if (!prefs.hasToken()) {
            SierraPresence.degradar(MotivoCorte.SIN_TOKEN)
            hablar("voz_corta_sin_token")
            return
        }

        SierraPresence.entrar(EstadoSierra.PENSANDO, linea = getString(R.string.generando_imagen))

        val (_, width, height) = resoluciones[binding.resolucionSpinner.selectedItemPosition]
        val steps = binding.stepsEditText.text.toString().trim().toIntOrNull()
        val cfg = binding.cfgEditText.text.toString().trim().toFloatOrNull()
        val sampler = samplers[binding.samplerSpinner.selectedItemPosition]
        val scheduler = schedulers[binding.schedulerSpinner.selectedItemPosition]

        val params = mutableMapOf<String, Any>(
            "prompt" to descripcion,
            "width" to width,
            "height" to height,
            "sampler_name" to sampler,
            "scheduler" to scheduler,
        )
        if (steps != null) params["steps"] = steps
        if (cfg != null) params["cfg"] = cfg

        // generate_image es Nivel 1 (reclasificado 2026-08-25: rapido, local,
        // bajo riesgo -- a diferencia de generate_video, que sigue en Nivel 3
        // y sigue yendo por Cortana + confirmacion). Directo a Canal A, sin
        // IA en el medio.
        //
        // El Executor asigna una seed al azar en cada tarea (executor.py:163,
        // secrets.randbelow) -- mandar el mismo prompt N veces ya produce N
        // resultados distintos solos, no hace falta armar una seed aca.
        val cantidad = variacionesSeleccionadas
        val client = CanalADirectClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        val idsCreados = mutableListOf<String>()
        var respuestasRecibidas = 0
        var ultimoErrorCreacion: CanalADirectError? = null

        repeat(cantidad) {
            client.crearTareaNivel1(
                action = "generate_image",
                params = params,
                // Canal A guardo la tarea: eso NO es que se ejecuto. Hasta
                // que el Executor reporte, el estado honesto es "en cola".
                onSuccess = { taskId ->
                    runOnUiThread {
                        idsCreados.add(taskId)
                        respuestasRecibidas++
                        if (respuestasRecibidas == cantidad) resolverCreacionLote(idsCreados, cantidad, ultimoErrorCreacion)
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        ultimoErrorCreacion = error
                        respuestasRecibidas++
                        if (respuestasRecibidas == cantidad) resolverCreacionLote(idsCreados, cantidad, ultimoErrorCreacion)
                    }
                }
            )
        }
    }

    private fun resolverCreacionLote(idsCreados: List<String>, cantidad: Int, ultimoError: CanalADirectError?) {
        if (idsCreados.isNotEmpty()) {
            empezarASeguirLote(idsCreados.toSet(), cantidad)
        } else {
            mostrarErrorImagen(ultimoError ?: CanalADirectError("No se pudo encolar ninguna imagen"))
        }
    }

    private fun empezarASeguirLote(taskIds: Set<String>, total: Int) {
        loteActivo = taskIds.toMutableSet()
        loteTotal = total
        loteListasCount = 0
        // Las que ni se pudieron crear (POST /tasks fallo) ya cuentan como
        // error antes de arrancar a sondear.
        loteErrorCount = total - taskIds.size
        ultimoDetalleTarea = null
        // El Executor procesa las tareas de a una (una sola GPU): ~10s por
        // imagen turbo (catalog.py). Con hasta MAX_VARIACIONES en serie, la
        // ventana fija de una sola imagen se queda corta -- 10s extra de
        // margen por cada imagen mas alla de la primera.
        pollsRestantesLote = MAX_POLLS_TAREA + (total - 1) * POLLS_EXTRA_POR_VARIACION

        if (total == 1) {
            SierraPresence.entrar(EstadoSierra.EN_COLA, linea = getString(R.string.acuse_encolado))
            hablar("voz_encolado")
        } else {
            val linea = getString(R.string.acuse_lote_encolado, total)
            SierraPresence.entrar(EstadoSierra.EN_COLA, linea = linea)
            hablar(clip = null, textoLibre = linea)
        }

        if (loteActivo.isEmpty()) {
            finalizarLote()
        } else {
            poller.postDelayed({ sondearLote() }, POLL_TAREA_MS)
        }
    }

    private fun sondearLote() {
        if (loteActivo.isEmpty()) return
        if (pollsRestantesLote-- <= 0) {
            loteActivo = mutableSetOf()
            return  // el timeout de EN_COLA en SierraPresence dice lo suyo
        }

        val idsDeEsteTurno = loteActivo.toSet()
        val client = CanalADirectClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.consultarEstados(
            taskIds = idsDeEsteTurno,
            onResult = { encontradas -> runOnUiThread { procesarLote(idsDeEsteTurno, encontradas) } },
            onError = { runOnUiThread { reintentarSondeoLote(idsDeEsteTurno) } }
        )
    }

    private fun reintentarSondeoLote(idsDeEsteTurno: Set<String>) {
        // Turno viejo: ya se disparo otro lote mientras esta consulta viajaba.
        if (idsDeEsteTurno != loteActivo) return
        poller.postDelayed({ sondearLote() }, POLL_TAREA_MS)
    }

    private fun procesarLote(idsDeEsteTurno: Set<String>, encontradas: Map<String, EstadoTarea>) {
        if (idsDeEsteTurno != loteActivo) return

        for (id in idsDeEsteTurno) {
            val estado = encontradas[id] ?: continue  // no aparecio todavia, se reintenta
            if (estado.status != "done") continue
            loteActivo.remove(id)
            if (estado.result == "success") loteListasCount++ else loteErrorCount++
            ultimoDetalleTarea = estado.resultDetail
        }

        if (loteActivo.isEmpty()) {
            finalizarLote()
        } else {
            poller.postDelayed({ sondearLote() }, POLL_TAREA_MS)
        }
    }

    private fun finalizarLote() {
        val total = loteTotal
        val listas = loteListasCount
        val errores = loteErrorCount
        val detalle = ultimoDetalleTarea
        loteActivo = mutableSetOf()

        if (total == 1) {
            val ok = errores == 0 && listas == 1
            val linea = if (ok) getString(R.string.acuse_ejecutado)
            else getString(R.string.acuse_fallo, detalle ?: "")

            SierraPresence.entrar(EstadoSierra.LISTA, detalle = detalle, linea = linea)
            detalle?.let { binding.respuestaTextView.text = it }
            hablar(if (ok) "voz_ejecutado" else "voz_fallo", textoLibre = if (ok) null else linea)
            return
        }

        val linea = if (errores == 0) getString(R.string.acuse_lote_listo, listas)
        else getString(R.string.acuse_lote_parcial, listas, total, errores)
        SierraPresence.entrar(EstadoSierra.LISTA, linea = linea)
        hablar(clip = null, textoLibre = linea)
    }

    private fun mostrarErrorImagen(error: CanalADirectError) {
        loteActivo = mutableSetOf()
        val mensaje = if (error.httpCode != null) {
            getString(R.string.error_servidor, error.httpCode)
        } else {
            getString(R.string.error_conexion, error.message ?: "")
        }
        binding.respuestaTextView.text = mensaje
        SierraPresence.degradar(MotivoCorte.SIN_PC, detalle = mensaje)
        hablar("voz_corta_sin_pc")
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

        SierraPresence.observar(listenerPresencia)
        if (!prefs.hasToken()) {
            SierraPresence.degradar(MotivoCorte.SIN_TOKEN)
        } else {
            SierraPresence.limpiarCorte()
        }
        // El servicio apagado deja a la home sin fuente de confirmaciones:
        // mientras esta pantalla este al frente, sondea ella.
        if (!prefs.vigilanciaActiva && prefs.hasToken()) arrancarPollConfirmaciones()
        // Un lote puede haber quedado a medio seguir al irse a background.
        if (loteActivo.isNotEmpty()) poller.postDelayed({ sondearLote() }, POLL_TAREA_MS)
    }

    override fun onPause() {
        super.onPause()
        // Nada de seguir pegandole a la red con la app en background.
        poller.removeCallbacksAndMessages(null)
        pollConfirmacionesActivo = false
        SierraPresence.dejarDeObservar(listenerPresencia)
        pararPulso()
    }

    private fun arrancarPollConfirmaciones() {
        if (pollConfirmacionesActivo) return
        pollConfirmacionesActivo = true
        val tick = object : Runnable {
            override fun run() {
                if (!pollConfirmacionesActivo) return
                CanalAConfirmationsClient(baseUrl = prefs.baseUrl(), token = prefs.token).fetchPending(
                    onSuccess = { pendientes ->
                        runOnUiThread {
                            SierraPresence.confirmacionesVivas(
                                pendientes.size,
                                pendientes.minByOrNull { it.expiresAt }?.let { segundosHasta(it.expiresAt) }
                            )
                        }
                    },
                    onError = { }
                )
                poller.postDelayed(this, POLL_CONFIRMACIONES_MS)
            }
        }
        poller.post(tick)
    }

    private fun segundosHasta(iso: String): Long? = try {
        java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse(iso)).seconds
    } catch (e: Exception) {
        null
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
        if (listening) {
            SierraPresence.entrar(EstadoSierra.ESCUCHANDO)
            hablar("voz_escuchando")
        }
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
        SierraPresence.entrar(EstadoSierra.PENSANDO)
        hablar("voz_pensando")

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

        SierraPresence.entrar(EstadoSierra.LISTA)
        // El chat es texto variable: no hay clip grabado, va por TTS.
        hablar(clip = null, textoLibre = respuesta.mensaje)
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
        // Con el chip en Hermes la capacidad es distinta, y se dice en voz alta.
        val motivo = if (prefs.usarCortana) MotivoCorte.SIN_PC else MotivoCorte.HERMES
        SierraPresence.degradar(motivo, detalle = mensaje)
        hablar(if (motivo == MotivoCorte.HERMES) "voz_corta_hermes" else "voz_corta_sin_pc")
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
        poller.removeCallbacksAndMessages(null)
        SierraPresence.dejarDeObservar(listenerPresencia)
        pararPulso()
        voz.liberar()
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val POLL_TAREA_MS = 3_000L
        private const val POLL_CONFIRMACIONES_MS = 4_000L
        /** 3s x 40 = 120s, el mismo techo que el timeout de EN_COLA. */
        private const val MAX_POLLS_TAREA = 40
        /** 10 polls x 3s = 30s extra de margen por cada imagen mas alla de la
         * primera -- cubre el ~10s/imagen que tarda el Executor en serie. */
        private const val POLLS_EXTRA_POR_VARIACION = 10
        private const val MAX_VARIACIONES = 10
    }
}
