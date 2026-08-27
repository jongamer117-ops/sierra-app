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
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
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
        soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
    }

    fun decirTextoLibre(texto: String) {
        if (!ttsReady || texto.isBlank()) return
        tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "sierra_libre")
    }

    fun clipDisponible(nombreRaw: String): Boolean = clipIds.containsKey(nombreRaw)

    fun liberar() {
        soundPool.release()
        tts?.shutdown()
        tts = null
        ttsReady = false
        clipIds.clear()
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
