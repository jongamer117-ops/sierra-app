package com.sierra.voiceapp

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sierra.voiceapp.databinding.ActivityVncViewerBinding
import com.sierra.voiceapp.network.VncClient

/**
 * Pantalla de vista en vivo de sierra-pc vía VNC (wayvnc).
 * Incluye soporte de toques/clicks mapeados al framebuffer remoto.
 */
class VncViewerActivity : AppCompatActivity(), VncClient.Listener {

    private lateinit var binding: ActivityVncViewerBinding
    private lateinit var prefs: SierraPrefs
    private var client: VncClient? = null
    private var currentBitmap: Bitmap? = null

    // Tamaño del framebuffer (se actualiza en onConnected)
    private var fbWidth = 0
    private var fbHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.retryButton.setOnClickListener { conectar() }

        // Toques sobre la imagen → pointer events al VNC
        binding.vncImageView.setOnTouchListener { _, event -> handleTouch(event) }

        conectar()
    }

    private fun conectar() {
        client?.disconnect()
        client = null

        binding.statusTextView.text = getString(R.string.vnc_conectando)
        binding.statusTextView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.VISIBLE
        binding.retryButton.visibility = View.GONE
        binding.vncImageView.setImageBitmap(null)

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
     * Mapea un MotionEvent del ImageView (fitCenter) a coordenadas del framebuffer
     * y envía el PointerEvent correspondiente.
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        val c = client ?: return false
        if (fbWidth <= 0 || fbHeight <= 0) return false

        val coords = mapTouchToFramebuffer(event.x, event.y) ?: return false
        val (fbX, fbY) = coords

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                c.sendPointerEvent(fbX, fbY, VncClient.BUTTON_LEFT)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Drag con botón izquierdo presionado
                c.sendPointerEvent(fbX, fbY, VncClient.BUTTON_LEFT)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Soltar botón
                c.sendPointerEvent(fbX, fbY, 0)
                return true
            }
        }
        return false
    }

    /**
     * Convierte coordenadas de la vista (ImageView con scaleType=fitCenter)
     * a coordenadas del framebuffer remoto.
     *
     * Devuelve null si el toque cayó fuera de la imagen (en las barras negras).
     */
    private fun mapTouchToFramebuffer(viewX: Float, viewY: Float): Pair<Int, Int>? {
        val imageView = binding.vncImageView
        val drawable = imageView.drawable ?: return null

        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return null

        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        if (imageWidth <= 0f || imageHeight <= 0f) return null

        // Escala que usa fitCenter
        val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        // Offset por letterboxing (centrado)
        val offsetX = (viewWidth - scaledWidth) / 2f
        val offsetY = (viewHeight - scaledHeight) / 2f

        // Coordenadas relativas a la imagen escalada
        val imgX = viewX - offsetX
        val imgY = viewY - offsetY

        // Fuera de la imagen real → ignorar
        if (imgX < 0f || imgY < 0f || imgX > scaledWidth || imgY > scaledHeight) {
            return null
        }

        // Mapear a framebuffer
        val fbX = ((imgX / scaledWidth) * fbWidth).toInt().coerceIn(0, fbWidth - 1)
        val fbY = ((imgY / scaledHeight) * fbHeight).toInt().coerceIn(0, fbHeight - 1)

        return fbX to fbY
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
            binding.vncImageView.setImageBitmap(bitmap)
            binding.progressBar.visibility = View.GONE
            if (binding.statusTextView.visibility == View.VISIBLE &&
                binding.statusTextView.text.toString().startsWith("Conectado")
            ) {
                // ya se va a ocultar solo
            } else {
                binding.statusTextView.visibility = View.GONE
            }
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
