package com.sierra.voiceapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.sierra.voiceapp.network.CanalAConfirmationsClient

/**
 * Recibe el toque en "Aprobar"/"Rechazar" de una notificación de
 * confirmación pendiente y llama a Canal A directo, sin abrir la app.
 *
 * Sigue siendo una decisión humana explícita por cada acción -- esto solo
 * evita el paso extra de abrir la pantalla de Confirmaciones para lo mismo
 * que ya hace esa pantalla.
 */
class ConfirmacionActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECIDIR) return
        val confirmationId = intent.getStringExtra(EXTRA_CONFIRMATION_ID) ?: return
        val aprobar = intent.getBooleanExtra(EXTRA_APROBAR, false)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        val prefs = SierraPrefs(context)
        if (!prefs.hasToken()) return

        val pendingResult = goAsync()
        val client = CanalAConfirmationsClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        val manager = NotificationManagerCompat.from(context)

        val terminar: () -> Unit = {
            manager.cancel(notificationId)
            pendingResult.finish()
        }

        if (aprobar) {
            client.approve(confirmationId, onSuccess = terminar, onError = { pendingResult.finish() })
        } else {
            client.reject(confirmationId, onSuccess = terminar, onError = { pendingResult.finish() })
        }
    }

    companion object {
        const val ACTION_DECIDIR = "com.sierra.voiceapp.ACCION_CONFIRMACION"
        const val EXTRA_CONFIRMATION_ID = "confirmation_id"
        const val EXTRA_APROBAR = "aprobar"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
