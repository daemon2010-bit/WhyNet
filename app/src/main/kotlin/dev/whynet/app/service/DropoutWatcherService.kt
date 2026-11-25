package dev.whynet.app.service

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
import dev.whynet.app.MainActivity
import dev.whynet.app.R
import dev.whynet.core.probes.EnvironmentCollector
import dev.whynet.core.probes.NetworkProbes
import dev.whynet.core.probes.PingTick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DropoutWatcherState(
    val isRunning: Boolean = false,
    val host: String = "1.1.1.1",
    val ticks: List<PingTick> = emptyList(),
    val totalSent: Int = 0,
    val totalLost: Int = 0,
    val lastLine: String? = null,
)

object DropoutWatcherStore {
    private val _state = MutableStateFlow(DropoutWatcherState())
    val state: StateFlow<DropoutWatcherState> = _state.asStateFlow()

    internal fun update(block: (DropoutWatcherState) -> DropoutWatcherState) {
        _state.update(block)
    }
}

/**
 * Фоновый ForegroundService для многочасового «Ловца обрывов».
 * Работает при свёрнутом приложении и выключенном экране, обновляя статус в шторке.
 */
class DropoutWatcherService : Service() {

    companion object {
        const val ACTION_START = "dev.whynet.app.action.WATCHER_START"
        const val ACTION_STOP = "dev.whynet.app.action.WATCHER_STOP"
        const val EXTRA_HOST = "extra_host"
        private const val CHANNEL_ID = "whynet_dropout_watcher"
        private const val NOTIFICATION_ID = 302

        fun start(context: Context, host: String) {
            val intent = Intent(context, DropoutWatcherService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DropoutWatcherService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWatching()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST)?.ifBlank { "1.1.1.1" } ?: "1.1.1.1"
                startWatching(host)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopWatching()
        scope.cancel()
        super.onDestroy()
    }

    private fun startWatching(host: String) {
        watchJob?.cancel()
        ensureChannel()
        val initialNotif = buildNotification(host, "Запуск мониторинга...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        DropoutWatcherStore.update {
            DropoutWatcherState(isRunning = true, host = host, lastLine = "Фоновое наблюдение запущено ($host)...")
        }

        val probes = NetworkProbes(applicationContext)
        val env = EnvironmentCollector(applicationContext)
        val nm = getSystemService(NotificationManager::class.java)

        watchJob = scope.launch {
            val snap = env.collectSnapshot(fetchExternalIp = false)
            var sent = 0
            var lost = 0
            probes.observeContinuousPing(
                gatewayIp = snap.gatewayIp,
                externalHost = host,
                intervalMs = 1000L,
                maxTicks = 7200, // до 2 часов
            ).collect { tick ->
                sent++
                if (tick.externalRttMs == null) lost++
                val extStr = tick.externalRttMs?.let { "${"%.0f".format(it)} мс" } ?: "ПОТЕРЯ"
                val lossPct = (lost.toDouble() / sent) * 100.0
                val line = "#$sent: $host $extStr · потери ${"%.1f".format(lossPct)}%"

                DropoutWatcherStore.update { st ->
                    st.copy(
                        isRunning = true,
                        ticks = (st.ticks + tick).takeLast(120),
                        totalSent = sent,
                        totalLost = lost,
                        lastLine = line,
                    )
                }
                if (sent % 3 == 0 || tick.externalRttMs == null) {
                    nm.notify(NOTIFICATION_ID, buildNotification(host, line))
                }
            }
            stopWatching()
            stopSelf()
        }
    }

    private fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        DropoutWatcherStore.update { it.copy(isRunning = false, lastLine = "Наблюдение остановлено") }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ловец обрывов WhyNet", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(host: String, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, DropoutWatcherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_net_check)
            .setContentTitle("WhyNet · Ловец обрывов ($host)")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Стоп", stopIntent).build())
            .build()
    }
}
