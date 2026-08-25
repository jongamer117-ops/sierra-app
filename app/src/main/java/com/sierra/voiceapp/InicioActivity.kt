package com.sierra.voiceapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sierra.voiceapp.databinding.ActivityInicioBinding

/**
 * Pantalla de entrada de la app: elegir entre ver la pantalla de sierra-pc
 * en vivo (VNC) o mandar comandos por voz/chat (MainActivity, sin cambios).
 */
class InicioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInicioBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInicioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.verPantallaOpcion.setOnClickListener {
            startActivity(Intent(this, VncViewerActivity::class.java))
        }
        binding.mandarComandosOpcion.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
    }
}
