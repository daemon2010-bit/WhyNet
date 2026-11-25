package dev.whynet.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TargetType {
    NETWORK_HEALTH,
    WEBSITE,
    P2P_CALLS,
    APP_PACKAGE,
}

@Serializable
enum class TransportKind {
    WIFI,
    CELLULAR,
    ETHERNET,
    NONE,
    OTHER,
}

@Serializable
enum class StageId {
    ENVIRONMENT,
    LOCAL_LINK,
    DNS,
    TCP,
    TLS_SNI,
    HTTP_PAYLOAD,
    UDP_STUN,
    CONTROL_TUNNEL,
}

@Serializable
enum class ProbeStatus {
    SUCCESS,
    NETWORK_FAILURE,
    UNSUPPORTED,
    PERMISSION_DENIED,
    CANCELLED,
    TIMEOUT,
    TOOL_ERROR,
}

@Serializable
enum class ConfidenceLevel {
    CONFIRMED_BY_EXPERIMENT,
    PROBABLE,
    POSSIBLE,
    INSUFFICIENT_DATA,
}

@Serializable
enum class VerdictCategory {
    HEALTHY,
    LOCAL_LINK_DEGRADATION,
    CAPTIVE_PORTAL,
    LOCAL_VPN_OR_FILTER_INTERFERENCE,
    DNS_FAILURE_OR_SPOOFING,
    IP_FAMILY_BROKEN,
    SNI_FILTERING_OR_DPI,
    UDP_BLOCKED_OR_NAT_RESTRICTED,
    REDIRECT_CHAIN_BLOCKED,
    SERVER_OR_PAYLOAD_FAILURE,
    ANTIBOT_OR_CAPTCHA,
    SERVICE_RESTRICTED_OR_AUTH_REQUIRED,
    DIAGNOSTIC_TOOL_LIMITATION,
    UNKNOWN,
}

@Serializable
enum class ActionType {
    OPEN_WIFI_SETTINGS,
    OPEN_PRIVATE_DNS_SETTINGS,
    OPEN_VPN_SETTINGS,
    OPEN_NETWORK_SETTINGS,
    OPEN_DROPOUT_WATCHER,
    SWITCH_NETWORK_COMPARE,
    RETRY_FAILED_STAGE,
    MANUAL_STEP,
}

@Serializable
enum class VerificationOutcome {
    HELPED,
    NOT_HELPED,
    UNKNOWN,
}

@Serializable
data class DiagnosticTarget(
    val type: TargetType,
    val hostOrUrl: String,
    val port: Int = 443,
    val label: String = hostOrUrl,
    val packageName: String? = null,
    val userSymptom: String? = null,
)

@Serializable
data class CapabilityState(
    val hasWifiScanPermission: Boolean = false,
    val hasPhoneStatePermission: Boolean = false,
    val hasVpnPermission: Boolean = false,
    val supportsDnsResolver: Boolean = true,
)

@Serializable
data class NetworkSnapshot(
    val capturedAtEpochMs: Long,
    val transport: TransportKind,
    val isValidated: Boolean,
    val isCaptivePortal: Boolean,
    val hasInternetCapability: Boolean,
    val activeExternalVpn: Boolean,
    val systemProxy: String? = null,
    val privateDnsActive: Boolean = false,
    val privateDnsServerName: String? = null,
    val dnsServers: List<String> = emptyList(),
    val gatewayIp: String? = null,
    val localIpv4: String? = null,
    val localIpv6: String? = null,
    val externalIp: String? = null,
    val isCgnatLikely: Boolean? = null,
    val interfaceMtu: Int? = null,
    val wifiBandGhz: Double? = null,
    val wifiRssiDbm: Int? = null,
    val wifiLinkSpeedMbps: Int? = null,
    val wifiSsid: String? = null,
    val wifiChannel: Int? = null,
    val wifiTxLinkSpeedMbps: Int? = null,
    val wifiRxLinkSpeedMbps: Int? = null,
    val wifiStandard: String? = null,
    val cellularRsrpDbm: Int? = null,
    val cellularNetworkType: String? = null,
    val cellularEnbId: Int? = null,
    val cellularTac: Int? = null,
    val cellularBandLabel: String? = null,
    val wifiChannelCongestionPct: Int? = null,
    val wifiSameChannelInterferers: Int? = null,
    val wifiRecommendedChannel: Int? = null,
)

@Serializable
data class GatewayAndExternalMetrics(
    val gatewayIp: String? = null,
    val gatewaySent: Int = 0,
    val gatewayReceived: Int = 0,
    val gatewayAvgMs: Double? = null,
    val gatewayP95Ms: Double? = null,
    val externalSent: Int = 0,
    val externalReceived: Int = 0,
    val externalAvgMs: Double? = null,
    val externalP95Ms: Double? = null,
    val externalJitterMs: Double? = null,
) {
    val gatewayLossPercent: Double
        get() = if (gatewaySent == 0) 0.0 else ((gatewaySent - gatewayReceived).toDouble() / gatewaySent) * 100.0

    val externalLossPercent: Double
        get() = if (externalSent == 0) 0.0 else ((externalSent - externalReceived).toDouble() / externalSent) * 100.0
}

