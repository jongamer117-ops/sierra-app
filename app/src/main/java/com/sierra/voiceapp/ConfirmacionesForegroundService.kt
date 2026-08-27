package com.sierra.voiceapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sierra.voiceapp.network.CanalAConfirmationsClient
import com.sierra.voiceapp.network.ConfirmacionPendiente
import java.time.Instant

/**
 * Servicio en primer plano que vigila confirmaciones de Nivel 3 pendientes
 * en Canal A y las notifica con botones de Aprobar/Rechazar directo en la
 * notificación -- sin tener que abrir la app cada vez.
 *
 * El ícono fijo en la barra de notificaciones es requisito de Android para
 * servicios en primer plano; no se puede ocultar mientras el servicio está
 * activo (se puede desactivar entero desde Ajustes).
 *
 * Nunca aprueba/rechaza nada por sí solo -- solo avisa. La decisión sigue
 * siendo siempre humana (INVARIANTE #11 del canon de Sierra): esto no
 * mueve la aprobación a Telegram ni a ningún canal donde viva el decisor,
 * sigue siendo esta app, en este dispositivo, fuera de banda.
 */
class ConfirmacionesForegroundService : Service() {

    private lateinit var prefs: SierraPrefs
    private val pollHandler = Handler(Looper.getMainLooper())
    private val idsYaNotificados = mutableSetOf<String>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            revisarPendientes()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = SierraPrefs(this)
        SierraPresence.inicializar(applicationContext)
        crearCanales()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_NOTIFICATION_ID, notificacionVigilando(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notificacionVigilando())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun revisarPendientes() {
        if (!prefs.hasToken()) return
        val client = CanalAConfirmationsClient(baseUrl = prefs.baseUrl(), token = prefs.token)
        client.fetchPending(
            onSuccess = { pendientes -> procesarPendientes(pendientes) },
            // Fallo de red puntual: se reintenta solo en el siguiente poll,
            // sin molestar con un toast (no hay UI visible aqui de todas formas).
            onError = { }
        )
    }

    private fun procesarPendientes(pendientes: List<ConfirmacionPendiente>) {
        val idsActuales = pendientes.map { it.confirmationId }.toSet()
        val manager = NotificationManagerCompat.from(this)

        // Quita notificaciones de confirmaciones que ya no siguen pendientes
        // (aprobadas/rechazadas/expiradas desde la pantalla o desde aqui mismo).
        (idsYaNotificados - idsActuales).forEach { id -> manager.cancel(notificationIdPara(id)) }
        idsYaNotificados.retainAll(idsActuales)

        pendientes.filter { it.confirmationId !in idsYaNotificados }.forEach { confirmacion ->
            idsYaNotificados.add(confirmacion.confirmationId)
            notificarConfirmacion(confirmacion)
        }

        // El mismo pulso que ya late para notificar alimenta el estado de la
        // home: el icono de confirmaciones estaba mudo hasta que lo tocabas.
        // Esto solo avisa -- la decision sigue siendo humana y en su pantalla.
        val proxima = pendientes.minByOrNull { it.expiresAt }
        SierraPresence.confirmacionesVivas(
            cantidad = pendientes.size,
            expiraEnSegundos = proxima?.let { segundosHasta(it.expiresAt) }
        )
    }

    private fun segundosHasta(expiresAt: String): Long = try {
        (Instant.parse(expiresAt).epochSecond - Instant.now().epochSecond).coerceAtLeast(0L)
    } catch (e: Exception) {
        0L
    }

    private fun notificarConfirmacion(confirmacion: ConfirmacionPendiente) {
        val notificationId = notificationIdPara(confirmacion.confirmationId)

        val aprobarIntent = accionPendingIntent(confirmacion.confirmationId, notificationId, aprobar = true)
        val rechazarIntent = accionPendingIntent(confirmacion.confirmationId, notificationId, aprobar = false)
        val abrirAppIntent = PendingIntent.getActivity(
            this, notificationId,
            Intent(this, ConfirmacionesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTAS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_confirmacion_titulo))
            .setContentText(confirmacion.confirmationDetail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(confirmacion.confirmationDetail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .setContentIntent(abrirAppIntent)
            .addAction(0, getString(R.string.btn_aprobar), aprobarIntent)
            .addAction(0, getString(R.string.btn_rechazar), rechazarIntent)
            .build()

        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    private fun accionPendingIntent(confirmationId: String, notificationId: Int, aprobar: Boolean): PendingIntent {
        val intent = Intent(this, ConfirmacionActionReceiver::class.java).apply {
            action = ConfirmacionActionReceiver.ACTION_DECIDIR
            putExtra(ConfirmacionActionReceiver.EXTRA_CONFIRMATION_ID, confirmationId)
            putExtra(ConfirmacionActionReceiver.EXTRA_APROBAR, aprobar)
            putExtra(ConfirmacionActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        // requestCode distinto para aprobar vs rechazar del mismo
        // confirmationId -- si no, PendingIntent los trata como el mismo
        // intent y el segundo pisa al primero.
        val requestCode = notificationId * 2 + if (aprobar) 0 else 1
        return PendingIntent.getBroadcast(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationIdPara(confirmationId: String): Int =
        NOTIFICATION_ID_BASE + (confirmationId.hashCode() and 0x0FFFFFFF)

    private fun notificacionVigilando(): Notification =
        NotificationCompat.Builder(this, CHANNEL_VIGILANCIA)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_vigilando_titulo))
            .setContentText(getString(R.string.notif_vigilando_texto))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun crearCanales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_VIGILANCIA, getString(R.string.notif_canal_vigilancia), NotificationManager.IMPORTANCE_MIN)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTAS, getString(R.string.notif_canal_alertas), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        private const val POLL_INTERVAL_MS = 4000L
        private const val ONGOING_NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ID_BASE = 2000
        private const val CHANNEL_VIGILANCIA = "sierra_vigilancia"
        private const val CHANNEL_ALERTAS = "sierra_confirmaciones"
    }
}
