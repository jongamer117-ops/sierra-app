package com.sierra.voiceapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.sierra.voiceapp.databinding.ActivitySettingsBinding
import com.sierra.voiceapp.network.GithubUpdateClient
import com.sierra.voiceapp.network.UpdateError
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SierraPrefs
    private val updateClient = GithubUpdateClient()

    // Guardados para poder instalar directo al volver de "instalar apps
    // desconocidas", sin que el usuario tenga que tocar el botón de nuevo.
    private var apkPendienteDeInstalar: File? = null
    private var shaPendienteDeInstalar: String? = null

    private val permisoInstalarLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val archivo = apkPendienteDeInstalar
            val sha = shaPendienteDeInstalar
            if (archivo != null && sha != null && puedeInstalarPaquetes()) {
                lanzarInstalador(archivo, sha)
            } else if (archivo != null) {
                binding.updateStatusTextView.text = getString(R.string.settings_update_permiso_requerido)
                binding.actualizarButton.isEnabled = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)

        binding.ipEditText.setText(prefs.serverIp)
        binding.portEditText.setText(prefs.serverPort.toString())
        binding.tokenEditText.setText(prefs.token)

        binding.saveButton.setOnClickListener { guardarYSalir() }
        binding.actualizarButton.setOnClickListener { buscarActualizacion() }
    }

    private fun guardarYSalir() {
        val token = binding.tokenEditText.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.settings_token_requerido, Toast.LENGTH_LONG).show()
            return
        }

        val ip = binding.ipEditText.text.toString().trim().ifEmpty { SierraPrefs.DEFAULT_IP }
        val port = binding.portEditText.text.toString().trim().toIntOrNull() ?: SierraPrefs.DEFAULT_PORT

        prefs.serverIp = ip
        prefs.serverPort = port
        prefs.token = token

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun buscarActualizacion() {
        binding.actualizarButton.isEnabled = false
        binding.updateStatusTextView.text = getString(R.string.settings_update_checking)

        updateClient.fetchLatestCommitSha(
            onSuccess = { sha -> runOnUiThread { onShaObtenido(sha) } },
            onError = { error -> runOnUiThread { mostrarErrorActualizacion(error) } }
        )
    }

    private fun onShaObtenido(shaRemoto: String) {
        if (shaRemoto == prefs.lastInstalledCommitSha) {
            binding.updateStatusTextView.text = getString(R.string.settings_update_none)
            binding.actualizarButton.isEnabled = true
            return
        }
        descargarYInstalar(shaRemoto)
    }

    private fun descargarYInstalar(shaRemoto: String) {
        binding.updateStatusTextView.text = getString(R.string.settings_update_downloading)

        val carpetaUpdates = File(getExternalFilesDir(null), "updates").apply { mkdirs() }
        val destino = File(carpetaUpdates, "sierra-voice-app-update.apk")

        updateClient.downloadApk(
            destino = destino,
            onSuccess = { archivo -> runOnUiThread { instalarApk(archivo, shaRemoto) } },
            onError = { error -> runOnUiThread { mostrarErrorActualizacion(error) } }
        )
    }

    private fun puedeInstalarPaquetes(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun instalarApk(archivo: File, shaRemoto: String) {
        if (!puedeInstalarPaquetes()) {
            // Guardamos qué instalar para retomar automáticamente apenas el
            // usuario habilite el permiso y vuelva a la app, sin que tenga
            // que tocar "Buscar actualización" de nuevo.
            apkPendienteDeInstalar = archivo
            shaPendienteDeInstalar = shaRemoto
            binding.updateStatusTextView.text = getString(R.string.settings_update_permiso_requerido)
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            permisoInstalarLauncher.launch(intent)
            return
        }

        lanzarInstalador(archivo, shaRemoto)
    }

    private fun lanzarInstalador(archivo: File, shaRemoto: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", archivo)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        binding.updateStatusTextView.text = getString(R.string.settings_update_ready)
        binding.actualizarButton.isEnabled = true
        apkPendienteDeInstalar = null
        shaPendienteDeInstalar = null
        prefs.lastInstalledCommitSha = shaRemoto
        startActivity(installIntent)
    }

    private fun mostrarErrorActualizacion(error: UpdateError) {
        binding.updateStatusTextView.text = getString(R.string.settings_update_error, error.message ?: "")
        binding.actualizarButton.isEnabled = true
    }
}
