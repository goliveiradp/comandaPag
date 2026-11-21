package br.com.comandapag.rede

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Mantém o app vivo enquanto esta maquininha é o servidor da rede, mesmo com a tela
 * apagada. O servidor em si é criado nas dependências do app; aqui só fica a notificação.
 */
class ServicoServidor : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION") // Notification.Builder(context) só é usado antes do Android 8
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gerenciador = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            gerenciador.createNotificationChannel(
                NotificationChannel(CANAL, "Servidor ComandaPag", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notificacao = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL) else Notification.Builder(this))
            .setContentTitle("ComandaPag: servidor ativo")
            .setContentText("As outras maquininhas estão usando os dados deste aparelho.")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notificacao)
        }
        return START_STICKY
    }

    companion object {
        private const val CANAL = "servidor"
        private const val ID = 1

        fun iniciar(context: Context) {
            val intent = Intent(context, ServicoServidor::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
        }

        fun parar(context: Context) {
            context.stopService(Intent(context, ServicoServidor::class.java))
        }
    }
}
