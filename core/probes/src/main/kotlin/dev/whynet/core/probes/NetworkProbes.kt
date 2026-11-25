package dev.whynet.core.probes

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import dev.whynet.core.engine.PingOutputParsers
import dev.whynet.core.engine.WifiChannelAnalyzer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import dev.whynet.core.model.DnsCompareMetrics
import dev.whynet.core.model.DualSniTlsMetrics
import dev.whynet.core.model.GatewayAndExternalMetrics
import dev.whynet.core.model.HttpPayloadMetrics
import dev.whynet.core.model.MtuProbeMetrics
import dev.whynet.core.model.ProbeResult
import dev.whynet.core.model.ProbeStatus
import dev.whynet.core.model.RedirectHop
import dev.whynet.core.model.StageId
import dev.whynet.core.model.TcpDualStackMetrics
import dev.whynet.core.model.TracerouteHop
import dev.whynet.core.model.TracerouteMetrics
import dev.whynet.core.model.UdpStunMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil

data class WifiChannelEntry(
    val uniqueKey: String,
    val ssid: String,
    val bssidDisplay: String,
    val frequencyMhz: Int,
    val bandGhz: Double,
    val channel: Int,
    val rssiDbm: Int,
    val channelWidthMhz: Int = 20,
    val centerFreqMhz: Int = frequencyMhz,
    val wifiStandard: String = "Wi-Fi",
    val security: String = "—",
    val vendor: String = "Неизвестно",
    val isConnected: Boolean = false,
    val rawBssid: String = "",
    val roamingFeatures: String = "",
    val estimatedDistanceM: Double? = null,
    val supports11mcRtt: Boolean = false,
)

data class WifiScanReport(
    val entries: List<WifiChannelEntry>,
    val activeScanAccepted: Boolean,
    val scannedAtEpochMs: Long,
    val supports6Ghz: Boolean = false,
)

data class LiveWifiTick(
    val sequence: Int,
    val timestampMs: Long,
    val isWifiConnected: Boolean,
    val ssid: String,
    val bssidDisplay: String,
    val rawBssid: String,
    val rssiDbm: Int?,
    val linkSpeedMbps: Int?,
    val txLinkSpeedMbps: Int? = null,
    val rxLinkSpeedMbps: Int? = null,
    val frequencyMhz: Int?,
    val bandGhz: Double?,
    val channel: Int?,
    val wifiStandard: String,
    val gatewayRttMs: Double? = null,
    val estimatedDistanceM: Double? = null,
)

data class TrafficSpeedTick(
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long,
)

data class LanDeviceEntry(
    val ip: String,
    val hostname: String?,
    val rttMs: Double?,
    val openPorts: List<Int>,
    val deviceType: String,
    val isGateway: Boolean = false,
    val isSelf: Boolean = false,
)

data class PingTick(
    val sequence: Int,
    val timestampMs: Long,
    val gatewayRttMs: Double?,
    val gatewayMonitored: Boolean,
    val externalRttMs: Double?,
)

