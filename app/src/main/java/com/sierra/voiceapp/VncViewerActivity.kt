package com.sierra.voiceapp

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sierra.voiceapp.databinding.ActivityVncViewerBinding
import com.sierra.voiceapp.network.VncClient

/**
 * Pantalla de vista en vivo de sierra-pc vía VNC (wayvnc).
 * Prioridad: que se vea la pantalla. Mouse/taps se pueden agregar después.
 */
class VncViewerActivity : AppCompatActivity(), VncClient.Listener {

    private lateinit var binding: ActivityVncViewerBinding
    private lateinit var prefs: SierraPrefs
    private var client: VncClient? = null
    private var currentBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVncViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.retryButton.setOnClickListener { conectar() }

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

    override fun onConnected(width: Int, height: Int) {
        runOnUiThread {
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
