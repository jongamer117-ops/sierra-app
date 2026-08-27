package com.sierra.voiceapp

import android.content.Context
import android.os.Handler
import android.os.Looper

enum class EstadoSierra {
    QUIETA,
    ESCUCHANDO,
    PENSANDO,
    EN_COLA,
    ESPERANDO_SI,
    LISTA,
    CORTA
}

enum class MotivoCorte {
    SIN_PC,
    SIN_TOKEN,
    HERMES,
    TIMEOUT
}

/**
 * Máquina de estados de presencia. Singleton sin Activity retenida.
 * Los listeners se invocan siempre en el hilo principal.
 */
object SierraPresence {

    data class Snapshot(
        val estado: EstadoSierra,
        val linea: String,
        val detalle: String? = null,
        val confirmaciones: Int = 0
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(Snapshot) -> Unit>()

    private var appContext: Context? = null
    private var estadoOperativo: EstadoSierra = EstadoSierra.QUIETA
    private var lineaOperativa: String? = null
    private var detalle: String? = null
    private var motivoCorte: MotivoCorte? = null
    private var cantidadConfirmaciones: Int = 0
    private var expiraEnSegundos: Long? = null

    private val timeoutRunnable = Runnable { onTimeout() }

    fun inicializar(context: Context) {
        appContext = context.applicationContext
        emitir()
    }

    fun observar(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        val snap = snapshotActual()
        mainHandler.post { listener(snap) }
    }

    fun dejarDeObservar(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun entrar(estado: EstadoSierra, detalle: String? = null, linea: String? = null) {
        when (estado) {
            EstadoSierra.CORTA -> {
                motivoCorte = MotivoCorte.SIN_PC
                this.detalle = detalle
            }
            EstadoSierra.ESPERANDO_SI -> {
                // La vía canónica es confirmacionesVivas().
            }
            else -> {
                estadoOperativo = estado
                lineaOperativa = linea
                this.detalle = detalle
            }
        }
        programarTimeout(estadoEfectivo())
        emitir()
    }

    fun confirmacionesVivas(cantidad: Int, expiraEnSegundos: Long?) {
        cantidadConfirmaciones = cantidad.coerceAtLeast(0)
        this.expiraEnSegundos = expiraEnSegundos
        programarTimeout(estadoEfectivo())
        emitir()
    }

    fun degradar(motivo: MotivoCorte, detalle: String? = null) {
        motivoCorte = motivo
        this.detalle = detalle
        programarTimeout(estadoEfectivo())
        emitir()
    }

    fun tickConfirmacion() {
        val actual = expiraEnSegundos ?: return
        expiraEnSegundos = (actual - 1).coerceAtLeast(0)
        emitir()
    }

    fun limpiarCorte() {
        motivoCorte = null
        programarTimeout(estadoEfectivo())
        emitir()
    }

    fun snapshotActual(): Snapshot {
        val estado = estadoEfectivo()
        return Snapshot(
            estado = estado,
            linea = resolverLinea(estado),
            detalle = detalle,
            confirmaciones = cantidadConfirmaciones
        )
    }

    private fun estadoEfectivo(): EstadoSierra {
        if (cantidadConfirmaciones > 0) return EstadoSierra.ESPERANDO_SI
        if (motivoCorte != null) return EstadoSierra.CORTA
        return estadoOperativo
    }

    private fun resolverLinea(estado: EstadoSierra): String {
        val ctx = appContext
        if (ctx == null) return estado.name.lowercase()
        return when (estado) {
            EstadoSierra.QUIETA -> ctx.getString(R.string.presencia_quieta)
            EstadoSierra.ESCUCHANDO -> ctx.getString(R.string.presencia_escuchando)
            EstadoSierra.PENSANDO -> lineaOperativa ?: ctx.getString(R.string.presencia_pensando)
            EstadoSierra.EN_COLA -> lineaOperativa ?: ctx.getString(R.string.presencia_en_cola)
            EstadoSierra.ESPERANDO_SI -> {
                val segs = (expiraEnSegundos ?: 0L).coerceAtLeast(0L)
                val m = (segs / 60).toInt()
                val s = (segs % 60).toInt()
                val cuenta = String.format("%d:%02d", m, s)
                ctx.getString(R.string.presencia_esperando_si, cuenta)
            }
            EstadoSierra.LISTA -> lineaOperativa ?: ctx.getString(R.string.presencia_lista)
            EstadoSierra.CORTA -> when (motivoCorte) {
                MotivoCorte.SIN_TOKEN -> ctx.getString(R.string.presencia_corta_sin_token)
                MotivoCorte.HERMES -> ctx.getString(R.string.presencia_corta_hermes)
                MotivoCorte.TIMEOUT -> ctx.getString(R.string.presencia_corta_timeout)
                MotivoCorte.SIN_PC, null -> ctx.getString(R.string.presencia_corta_sin_pc)
            }
        }
    }

    private fun programarTimeout(estado: EstadoSierra) {
        mainHandler.removeCallbacks(timeoutRunnable)
        val delay = when (estado) {
            EstadoSierra.PENSANDO -> TIMEOUT_PENSANDO_MS
            EstadoSierra.EN_COLA -> TIMEOUT_EN_COLA_MS
            EstadoSierra.ESCUCHANDO -> TIMEOUT_ESCUCHANDO_MS
            EstadoSierra.LISTA -> TIMEOUT_LISTA_MS
            else -> return
        }
        mainHandler.postDelayed(timeoutRunnable, delay)
    }

    private fun onTimeout() {
        val ctx = appContext
        when (estadoEfectivo()) {
            EstadoSierra.PENSANDO -> {
                motivoCorte = MotivoCorte.TIMEOUT
                detalle = null
            }
            EstadoSierra.EN_COLA -> {
                estadoOperativo = EstadoSierra.LISTA
                lineaOperativa = ctx?.getString(R.string.presencia_sigue_en_cola)
                detalle = null
            }
            EstadoSierra.ESCUCHANDO -> {
                estadoOperativo = EstadoSierra.QUIETA
                lineaOperativa = null
                detalle = null
            }
            EstadoSierra.LISTA -> {
                estadoOperativo = EstadoSierra.QUIETA
                lineaOperativa = null
                detalle = null
            }
            else -> return
        }
        programarTimeout(estadoEfectivo())
        emitir()
    }

    private fun emitir() {
        val snap = snapshotActual()
        mainHandler.post {
            listeners.forEach { it(snap) }
        }
    }

    private const val TIMEOUT_PENSANDO_MS = 50_000L
    private const val TIMEOUT_EN_COLA_MS = 120_000L
    private const val TIMEOUT_ESCUCHANDO_MS = 30_000L
    private const val TIMEOUT_LISTA_MS = 8_000L
}
