package com.sierra.voiceapp

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import java.util.Locale

class VozSierra(private val context: Context) {

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    private val clipIds = mutableMapOf<String, Int>()

    /** soundIds que SoundPool ya termino de decodificar y se pueden reproducir. */
    private val clipsListos = mutableSetOf<Int>()

    /** soundId pedido antes de que terminara de cargar: se reproduce solo si
     * sigue siendo el ultimo pedido cuando la carga termina. Ver decir(). */
    private var clipPendiente: Int? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Una carga puede terminar despues de liberar() (la Activity se destruyo
     * mientras el clip decodificaba); sin esto, el listener tocaria un SoundPool
     * ya liberado. */
    private var liberado = false

    init {
        // SoundPool.load() es asincrono: devuelve el soundId al instante pero la
        // muestra no suena hasta que este listener dispara, y un play() antes de
        // eso se traga el audio en silencio (ni suena ni falla). Importa justo en
        // el peor momento: la primera frase es la de apertura, en onCreate, a
        // milisegundos de haber pedido la carga.
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (liberado) return@setOnLoadCompleteListener
            if (status != 0) {
                // Fallo la decodificacion: que no quede un pendiente esperando algo
                // que nunca va a sonar.
                if (clipPendiente == sampleId) clipPendiente = null
                return@setOnLoadCompleteListener
            }
            clipsListos.add(sampleId)
            if (clipPendiente == sampleId) {
                clipPendiente = null
                reproducir(sampleId)
            }
        }
        precargar()
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
    }

    fun decir(nombreRaw: String) {
        val soundId = clipIds[nombreRaw] ?: return
        if (clipsListos.contains(soundId)) {
            // Una frase nueva cancela cualquier pendiente: si el clip viejo
            // terminara de cargar despues, hablaria encima de esta.
            clipPendiente = null
            reproducir(soundId)
        } else {
            // Todavia cargando. Se guarda solo el ultimo pedido -- si el estado
            // cambio dos veces mientras cargaba, la frase que vale es la actual.
            clipPendiente = soundId
        }
    }

    private fun reproducir(soundId: Int) {
        soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun decirTextoLibre(texto: String) {
        if (!ttsReady || texto.isBlank()) return
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "sierra_libre")
    }

    /** Si existe un clip grabado con la voz real para esta frase -- es la
     * pregunta de ruteo (voz de Sierra vs. TTS de Android), no la de si ya
     * termino de cargar. Un clip que todavia carga igual se reproduce: decir()
     * lo deja pendiente. Caer al TTS generico por unos milisegundos de carga
     * seria peor que esperarlos. */
    fun clipDisponible(nombreRaw: String): Boolean = clipIds.containsKey(nombreRaw)

    fun liberar() {
        liberado = true
        clipPendiente = null
        soundPool.release()
        tts?.shutdown()
        tts = null
        ttsReady = false
        clipIds.clear()
        clipsListos.clear()
    }

    private fun precargar() {
        NOMBRES.forEach { nombre ->
            val resId = context.resources.getIdentifier(nombre, "raw", context.packageName)
            if (resId != 0) {
                clipIds[nombre] = soundPool.load(context, resId, 1)
            }
        }
    }

    companion object {
        val NOMBRES = listOf(
            "voz_quieta",
            "voz_escuchando",
            "voz_pensando",
            "voz_en_cola",
            "voz_esperando_si",
            "voz_lista",
            "voz_corta_sin_pc",
            "voz_corta_hermes",
            "voz_corta_sin_token",
            "voz_hora",
            "voz_firefox",
            "voz_encolado",
            "voz_ejecutado",
            "voz_fallo",
            "voz_timeout"
        )

        fun clipParaEstado(estado: EstadoSierra, motivo: MotivoCorte? = null): String? =
            when (estado) {
                EstadoSierra.QUIETA -> "voz_quieta"
                EstadoSierra.ESCUCHANDO -> "voz_escuchando"
                EstadoSierra.PENSANDO -> "voz_pensando"
                EstadoSierra.EN_COLA -> "voz_en_cola"
                EstadoSierra.ESPERANDO_SI -> "voz_esperando_si"
                EstadoSierra.LISTA -> "voz_lista"
                EstadoSierra.CORTA -> when (motivo) {
                    MotivoCorte.SIN_TOKEN -> "voz_corta_sin_token"
                    MotivoCorte.HERMES -> "voz_corta_hermes"
                    MotivoCorte.TIMEOUT -> "voz_timeout"
                    else -> "voz_corta_sin_pc"
                }
            }
    }
}
