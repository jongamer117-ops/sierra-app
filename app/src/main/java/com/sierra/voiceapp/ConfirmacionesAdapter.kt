package com.sierra.voiceapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sierra.voiceapp.databinding.ItemConfirmacionBinding
import com.sierra.voiceapp.network.ConfirmacionPendiente
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

class ConfirmacionesAdapter(
    private val onAprobar: (ConfirmacionPendiente) -> Unit,
    private val onRechazar: (ConfirmacionPendiente) -> Unit
) : RecyclerView.Adapter<ConfirmacionesAdapter.ViewHolder>() {

    private val items = mutableListOf<ConfirmacionPendiente>()

    fun submitList(nuevos: List<ConfirmacionPendiente>) {
        items.clear()
        items.addAll(nuevos)
        notifyDataSetChanged()
    }

    /** Recalcula el texto de "expira en" de los items visibles sin volver a pegarle a la red. */
    fun refreshCountdowns() {
        notifyItemRangeChanged(0, items.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConfirmacionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemConfirmacionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(confirmacion: ConfirmacionPendiente) {
            binding.detalleTextView.text = confirmacion.confirmationDetail
            binding.expiraTextView.text = expiraEnTexto(confirmacion.expiresAt, binding.root.context)

            binding.aprobarButton.setOnClickListener { onAprobar(confirmacion) }
            binding.rechazarButton.setOnClickListener { onRechazar(confirmacion) }
        }

        private fun expiraEnTexto(expiresAtIso: String, context: android.content.Context): String {
            val segundosRestantes = try {
                Duration.between(Instant.now(), Instant.parse(expiresAtIso)).seconds
            } catch (e: DateTimeParseException) {
                return context.getString(R.string.confirmacion_expira_desconocido)
            }

            if (segundosRestantes <= 0) {
                return context.getString(R.string.confirmacion_expirada)
            }
            val minutos = segundosRestantes / 60
            val segundos = segundosRestantes % 60
            return context.getString(R.string.confirmacion_expira_en, minutos, segundos)
        }
    }
}
