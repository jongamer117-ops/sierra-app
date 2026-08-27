package com.sierra.voiceapp

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sierra.voiceapp.databinding.ActivityVncViewerBinding
import com.sierra.voiceapp.network.VncClient

/**
 * Pantalla de vista en vivo de sierra-pc vía VNC (wayvnc).
 *
 * Zoom/pan tipo tablet: dos dedos mueven y escalan la vista local (pellizcar
 * para acercar, arrastrar con dos dedos para paniar), nunca tocan el remoto.
 * Un dedo sigue siendo click/drag sobre el framebuffer, mapeado a traves de
 * la transformacion actual -- funciona igual esté zoomeado o no.
 */
class VncViewerActivity : AppCompatActivity(), VncClient.Listener {

    private lateinit var binding: ActivityVncViewerBinding
    private lateinit var prefs: SierraPrefs
    private var client: VncClient? = null
    private var currentBitmap: Bitmap? = null

    // Tamaño del framebuffer (se actualiza en onConnected)
    private var fbWidth = 0
    private var fbHeight = 0

    // Transformacion imagen -> vista. viewMatrix es la que se ve ahora;
    // restMatrix es el "fit" original (equivalente al fitCenter de antes),
    // sirve de piso: no se puede pellizcar mas alla de eso hacia afuera.
    private val viewMatrix = Matrix()
    private val restMatrix = Matrix()
    private var restScale = 1f
    private var matrixLista = false
    private val zoomMaximo = 6f

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // Un segundo dedo abajo pasa el gesto a pan/zoom local -- deja de
    // mandarse como click hasta soltar todos los dedos.
    private var multitouch = false
    private var clickEnCurso = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.retryButton.setOnClickListener { conectar() }

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Sin fit calculado (antes del primer frame) restScale es 0 --
                // no hay nada que pellizcar todavia.
                if (!matrixLista) return true
                val actual = escalaActual()
                val objetivo = (actual * detector.scaleFactor).coerceIn(restScale, restScale * zoomMaximo)
                val factorAplicable = objetivo / actual
                if (factorAplicable != 1f) {
                    viewMatrix.postScale(factorAplicable, factorAplicable, detector.focusX, detector.focusY)
                }
                // El foco tambien se mueve en un arrastre de dos dedos sin
                // pellizcar -- este delta es lo que paniar la vista.
                viewMatrix.postTranslate(detector.focusX - lastFocusX, detector.focusY - lastFocusY)
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                clampPan()
                aplicarMatriz()
                return true
            }
        })

        // Toques sobre la imagen → pointer events al VNC (un dedo) o
        // pan/zoom local (dos dedos).
        binding.vncImageView.setOnTouchListener { _, event -> handleTouch(event) }

        conectar()
    }

    private fun escalaActual(): Float {
        val valores = FloatArray(9)
        viewMatrix.getValues(valores)
        return valores[Matrix.MSCALE_X]
    }

    private fun inicializarMatrizFit() {
        if (matrixLista) return
        val iv = binding.vncImageView
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        if (vw <= 0f || vh <= 0f || fbWidth <= 0 || fbHeight <= 0) return

        val s = minOf(vw / fbWidth, vh / fbHeight)
        val dx = (vw - fbWidth * s) / 2f
        val dy = (vh - fbHeight * s) / 2f
        restMatrix.reset()
        restMatrix.setScale(s, s)
        restMatrix.postTranslate(dx, dy)
        restScale = s
        viewMatrix.set(restMatrix)
        matrixLista = true
        aplicarMatriz()
    }

    /** Que la imagen nunca se pueda arrastrar completamente fuera de la vista. */
    private fun clampPan() {
        val iv = binding.vncImageView
        val vw = iv.width.toFloat()
        val vh = iv.height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val rect = RectF(0f, 0f, fbWidth.toFloat(), fbHeight.toFloat())
        viewMatrix.mapRect(rect)

        var dx = 0f
        var dy = 0f
        if (rect.width() <= vw) {
            dx = (vw - rect.width()) / 2f - rect.left
        } else if (rect.left > 0f) {
            dx = -rect.left
        } else if (rect.right < vw) {
            dx = vw - rect.right
        }
        if (rect.height() <= vh) {
            dy = (vh - rect.height()) / 2f - rect.top
        } else if (rect.top > 0f) {
            dy = -rect.top
        } else if (rect.bottom < vh) {
            dy = vh - rect.bottom
        }
        if (dx != 0f || dy != 0f) viewMatrix.postTranslate(dx, dy)
    }

    private fun aplicarMatriz() {
        binding.vncImageView.imageMatrix = viewMatrix
    }

    private fun conectar() {
        client?.disconnect()
        client = null

        binding.statusTextView.text = getString(R.string.vnc_conectando)
        binding.statusTextView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.vncImageView.setImageBitmap(null)
        // Cada conexion recalcula el "fit" -- el tamano de framebuffer podria
        // cambiar (otro monitor, otra resolucion).
        matrixLista = false

        val ip = prefs.vncIp.trim().ifEmpty { SierraPrefs.DEFAULT_VNC_IP }
        val port = prefs.vncPort

        if (ip.isBlank()) {
            mostrarError(getString(R.string.vnc_error_ip_vacia))
            return
        }

        client = VncClient(ip, port, this)
        client?.connect()
    }

    /**
     * Un dedo = click/drag sobre el remoto. Dos dedos = pan/zoom local (lo
     * maneja scaleGestureDetector arriba, via postScale/postTranslate sobre
     * viewMatrix). Nunca se manda un pointer event mientras hay mas de un
     * dedo en pantalla.
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multitouch = false
                enviarClick(event, VncClient.BUTTON_LEFT)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Aparece un segundo dedo: si habia un click en curso con el
                // primero, soltamos el boton para no dejarlo pegado del lado
                // del servidor mientras pasamos a pan/zoom.
                if (!multitouch && clickEnCurso) enviarClick(event, 0)
                multitouch = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!multitouch && event.pointerCount == 1) {
                    enviarClick(event, VncClient.BUTTON_LEFT)
                }
                // multitouch: el pan/zoom ya lo aplico scaleGestureDetector.
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!multitouch) enviarClick(event, 0)
                multitouch = false
                clickEnCurso = false
            }
        }
        return true
    }

    private fun enviarClick(event: MotionEvent, buttonMask: Int) {
        val c = client ?: return
        val coords = mapTouchToFramebuffer(event.x, event.y) ?: return
        c.sendPointerEvent(coords.first, coords.second, buttonMask)
        clickEnCurso = buttonMask != 0
    }

    /**
     * Convierte coordenadas de la vista a coordenadas del framebuffer
     * invirtiendo viewMatrix -- funciona igual esté zoomeado, paniado, o en
     * el fit original, porque es literalmente la transformacion inversa de
     * lo que se esta dibujando ahora.
     *
     * Devuelve null si el toque cayo fuera de la imagen.
     */
    private fun mapTouchToFramebuffer(viewX: Float, viewY: Float): Pair<Int, Int>? {
        if (fbWidth <= 0 || fbHeight <= 0) return null

        val inversa = Matrix()
        if (!viewMatrix.invert(inversa)) return null

        val pts = floatArrayOf(viewX, viewY)
        inversa.mapPoints(pts)
        val fbXf = pts[0]
        val fbYf = pts[1]

        if (fbXf < 0f || fbYf < 0f || fbXf >= fbWidth || fbYf >= fbHeight) return null

        return fbXf.toInt().coerceIn(0, fbWidth - 1) to fbYf.toInt().coerceIn(0, fbHeight - 1)
    }

    override fun onConnected(width: Int, height: Int) {
        runOnUiThread {
            fbWidth = width
            fbHeight = height
            binding.statusTextView.text = getString(R.string.vnc_conectado, width, height)
            binding.progressBar.visibility = View.GONE
            // Dejamos el status un momento y luego lo ocultamos para no tapar la imagen
            binding.statusTextView.postDelayed({
                if (binding.vncImageView.drawable != null) {
                    binding.statusTextView.visibility = View.GONE
                }
            }, 1800)
        }
    }

    override fun onFrame(bitmap: Bitmap) {
        runOnUiThread {
            currentBitmap?.recycle()
            currentBitmap = bitmap
            inicializarMatrizFit() // no-op si ya estaba lista
            binding.vncImageView.setImageBitmap(bitmap)
            aplicarMatriz()
            binding.progressBar.visibility = View.GONE
            if (binding.statusTextView.visibility == View.VISIBLE &&
                binding.statusTextView.text.toString().startsWith("Conectado")
            ) {
                // ya se va a ocultar solo
            } else {
                binding.statusTextView.visibility = View.GONE
            }
            // Recien ahora pedimos el proximo frame -- nunca mas de uno en
            // vuelo. Sin esto la red inunda la cola del hilo principal con
            // bitmaps de 4K sin consumir y la app se ahoga en vez de mostrar
            // nada (ver VncClient.frameConsumed).
            client?.frameConsumed()
        }
    }

    override fun onError(error: VncClient.VncError) {
        runOnUiThread {
            val mensaje = when (error) {
                is VncClient.VncError.ConnectionFailed ->
                    getString(R.string.vnc_error_conexion, error.message)
                is VncClient.VncError.Timeout ->
                    getString(R.string.vnc_error_timeout)
                is VncClient.VncError.ProtocolError ->
                    getString(R.string.vnc_error_protocolo, error.message)
                is VncClient.VncError.Unsupported ->
                    getString(R.string.vnc_error_no_soportado, error.message)
                is VncClient.VncError.Closed ->
                    getString(R.string.vnc_error_cerrado)
                is VncClient.VncError.Unknown ->
                    getString(R.string.vnc_error_desconocido, error.message)
            }
            mostrarError(mensaje)
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            // Solo mostramos "desconectado" si no hay un error ya visible
            if (binding.retryButton.visibility != View.VISIBLE) {
                binding.statusTextView.text = getString(R.string.vnc_desconectado)
                binding.statusTextView.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.retryButton.visibility = View.VISIBLE
            }
        }
    }

    private fun mostrarError(mensaje: String) {
        binding.statusTextView.text = mensaje
        binding.statusTextView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.retryButton.visibility = View.VISIBLE
        Toast.makeText(this, mensaje, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        client?.disconnect()
        client = null
        currentBitmap?.recycle()
        currentBitmap = null
        super.onDestroy()
    }
}
