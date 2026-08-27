package com.sierra.voiceapp.network

import android.graphics.Bitmap
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente RFB mínimo (VNC) para ver la pantalla de sierra-pc.
 *
 * Soporta:
 * - Protocolo 3.8
 * - Security type None (sin auth)
 * - Pixel format 32-bit little-endian RGB
 * - Encoding Raw
 * - Pointer events (clicks / toques / drag)
 *
 * Diseñado para wayvnc sin TLS ni contraseña.
 */
class VncClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected(width: Int, height: Int)
        fun onFrame(bitmap: Bitmap)
        fun onError(error: VncError)
        fun onDisconnected()
    }

    sealed class VncError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class ConnectionFailed(msg: String, cause: Throwable? = null) : VncError(msg, cause)
        class Timeout(msg: String = "Tiempo de espera agotado") : VncError(msg)
        class ProtocolError(msg: String) : VncError(msg)
        class Unsupported(msg: String) : VncError(msg)
        class Closed(msg: String = "Conexión cerrada por el servidor") : VncError(msg)
        class Unknown(msg: String, cause: Throwable? = null) : VncError(msg, cause)
    }

    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    // Escritura en socket = I/O de red. sendPointerEvent() llega desde el
    // touch listener y frameConsumed() desde runOnUiThread -- ambos hilo
    // principal. Escribir ahi tira NetworkOnMainThreadException (StrictMode
    // lo prohibe, no es opcional). Todo lo que escribe pasa por este hilo
    // dedicado, nunca por el que llama.
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "VncClient-writer") }

    private var fbWidth = 0
    private var fbHeight = 0
    private var bitmap: Bitmap? = null

    /** Ancho y alto del framebuffer (válidos después de onConnected). */
    val framebufferWidth: Int get() = fbWidth
    val framebufferHeight: Int get() = fbHeight

    fun connect() {
        if (running.getAndSet(true)) return

        Thread({
            try {
                doConnect()
            } catch (e: Exception) {
                if (running.get()) {
                    val error = when (e) {
                        is VncError -> e
                        is SocketTimeoutException -> VncError.Timeout()
                        is EOFException -> VncError.Closed()
                        is IOException -> VncError.ConnectionFailed(e.message ?: "Error de red", e)
                        else -> VncError.Unknown(e.message ?: "Error inesperado", e)
                    }
                    listener.onError(error)
                }
            } finally {
                cleanup()
                if (running.get()) {
                    running.set(false)
                    listener.onDisconnected()
                }
            }
        }, "VncClient").start()
    }

    fun disconnect() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: Exception) {}
    }

    /**
     * Envía un PointerEvent RFB.
     *
     * buttonMask:
     *   bit 0 = left button
     *   bit 1 = middle
     *   bit 2 = right
     *   bit 3 = wheel up
     *   bit 4 = wheel down
     *
     * Coordenadas en el sistema del framebuffer (0..fbWidth-1, 0..fbHeight-1).
     */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        if (!running.get()) return

        // Clamp para no mandar coordenadas fuera del framebuffer
        val cx = x.coerceIn(0, (fbWidth - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (fbHeight - 1).coerceAtLeast(0))

        submitWrite {
            val out = output ?: return@submitWrite
            try {
                synchronized(out) {
                    out.writeByte(5)          // PointerEvent
                    out.writeByte(buttonMask and 0xFF)
                    out.writeShort(cx)
                    out.writeShort(cy)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo enviar pointer event", e)
            }
        }
    }

    /** El caller (UI) puede llegar despues de disconnect() haber apagado
     * `writer` -- execute() sobre un executor cerrado tira
     * RejectedExecutionException, y no es un error real, solo perdimos la
     * carrera contra la desconexion. Se ignora en vez de tirar la app. */
    private fun submitWrite(work: () -> Unit) {
        try {
            writer.execute(work)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Ya se desconecto -- nada que escribir.
        }
    }

    private fun doConnect() {
        val sock = Socket()
        sock.soTimeout = 12_000 // timeout de lectura
        sock.connect(InetSocketAddress(host, port), 8_000)
        socket = sock
        input = DataInputStream(sock.getInputStream())
        output = DataOutputStream(sock.getOutputStream())

        // 1. Protocol version
        val versionBytes = ByteArray(12)
        input!!.readFully(versionBytes)
        val version = String(versionBytes, Charsets.US_ASCII).trim()
        Log.d(TAG, "Server version: $version")

        if (!version.startsWith("RFB 003.")) {
            throw VncError.ProtocolError("Versión no soportada: $version")
        }

        // Respondemos 3.8
        output!!.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII))
        output!!.flush()

        // 2. Security types
        val numTypes = input!!.readUnsignedByte()
        if (numTypes == 0) {
            // Failure
            val reasonLen = input!!.readInt()
            val reason = ByteArray(reasonLen)
            input!!.readFully(reason)
            throw VncError.ProtocolError("Servidor rechazó: ${String(reason)}")
        }

        val types = ByteArray(numTypes)
        input!!.readFully(types)
        Log.d(TAG, "Security types: ${types.joinToString()}")

        // Preferimos None (1)
        if (!types.contains(1.toByte())) {
            throw VncError.Unsupported("El servidor no ofrece security type None (sin contraseña)")
        }

        output!!.writeByte(1) // None
        output!!.flush()

        // Security result (para 3.8)
        val securityResult = input!!.readInt()
        if (securityResult != 0) {
            val reasonLen = input!!.readInt()
            val reason = ByteArray(reasonLen)
            input!!.readFully(reason)
            throw VncError.ProtocolError("Security failed: ${String(reason)}")
        }

        // 3. ClientInit (shared = 1)
        output!!.writeByte(1)
        output!!.flush()

        // 4. ServerInit
        fbWidth = input!!.readUnsignedShort()
        fbHeight = input!!.readUnsignedShort()
        Log.d(TAG, "Framebuffer: ${fbWidth}x${fbHeight}")

        // Pixel format (16 bytes) — lo ignoramos y forzamos el nuestro después
        val pixelFormat = ByteArray(16)
        input!!.readFully(pixelFormat)

        val nameLen = input!!.readInt()
        val nameBytes = ByteArray(nameLen)
        input!!.readFully(nameBytes)
        val desktopName = String(nameBytes, Charsets.UTF_8)
        Log.d(TAG, "Desktop name: $desktopName")

        // SetPixelFormat: 32-bit little-endian RGB
        setPixelFormat()

        // SetEncodings: solo Raw (0)
        setEncodings(intArrayOf(0))

        bitmap = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888)

        listener.onConnected(fbWidth, fbHeight)

        // Primer update completo
        requestFramebufferUpdate(false)

        // Loop de mensajes
        while (running.get()) {
            val msgType = try {
                input!!.readUnsignedByte()
            } catch (e: SocketTimeoutException) {
                // Pedimos otro update si no hay nada
                requestFramebufferUpdate(true)
                continue
            }

            when (msgType) {
                0 -> handleFramebufferUpdate() // FramebufferUpdate
                2 -> handleBell()
                3 -> handleServerCutText()
                else -> {
                    Log.w(TAG, "Mensaje desconocido: $msgType")
                    // Intentamos no romper; algunos mensajes tienen longitud fija
                }
            }
        }
    }

    private fun setPixelFormat() {
        // Message type 0
        output!!.writeByte(0)
        output!!.writeByte(0) // padding
        output!!.writeByte(0)
        output!!.writeByte(0)

        // bits-per-pixel, depth, big-endian, true-colour
        output!!.writeByte(32)
        output!!.writeByte(24)
        output!!.writeByte(0) // little-endian
        output!!.writeByte(1) // true colour

        // max values
        output!!.writeShort(255) // red-max
        output!!.writeShort(255) // green-max
        output!!.writeShort(255) // blue-max

        // shifts
        output!!.writeByte(16) // red-shift
        output!!.writeByte(8)  // green-shift
        output!!.writeByte(0)  // blue-shift

        // padding
        output!!.writeByte(0)
        output!!.writeByte(0)
        output!!.writeByte(0)
        output!!.flush()
    }

    private fun setEncodings(encodings: IntArray) {
        output!!.writeByte(2) // SetEncodings
        output!!.writeByte(0) // padding
        output!!.writeShort(encodings.size)
        for (enc in encodings) {
            output!!.writeInt(enc)
        }
        output!!.flush()
    }

    /**
     * Corre en dos hilos distintos segun quien llame: el de lectura (pedido
     * inicial y el reintento por timeout) y el `writer` (via frameConsumed).
     * En el de lectura un fallo ya lo captura el try/catch de connect() y
     * dispara onError(); en el `writer` no hay nadie escuchando -- ahi una
     * excepcion sin atrapar tira el proceso entero, no solo esta clase. Se
     * traga el error aca: si el socket se esta cerrando, la lectura
     * bloqueada del otro hilo ya se va a enterar y va a avisar una sola vez.
     */
    private fun requestFramebufferUpdate(incremental: Boolean) {
        val out = output ?: return
        try {
            synchronized(out) {
                out.writeByte(3) // FramebufferUpdateRequest
                out.writeByte(if (incremental) 1 else 0)
                out.writeShort(0) // x
                out.writeShort(0) // y
                out.writeShort(fbWidth)
                out.writeShort(fbHeight)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo pedir el proximo frame", e)
        }
    }

    private fun handleFramebufferUpdate() {
        input!!.readByte() // padding
        val numRects = input!!.readUnsignedShort()

        val bmp = bitmap ?: return

        for (i in 0 until numRects) {
            val x = input!!.readUnsignedShort()
            val y = input!!.readUnsignedShort()
            val w = input!!.readUnsignedShort()
            val h = input!!.readUnsignedShort()
            val encoding = input!!.readInt()

            when (encoding) {
                0 -> { // Raw
                    val pixelCount = w * h
                    val bytes = ByteArray(pixelCount * 4)
                    input!!.readFully(bytes)

                    // Convert según nuestro pixel format (little-endian, R shift 16)
                    // En memoria little-endian: B G R A
                    // Color.rgb(r,g,b) es una llamada a funcion por pixel -- en un
                    // rect de pantalla completa (4K = 8.3M pixeles) eso pesa. Mismo
                    // resultado armando el Int a mano (0xFF shl 24 es el alpha fijo).
                    val pixels = IntArray(pixelCount)
                    var idx = 0
                    for (p in 0 until pixelCount) {
                        val b = bytes[idx].toInt() and 0xFF
                        val g = bytes[idx + 1].toInt() and 0xFF
                        val r = bytes[idx + 2].toInt() and 0xFF
                        pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        idx += 4
                    }
                    bmp.setPixels(pixels, 0, w, x, y, w, h)
                }
                else -> {
                    Log.w(TAG, "Encoding no soportado: $encoding (rect ${w}x${h})")
                    throw VncError.Unsupported("Encoding $encoding no implementado aún")
                }
            }
        }

        // Entregar copia para no compartir el bitmap mutable entre hilos
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
        listener.onFrame(copy)

        // NO pedimos el siguiente update aca. wayvnc manda updates apenas hay
        // cambios en pantalla -- con video o texto scrolleando eso es
        // practicamente sin pausa. Si pidieramos el proximo de una, la UI
        // (un frame de 4K crudo son ~31MB) se queda atras y la cola de
        // Runnables de runOnUiThread se llena de bitmaps sin consumir: la
        // memoria se dispara y la imagen nunca llega a pintarse (se ve
        // "tarda en conectar" cuando en realidad ya conecto, esta ahogada).
        // frameConsumed() es quien pide el siguiente, llamado por la UI
        // recien despues de terminar de mostrar este.
    }

    /**
     * La UI llama esto apenas termino de mostrar el frame que le mandamos por
     * onFrame() -- recien ahi se pide el siguiente. Este pull-based pacing es
     * lo que evita la inundacion: nunca hay mas de un frame en vuelo.
     */
    fun frameConsumed() {
        submitWrite {
            if (running.get()) requestFramebufferUpdate(true)
        }
    }

    private fun handleBell() {
        // ignoramos
    }

    private fun handleServerCutText() {
        input!!.readByte() // padding
        input!!.readByte()
        input!!.readByte()
        val len = input!!.readInt()
        if (len > 0 && len < 1_000_000) {
            val data = ByteArray(len)
            input!!.readFully(data)
        }
    }

    private fun cleanup() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null
        output = null
        socket = null
        // Cada reconexion crea un VncClient nuevo (ver VncViewerActivity.conectar) --
        // sin esto, cada intento deja un hilo de escritura huerfano.
        writer.shutdownNow()
    }

    companion object {
        private const val TAG = "VncClient"

        /** Máscara de botón izquierdo (tap / click). */
        const val BUTTON_LEFT = 1
    }
}
