package dev.whynet.core.tunnel

import dev.whynet.core.model.ProbeResult
import kotlinx.coroutines.flow.Flow

/**
 * Контракт для Этапа 3: локальный VpnService-наблюдатель соединений выбранного приложения (без root и без расшифровки HTTPS).
 */
interface LocalTrafficObserver {
    val isSupported: Boolean
    fun observePackageConnections(packageName: String): Flow<ObservedConnection>
    suspend fun stop()
}

data class ObservedConnection(
    val timestampEpochMs: Long,
    val protocol: String,
    val destinationIp: String,
    val destinationPort: Int,
    val domainOrSni: String?,
    val bytesSent: Long,
    val bytesReceived: Long,
)

/**
 * Контракт для Этапа 4: опциональный временный контрольный VPN-туннель только для процесса диагноста (схема A-B-A).
 * В Этапе 1-2 используется NoOpControlTunnelProvider.
 */
interface ControlTunnelProvider {
    val isConfigured: Boolean
    val providerName: String

    suspend fun runIsolatedControlCheck(
        block: suspend () -> List<ProbeResult>,
    ): ControlTunnelResult
}

data class ControlTunnelResult(
    val executed: Boolean,
    val tunnelError: String? = null,
    val probes: List<ProbeResult> = emptyList(),
)

class NoOpControlTunnelProvider : ControlTunnelProvider {
    override val isConfigured: Boolean = false
    override val providerName: String = "Не настроен (Этап 4)"

    override suspend fun runIsolatedControlCheck(
        block: suspend () -> List<ProbeResult>,
    ): ControlTunnelResult = ControlTunnelResult(
        executed = false,
        tunnelError = "Контрольный VPN не настроен в текущей версии.",
    )
}
