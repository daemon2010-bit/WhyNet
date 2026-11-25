package dev.whynet.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.whynet.app.MainActivity
import dev.whynet.app.R
import dev.whynet.core.engine.PacketMetadataParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class ObservedAppEndpoint(
    val key: String,
    val protocol: String,
    val dstIp: String,
    val dstPort: Int,
    val domainOrSni: String?,
    val packetCount: Int,
    val lastSeenEpochMs: Long,
    val seenAfterFreezeMark: Boolean = false,
)

data class AppObserverState(
    val isRunning: Boolean = false,
    val autoMode: Boolean = false,
    val remainingSeconds: Int = 0,
    val autoCompletedEpochMs: Long? = null,
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    val startedAtEpochMs: Long = 0L,
    val freezeMarkedAtEpochMs: Long? = null,
    val totalPackets: Int = 0,
    val dnsQueries: List<String> = emptyList(),
    val endpoints: List<ObservedAppEndpoint> = emptyList(),
    val statusMessage: String? = null,
)

object AppObserverStore {
    private val _state = MutableStateFlow(AppObserverState())
    val state: StateFlow<AppObserverState> = _state.asStateFlow()

    internal fun update(block: (AppObserverState) -> AppObserverState) {
        _state.update(block)
    }

    fun markFreezeHere() {
        _state.update {
            if (it.isRunning) {
                it.copy(
                    freezeMarkedAtEpochMs = System.currentTimeMillis(),
                    statusMessage = "Отметка «Зависло здесь!» поставлена",
                )
            } else {
                it
            }
        }
    }

    fun clear() {
        _state.value = AppObserverState()
    }
}

/**
 * Локальный per-app VpnService-наблюдатель (Этап 3).
 * Изолирует только выбранный пакет (addAllowedApplication), не расшифровывает HTTPS,
 * пробрасывает DNS через protect(DatagramSocket) и фиксирует все домены/SNI/IP/порты.
 */
class WhyNetObserverVpnService : VpnService() {

