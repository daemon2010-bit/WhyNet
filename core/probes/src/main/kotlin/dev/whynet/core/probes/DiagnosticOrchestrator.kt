package dev.whynet.core.probes

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import dev.whynet.core.engine.DiagnosisEngine
import dev.whynet.core.model.DiagnosticSession
import dev.whynet.core.model.DiagnosticTarget
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.ProbeResult
import dev.whynet.core.model.ProbeStatus
import dev.whynet.core.model.StageId
import dev.whynet.core.model.TargetType
import dev.whynet.core.model.TransportKind
import dev.whynet.core.model.VerificationOutcome
import dev.whynet.core.model.VerificationRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.UUID

sealed interface DiagnosticRunEvent {
    data class StageStarted(val stage: StageId, val label: String) : DiagnosticRunEvent
    data class LogLine(val elapsedMs: Long, val text: String) : DiagnosticRunEvent
    data class SnapshotReady(val snapshot: NetworkSnapshot) : DiagnosticRunEvent
    data class ProbeCompleted(val probe: ProbeResult) : DiagnosticRunEvent
    data class Finished(val session: DiagnosticSession) : DiagnosticRunEvent
}

class DiagnosticOrchestrator(
    private val context: Context,
    private val environmentCollector: EnvironmentCollector = EnvironmentCollector(context),
    private val probes: NetworkProbes = NetworkProbes(context),
    private val engine: DiagnosisEngine = DiagnosisEngine(),
    private val appVersion: String = "0.2.0-mvp",
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun plannedStagesFor(type: TargetType): List<StageId> = when (type) {
        TargetType.WEBSITE -> listOf(
            StageId.ENVIRONMENT,
            StageId.LOCAL_LINK,
            StageId.DNS,
            StageId.TCP,
            StageId.TLS_SNI,
            StageId.HTTP_PAYLOAD,
        )
        TargetType.NETWORK_HEALTH, TargetType.P2P_CALLS -> listOf(
            StageId.ENVIRONMENT,
            StageId.LOCAL_LINK,
            StageId.DNS,
            StageId.TCP,
            StageId.TLS_SNI,
            StageId.HTTP_PAYLOAD,
            StageId.UDP_STUN,
        )
        TargetType.APP_PACKAGE -> listOf(
            StageId.ENVIRONMENT,
            StageId.LOCAL_LINK,
            StageId.DNS,
            StageId.TCP,
            StageId.TLS_SNI,
        )
    }

    fun runDiagnostic(
        rawTarget: DiagnosticTarget,
        overallDeadlineMs: Long = 20_000L,
    ): Flow<DiagnosticRunEvent> = channelFlow {
        val runStart = System.currentTimeMillis()
        suspend fun log(msg: String) {
            send(DiagnosticRunEvent.LogLine(System.currentTimeMillis() - runStart, msg))
        }

        val target = normalizeTarget(rawTarget)
        val host = extractHost(target.hostOrUrl)
        val capabilities = environmentCollector.readCapabilities()
        val results = mutableListOf<ProbeResult>()
        var networkChanged = false
        val initialNetwork = environmentCollector.getActiveNetwork()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (initialNetwork != null && network != initialNetwork) {
                    networkChanged = true
                }
            }

            override fun onLost(network: Network) {
                if (initialNetwork == null || network == initialNetwork) {
                    networkChanged = true
                }
            }
        }
        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }

        var snapshot: NetworkSnapshot? = null
        try {
            withTimeout(overallDeadlineMs) {
                // 0. Среда
                send(DiagnosticRunEvent.StageStarted(StageId.ENVIRONMENT, "Снимок сети и настроек"))
                log("Считываем Wi-Fi/сотовую сеть, VPN, прокси, DNS и внешний IP...")
                val envStart = System.currentTimeMillis()
                val snap = environmentCollector.collectSnapshot(fetchExternalIp = true)
                snapshot = snap
                send(DiagnosticRunEvent.SnapshotReady(snap))

                val envProbe = ProbeResult(
                    stage = StageId.ENVIRONMENT,
                    status = if (snap.transport == TransportKind.NONE || snap.isCaptivePortal) {
                        ProbeStatus.NETWORK_FAILURE
                    } else {
                        ProbeStatus.SUCCESS
                    },
                    startedAtEpochMs = envStart,
                    durationMs = System.currentTimeMillis() - envStart,
                    summary = buildEnvSummary(snap),
                    rawDetails = "transport=${snap.transport} vpn=${snap.activeExternalVpn} proxy=${snap.systemProxy ?: "none"} privateDns=${snap.privateDnsServerName ?: snap.privateDnsActive} gw=${snap.gatewayIp ?: "n/a"} extIp=${maskExternalIp(snap.externalIp)}",
                )
                results += envProbe
                log("Среда: ${envProbe.summary}")
                send(DiagnosticRunEvent.ProbeCompleted(envProbe))

                if (snap.transport != TransportKind.NONE && !snap.isCaptivePortal) {
                    // 1. Локальный участок (шлюз + внешний контроль)
                    send(DiagnosticRunEvent.StageStarted(StageId.LOCAL_LINK, "Шлюз (роутер) и внешняя связность"))
                    val localProbe = probes.runGatewayAndExternalProbe(
                        gatewayIp = snap.gatewayIp,
                        count = 5,
                        onProgress = { msg -> log(msg) },
                    )
                    results += localProbe
                    log("Шлюз/сеть: ${localProbe.summary}")
                    send(DiagnosticRunEvent.ProbeCompleted(localProbe))

                    // 2. DNS (Системный vs DoH)
                    send(DiagnosticRunEvent.StageStarted(StageId.DNS, "DNS: системный и контрольный DoH"))
                    val dnsProbe = probes.runDnsCompareProbe(
                        hostname = host,
                        network = initialNetwork,
                        onProgress = { msg -> log(msg) },
                    )
                    results += dnsProbe
                    send(DiagnosticRunEvent.ProbeCompleted(dnsProbe))

                    val dnsMetrics = dnsProbe.dnsCompare
                    val resolvedV4 = dnsMetrics?.systemIpv4?.firstOrNull()
                        ?: dnsMetrics?.dohIpv4?.firstOrNull()
                    // Методика М17: проверяем IPv6 только если текущая сеть действительно выдала глобальный IPv6
                    val resolvedV6 = if (snap.localIpv6 != null) {
                        dnsMetrics?.systemIpv6?.firstOrNull() ?: dnsMetrics?.dohIpv6?.firstOrNull()
                    } else {
                        null
                    }
                    if (snap.localIpv6 == null && (!dnsMetrics?.systemIpv6.isNullOrEmpty() || !dnsMetrics?.dohIpv6.isNullOrEmpty())) {
                        log("Сеть без глобального IPv6 — пропускаем TCP IPv6 (норма для IPv4-only).")
                    }
                    val usedDohFallback = dnsMetrics?.systemIpv4.isNullOrEmpty() && !dnsMetrics?.dohIpv4.isNullOrEmpty()

                    // 3. TCP Dual-Stack (IPv4 / IPv6) + авто-фолбэк на www.<host>, если голый домен не отвечает (как делает браузер, например для rzd.ru)
                    var effectiveHost = host
                    var effectiveUrl = target.hostOrUrl
                    if (resolvedV4 != null || resolvedV6 != null) {
                        send(DiagnosticRunEvent.StageStarted(StageId.TCP, "TCP-подключение (IPv4 / IPv6)"))
                        var tcpProbe = probes.runTcpDualStackProbe(
                            hostname = effectiveHost,
                            port = target.port,
                            ipv4 = resolvedV4,
                            ipv6 = resolvedV6,
                            usedDohFallback = usedDohFallback,
                            network = initialNetwork,
                            onProgress = { msg -> log(msg) },
                        )
                        var workingIp = when {
                            tcpProbe.tcpDualStack?.ipv4Ok == true -> resolvedV4
                            tcpProbe.tcpDualStack?.ipv6Ok == true -> resolvedV6
                            else -> null
                        }

                        if (workingIp == null && target.type == TargetType.WEBSITE && !effectiveHost.startsWith("www.")) {
                            val wwwHost = "www.$effectiveHost"
                            log("Голый домен $effectiveHost не ответил по TCP - пробуем $wwwHost (как браузер)...")
                            val wwwDns = probes.runDnsCompareProbe(hostname = wwwHost, network = initialNetwork)
                            val wwwV4 = wwwDns.dnsCompare?.systemIpv4?.firstOrNull() ?: wwwDns.dnsCompare?.dohIpv4?.firstOrNull()
                            val wwwV6 = if (snap.localIpv6 != null) {
                                wwwDns.dnsCompare?.systemIpv6?.firstOrNull() ?: wwwDns.dnsCompare?.dohIpv6?.firstOrNull()
                            } else {
                                null
                            }
                            if (wwwV4 != null || wwwV6 != null) {
                                val wwwTcp = probes.runTcpDualStackProbe(
                                    hostname = wwwHost,
                                    port = target.port,
                                    ipv4 = wwwV4,
                                    ipv6 = wwwV6,
                                    usedDohFallback = false,
                                    network = initialNetwork,
                                )
                                val wwwWorkingIp = when {
                                    wwwTcp.tcpDualStack?.ipv4Ok == true -> wwwV4
                                    wwwTcp.tcpDualStack?.ipv6Ok == true -> wwwV6
                                    else -> null
                                }
                                if (wwwWorkingIp != null) {
                                    log("$wwwHost доступен по TCP - продолжаем проверку через $wwwHost.")
                                    effectiveHost = wwwHost
                                    effectiveUrl = "https://$wwwHost"
                                    tcpProbe = wwwTcp.copy(
                                        summary = "${wwwTcp.summary} (через $wwwHost; голый $host не отвечал)",
                                    )
                                    workingIp = wwwWorkingIp
                                }
                            }
                        }

                        results += tcpProbe
                        send(DiagnosticRunEvent.ProbeCompleted(tcpProbe))

                        // 4. Двойной SNI / TLS-тест
                        if (workingIp != null && target.port == 443) {
                            send(DiagnosticRunEvent.StageStarted(StageId.TLS_SNI, "TLS и двойной SNI-тест"))
                            val tlsProbe = probes.runDualSniTlsProbe(
                                ipAddress = workingIp,
                                port = target.port,
                                targetSni = effectiveHost,
                                network = initialNetwork,
                                onProgress = { msg -> log(msg) },
                            )
                            results += tlsProbe
                            send(DiagnosticRunEvent.ProbeCompleted(tlsProbe))

                            // 5. HTTP-редиректы и Payload Range 0-1023
                            if (tlsProbe.status == ProbeStatus.SUCCESS) {
                                send(DiagnosticRunEvent.StageStarted(StageId.HTTP_PAYLOAD, "HTTP-редиректы и передача данных (1 КБ)"))
                                val url = normalizeHttpsUrl(effectiveUrl)
                                val httpProbe = probes.runHttpPayloadProbe(
                                    initialUrl = url,
                                    onProgress = { msg -> log(msg) },
                                )
                                results += httpProbe
                                send(DiagnosticRunEvent.ProbeCompleted(httpProbe))
                            } else {
                                log("Пропускаем HTTP-запрос, так как TLS не установлен.")
                            }
                        } else if (workingIp == null) {
                            log("Пропускаем TLS/HTTP, так как TCP-порт ${target.port} не ответил.")
                        }
                    }

                    // 6. UDP / STUN
                    if (target.type == TargetType.P2P_CALLS || target.type == TargetType.NETWORK_HEALTH) {
                        send(DiagnosticRunEvent.StageStarted(StageId.UDP_STUN, "UDP / STUN (звонки и P2P)"))
                        val udpProbe = probes.runUdpStunProbe(onProgress = { msg -> log(msg) })
                        results += udpProbe
                        send(DiagnosticRunEvent.ProbeCompleted(udpProbe))
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            log("Общий лимит времени (${overallDeadlineMs / 1000} с): завершаем по собранным фактам")
            results += ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.TIMEOUT,
                startedAtEpochMs = System.currentTimeMillis(),
                durationMs = 0,
                summary = "Превышен общий лимит времени проверки (${overallDeadlineMs / 1000} с)",
                errorCode = "OVERALL_DEADLINE",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Прерывание этапа: ${e.javaClass.simpleName}")
            results += ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = System.currentTimeMillis(),
                durationMs = 0,
                summary = "Сбой проверки: ${e.javaClass.simpleName}",
                errorCode = e.javaClass.simpleName,
            )
        } finally {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }

        log("Формируем итоговый вердикт по собранным фактам...")
        val finalSnapshot = snapshot ?: environmentCollector.collectSnapshot(fetchExternalIp = false)
        val verdict = engine.evaluate(
            target = target,
            snapshot = finalSnapshot,
            probes = results,
            networkChangedDuringRun = networkChanged,
        )

        val session = DiagnosticSession(
            sessionId = UUID.randomUUID().toString().take(8),
            createdAtEpochMs = System.currentTimeMillis(),
            appVersion = appVersion,
            rulesVersion = DiagnosisEngine.RULES_VERSION,
            deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidApiLevel = Build.VERSION.SDK_INT,
            target = target,
            capabilities = capabilities,
            snapshot = finalSnapshot,
            probes = results,
            verdict = verdict,
            networkChangedDuringRun = networkChanged,
        )
        send(DiagnosticRunEvent.Finished(session))
    }

    suspend fun verifyFailedStage(session: DiagnosticSession): VerificationRecord {
        val stage = session.verdict.primaryFailedStage ?: StageId.HTTP_PAYLOAD
        val host = extractHost(session.target.hostOrUrl)
        val net = environmentCollector.getActiveNetwork()

        val probe: ProbeResult = when (stage) {
            StageId.ENVIRONMENT -> {
                val snap = environmentCollector.collectSnapshot(fetchExternalIp = false)
                ProbeResult(
                    stage = StageId.ENVIRONMENT,
                    status = if (snap.transport != TransportKind.NONE && !snap.isCaptivePortal) ProbeStatus.SUCCESS else ProbeStatus.NETWORK_FAILURE,
                    startedAtEpochMs = System.currentTimeMillis(),
                    durationMs = 0,
                    summary = buildEnvSummary(snap),
                )
            }
            StageId.LOCAL_LINK -> {
                val snap = environmentCollector.collectSnapshot(fetchExternalIp = false)
                probes.runGatewayAndExternalProbe(snap.gatewayIp, count = 5)
            }
            StageId.DNS -> probes.runDnsCompareProbe(host, net)
            StageId.TCP -> {
                val dns = probes.runDnsCompareProbe(host, net).dnsCompare
                probes.runTcpDualStackProbe(
                    hostname = host,
                    port = session.target.port,
                    ipv4 = dns?.systemIpv4?.firstOrNull() ?: dns?.dohIpv4?.firstOrNull(),
                    ipv6 = dns?.systemIpv6?.firstOrNull() ?: dns?.dohIpv6?.firstOrNull(),
                    usedDohFallback = false,
                    network = net,
                )
            }
            StageId.TLS_SNI -> {
                val dns = probes.runDnsCompareProbe(host, net).dnsCompare
                val ip = dns?.systemIpv4?.firstOrNull() ?: dns?.dohIpv4?.firstOrNull()
                if (ip != null) {
                    probes.runDualSniTlsProbe(ip, session.target.port, host, network = net)
                } else {
                    ProbeResult(StageId.TLS_SNI, ProbeStatus.NETWORK_FAILURE, System.currentTimeMillis(), 0, "Нет IP для TLS")
                }
            }
            StageId.HTTP_PAYLOAD -> probes.runHttpPayloadProbe(normalizeHttpsUrl(session.target.hostOrUrl))
            StageId.UDP_STUN -> probes.runUdpStunProbe()
            StageId.CONTROL_TUNNEL -> ProbeResult(StageId.CONTROL_TUNNEL, ProbeStatus.UNSUPPORTED, System.currentTimeMillis(), 0, "Контрольный VPN не настроен")
        }

        val outcome = when (probe.status) {
            ProbeStatus.SUCCESS -> VerificationOutcome.HELPED
            ProbeStatus.NETWORK_FAILURE, ProbeStatus.TIMEOUT -> VerificationOutcome.NOT_HELPED
            else -> VerificationOutcome.UNKNOWN
        }
        return VerificationRecord(
            verifiedAtEpochMs = System.currentTimeMillis(),
            retestedStage = stage,
            probeStatus = probe.status,
            outcome = outcome,
            note = probe.summary,
        )
    }

    private fun buildEnvSummary(snap: NetworkSnapshot): String = buildString {
        append(
            when (snap.transport) {
                TransportKind.WIFI -> "Wi-Fi${snap.wifiBandGhz?.let { " $it ГГц" } ?: ""}${snap.wifiRssiDbm?.let { " ($it дБм)" } ?: ""}"
                TransportKind.CELLULAR -> "Мобильная сеть${snap.cellularNetworkType?.let { " ($it)" } ?: ""}"
                TransportKind.ETHERNET -> "Ethernet"
                TransportKind.NONE -> "Нет подключения"
                TransportKind.OTHER -> "Другая сеть"
            },
        )
        if (snap.activeExternalVpn) append(" · Активен VPN")
        if (snap.isCaptivePortal) append(" · Требуется вход (Captive Portal)")
        if (!snap.systemProxy.isNullOrBlank()) append(" · Прокси")
    }

    private fun normalizeTarget(target: DiagnosticTarget): DiagnosticTarget {
        val cleaned = target.hostOrUrl.trim().ifBlank { "ya.ru" }
        return target.copy(hostOrUrl = cleaned)
    }

    private fun extractHost(hostOrUrl: String): String {
        val trimmed = hostOrUrl.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return runCatching { URI(withScheme).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: trimmed.substringBefore('/')
    }

    private fun normalizeHttpsUrl(hostOrUrl: String): String {
        val trimmed = hostOrUrl.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun maskExternalIp(ip: String?): String {
        if (ip.isNullOrBlank()) return "n/a"
        val p = ip.split('.')
        return if (p.size == 4) "${p[0]}.${p[1]}.x.x" else ip.take(12) + "..."
    }
}
