package com.retrovika.app.core.catalog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.settings.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serviço em primeiro plano enquanto há downloads na fila: os downloads rodam no escopo do app, e sem ele o
 * Android mata o processo (e o download de vários GB no meio) pouco depois de o usuário sair do app. Mostra
 * a notificação com a quantidade e o progresso somado.
 *
 * O [DownloadManager] só o inicia (quando a fila deixa de estar vazia); quem o encerra é ele mesmo, ao ver a
 * fila vazia. Assim o startForeground sempre roda antes de parar: um serviço iniciado com
 * startForegroundService que para antes disso derruba o app.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val downloads = applicationContext.container.downloads
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(downloads.tasks.value), foregroundType())
        } catch (t: Throwable) {
            // Sem permissão para o primeiro plano agora (app em segundo plano no Android 12+): os downloads
            // continuam no escopo do app, só sem a proteção do serviço.
            Log.w(TAG, "startForeground falhou", t)
            running = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        if (watcher?.isActive != true) watcher = scope.launch { watch(downloads) }
        return START_NOT_STICKY
    }

    private suspend fun watch(downloads: DownloadManager) {
        val manager = getSystemService(NotificationManager::class.java)
        while (true) {
            val list = downloads.tasks.value
            val active = list.count { it.status in DownloadManager.ACTIVE }
            if (active == 0) {
                // Primeiro avisa que vai parar, depois confere de novo: um download pedido entre a leitura acima e
                // este ponto viu running = true e não pediu o serviço; a nova leitura o encontra e o serviço segue.
                running = false
                if (downloads.tasks.value.any { it.status in DownloadManager.ACTIVE }) {
                    running = true
                    continue
                }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                // Com o id do último pedido: se a fila voltou a ter itens e um pedido novo chegou, ele não para.
                stopSelf(lastStartId)
                watcher = null
                return
            }
            runCatching { manager?.notify(NOTIFICATION_ID, build(list)) }
            // Atualiza uma vez por segundo, ou na hora quando a quantidade muda. Contada direto da lista (não do
            // activeCount, derivado e atrasado): com ele, a diferença momentânea virava um laço sem suspender.
            withTimeoutOrNull(UPDATE_MS) { downloads.tasks.first { l -> l.count { it.status in DownloadManager.ACTIVE } != active } }
        }
    }

    private fun build(list: List<DownloadTask>): Notification {
        val res = localized()
        ensureChannel(this)
        val active = list.filter { it.status in DownloadManager.ACTIVE }
        val busy = active.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.EXTRACTING }
        val known = busy.filter { it.status == DownloadStatus.DOWNLOADING && it.bytesTotal > 0 }
        val total = known.sumOf { it.bytesTotal }
        val done = known.sumOf { it.bytesDone.coerceAtMost(it.bytesTotal) }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(res.resources.getQuantityString(R.plurals.download_notification_title, active.size.coerceAtLeast(1), active.size.coerceAtLeast(1)))
            .setContentText(busy.firstOrNull()?.title ?: active.firstOrNull()?.title)
            .setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (open != null) setContentIntent(open) }
            .build()
    }

    /** Android 15+: o tipo dataSync tem limite de horas por dia; ao estourar, para sem derrubar os downloads. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        running = false
        watcher?.cancel()
        watcher = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4101
        private const val UPDATE_MS = 1_000L

        /** O serviço está em primeiro plano acompanhando a fila. */
        @Volatile var running = false
            private set

        private fun foregroundType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        /** Canal com o nome no idioma do app; recriar só atualiza o nome. */
        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val name = context.localized().getString(R.string.download_channel_name)
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            runCatching { context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel) }
        }

        /**
         * Inicia o serviço. Chamado quando a fila deixa de estar vazia, o que acontece pela tela (um download
         * pedido pelo usuário); com o app em segundo plano o Android 12+ recusa, e o download segue sem ele.
         */
        fun start(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (t: Throwable) {
                // ForegroundServiceStartNotAllowedException (Android 12+) ou IllegalStateException (8–11).
                Log.w(TAG, "não deu para iniciar o serviço de downloads", t)
            }
        }
    }
}
