package com.sierra.voiceapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.sierra.voiceapp.databinding.ActivityConfirmacionesBinding
import com.sierra.voiceapp.network.CanalAApiError
import com.sierra.voiceapp.network.CanalAConfirmationsClient
import com.sierra.voiceapp.network.ConfirmacionPendiente
import java.util.Locale

/**
 * Pantalla de confirmaciones de Nivel 3 pendientes en Canal A. Cada una es
 * una decision individual: no hay "aprobar todo" ni auto-aprobacion.
 */
class ConfirmacionesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmacionesBinding
    private lateinit var prefs: SierraPrefs
    private lateinit var adapter: ConfirmacionesAdapter

    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private val pollHandler = Handler(Looper.getMainLooper())
    private val countdownHandler = Handler(Looper.getMainLooper())
    private val idsYaLeidos = mutableSetOf<String>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            cargarPendientes()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val countdownRunnable = object : Runnable {
        override fun run() {
            adapter.refreshCountdowns()
            countdownHandler.postDelayed(this, COUNTDOWN_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmacionesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                ttsReady = true
            }
        }

        adapter = ConfirmacionesAdapter(
            onAprobar = { confirmarDecision(it, aprobar = true) },
            onRechazar = { confirmarDecision(it, aprobar = false) }
        )
        binding.confirmacionesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.confirmacionesRecyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.hasToken()) {
            Toast.makeText(this, R.string.error_falta_token, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pollHandler.post(pollRunnable)
        countdownHandler.postDelayed(countdownRunnable, COUNTDOWN_TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
        countdownHandler.removeCallbacks(countdownRunnable)
    }

    override fun onDestroy() {
        textToSpeech?.shutdown()
        super.onDestroy()
    }

    private fun client(): CanalAConfirmationsClient =
        CanalAConfirmationsClient(baseUrl = prefs.baseUrl(), token = prefs.token)

    private fun cargarPendientes() {
        client().fetchPending(
            onSuccess = { pendientes -> runOnUiThread { mostrarPendientes(pendientes) } },
            onError = { error -> runOnUiThread { mostrarErrorCarga(error) } }
        )
    }

    private fun mostrarPendientes(pendientes: List<ConfirmacionPendiente>) {
        adapter.submitList(pendientes)
        binding.emptyStateTextView.visibility =
            if (pendientes.isEmpty()) View.VISIBLE else View.GONE

        val nuevas = pendientes.filter { it.confirmationId !in idsYaLeidos }
        nuevas.forEach { idsYaLeidos.add(it.confirmationId) }

        if (binding.leerSwitch.isChecked && ttsReady) {
            nuevas.forEach { confirmacion ->
                textToSpeech?.speak(
                    confirmacion.confirmationDetail,
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "confirmacion_${confirmacion.confirmationId}"
                )
            }
        }
    }

    private fun mostrarErrorCarga(error: CanalAApiError) {
        // Un error de polling no debe tapar la lista con un toast cada pocos
        // segundos; se muestra una sola vez por sesion de pantalla.
        if (!huboErrorDeCarga) {
            huboErrorDeCarga = true
            val mensaje = if (error.httpCode != null) {
                getString(R.string.error_servidor, error.httpCode)
            } else {
                getString(R.string.error_conexion, error.message ?: "")
            }
            Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
        }
    }

    private var huboErrorDeCarga = false

    private fun confirmarDecision(confirmacion: ConfirmacionPendiente, aprobar: Boolean) {
        val accion: (String, () -> Unit, (CanalAApiError) -> Unit) -> Unit =
            if (aprobar) client()::approve else client()::reject

        accion(
            confirmacion.confirmationId,
            {
                runOnUiThread {
                    val mensaje = if (aprobar) R.string.confirmacion_aprobada else R.string.confirmacion_rechazada
                    Toast.makeText(this, mensaje, Toast.LENGTH_SHORT).show()
                    cargarPendientes()
                }
            },
            { error ->
                runOnUiThread {
                    val mensaje = if (error.httpCode != null) {
                        getString(R.string.error_servidor, error.httpCode)
                    } else {
                        getString(R.string.error_conexion, error.message ?: "")
                    }
                    Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
                    cargarPendientes()
                }
            }
        )
    }

    companion object {
        private const val POLL_INTERVAL_MS = 4000L
        private const val COUNTDOWN_TICK_MS = 1000L
    }
}