class NetworkProbes(
    private val context: Context,
) {
    private val dnsExecutor = Executors.newCachedThreadPool()
    private val json = Json { ignoreUnknownKeys = true }

    private val dohHttpClient = OkHttpClient.Builder()
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .callTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    private val payloadHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(4000, TimeUnit.MILLISECONDS)
        .callTimeout(6000, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Слой 1: Быстрый параллельный замер шлюза (роутера) и внешнего узла (кейс T34).
     * Сначала за <=700мс находит рабочий способ отклика шлюза (TCP 80/443/53 или isReachable),
     * чтобы не тратить 20+ секунд на «молчащие» гостевые шлюзы.
     */
    suspend fun runGatewayAndExternalProbe(
        gatewayIp: String?,
        externalHost: String = "1.1.1.1",
        count: Int = 5,
        onProgress: suspend (String) -> Unit = {},
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        try {
            coroutineScope {
                val gwDeferred = async {
                    if (gatewayIp.isNullOrBlank()) {
                        null
                    } else {
                        onProgress("Проверяем отклик шлюза $gatewayIp...")
                        val responsivePort = discoverGatewayProbePort(gatewayIp)
                        if (responsivePort == null) {
                            // Шлюз фильтрует все пробы — фиксируем быстро, без зависания
                            emptyList<Double?>()
                        } else {
                            val modeLabel = if (responsivePort == -1) "ICMP/echo" else "порт $responsivePort"
                            onProgress("Серия $count проб до шлюза $gatewayIp ($modeLabel)...")
                            runSinglePortSeries(
                                host = gatewayIp,
                                port = responsivePort,
                                count = count,
                                timeoutMs = 800,
                                acceptResetAsAlive = true,
                            )
                        }
                    }
                }
                val extDeferred = async {
                    onProgress("Серия $count проб до внешнего контроля $externalHost:443...")
                    val primarySamples = runSinglePortSeries(
                        host = externalHost,
                        port = 443,
                        count = count,
                        timeoutMs = 1200,
                        acceptResetAsAlive = false,
                    )
                    val primaryOkCount = primarySamples.count { it != null }
                    if (primaryOkCount >= (count + 1) / 2) {
                        externalHost to primarySamples
                    } else {
                        val fallbacks = listOf("8.8.8.8", "ya.ru").filterNot { it.equals(externalHost, ignoreCase = true) }
                        var bestHost = externalHost
                        var bestSamples = primarySamples
                        var bestOkCount = primaryOkCount
                        for (fbHost in fallbacks) {
                            onProgress("Внешний контроль $bestHost ($bestOkCount/$count) нестабилен, пробуем резерв $fbHost:443...")
                            val fbSamples = runSinglePortSeries(
                                host = fbHost,
                                port = 443,
                                count = count,
                                timeoutMs = 1200,
                                acceptResetAsAlive = false,
                            )
                            val fbOkCount = fbSamples.count { it != null }
                            if (fbOkCount > bestOkCount) {
                                bestHost = fbHost
                                bestSamples = fbSamples
                                bestOkCount = fbOkCount
                                if (bestOkCount == count) break
                            }
                        }
                        bestHost to bestSamples
                    }
                }

                val gwSamples = gwDeferred.await()
                val (usedExternalHost, extSamples) = extDeferred.await()

                val gwFiltered = gwSamples != null && gwSamples.isEmpty()
                val gwSent = if (gatewayIp.isNullOrBlank() || gwFiltered) 0 else gwSamples?.size ?: 0
                val gwOk = gwSamples?.filterNotNull().orEmpty()
                val extOk = extSamples.filterNotNull()

                val metrics = GatewayAndExternalMetrics(
                    gatewayIp = gatewayIp,
                    gatewaySent = gwSent,
                    gatewayReceived = gwOk.size,
                    gatewayAvgMs = gwOk.takeIf { it.isNotEmpty() }?.average(),
                    gatewayP95Ms = percentile95(gwOk),
                    externalSent = count,
                    externalReceived = extOk.size,
                    externalAvgMs = extOk.takeIf { it.isNotEmpty() }?.average(),
                    externalP95Ms = percentile95(extOk),
                    externalJitterMs = calculateJitter(extOk),
                )

                val gwBad = metrics.gatewaySent > 0 && metrics.gatewayLossPercent >= 5.0
                val extBad = metrics.externalLossPercent >= 20.0
                val status = if (gwBad || extBad) ProbeStatus.NETWORK_FAILURE else ProbeStatus.SUCCESS

                val summary = buildString {
                    when {
                        gwFiltered -> append("Шлюз $gatewayIp фильтрует пробы · ")
                        metrics.gatewaySent > 0 -> {
                            append("Шлюз: ${metrics.gatewayReceived}/${metrics.gatewaySent}")
                            metrics.gatewayAvgMs?.let { append(" (${"%.0f".format(it)} мс)") }
                            append(" · ")
                        }
                    }
                    append("Внешний ($usedExternalHost): ${metrics.externalReceived}/${metrics.externalSent}")
                    metrics.externalAvgMs?.let { append(" (${"%.0f".format(it)} мс)") }
                }

                ProbeResult(
                    stage = StageId.LOCAL_LINK,
                    status = status,
                    startedAtEpochMs = started,
                    durationMs = System.currentTimeMillis() - started,
                    summary = summary,
                    rawDetails = "gateway=${gatewayIp ?: "n/a"} filtered=$gwFiltered loss=${"%.1f".format(metrics.gatewayLossPercent)}% p95=${metrics.gatewayP95Ms?.let { "%.1f".format(it) } ?: "n/a"}; ext=$usedExternalHost loss=${"%.1f".format(metrics.externalLossPercent)}% jitter=${metrics.externalJitterMs?.let { "%.1f".format(it) } ?: "n/a"}",
                    gatewayExternal = metrics,
                )
            }
        } catch (e: Exception) {
            ProbeResult(
                stage = StageId.LOCAL_LINK,
                status = ProbeStatus.TOOL_ERROR,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                summary = "Ошибка измерения шлюза: ${e.message}",
                errorCode = e.javaClass.simpleName,
            )
        }
    }

    /**
     * Слой 2: Сравнение системного DNS (DnsResolver) и эталонного DoH (Cloudflare / Google).
     */
    suspend fun runDnsCompareProbe(
        hostname: String,
        network: Network?,
        onProgress: suspend (String) -> Unit = {},
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        onProgress("Системный DNS → $hostname...")
        val sysStart = System.currentTimeMillis()
        var sysError: String? = null
        val sysV4 = mutableListOf<String>()
        val sysV6 = mutableListOf<String>()

        try {
            val addrs = resolveViaSystem(hostname, network)
            addrs.forEach { addr ->
                when (addr) {
                    is Inet4Address -> addr.hostAddress?.let(sysV4::add)
                    is Inet6Address -> addr.hostAddress?.let(sysV6::add)
                }
            }
            if (sysV4.isEmpty() && sysV6.isEmpty()) {
                sysError = "Пустой ответ системного DNS"
            }
        } catch (e: Exception) {
            sysError = "${e.javaClass.simpleName}: ${e.message}"
        }
        val sysRtt = System.currentTimeMillis() - sysStart
        onProgress("Системный DNS: ${sysV4.firstOrNull() ?: sysV6.firstOrNull() ?: sysError} (${sysRtt} мс)")

        // Контрольный DoH
        onProgress("Контрольный DoH (1.1.1.1) → $hostname...")
        val dohStart = System.currentTimeMillis()
        var dohProvider = "1.1.1.1"
        var dohError: String? = null
        var dohReachable = true
        var dohV4 = emptyList<String>()
        var dohV6 = emptyList<String>()

        try {
            val primary = queryDoh(hostname, "https://1.1.1.1/dns-query")
            dohV4 = primary.first
            dohV6 = primary.second
        } catch (_: Exception) {
            onProgress("1.1.1.1 DoH не ответил, пробуем dns.google...")
            dohProvider = "8.8.8.8"
            try {
                val backup = queryDoh(hostname, "https://dns.google/resolve")
                dohV4 = backup.first
                dohV6 = backup.second
            } catch (e2: Exception) {
                dohReachable = false
                dohError = "${e2.javaClass.simpleName}: ${e2.message}"
            }
        }
        val dohRtt = System.currentTimeMillis() - dohStart
        onProgress("DoH ($dohProvider): ${dohV4.firstOrNull() ?: dohError ?: "пусто"} (${dohRtt} мс)")

        val allSys = sysV4 + sysV6
        val suspicious = allSys.any { it == "0.0.0.0" || it == "127.0.0.1" || it == "::" || it == "::1" }
        val metrics = DnsCompareMetrics(
            hostname = hostname,
            systemIpv4 = sysV4,
            systemIpv6 = sysV6,
            systemRttMs = sysRtt,
            systemError = sysError,
            dohProvider = dohProvider,
            dohIpv4 = dohV4,
            dohIpv6 = dohV6,
            dohRttMs = dohRtt,
            dohError = dohError,
            isSuspiciousSystemIp = suspicious,
            dohControlReachable = dohReachable,
        )

        val status = when {
            allSys.isNotEmpty() && !suspicious -> ProbeStatus.SUCCESS
            else -> ProbeStatus.NETWORK_FAILURE
        }
        val summary = when {
            suspicious -> "Системный DNS вернул заглушку (${allSys.joinToString()})"
            allSys.isEmpty() -> when {
                dohV4.isNotEmpty() || dohV6.isNotEmpty() -> "Системный DNS не разрешил $hostname (DoH нашёл ${(dohV4 + dohV6).first()})"
                dohReachable -> "Домен $hostname не найден ни системным DNS, ни DoH (NXDOMAIN)"
                else -> "Системный DNS не разрешил $hostname (DoH тоже недоступен)"
            }
            else -> "IPv4: ${sysV4.firstOrNull() ?: "—"} (${sysRtt} мс), DoH: ${dohV4.firstOrNull() ?: "—"}"
        }

        ProbeResult(
            stage = StageId.DNS,
            status = status,
            startedAtEpochMs = started,
            durationMs = System.currentTimeMillis() - started,
            summary = summary,
            errorCode = sysError?.substringBefore(':'),
            rawDetails = "sysV4=$sysV4 sysV6=$sysV6 (${sysRtt}ms) | doh($dohProvider)V4=$dohV4 dohV6=$dohV6 (${dohRtt}ms)",
            dnsCompare = metrics,
        )
    }

    /**
     * Слой 3–4: Раздельный TCP Connect по IPv4 и IPv6.
     */
    suspend fun runTcpDualStackProbe(
        hostname: String,
        port: Int,
        ipv4: String?,
        ipv6: String?,
        usedDohFallback: Boolean,
        network: Network?,
        onProgress: suspend (String) -> Unit = {},
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (ipv4 == null && ipv6 == null) {
            return@withContext ProbeResult(
                stage = StageId.TCP,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = started,
                durationMs = 0,
                summary = "Нет IP-адресов для TCP-подключения",
                errorCode = "NO_IP",
            )
        }

        onProgress("TCP-подключение: IPv4=${ipv4 ?: "нет"}, IPv6=${ipv6 ?: "нет"} (порт $port)...")
        coroutineScope {
            val v4Def = async { ipv4?.let { testTcpConnect(it, port, network) } }
            val v6Def = async { ipv6?.let { testTcpConnect(it, port, network) } }
            val v4Res = v4Def.await()
            val v6Res = v6Def.await()

            val metrics = TcpDualStackMetrics(
                targetHost = hostname,
                port = port,
                ipv4Address = ipv4,
                ipv4Ok = v4Res?.first,
                ipv4RttMs = v4Res?.second,
                ipv4Error = v4Res?.third,
                ipv6Address = ipv6,
                ipv6Ok = v6Res?.first,
                ipv6RttMs = v6Res?.second,
                ipv6Error = v6Res?.third,
                usedDohFallbackIp = usedDohFallback,
            )

            val anyOk = metrics.ipv4Ok == true || metrics.ipv6Ok == true
            val status = if (anyOk) ProbeStatus.SUCCESS else ProbeStatus.NETWORK_FAILURE
            val summary = buildString {
                if (ipv4 != null) {
                    append("IPv4: ")
                    append(if (metrics.ipv4Ok == true) "OK (${metrics.ipv4RttMs} мс)" else metrics.ipv4Error ?: "сбой")
                }
                if (ipv6 != null) {
                    if (isNotEmpty()) append(" · ")
                    append("IPv6: ")
                    append(if (metrics.ipv6Ok == true) "OK (${metrics.ipv6RttMs} мс)" else metrics.ipv6Error ?: "сбой")
                }
            }
            onProgress("TCP итог: $summary")

            ProbeResult(
                stage = StageId.TCP,
                status = status,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                summary = summary,
                errorCode = if (!anyOk) (metrics.ipv4Error ?: metrics.ipv6Error)?.substringBefore(':') else null,
                rawDetails = "port=$port dohFallback=$usedDohFallback v4=$ipv4(${metrics.ipv4Ok}) v6=$ipv6(${metrics.ipv6Ok})",
                tcpDualStack = metrics,
            )
        }
    }

    /**
     * Слой 5: Двойной SNI / TLS-тест на один и тот же IP.
     */
    suspend fun runDualSniTlsProbe(
        ipAddress: String,
        port: Int,
        targetSni: String,
        neutralSni: String = pickNeutralSni(targetSni),
        network: Network?,
        onProgress: suspend (String) -> Unit = {},
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        onProgress("TLS Handshake → $ipAddress:$port (SNI: $targetSni)...")
        val targetAttempt = performTlsHandshake(
            ipAddress = ipAddress,
            port = port,
            sni = targetSni,
            verifyCertificates = true,
            network = network,
        )

        var neutralTested = false
        var neutralOk: Boolean? = null
        var neutralRtt: Long? = null
        var neutralErr: String? = null

        if (!targetAttempt.ok) {
            onProgress("Сбой целевого SNI (${targetAttempt.errorKind}). Контрольный TLS с нейтральным SNI $neutralSni на $ipAddress...")
            neutralTested = true
            val neutralAttempt = performTlsHandshake(
                ipAddress = ipAddress,
                port = port,
                sni = neutralSni,
                verifyCertificates = false,
                network = network,
            )
            neutralOk = neutralAttempt.ok
            neutralRtt = neutralAttempt.rttMs
            neutralErr = neutralAttempt.error
            onProgress("Нейтральный SNI ($neutralSni): ${if (neutralAttempt.ok) "OK (${neutralAttempt.rttMs} мс)" else neutralAttempt.error}")
        } else {
            onProgress("TLS ${targetAttempt.protocol ?: "OK"} установлен за ${targetAttempt.rttMs} мс")
        }

        val metrics = DualSniTlsMetrics(
            ipAddress = ipAddress,
            port = port,
            targetSni = targetSni,
            targetSniOk = targetAttempt.ok,
            targetSniRttMs = targetAttempt.rttMs,
            targetSniError = targetAttempt.error,
            targetSniErrorKind = targetAttempt.errorKind,
            neutralSni = if (neutralTested) neutralSni else null,
            neutralSniTested = neutralTested,
            neutralSniOk = neutralOk,
            neutralSniRttMs = neutralRtt,
            neutralSniError = neutralErr,
            certificateValid = if (targetAttempt.ok) true else targetAttempt.certValid,
            tlsProtocol = targetAttempt.protocol,
        )

        val status = when {
            targetAttempt.ok -> ProbeStatus.SUCCESS
            targetAttempt.errorKind == "TIMEOUT" -> ProbeStatus.TIMEOUT
            else -> ProbeStatus.NETWORK_FAILURE
        }
        val summary = when {
            targetAttempt.ok -> "TLS ${targetAttempt.protocol ?: "OK"} пройден за ${targetAttempt.rttMs} мс"
            neutralTested && neutralOk == true -> "Целевой SNI ($targetSni) оборван, нейтральный ($neutralSni) прошёл!"
            neutralTested && neutralOk == false -> "Оборваны оба SNI ($targetSni и $neutralSni) на IP $ipAddress"
            else -> "Ошибка TLS: ${targetAttempt.error}"
        }

        ProbeResult(
            stage = StageId.TLS_SNI,
            status = status,
            startedAtEpochMs = started,
            durationMs = System.currentTimeMillis() - started,
            summary = summary,
            errorCode = targetAttempt.errorKind,
            rawDetails = "ip=$ipAddress:$port targetSni=$targetSni ok=${targetAttempt.ok} err=${targetAttempt.error}; neutralSni=$neutralSni tested=$neutralTested ok=$neutralOk err=$neutralErr",
            dualSniTls = metrics,
        )
    }

    /**
     * Слой 6: Цепочка HTTP-редиректов + тест реальной отдачи тела Range: bytes=0-1023 + капча/антибот.
     */
    suspend fun runHttpPayloadProbe(
        initialUrl: String,
        onProgress: suspend (String) -> Unit = {},
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val hops = mutableListOf<RedirectHop>()
        var currentUrl = initialUrl
        var finalHost: String? = null
        var statusCode: Int? = null
        var ttfbMs: Long? = null
        var failedRedirectHost: String? = null
        var failedRedirectError: String? = null
        var rangeTested = false
        var rangeBytesRead = 0
        var rangeOk: Boolean? = null
        var rangeError: String? = null
        val antibotSignals = mutableListOf<String>()
        val semanticSignals = mutableListOf<String>()
        val diagHeaders = mutableMapOf<String, String>()

        try {
            var hopIndex = 0
            while (hopIndex < 5) {
                val currentHost = runCatching { URI(currentUrl).host }.getOrNull().orEmpty()
                onProgress("HTTP GET Range 0-8191 → $currentHost (шаг ${hopIndex + 1})...")
                val hopStart = System.currentTimeMillis()
                val req = Request.Builder()
                    .url(currentUrl)
                    .get()
                    .header("Range", "bytes=0-8191")
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) WhyNetDiag/1.0")
                    .header("Accept", "*/*")
                    .build()

                val response = try {
                    payloadHttpClient.newCall(req).execute()
                } catch (e: Exception) {
                    if (hops.isNotEmpty()) {
                        failedRedirectHost = currentHost
                        failedRedirectError = "${e.javaClass.simpleName}: ${e.message}"
                    } else {
                        throw e
                    }
                    break
                }

                response.use { resp ->
                    val hopRtt = System.currentTimeMillis() - hopStart
                    val code = resp.code
                    statusCode = code
                    ttfbMs = hopRtt
                    finalHost = currentHost

                    listOf("X-Mds-Request-Id", "X-Request-Id", "CF-Ray", "Cf-Mitigated", "X-Yandex-Captcha", "Server")
                        .forEach { h -> resp.header(h)?.let { diagHeaders[h] = it } }

                    if (resp.header("X-Yandex-Captcha") != null) {
                        antibotSignals += "Заголовок X-Yandex-Captcha"
                    }
                    if (resp.header("Cf-Mitigated")?.contains("challenge", ignoreCase = true) == true) {
                        antibotSignals += "Cloudflare Challenge (Cf-Mitigated)"
                    }
                    if (currentUrl.contains("/sorry/", ignoreCase = true) || currentUrl.contains("captcha", ignoreCase = true)) {
                        antibotSignals += "Редирект на антибот-страницу ($currentHost)"
                    }
                    if (currentUrl.contains("unsupported", ignoreCase = true) ||
                        currentUrl.contains("not-available", ignoreCase = true) ||
                        currentUrl.contains("geo-block", ignoreCase = true)
                    ) {
                        semanticSignals += "Перенаправление на страницу ограничения региона ($currentHost)"
                    }

                    if (code in 300..399) {
                        val location = resp.header("Location")
                        hops += RedirectHop(currentUrl, currentHost, code, hopRtt)
                        onProgress("HTTP $code редирект → ${location?.take(60) ?: "нет Location"}")
                        if (location.isNullOrBlank()) break
                        val resolved = resp.request.url.resolve(location)?.toString() ?: break
                        if (resolved.contains("/sorry/", ignoreCase = true)) {
                            antibotSignals += "Редирект на /sorry/"
                        }
                        currentUrl = resolved
                        hopIndex++
                    } else {
                        rangeTested = true
                        try {
                            val stream = resp.body.byteStream()
                            val buf = ByteArray(8192)
                            var totalRead = 0
                            while (totalRead < buf.size) {
                                val n = stream.read(buf, totalRead, buf.size - totalRead)
                                if (n <= 0) break
                                totalRead += n
                            }
                            rangeBytesRead = totalRead
                            rangeOk = true
                            onProgress("HTTP $code, получено $rangeBytesRead Б тела (${hopRtt} мс)")
                            val snippet = if (totalRead > 0) String(buf, 0, totalRead, Charsets.UTF_8) else ""
                            if (snippet.contains("unusual traffic", ignoreCase = true) ||
                                snippet.contains("SmartCaptcha", ignoreCase = true) ||
                                snippet.contains("Just a moment", ignoreCase = true) ||
                                snippet.contains("Attention Required", ignoreCase = true) ||
                                snippet.contains("cf-browser-verification", ignoreCase = true) ||
                                snippet.contains("Sorry, you have been blocked", ignoreCase = true) ||
                                snippet.contains("g-recaptcha", ignoreCase = true)
                            ) {
                                antibotSignals += "В теле ответа обнаружены признаки капчи/антибота"
                            }
                            if (snippet.contains("isn't currently supported in your country", ignoreCase = true) ||
                                snippet.contains("not available in your country", ignoreCase = true) ||
                                snippet.contains("not available in your region", ignoreCase = true) ||
                                snippet.contains("not supported in your country", ignoreCase = true) ||
                                snippet.contains("unsupported_country", ignoreCase = true) ||
                                snippet.contains("country/region", ignoreCase = true) ||
                                snippet.contains("недоступен в вашей стране", ignoreCase = true) ||
                                snippet.contains("не поддерживается в вашем регионе", ignoreCase = true)
                            ) {
                                semanticSignals += "Текст страницы указывает на региональное ограничение сервиса"
                            }
                        } catch (e: Exception) {
                            rangeOk = false
                            rangeError = "${e.javaClass.simpleName}: ${e.message}"
                            onProgress("Обрыв при чтении тела: $rangeError")
                        }
                        break
                    }
                }
            }

            val antibot = antibotSignals.isNotEmpty() || statusCode == 429
            val semanticIssue = semanticSignals.isNotEmpty()
            val metrics = HttpPayloadMetrics(
                initialUrl = initialUrl,
                finalHost = finalHost,
                statusCode = statusCode,
                ttfbMs = ttfbMs,
                redirectChain = hops,
                failedRedirectHost = failedRedirectHost,
                failedRedirectError = failedRedirectError,
                rangeTested = rangeTested,
                rangeBytesRead = rangeBytesRead,
                rangeOk = rangeOk,
                rangeError = rangeError,
                captchaOrAntibotDetected = antibot,
                antibotSignals = antibotSignals,
                semanticIssueDetected = semanticIssue,
                semanticSignals = semanticSignals,
                diagnosticHeaders = diagHeaders,
            )

            val healthyHttp = failedRedirectHost == null &&
                !antibot &&
                !semanticIssue &&
                rangeOk != false &&
                statusCode != null &&
                statusCode in 200..399

            val status = if (healthyHttp) ProbeStatus.SUCCESS else ProbeStatus.NETWORK_FAILURE
            val summary = when {
                failedRedirectHost != null -> "Обрыв на редиректе → $failedRedirectHost"
                antibot -> "Капча / антибот (HTTP ${statusCode ?: "?"})"
                semanticIssue -> "Ограничение сервиса (HTTP ${statusCode ?: "?"})"
                rangeOk == false -> "HTTP ${statusCode ?: "?"}, но обрыв при чтении тела ($rangeError)"
                statusCode != null -> "HTTP $statusCode, прочитано $rangeBytesRead Б тела (TTFB ${ttfbMs ?: 0} мс)"
                else -> "Нет HTTP-ответа"
            }

            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = status,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                summary = summary,
                errorCode = if (!healthyHttp) (failedRedirectError ?: rangeError ?: "HTTP_${statusCode ?: 0}") else null,
                rawDetails = "finalHost=$finalHost code=$statusCode hops=${hops.size} rangeBytes=$rangeBytesRead headers=$diagHeaders",
                httpPayload = metrics,
            )
        } catch (e: SocketTimeoutException) {
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.TIMEOUT,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                summary = "Тайм-аут HTTP-запроса",
                errorCode = "TIMEOUT",
                rawDetails = e.message,
            )
        } catch (e: Exception) {
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                summary = "Ошибка HTTP: ${e.message}",
                errorCode = e.javaClass.simpleName,
                rawDetails = e.toString(),
            )
        }
    }

    /**
     * Слой 4 (UDP/P2P): RFC 5389 STUN Binding Request.
     */
    suspend fun runUdpStunProbe(
        stunHost: String = "stun.l.google.com",
        stunPort: Int = 19302,
        onProgress: suspend (String) -> Unit = {},
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val servers = listOf(stunHost to stunPort, "stun.cloudflare.com" to 3478)
        var lastError: String? = null

        for ((host, port) in servers) {
            onProgress("UDP/STUN Binding Request → $host:$port...")
            val attemptStart = System.currentTimeMillis()
            try {
                val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val reqBytes = ByteArray(20)
                reqBytes[0] = 0x00
                reqBytes[1] = 0x01
                reqBytes[2] = 0x00
                reqBytes[3] = 0x00
                reqBytes[4] = 0x21
                reqBytes[5] = 0x12
                reqBytes[6] = 0xA4.toByte()
                reqBytes[7] = 0x42
                System.arraycopy(txId, 0, reqBytes, 8, 12)

                DatagramSocket().use { socket ->
                    socket.soTimeout = 2000
                    val addr = InetAddress.getByName(host)
                    socket.send(DatagramPacket(reqBytes, reqBytes.size, addr, port))

                    val recvBuf = ByteArray(512)
                    val respPacket = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(respPacket)
                    val rtt = System.currentTimeMillis() - attemptStart

                    val validTx = respPacket.length >= 20 &&
                        (0 until 12).all { i -> recvBuf[8 + i] == txId[i] }

                    if (validTx) {
                        val metrics = UdpStunMetrics(
                            stunServer = "$host:$port",
                            udpReachable = true,
                            rttMs = rtt,
                        )
                        onProgress("UDP/STUN ответил за ${rtt} мс")
                        return@withContext ProbeResult(
                            stage = StageId.UDP_STUN,
                            status = ProbeStatus.SUCCESS,
                            startedAtEpochMs = started,
                            durationMs = System.currentTimeMillis() - started,
                            summary = "UDP/STUN доступен ($host:$port, ${rtt} мс)",
                            rawDetails = "stun=$host:$port rtt=${rtt}ms bytes=${respPacket.length}",
                            udpStun = metrics,
                        )
                    }
                }
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }

        val metrics = UdpStunMetrics(
            stunServer = "$stunHost:$stunPort",
            udpReachable = false,
            error = lastError,
        )
        ProbeResult(
            stage = StageId.UDP_STUN,
            status = ProbeStatus.TIMEOUT,
            startedAtEpochMs = started,
            durationMs = System.currentTimeMillis() - started,
            summary = "UDP/STUN не отвечает ($lastError)",
            errorCode = "UDP_STUN_FAIL",
            rawDetails = lastError,
            udpStun = metrics,
        )
    }

    /**
     * Инструмент «Ловец обрывов / Живой Ping»: непрерывная серия параллельных проб
     * до локального шлюза и внешнего хоста раз в 1 с для графика RTT/потерь.
     */
    fun observeContinuousPing(
        gatewayIp: String?,
        externalHost: String = "1.1.1.1",
        intervalMs: Long = 1000L,
        maxTicks: Int = 600,
    ): Flow<PingTick> = flow {
        val gwPort = if (!gatewayIp.isNullOrBlank()) discoverGatewayProbePort(gatewayIp) else null
        var seq = 0
        while (seq < maxTicks) {
            seq++
            val tickStart = System.currentTimeMillis()
            val (gwRtt, extRtt) = coroutineScope {
                val gwDef = async(Dispatchers.IO) {
                    if (gatewayIp != null && gwPort != null) {
                        if (gwPort == -1) {
                            val t0 = System.nanoTime()
                            val ok = runCatching { InetAddress.getByName(gatewayIp).isReachable(800) }.getOrDefault(false)
                            if (ok) (System.nanoTime() - t0) / 1_000_000.0 else null
                        } else {
                            probeSinglePort(gatewayIp, gwPort, timeoutMs = 800, acceptResetAsAlive = true)
                        }
                    } else {
                        null
                    }
                }
                val extDef = async(Dispatchers.IO) {
                    probeSinglePort(externalHost, port = 443, timeoutMs = 1200, acceptResetAsAlive = false)
                }
                gwDef.await() to extDef.await()
            }
            emit(
                PingTick(
                    sequence = seq,
                    timestampMs = tickStart,
                    gatewayRttMs = gwRtt,
                    gatewayMonitored = gwPort != null,
                    externalRttMs = extRtt,
                ),
            )
            val elapsed = System.currentTimeMillis() - tickStart
            val sleepMs = (intervalMs - elapsed).coerceAtLeast(150L)
            delay(sleepMs)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Инструмент «Трассировка (Traceroute)»: пошаговый TTL 1..15 через /system/bin/ping.
     */
    suspend fun runTracerouteProbe(
        host: String,
        maxHops: Int = 15,
        onProgress: suspend (String) -> Unit = {},
        onHop: suspend (TracerouteHop) -> Unit = {},
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val resolvedIp = runCatching {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
                ?: InetAddress.getByName(host).hostAddress
        }.getOrNull()

        if (resolvedIp == null) {
            return@withContext ProbeResult(
                stage = StageId.LOCAL_LINK,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                summary = "Не удалось разрешить адрес $host для трассировки",
            )
        }

        val hops = mutableListOf<TracerouteHop>()
        var reached = false
        var consecutiveStars = 0
        var pingSupported = true

        for (ttl in 1..maxHops) {
            onProgress("Трассировка $host ($resolvedIp): хоп $ttl/$maxHops...")
            val t0 = System.nanoTime()
            val procResult = runSystemPing(listOf("/system/bin/ping", "-n", "-c", "1", "-t", ttl.toString(), "-W", "1", resolvedIp))
            if (procResult == null) {
                pingSupported = false
                break
            }
            val parsed = PingOutputParsers.parseTraceroutePingOutput(procResult.second, resolvedIp)
            val fallbackRtt = if (parsed.responderIp != null && parsed.rttMs == null) {
                (System.nanoTime() - t0) / 1_000_000.0
            } else {
                parsed.rttMs
            }

            val hop = TracerouteHop(
                ttl = ttl,
                ip = parsed.responderIp,
                rttMs = fallbackRtt,
                reachedTarget = parsed.isTargetReply,
                note = when {
                    parsed.isTargetReply -> "Цель достигнута"
                    parsed.responderIp == null -> "* (фильтрация ICMP на узле)"
                    else -> null
                },
            )
            hops += hop
            onHop(hop)

            if (parsed.isTargetReply) {
                reached = true
                break
            }
            if (parsed.responderIp == null) {
                consecutiveStars++
                if (ttl >= 5 && consecutiveStars >= 4) break
            } else {
                consecutiveStars = 0
            }
        }

        if (!pingSupported) {
            return@withContext ProbeResult(
                stage = StageId.LOCAL_LINK,
                status = ProbeStatus.UNSUPPORTED,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                summary = "На данном устройстве системный ping недоступен без root",
                errorCode = "PING_UNSUPPORTED",
            )
        }

        val metrics = TracerouteMetrics(
            targetHost = host,
            resolvedIp = resolvedIp,
            hops = hops,
            reachedTarget = reached,
        )
        val respondingCount = hops.count { it.ip != null }
        ProbeResult(
            stage = StageId.LOCAL_LINK,
            status = if (reached || respondingCount > 0) ProbeStatus.SUCCESS else ProbeStatus.TIMEOUT,
            startedAtEpochMs = started,
            durationMs = System.currentTimeMillis() - started,
            summary = if (reached) {
                "Цель $resolvedIp достигнута за ${hops.size} хопов"
            } else {
                "Пройдено ${hops.size} хопов (ответило $respondingCount; часть узлов может скрывать ICMP)"
            },
            rawDetails = hops.joinToString("\n") { h ->
                val ipStr = h.ip ?: "*"
                val rttStr = h.rttMs?.let { "%.1f мс".format(it) } ?: "—"
                "${h.ttl.toString().padStart(2)}  $ipStr  ($rttStr)"
            },
            traceroute = metrics,
        )
    }

    /**
     * Инструмент «Поиск MTU / PMTU» (Методика М19 / 2.8).
     */
    suspend fun runMtuProbe(
        host: String = "1.1.1.1",
        interfaceMtu: Int? = null,
        onProgress: suspend (String) -> Unit = {},
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val resolvedIp = runCatching {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
                ?: InetAddress.getByName(host).hostAddress
        }.getOrNull() ?: host

        // Типовые размеры полезной нагрузки ICMP (+ 28 байт IP+ICMP заголовков = MTU)
        val candidates = listOf(1472, 1464, 1452, 1420, 1392, 1352, 1252, 548)
        var maxPassed: Int? = null
        var fragSeen = false
        var pingSupported = true
        val logLines = mutableListOf<String>()

        for (size in candidates) {
            onProgress("MTU-зонд (DF) → $resolvedIp: payload $size Б (MTU ${size + 28})...")
            val res = runSystemPing(listOf("/system/bin/ping", "-c", "1", "-M", "do", "-s", size.toString(), "-W", "1", resolvedIp))
            if (res == null) {
                pingSupported = false
                break
            }
            val parsed = PingOutputParsers.parseMtuPingOutput(res.second, res.first)
            if (parsed.fragmentationNeeded) fragSeen = true
            logLines += "payload=$size (MTU=${size + 28}): ${if (parsed.passed) "OK" else if (parsed.fragmentationNeeded) "FRAG_NEEDED" else "DROP"}"
            if (parsed.passed) {
                maxPassed = size
                break
            }
        }

        if (!pingSupported) {
            return@withContext ProbeResult(
                stage = StageId.LOCAL_LINK,
                status = ProbeStatus.UNSUPPORTED,
                startedAtEpochMs = started,
                durationMs = System.currentTimeMillis() - started,
                summary = "ICMP MTU-зонд не поддерживается на устройстве (MTU интерфейса: ${interfaceMtu ?: "неизвестно"})",
                mtuProbe = MtuProbeMetrics(host, interfaceMtu, null, null, false),
            )
        }

        val estimatedMtu = maxPassed?.let { it + 28 }
        val blackHole = maxPassed != null && maxPassed < 1472 && !fragSeen
        val metrics = MtuProbeMetrics(
            targetHost = host,
            interfaceMtu = interfaceMtu,
            maxPayloadPassed = maxPassed,
            estimatedPathMtu = estimatedMtu,
            pmtudBlackHoleSuspected = blackHole,
        )

        val summary = when {
            estimatedMtu == null -> "ICMP MTU-пакеты не прошли (возможна фильтрация ICMP). MTU интерфейса: ${interfaceMtu ?: "—"}"
            estimatedMtu >= 1500 -> "MTU пути: $estimatedMtu Б — норма (Ethernet), фрагментации нет"
            blackHole -> "MTU пути ≈ $estimatedMtu Б (ниже 1500 без ICMP Frag Needed — риск PMTUD black hole)"
            else -> "MTU пути ≈ $estimatedMtu Б (туннель / PPPoE / мобильная сеть)"
        }

        ProbeResult(
            stage = StageId.LOCAL_LINK,
            status = if (estimatedMtu != null) ProbeStatus.SUCCESS else ProbeStatus.TIMEOUT,
            startedAtEpochMs = started,
            durationMs = System.currentTimeMillis() - started,
            summary = summary,
            rawDetails = "interfaceMtu=${interfaceMtu ?: "n/a"}\n" + logLines.joinToString("\n"),
            mtuProbe = metrics,
        )
    }

    private fun runSystemPing(command: List<String>): Pair<Int, String>? = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(2200, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            return 1 to "timeout"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.exitValue() to output
    }.getOrNull()

    /**
     * Инструмент: безопасное чтение списка соседних Wi-Fi сетей с шириной канала,
     * стандартом (Wi-Fi 4/5/6/6E/7), шифрованием, OUI-производителем и пометкой текущей сети.
     */
    @SuppressLint("MissingPermission")
    fun scanWifiChannels(): Result<List<WifiChannelEntry>> =
        scanWifiChannelsDetailed(triggerActiveScan = false).map { it.entries }

    @SuppressLint("MissingPermission")
    fun scanWifiChannelsDetailed(triggerActiveScan: Boolean = true): Result<WifiScanReport> {
        val hasPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasPerm) {
            return Result.failure(SecurityException("Нет разрешения на сканирование Wi-Fi (NEARBY_WIFI_DEVICES / Location)."))
        }

        return runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val activeAccepted = if (triggerActiveScan) runCatching { wm.startScan() }.getOrDefault(false) else true
            val currentInfo = readActiveWifiInfo(wm)
            val connectedBssid = currentInfo?.bssid?.lowercase().orEmpty()
                .takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }
            val connectedSsid = currentInfo?.ssid?.trim('"')
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            val connectedFreq = currentInfo?.frequency ?: 0

            val list = wm.scanResults
                .sortedByDescending { it.level }
                .mapIndexed { idx, sr ->
                    val freq = sr.frequency
                    val band = freqToBandGhz(freq)
                    val ssidStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        sr.wifiSsid?.toString()?.trim('"').orEmpty()
                    } else {
                        @Suppress("DEPRECATION")
                        sr.SSID.orEmpty()
                    }.ifBlank { "<Скрытая сеть>" }
                    val rawBssid = sr.BSSID.orEmpty()
                    val bssidDisplay = formatBssidSafe(rawBssid)
                    val widthMhz = scanResultWidthMhz(sr)
                    val centerFreq = if (sr.centerFreq0 > 0) sr.centerFreq0 else freq
                    val isConn = when {
                        connectedBssid != null -> rawBssid.equals(connectedBssid, ignoreCase = true)
                        connectedSsid != null && connectedFreq > 0 -> ssidStr == connectedSsid && freq == connectedFreq
                        else -> false
                    }

                    WifiChannelEntry(
                        uniqueKey = "${idx}_${rawBssid}_${freq}",
                        ssid = ssidStr,
                        bssidDisplay = bssidDisplay,
                        frequencyMhz = freq,
                        bandGhz = band,
                        channel = frequencyToChannel(freq),
                        rssiDbm = sr.level,
                        channelWidthMhz = widthMhz,
                        centerFreqMhz = centerFreq,
                        wifiStandard = scanResultStandardLabel(sr),
                        security = parseSecurityLabel(sr.capabilities.orEmpty()),
                        vendor = WifiChannelAnalyzer.lookupVendor(rawBssid),
                        isConnected = isConn,
                        rawBssid = rawBssid,
                        roamingFeatures = parseRoamingFeatures(sr),
                        estimatedDistanceM = WifiChannelAnalyzer.estimateDistanceMeters(sr.level, freq),
                        supports11mcRtt = sr.is80211mcResponder,
                    )
                }

            val supports6g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { wm.is6GHzBandSupported }.getOrDefault(false)
            } else {
                false
            }

            WifiScanReport(
                entries = list,
                activeScanAccepted = activeAccepted,
                scannedAtEpochMs = System.currentTimeMillis(),
                supports6Ghz = supports6g,
            )
        }
    }

    /**
     * Поток реального времени для анализа Wi-Fi эфира (2.4 / 5 / 6 ГГц):
     * подписывается на SCAN_RESULTS_AVAILABLE_ACTION и инициирует startScan() каждые [intervalMs].
     * При отключённом ограничении сканирования Wi-Fi обновляется каждые 2-3 секунды.
     */
    @SuppressLint("MissingPermission")
    fun observeLiveWifiScans(intervalMs: Long = 2800L): Flow<WifiScanReport> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                scanWifiChannelsDetailed(triggerActiveScan = false).onSuccess { report ->
                    trySend(report)
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }

        // Немедленный первичный снимок и фоновый цикл startScan()
        scanWifiChannelsDetailed(triggerActiveScan = true).onSuccess { trySend(it) }
        val ticker = launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMs)
                scanWifiChannelsDetailed(triggerActiveScan = true).onSuccess { trySend(it) }
            }
        }

        awaitClose {
            ticker.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Живой монитор текущей скорости трафика устройства (TrafficStats, 1 Гц).
     */
    fun observeTrafficSpeed(intervalMs: Long = 1000L): Flow<TrafficSpeedTick> = flow {
        var prevRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        var prevTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        var prevTs = System.currentTimeMillis()
        while (true) {
            delay(intervalMs)
            val now = System.currentTimeMillis()
            val curRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            val curTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            val dtSec = ((now - prevTs).coerceAtLeast(200L)) / 1000.0
            val rxRate = ((curRx - prevRx).coerceAtLeast(0L) / dtSec).toLong()
            val txRate = ((curTx - prevTx).coerceAtLeast(0L) / dtSec).toLong()
            prevRx = curRx
            prevTx = curTx
            prevTs = now
            emit(TrafficSpeedTick(rxBytesPerSec = rxRate, txBytesPerSec = txRate))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Живой поток уровня сигнала текущей Wi-Fi сети + пинга до шлюза раз в 1 с
     * (не подпадает под Android scan throttling!).
     */
    @SuppressLint("MissingPermission")
    fun observeLiveWifiSignal(
        gatewayIp: String? = null,
        intervalMs: Long = 1000L,
        maxTicks: Int = 900,
    ): Flow<LiveWifiTick> = flow {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val gwPort = if (!gatewayIp.isNullOrBlank()) discoverGatewayProbePort(gatewayIp) else null
        var seq = 0
        while (seq < maxTicks) {
            seq++
            val now = System.currentTimeMillis()
            val info = readActiveWifiInfo(wm)
            val freq = info?.frequency?.takeIf { it > 0 }
            val rssi = info?.rssi?.takeIf { it in -120..-1 }
            val rawBssid = info?.bssid.orEmpty().takeIf { it != "02:00:00:00:00:00" }.orEmpty()
            val ssid = info?.ssid?.trim('"')
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                ?: if (rssi != null) "Текущая Wi-Fi сеть" else "Wi-Fi не подключён"

            val stdLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info != null) {
                wifiStandardCodeToLabel(info.wifiStandard)
            } else {
                "Wi-Fi"
            }
            val txMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info?.txLinkSpeedMbps?.takeIf { it > 0 } else info?.linkSpeed?.takeIf { it > 0 }
            val rxMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info?.rxLinkSpeedMbps?.takeIf { it > 0 } else info?.linkSpeed?.takeIf { it > 0 }
            val gwRtt = if (gatewayIp != null && gwPort != null) {
                if (gwPort == -1) {
                    val t0 = System.nanoTime()
                    val ok = runCatching { InetAddress.getByName(gatewayIp).isReachable(500) }.getOrDefault(false)
                    if (ok) (System.nanoTime() - t0) / 1_000_000.0 else null
                } else {
                    probeSinglePort(gatewayIp, gwPort, timeoutMs = 500, acceptResetAsAlive = true)
                }
            } else {
                null
            }

            emit(
                LiveWifiTick(
                    sequence = seq,
                    timestampMs = now,
                    isWifiConnected = rssi != null && freq != null,
                    ssid = ssid,
                    bssidDisplay = formatBssidSafe(rawBssid),
                    rawBssid = rawBssid,
                    rssiDbm = rssi,
                    linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
                    txLinkSpeedMbps = txMbps,
                    rxLinkSpeedMbps = rxMbps,
                    frequencyMhz = freq,
                    bandGhz = freq?.let(::freqToBandGhz),
                    channel = freq?.let(::frequencyToChannel)?.takeIf { it > 0 },
                    wifiStandard = stdLabel,
                    gatewayRttMs = gwRtt,
                    estimatedDistanceM = if (rssi != null && freq != null) WifiChannelAnalyzer.estimateDistanceMeters(rssi, freq) else null,
                ),
            )
            val elapsed = System.currentTimeMillis() - now
            delay((intervalMs - elapsed).coerceAtLeast(150L))
        }
    }.flowOn(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    private fun readActiveWifiInfo(wm: WifiManager): WifiInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val modern = caps?.transportInfo as? WifiInfo
        if (modern != null) return modern
        @Suppress("DEPRECATION")
        return runCatching { wm.connectionInfo }.getOrNull()
    }

    private fun freqToBandGhz(freq: Int): Double = when {
        freq in 2400..2500 -> 2.4
        freq in 4900..5900 -> 5.0
        freq in 5925..7125 -> 6.0
        else -> 0.0
    }

    private fun scanResultWidthMhz(sr: ScanResult): Int = when (sr.channelWidth) {
        ScanResult.CHANNEL_WIDTH_20MHZ -> 20
        ScanResult.CHANNEL_WIDTH_40MHZ -> 40
        ScanResult.CHANNEL_WIDTH_80MHZ -> 80
        ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
        else -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && sr.channelWidth == ScanResult.CHANNEL_WIDTH_320MHZ) {
                320
            } else {
                20
            }
        }
    }

    private fun parseRoamingFeatures(sr: ScanResult): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val ies = runCatching { sr.informationElements }.getOrNull().orEmpty()
        var hasK = false
        var hasR = false
        var hasV = false
        for (ie in ies) {
            when (ie.id) {
                70 -> hasK = true // RM Enabled Capabilities (802.11k)
                54 -> hasR = true // Mobility Domain (802.11r Fast Transition)
                127 -> {
                    // Extended Capabilities: bit 19 (byte 2, bit 3) = BSS Transition (802.11v)
                    val buf = ie.bytes
                    if (buf.remaining() >= 3) {
                        val b2 = buf.get(buf.position() + 2).toInt()
                        if ((b2 and 0x08) != 0) hasV = true
                    }
                }
            }
        }
        val list = buildList {
            if (hasK) add("k")
            if (hasV) add("v")
            if (hasR) add("r")
        }
        return if (list.isEmpty()) "" else "11${list.joinToString("/")}"
    }

    private fun scanResultStandardLabel(sr: ScanResult): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wifiStandardCodeToLabel(sr.wifiStandard)
        } else {
            "Wi-Fi"
        }

    private fun wifiStandardCodeToLabel(code: Int): String = when (code) {
        ScanResult.WIFI_STANDARD_LEGACY -> "Wi-Fi 1-3"
        ScanResult.WIFI_STANDARD_11N -> "Wi-Fi 4"
        ScanResult.WIFI_STANDARD_11AC -> "Wi-Fi 5"
        ScanResult.WIFI_STANDARD_11AX -> "Wi-Fi 6"
        ScanResult.WIFI_STANDARD_11BE -> "Wi-Fi 7"
        else -> "Wi-Fi"
    }

    private fun parseSecurityLabel(cap: String): String {
        val upper = cap.uppercase()
        return when {
            upper.contains("SAE") || upper.contains("WPA3") -> "WPA3"
            upper.contains("WPA2") || upper.contains("RSN") -> "WPA2"
            upper.contains("WPA") -> "WPA"
            upper.contains("WEP") -> "WEP"
            upper.contains("OWE") -> "OWE"
            else -> "Open"
        }
    }

    /**
     * Быстрый одиночный замер RTT до локального шлюза (используется для точек Wi-Fi Heatmap).
     */
    suspend fun probeGatewayQuickRtt(gatewayIp: String?): Double? = withContext(Dispatchers.IO) {
        if (gatewayIp.isNullOrBlank()) return@withContext null
        val port = discoverGatewayProbePort(gatewayIp) ?: return@withContext null
        if (port == -1) {
            val t0 = System.nanoTime()
            val ok = runCatching { InetAddress.getByName(gatewayIp).isReachable(700) }.getOrDefault(false)
            if (ok) (System.nanoTime() - t0) / 1_000_000.0 else null
        } else {
            probeSinglePort(gatewayIp, port, timeoutMs = 700, acceptResetAsAlive = true)
        }
    }

    /**
     * Быстрый LAN-сканер подсети /24 без root (аналог Fing / WiFiman Discovery):
     * параллельно (до 32 потоков) проверяет isReachable и базовые сервисные порты.
     */
    suspend fun scanLanDevices(
        localIpv4: String?,
        gatewayIp: String?,
        onProgress: suspend (Int) -> Unit = {},
    ): List<LanDeviceEntry> = withContext(Dispatchers.IO) {
        val baseIp = localIpv4 ?: gatewayIp ?: return@withContext emptyList()
        val parts = baseIp.split(".")
        if (parts.size != 4) return@withContext emptyList()
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val sem = Semaphore(32)
        val scannedCount = java.util.concurrent.atomic.AtomicInteger(0)

        coroutineScope {
            val ssdpDef = async(Dispatchers.IO) { PingToolsProbes().discoverSsdpNames(timeoutMs = 1100) }
            val jobs = (1..254).map { hostOctet ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val ip = "$prefix.$hostOctet"
                        val isSelf = ip == localIpv4
                        val isGw = ip == gatewayIp
                        val entry = if (isSelf) {
                            LanDeviceEntry(
                                ip = ip,
                                hostname = "Этот телефон",
                                rttMs = 0.0,
                                openPorts = emptyList(),
                                deviceType = "Смартфон (это устройство)",
                                isGateway = false,
                                isSelf = true,
                            )
                        } else {
                            probeLanHost(ip, isGw)
                        }
                        val done = scannedCount.incrementAndGet()
                        if (done % 16 == 0 || done == 254) {
                            onProgress((done * 100) / 254)
                        }
                        entry
                    }
                }
            }
            val ssdpMap = ssdpDef.await()
            jobs.awaitAll()
                .filterNotNull()
                .map { dev ->
                    val ssdpLabel = ssdpMap[dev.ip]
                    if (!ssdpLabel.isNullOrBlank()) {
                        dev.copy(
                            hostname = dev.hostname ?: ssdpLabel,
                            deviceType = if (dev.isGateway) "Роутер · $ssdpLabel" else ssdpLabel,
                        )
                    } else {
                        dev
                    }
                }
                .sortedWith(
                    compareByDescending<LanDeviceEntry> { it.isGateway }
                        .thenByDescending { it.isSelf }
                        .thenBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 },
                )
        }
    }

    private fun probeLanHost(ip: String, isGateway: Boolean): LanDeviceEntry? {
        val t0 = System.nanoTime()
        val addr = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return null
        val reachable = runCatching { addr.isReachable(280) }.getOrDefault(false)
        var rttMs: Double? = if (reachable) (System.nanoTime() - t0) / 1_000_000.0 else null

        val probePorts = if (isGateway || reachable) {
            listOf(80, 443, 53, 22, 445, 8080, 9100, 554)
        } else {
            // Даже если ICMP закрыт (Windows/iOS), проверяем 80/443/445/62078
            listOf(80, 443, 445, 62078)
        }
        val openPorts = mutableListOf<Int>()
        for (port in probePorts) {
            val portRtt = probeSinglePort(ip, port, timeoutMs = if (reachable) 220 else 140, acceptResetAsAlive = false)
            if (portRtt != null) {
                openPorts += port
                if (rttMs == null) rttMs = portRtt
            }
        }
        if (!reachable && openPorts.isEmpty() && !isGateway) return null

        val hostName = runCatching { addr.canonicalHostName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != ip }

        val deviceType = when {
            isGateway -> "Роутер / Шлюз"
            62078 in openPorts -> "Apple (iPhone / iPad)"
            9100 in openPorts -> "Принтер"
            554 in openPorts -> "IP-камера / медиа"
            445 in openPorts -> "ПК / NAS (Windows/SMB)"
            22 in openPorts -> "Сервер / Linux / Роутер"
            53 in openPorts -> "Роутер / DNS-узел"
            80 in openPorts || 443 in openPorts || 8080 in openPorts -> "Сетевое устройство (Web)"
            else -> "Устройство в сети"
        }

        return LanDeviceEntry(
            ip = ip,
            hostname = hostName,
            rttMs = rttMs,
            openPorts = openPorts,
            deviceType = deviceType,
            isGateway = isGateway,
            isSelf = false,
        )
    }

    private fun formatBssidSafe(bssid: String): String {
        val parts = bssid.split(":")
        return if (parts.size == 6) {
            "${parts[0]}:${parts[1]}:xx:xx:${parts[4]}:${parts[5]}"
        } else {
            bssid.ifBlank { "—" }
        }
    }

    /**
     * За <=700мс параллельно ищет, на какой порт/метод отвечает локальный шлюз.
     * Возвращает порт (80/443/53), -1 для isReachable, или null если шлюз всё дропает.
     */
    private suspend fun discoverGatewayProbePort(gatewayIp: String): Int? = coroutineScope {
        val ports = listOf(80, 443, 53)
        val checks = ports.map { port ->
            async(Dispatchers.IO) {
                val rtt = probeSinglePort(gatewayIp, port, timeoutMs = 600, acceptResetAsAlive = true)
                if (rtt != null) port else null
            }
        }
        val firstPort = checks.mapNotNull { it.await() }.firstOrNull()
        if (firstPort != null) return@coroutineScope firstPort

        val reachable = runCatching { InetAddress.getByName(gatewayIp).isReachable(600) }.getOrDefault(false)
        if (reachable) -1 else null
    }

    private suspend fun runSinglePortSeries(
        host: String,
        port: Int,
        count: Int,
        timeoutMs: Int,
        acceptResetAsAlive: Boolean,
    ): List<Double?> {
        val samples = mutableListOf<Double?>()
        repeat(count) { idx ->
            val rtt = if (port == -1) {
                val t0 = System.nanoTime()
                val ok = runCatching { InetAddress.getByName(host).isReachable(timeoutMs) }.getOrDefault(false)
                if (ok) (System.nanoTime() - t0) / 1_000_000.0 else null
            } else {
                probeSinglePort(host, port, timeoutMs, acceptResetAsAlive)
            }
            samples += rtt
            if (idx < count - 1) delay(120)
        }
        return samples
    }

    private fun probeSinglePort(
        host: String,
        port: Int,
        timeoutMs: Int,
        acceptResetAsAlive: Boolean,
    ): Double? {
        val t0 = System.nanoTime()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.nanoTime() - t0) / 1_000_000.0
        } catch (e: ConnectException) {
            if (acceptResetAsAlive && e.message?.contains("refused", ignoreCase = true) == true) {
                (System.nanoTime() - t0) / 1_000_000.0
            } else {
                null
            }
        } catch (_: IOException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveViaSystem(hostname: String, network: Network?): List<InetAddress> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolved = withTimeoutOrNull(2500L) {
                suspendCancellableCoroutine<List<InetAddress>> { cont ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    DnsResolver.getInstance().query(
                        network,
                        hostname,
                        DnsResolver.FLAG_NO_CACHE_LOOKUP,
                        dnsExecutor,
                        signal,
                        object : DnsResolver.Callback<List<InetAddress>> {
                            override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                                if (cont.isActive) cont.resume(answer)
                            }

                            override fun onError(error: DnsResolver.DnsException) {
                                if (cont.isActive) cont.resume(emptyList())
                            }
                        },
                    )
                }
            }
            if (!resolved.isNullOrEmpty()) return resolved
        }
        return InetAddress.getAllByName(hostname).toList()
    }

    private fun queryDoh(hostname: String, endpoint: String): Pair<List<String>, List<String>> {
        val v4 = queryDohType(hostname, endpoint, "A")
        val v6 = runCatching { queryDohType(hostname, endpoint, "AAAA") }.getOrDefault(emptyList())
        return v4 to v6
    }

    private fun queryDohType(hostname: String, endpoint: String, recordType: String): List<String> {
        val req = Request.Builder()
            .url("$endpoint?name=$hostname&type=$recordType")
            .header("Accept", "application/dns-json")
            .build()
        dohHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("DoH HTTP ${resp.code}")
            val body = resp.body.string()
            val root = json.parseToJsonElement(body).jsonObject
            val answers = root["Answer"]?.jsonArray ?: return emptyList()
            val expectedType = if (recordType == "A") 1 else 28
            return answers.mapNotNull { el ->
                val obj = el.jsonObject
                if (obj["type"]?.jsonPrimitive?.intOrNull == expectedType) {
                    obj["data"]?.jsonPrimitive?.content
                } else {
                    null
                }
            }
        }
    }

    private fun testTcpConnect(
        ip: String,
        port: Int,
        network: Network?,
    ): Triple<Boolean, Long?, String?> {
        val t0 = System.currentTimeMillis()
        return try {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            socket.use { s ->
                s.connect(InetSocketAddress(InetAddress.getByName(ip), port), 2500)
            }
            Triple(true, System.currentTimeMillis() - t0, null)
        } catch (e: SocketTimeoutException) {
            Triple(false, null, "TIMEOUT")
        } catch (e: Exception) {
            Triple(false, null, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private data class TlsAttempt(
        val ok: Boolean,
        val rttMs: Long,
        val protocol: String? = null,
        val certValid: Boolean? = null,
        val error: String? = null,
        val errorKind: String? = null,
    )

    private fun performTlsHandshake(
        ipAddress: String,
        port: Int,
        sni: String,
        verifyCertificates: Boolean,
        network: Network?,
    ): TlsAttempt {
        val t0 = System.currentTimeMillis()
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null
        return try {
            rawSocket = network?.socketFactory?.createSocket() ?: Socket()
            rawSocket.connect(InetSocketAddress(InetAddress.getByName(ipAddress), port), 3000)
            rawSocket.soTimeout = 3500

            val factory: SSLSocketFactory = if (verifyCertificates) {
                SSLSocketFactory.getDefault() as SSLSocketFactory
            } else {
                createPermissiveSslFactoryForNeutralSniProbe()
            }

            sslSocket = factory.createSocket(rawSocket, sni, port, true) as SSLSocket
            val params = sslSocket.sslParameters
            params.serverNames = listOf(SNIHostName(sni))
            if (verifyCertificates) {
                params.endpointIdentificationAlgorithm = "HTTPS"
            }
            sslSocket.sslParameters = params
            sslSocket.soTimeout = 3500
            sslSocket.startHandshake()

            TlsAttempt(
                ok = true,
                rttMs = System.currentTimeMillis() - t0,
                protocol = sslSocket.session.protocol,
                certValid = verifyCertificates,
            )
        } catch (e: SocketTimeoutException) {
            TlsAttempt(false, System.currentTimeMillis() - t0, error = "Тайм-аут TLS", errorKind = "TIMEOUT")
        } catch (e: EOFException) {
            TlsAttempt(false, System.currentTimeMillis() - t0, error = "EOF / разрыв TLS", errorKind = "EOF")
        } catch (e: SSLHandshakeException) {
            val msg = e.message ?: "SSLHandshakeException"
            val isCert = msg.contains("Cert", ignoreCase = true) || msg.contains("Trust", ignoreCase = true)
            TlsAttempt(
                ok = false,
                rttMs = System.currentTimeMillis() - t0,
                certValid = if (isCert) false else null,
                error = msg,
                errorKind = if (isCert) "CERT_ERROR" else "TLS_HANDSHAKE_RESET",
            )
        } catch (e: SSLException) {
            TlsAttempt(false, System.currentTimeMillis() - t0, error = e.message ?: "SSLException", errorKind = "TLS_RESET")
        } catch (e: IOException) {
            TlsAttempt(false, System.currentTimeMillis() - t0, error = e.message ?: "IOException", errorKind = "ECONNRESET")
        } finally {
            runCatching { sslSocket?.close() }
            runCatching { rawSocket?.close() }
        }
    }

    @SuppressLint("TrustAllX509TrustManager", "CustomX509TrustManager")
    private fun createPermissiveSslFactoryForNeutralSniProbe(): SSLSocketFactory {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            },
        )
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        return ctx.socketFactory
    }

    private fun pickNeutralSni(targetSni: String): String {
        val lower = targetSni.lowercase()
        return when {
            lower.endsWith("live.com") || lower.endsWith("microsoftonline.com") -> "microsoft.com"
            lower.endsWith("google.com") -> "example.com"
            else -> "example.com"
        }
    }

    private fun percentile95(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val idx = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun calculateJitter(values: List<Double>): Double? {
        if (values.size < 2) return null
        var sumDiff = 0.0
        for (i in 1 until values.size) {
            sumDiff += abs(values[i] - values[i - 1])
        }
        return sumDiff / (values.size - 1)
    }

    private fun frequencyToChannel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5170..5885 -> (freqMhz - 5000) / 5
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        else -> 0
    }
}