@Serializable
data class DnsCompareMetrics(
    val hostname: String,
    val systemIpv4: List<String> = emptyList(),
    val systemIpv6: List<String> = emptyList(),
    val systemRttMs: Long? = null,
    val systemError: String? = null,
    val dohProvider: String = "1.1.1.1",
    val dohIpv4: List<String> = emptyList(),
    val dohIpv6: List<String> = emptyList(),
    val dohRttMs: Long? = null,
    val dohError: String? = null,
    val isSuspiciousSystemIp: Boolean = false,
    val dohControlReachable: Boolean = true,
)

@Serializable
data class TcpDualStackMetrics(
    val targetHost: String,
    val port: Int,
    val ipv4Address: String? = null,
    val ipv4Ok: Boolean? = null,
    val ipv4RttMs: Long? = null,
    val ipv4Error: String? = null,
    val ipv6Address: String? = null,
    val ipv6Ok: Boolean? = null,
    val ipv6RttMs: Long? = null,
    val ipv6Error: String? = null,
    val usedDohFallbackIp: Boolean = false,
)

@Serializable
data class DualSniTlsMetrics(
    val ipAddress: String,
    val port: Int,
    val targetSni: String,
    val targetSniOk: Boolean,
    val targetSniRttMs: Long? = null,
    val targetSniError: String? = null,
    val targetSniErrorKind: String? = null,
    val neutralSni: String? = null,
    val neutralSniTested: Boolean = false,
    val neutralSniOk: Boolean? = null,
    val neutralSniRttMs: Long? = null,
    val neutralSniError: String? = null,
    val certificateValid: Boolean? = null,
    val tlsProtocol: String? = null,
)

@Serializable
data class RedirectHop(
    val urlSanitized: String,
    val host: String,
    val statusCode: Int,
    val rttMs: Long,
)

@Serializable
data class HttpPayloadMetrics(
    val initialUrl: String,
    val finalHost: String? = null,
    val statusCode: Int? = null,
    val ttfbMs: Long? = null,
    val redirectChain: List<RedirectHop> = emptyList(),
    val failedRedirectHost: String? = null,
    val failedRedirectError: String? = null,
    val rangeTested: Boolean = false,
    val rangeBytesRead: Int = 0,
    val rangeOk: Boolean? = null,
    val rangeError: String? = null,
    val captchaOrAntibotDetected: Boolean = false,
    val antibotSignals: List<String> = emptyList(),
    val semanticIssueDetected: Boolean = false,
    val semanticSignals: List<String> = emptyList(),
    val diagnosticHeaders: Map<String, String> = emptyMap(),
)

@Serializable
data class TracerouteHop(
    val ttl: Int,
    val ip: String?,
    val rttMs: Double?,
    val reachedTarget: Boolean = false,
    val note: String? = null,
)

@Serializable
data class TracerouteMetrics(
    val targetHost: String,
    val resolvedIp: String? = null,
    val hops: List<TracerouteHop> = emptyList(),
    val reachedTarget: Boolean = false,
)

@Serializable
data class MtuProbeMetrics(
    val targetHost: String,
    val interfaceMtu: Int? = null,
    val maxPayloadPassed: Int? = null,
    val estimatedPathMtu: Int? = null,
    val pmtudBlackHoleSuspected: Boolean = false,
)

@Serializable
data class UdpStunMetrics(
    val stunServer: String,
    val udpReachable: Boolean,
    val rttMs: Long? = null,
    val mappedAddress: String? = null,
    val error: String? = null,
)

@Serializable
data class ProbeResult(
    val stage: StageId,
    val status: ProbeStatus,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val summary: String,
    val errorCode: String? = null,
    val rawDetails: String? = null,
    val gatewayExternal: GatewayAndExternalMetrics? = null,
    val dnsCompare: DnsCompareMetrics? = null,
    val tcpDualStack: TcpDualStackMetrics? = null,
    val dualSniTls: DualSniTlsMetrics? = null,
    val httpPayload: HttpPayloadMetrics? = null,
    val udpStun: UdpStunMetrics? = null,
    val traceroute: TracerouteMetrics? = null,
    val mtuProbe: MtuProbeMetrics? = null,
)

@Serializable
data class RecommendedAction(
    val id: String,
    val title: String,
    val description: String,
    val type: ActionType,
    val reversibleNote: String? = null,
)

@Serializable
data class Verdict(
    val category: VerdictCategory,
    val confidence: ConfidenceLevel,
    val ruleId: String,
    val ruleVersion: String,
    val headline: String,
    val explanation: String,
    val confirmedFacts: List<String>,
    val hypotheses: List<String>,
    val unknowns: List<String>,
    val recommendedActions: List<RecommendedAction>,
    val primaryFailedStage: StageId? = null,
)

@Serializable
data class VerificationRecord(
    val verifiedAtEpochMs: Long,
    val retestedStage: StageId,
    val probeStatus: ProbeStatus,
    val outcome: VerificationOutcome,
    val note: String,
)

@Serializable
data class DiagnosticSession(
    val sessionId: String,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val rulesVersion: String,
    val deviceSummary: String,
    val androidApiLevel: Int,
    val target: DiagnosticTarget,
    val capabilities: CapabilityState,
    val snapshot: NetworkSnapshot,
    val probes: List<ProbeResult>,
    val verdict: Verdict,
    val networkChangedDuringRun: Boolean = false,
    val verification: VerificationRecord? = null,
)