    companion object {
        const val ACTION_START = "dev.whynet.app.action.OBSERVER_START"
        const val ACTION_STOP = "dev.whynet.app.action.OBSERVER_STOP"
        const val ACTION_FINISH_AND_CHECK = "dev.whynet.app.action.OBSERVER_FINISH_AND_CHECK"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_AUTO_STOP_SEC = "extra_auto_stop_sec"
        private const val CHANNEL_ID = "whynet_app_observer"
        private const val NOTIFICATION_ID = 301

        fun start(context: Context, packageName: String, label: String, autoStopSec: Int = 0) {
            val intent = Intent(context, WhyNetObserverVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_AUTO_STOP_SEC, autoStopSec)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WhyNetObserverVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun finishAndCheckNow(context: Context) {
            val intent = Intent(context, WhyNetObserverVpnService::class.java).apply {
                action = ACTION_FINISH_AND_CHECK
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var readerJob: Job? = null
    private var autoTimerJob: Job? = null
    private val ipToDomainMap = ConcurrentHashMap<String, String>()
    private val endpointsMap = ConcurrentHashMap<String, ObservedAppEndpoint>()
    private val dnsSet = ConcurrentHashMap.newKeySet<String>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopObserving("Наблюдение остановлено", triggerAutoCheck = false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FINISH_AND_CHECK -> {
                stopObserving("Поиск адресов завершён — запускаем проверку сети...", triggerAutoCheck = true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg.orEmpty()
                val autoStopSec = intent.getIntExtra(EXTRA_AUTO_STOP_SEC, 0).coerceIn(0, 60)
                if (pkg.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startObserving(pkg, label, autoStopSec)
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopObserving("Разрешение VPN отозвано системой", triggerAutoCheck = false)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopObserving(null, triggerAutoCheck = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startObserving(packageName: String, label: String, autoStopSec: Int) {
        stopObserving(null, triggerAutoCheck = false)
        ensureChannel()
        val isAuto = autoStopSec > 0
        val notification = buildNotification(label, remainingSec = if (isAuto) autoStopSec else null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            val builder = Builder()
                .setSession("WhyNet Observer ($label)")
                .setMtu(1500)
                .addAddress("10.215.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addAllowedApplication(packageName)

            val pfd = builder.establish()
                ?: throw IllegalStateException("Система не выдала локальный VPN-интерфейс")

            tunInterface = pfd
            endpointsMap.clear()
            dnsSet.clear()
            ipToDomainMap.clear()

            AppObserverStore.update {
                AppObserverState(
                    isRunning = true,
                    autoMode = isAuto,
                    remainingSeconds = autoStopSec,
                    autoCompletedEpochMs = null,
                    targetPackage = packageName,
                    targetLabel = label,
                    startedAtEpochMs = System.currentTimeMillis(),
                    statusMessage = if (isAuto) {
                        "Автопоиск серверов $label ($autoStopSec с) — откройте приложение и подождите"
                    } else {
                        "Слушаем соединения $label (откройте приложение на 5–10 с)"
                    },
                )
            }

            readerJob = serviceScope.launch {
                runTunLoop(pfd)
            }

            if (isAuto) {
                autoTimerJob = serviceScope.launch {
                    val nm = getSystemService(NotificationManager::class.java)
                    for (sec in autoStopSec downTo 1) {
                        AppObserverStore.update { it.copy(remainingSeconds = sec) }
                        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(label, remainingSec = sec)) }
                        delay(1000L)
                    }
                    stopObserving("Поиск адресов завершён — запускаем проверку сети...", triggerAutoCheck = true)
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            AppObserverStore.update {
                it.copy(
                    isRunning = false,
                    autoMode = false,
                    remainingSeconds = 0,
                    statusMessage = "Не удалось запустить наблюдатель: ${e.message}",
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopObserving(message: String?, triggerAutoCheck: Boolean) {
        autoTimerJob?.cancel()
        autoTimerJob = null
        readerJob?.cancel()
        readerJob = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }

        AppObserverStore.update { prev ->
            prev.copy(
                isRunning = false,
                remainingSeconds = 0,
                autoCompletedEpochMs = if (triggerAutoCheck) System.currentTimeMillis() else prev.autoCompletedEpochMs,
                statusMessage = message ?: prev.statusMessage,
            )
        }
    }

    private fun runTunLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(32767)
        var totalPkts = 0

        while (serviceScope.isActive) {
            val len = try {
                input.read(packet)
            } catch (_: Exception) {
                break
            }
            if (len <= 0) continue

            val parsed = PacketMetadataParser.parseIpv4Packet(packet, len) ?: continue
            totalPkts++

            // Запоминаем DNS-запросы и пробрасываем UDP:53, чтобы приложение получило IP и показало свои серверы
            if (parsed.protocol == "UDP" && parsed.dstPort == 53) {
                parsed.dnsQueryName?.let { qname ->
                    dnsSet.add(qname)
                }
                val copy = packet.copyOf(len)
                serviceScope.launch {
                    forwardDnsUdp(copy, output)
                }
            }

            val knownDomain = parsed.tlsSni ?: parsed.dnsQueryName ?: ipToDomainMap[parsed.dstIp]
            if (!knownDomain.isNullOrBlank() && parsed.dstPort != 53) {
                ipToDomainMap[parsed.dstIp] = knownDomain
            }

            if (parsed.dstPort != 53) {
                val key = "${parsed.protocol}_${parsed.dstIp}_${parsed.dstPort}"
                val now = System.currentTimeMillis()
                val afterFreeze = AppObserverStore.state.value.freezeMarkedAtEpochMs != null
                endpointsMap.compute(key) { _, old ->
                    ObservedAppEndpoint(
                        key = key,
                        protocol = parsed.protocol,
                        dstIp = parsed.dstIp,
                        dstPort = parsed.dstPort,
                        domainOrSni = knownDomain ?: old?.domainOrSni,
                        packetCount = (old?.packetCount ?: 0) + 1,
                        lastSeenEpochMs = now,
                        seenAfterFreezeMark = (old?.seenAfterFreezeMark == true) || afterFreeze,
                    )
                }
            }

            AppObserverStore.update { st ->
                st.copy(
                    totalPackets = totalPkts,
                    dnsQueries = dnsSet.toList().sorted(),
                    endpoints = endpointsMap.values.sortedByDescending { it.lastSeenEpochMs },
                )
            }
        }
    }

    private fun forwardDnsUdp(origPacket: ByteArray, tunOut: FileOutputStream) {
        runCatching {
            val ihl = (origPacket[0].toInt() and 0x0F) * 4
            val udpOffset = ihl
            val payloadOffset = udpOffset + 8
            val payloadLen = origPacket.size - payloadOffset
            if (payloadLen <= 0) return

            val srcPort = ((origPacket[udpOffset].toInt() and 0xFF) shl 8) or (origPacket[udpOffset + 1].toInt() and 0xFF)
            val dstPort = ((origPacket[udpOffset + 2].toInt() and 0xFF) shl 8) or (origPacket[udpOffset + 3].toInt() and 0xFF)
            val dstIpBytes = origPacket.copyOfRange(16, 20)
            val srcIpBytes = origPacket.copyOfRange(12, 16)

            DatagramSocket().use { sock ->
                protect(sock)
                sock.soTimeout = 2000
                val upstream = InetAddress.getByAddress(dstIpBytes)
                sock.send(DatagramPacket(origPacket, payloadOffset, payloadLen, upstream, dstPort))

                val respBuf = ByteArray(1500)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPkt)

                val dnsRespLen = respPkt.length
                val ipResp = buildIpv4UdpResponse(
                    srcIp = dstIpBytes,
                    dstIp = srcIpBytes,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    payload = respBuf,
                    payloadLen = dnsRespLen,
                )
                synchronized(tunOut) {
                    tunOut.write(ipResp)
                }
            }
        }
    }

    private fun buildIpv4UdpResponse(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLen: Int,
    ): ByteArray {
        val totalLen = 20 + 8 + payloadLen
        val out = ByteArray(totalLen)
        out[0] = 0x45
        out[1] = 0x00
        out[2] = ((totalLen ushr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[6] = 0x40
        out[8] = 64
        out[9] = 17 // UDP
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)

        // IPv4 header checksum
        var sum = 0
        var i = 0
        while (i < 20) {
            val word = ((out[i].toInt() and 0xFF) shl 8) or (out[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val csum = sum.inv() and 0xFFFF
        out[10] = ((csum ushr 8) and 0xFF).toByte()
        out[11] = (csum and 0xFF).toByte()

        // UDP header (checksum = 0 permitted for IPv4 UDP)
        val udpLen = 8 + payloadLen
        out[20] = ((srcPort ushr 8) and 0xFF).toByte()
        out[21] = (srcPort and 0xFF).toByte()
        out[22] = ((dstPort ushr 8) and 0xFF).toByte()
        out[23] = (dstPort and 0xFF).toByte()
        out[24] = ((udpLen ushr 8) and 0xFF).toByte()
        out[25] = (udpLen and 0xFF).toByte()
        out[26] = 0
        out[27] = 0

        System.arraycopy(payload, 0, out, 28, payloadLen)
        return out
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Наблюдение приложений WhyNet", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(appLabel: String, remainingSec: Int? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WhyNetObserverVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val finishNowIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, WhyNetObserverVpnService::class.java).setAction(ACTION_FINISH_AND_CHECK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (remainingSec != null) {
            "WhyNet ищет серверы: $appLabel (${remainingSec} с)"
        } else {
            "WhyNet наблюдает: $appLabel"
        }
        val text = if (remainingSec != null) {
            "Затем локальный VPN отключится и начнётся автопроверка"
        } else {
            "Фиксируем DNS, SNI и порты (без расшифровки HTTPS)"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_net_check)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
        if (remainingSec != null) {
            builder.addAction(Notification.Action.Builder(null, "Готово, проверить", finishNowIntent).build())
        }
        builder.addAction(Notification.Action.Builder(null, "Остановить", stopIntent).build())
        return builder.build()
    }
}
