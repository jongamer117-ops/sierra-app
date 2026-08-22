package com.sierra.voiceapp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sierra.voiceapp.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SierraPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = SierraPrefs(this)

        binding.ipEditText.setText(prefs.serverIp)
        binding.portEditText.setText(prefs.serverPort.toString())
        binding.tokenEditText.setText(prefs.token)

        binding.saveButton.setOnClickListener { guardarYSalir() }
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
}
