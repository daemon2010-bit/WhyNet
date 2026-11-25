package dev.whynet.core.engine

import dev.whynet.core.model.ConfidenceLevel
import dev.whynet.core.model.DiagnosticTarget
import dev.whynet.core.model.DnsCompareMetrics
import dev.whynet.core.model.DualSniTlsMetrics
import dev.whynet.core.model.GatewayAndExternalMetrics
import dev.whynet.core.model.HttpPayloadMetrics
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.ProbeResult
import dev.whynet.core.model.ProbeStatus
import dev.whynet.core.model.RedirectHop
import dev.whynet.core.model.StageId
import dev.whynet.core.model.TargetType
import dev.whynet.core.model.TcpDualStackMetrics
import dev.whynet.core.model.TransportKind
import dev.whynet.core.model.UdpStunMetrics
import dev.whynet.core.model.VerdictCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisEngineTest {

    private val engine = DiagnosisEngine()

    private fun baseSnapshot(
        transport: TransportKind = TransportKind.WIFI,
        activeVpn: Boolean = false,
        captivePortal: Boolean = false,
    ) = NetworkSnapshot(
        capturedAtEpochMs = 1_700_000_000_000L,
        transport = transport,
        isValidated = !captivePortal,
        isCaptivePortal = captivePortal,
        hasInternetCapability = true,
        activeExternalVpn = activeVpn,
        dnsServers = listOf("192.168.1.1"),
        gatewayIp = "192.168.1.1",
        localIpv4 = "192.168.1.50",
        wifiBandGhz = 5.0,
        wifiRssiDbm = -58,
    )

    @Test
    fun `case 1 cosmik ru - TCP ok but both target and neutral SNI fail means server issue`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "cosmik.ru")
        val probes = listOf(
            ProbeResult(
                stage = StageId.DNS,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 18,
                summary = "DNS OK",
                dnsCompare = DnsCompareMetrics("cosmik.ru", systemIpv4 = listOf("5.181.111.222"), dohIpv4 = listOf("5.181.111.222")),
            ),
            ProbeResult(
                stage = StageId.TCP,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 25,
                summary = "TCP OK",
                tcpDualStack = TcpDualStackMetrics("cosmik.ru", 443, ipv4Address = "5.181.111.222", ipv4Ok = true, ipv4RttMs = 25),
            ),
            ProbeResult(
                stage = StageId.TLS_SNI,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 120,
                summary = "TLS aborted",
                dualSniTls = DualSniTlsMetrics(
                    ipAddress = "5.181.111.222",
                    port = 443,
                    targetSni = "cosmik.ru",
                    targetSniOk = false,
                    targetSniError = "Connection reset",
                    neutralSni = "example.com",
                    neutralSniTested = true,
                    neutralSniOk = false,
                    neutralSniError = "Connection reset",
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.SERVER_OR_PAYLOAD_FAILURE, verdict.category)
        assertEquals(StageId.TLS_SNI, verdict.primaryFailedStage)
    }

    @Test
    fun `case 2 login live com - target SNI resets while neutral SNI succeeds on same IP`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "login.live.com")
        val probes = listOf(
            ProbeResult(
                stage = StageId.TCP,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 24,
                summary = "TCP OK",
                tcpDualStack = TcpDualStackMetrics("login.live.com", 443, ipv4Address = "20.190.147.5", ipv4Ok = true, ipv4RttMs = 24),
            ),
            ProbeResult(
                stage = StageId.TLS_SNI,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 40,
                summary = "Target SNI reset",
                dualSniTls = DualSniTlsMetrics(
                    ipAddress = "20.190.147.5",
                    port = 443,
                    targetSni = "login.live.com",
                    targetSniOk = false,
                    targetSniError = "ECONNRESET",
                    neutralSni = "microsoft.com",
                    neutralSniTested = true,
                    neutralSniOk = true,
                    neutralSniRttMs = 38,
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(activeVpn = false), probes)
        assertEquals(VerdictCategory.SNI_FILTERING_OR_DPI, verdict.category)
        assertEquals(ConfidenceLevel.CONFIRMED_BY_EXPERIMENT, verdict.confidence)
    }

    @Test
    fun `case 3 AdGuard or local VPN active during SNI split points to local filter first`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "login.live.com")
        val probes = listOf(
            ProbeResult(
                stage = StageId.TLS_SNI,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 30,
                summary = "Target SNI reset",
                dualSniTls = DualSniTlsMetrics(
                    ipAddress = "20.190.147.5",
                    port = 443,
                    targetSni = "login.live.com",
                    targetSniOk = false,
                    targetSniError = "ECONNRESET",
                    neutralSni = "microsoft.com",
                    neutralSniTested = true,
                    neutralSniOk = true,
                    neutralSniRttMs = 31,
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(activeVpn = true), probes)
        assertEquals(VerdictCategory.LOCAL_VPN_OR_FILTER_INTERFERENCE, verdict.category)
    }

    @Test
    fun `case 4 stuck DNS fails while DoH succeeds`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "controlplane.tailscale.com")
        val snapshot = baseSnapshot().copy(dnsServers = listOf("10.50.50.52"))
        val probes = listOf(
            ProbeResult(
                stage = StageId.DNS,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 1500,
                summary = "System DNS failed",
                dnsCompare = DnsCompareMetrics(
                    hostname = "controlplane.tailscale.com",
                    systemIpv4 = emptyList(),
                    systemError = "UnknownHostException",
                    dohIpv4 = listOf("192.200.0.1"),
                    dohControlReachable = true,
                ),
            ),
        )

        val verdict = engine.evaluate(target, snapshot, probes)
        assertEquals(VerdictCategory.DNS_FAILURE_OR_SPOOFING, verdict.category)
        assertEquals(ConfidenceLevel.CONFIRMED_BY_EXPERIMENT, verdict.confidence)
    }

    @Test
    fun `case 5 RustDesk - TCP ok but UDP STUN blocked`() {
        val target = DiagnosticTarget(TargetType.P2P_CALLS, "stun.l.google.com", port = 19302)
        val probes = listOf(
            ProbeResult(
                stage = StageId.TCP,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 20,
                summary = "TCP OK",
                tcpDualStack = TcpDualStackMetrics("google.com", 443, ipv4Address = "142.250.74.46", ipv4Ok = true, ipv4RttMs = 20),
            ),
            ProbeResult(
                stage = StageId.UDP_STUN,
                status = ProbeStatus.TIMEOUT,
                startedAtEpochMs = 0,
                durationMs = 2000,
                summary = "UDP timeout",
                udpStun = UdpStunMetrics("stun.l.google.com:19302", udpReachable = false, error = "Timeout"),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.UDP_BLOCKED_OR_NAT_RESTRICTED, verdict.category)
    }

    @Test
    fun `case 7 Yandex Disk MDS - HTTP 200 ok but Range 0-1023 aborts with close_notify`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "disk.yandex.ru")
        val probes = listOf(
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 450,
                summary = "Payload aborted",
                httpPayload = HttpPayloadMetrics(
                    initialUrl = "https://disk.yandex.ru",
                    statusCode = 206,
                    ttfbMs = 110,
                    rangeTested = true,
                    rangeBytesRead = 0,
                    rangeOk = false,
                    rangeError = "Unexpected EOF / close_notify",
                    diagnosticHeaders = mapOf("X-Mds-Request-Id" to "abc-123"),
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.SERVER_OR_PAYLOAD_FAILURE, verdict.category)
        assertTrue(verdict.confirmedFacts.any { it.contains("X-Mds-Request-Id") })
    }

    @Test
    fun `case T34 - high packet loss to local gateway localizes WiFi or router issue`() {
        val target = DiagnosticTarget(TargetType.NETWORK_HEALTH, "ya.ru")
        val probes = listOf(
            ProbeResult(
                stage = StageId.LOCAL_LINK,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 3000,
                summary = "Gateway loss 35%",
                gatewayExternal = GatewayAndExternalMetrics(
                    gatewayIp = "192.168.0.1",
                    gatewaySent = 10,
                    gatewayReceived = 6,
                    gatewayAvgMs = 85.0,
                    gatewayP95Ms = 418.0,
                    externalSent = 10,
                    externalReceived = 6,
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot().copy(wifiRssiDbm = -79), probes)
        assertEquals(VerdictCategory.LOCAL_LINK_DEGRADATION, verdict.category)
        assertEquals(StageId.LOCAL_LINK, verdict.primaryFailedStage)
    }

    @Test
    fun `redirect chain failure highlights blocked redirect host`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "login.microsoftonline.com")
        val probes = listOf(
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 300,
                summary = "Redirect failed",
                httpPayload = HttpPayloadMetrics(
                    initialUrl = "https://login.microsoftonline.com",
                    redirectChain = listOf(RedirectHop("https://login.microsoftonline.com", "login.microsoftonline.com", 302, 55)),
                    failedRedirectHost = "login.live.com",
                    failedRedirectError = "ECONNRESET",
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.REDIRECT_CHAIN_BLOCKED, verdict.category)
    }

    @Test
    fun `captive portal detected immediately from NetworkCapabilities`() {
        val target = DiagnosticTarget(TargetType.NETWORK_HEALTH, "ya.ru")
        val verdict = engine.evaluate(target, baseSnapshot(captivePortal = true), emptyList())
        assertEquals(VerdictCategory.CAPTIVE_PORTAL, verdict.category)
    }

    @Test
    fun `antibot or 429 is never marked healthy`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "gemini.google.com")
        val probes = listOf(
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 220,
                summary = "Captcha",
                httpPayload = HttpPayloadMetrics(
                    initialUrl = "https://gemini.google.com",
                    statusCode = 429,
                    captchaOrAntibotDetected = true,
                    antibotSignals = listOf("Redirect to /sorry/"),
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.ANTIBOT_OR_CAPTCHA, verdict.category)
        assertFalse(verdict.category == VerdictCategory.HEALTHY)
    }

    @Test
    fun `M17 IPv4-only network with ENETUNREACH on IPv6 is NOT blamed as broken IPv6`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "gemini.google.com")
        val ipv4OnlySnap = baseSnapshot().copy(localIpv6 = null)
        val probes = listOf(
            ProbeResult(
                stage = StageId.TCP,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 15,
                summary = "IPv4 OK",
                tcpDualStack = TcpDualStackMetrics(
                    targetHost = "gemini.google.com",
                    port = 443,
                    ipv4Address = "142.251.157.2",
                    ipv4Ok = true,
                    ipv4RttMs = 10,
                    ipv6Address = "2001:4860:4828:200::",
                    ipv6Ok = false,
                    ipv6Error = "ConnectException: ENETUNREACH (Network is unreachable)",
                ),
            ),
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 120,
                summary = "HTTP 200",
                httpPayload = HttpPayloadMetrics("https://gemini.google.com", statusCode = 200, rangeTested = true, rangeBytesRead = 1024, rangeOk = true),
            ),
        )

        val verdict = engine.evaluate(target, ipv4OnlySnap, probes)
        assertEquals(VerdictCategory.HEALTHY, verdict.category)
    }

    @Test
    fun `M17 dual-stack network with real IPv6 timeout IS flagged as IP_FAMILY_BROKEN`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "ya.ru")
        val dualStackSnap = baseSnapshot().copy(localIpv6 = "2001:db8::10")
        val probes = listOf(
            ProbeResult(
                stage = StageId.TCP,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 2500,
                summary = "IPv4 OK, IPv6 TIMEOUT",
                tcpDualStack = TcpDualStackMetrics(
                    targetHost = "ya.ru",
                    port = 443,
                    ipv4Address = "77.88.55.242",
                    ipv4Ok = true,
                    ipv4RttMs = 15,
                    ipv6Address = "2a02:6b8::2:242",
                    ipv6Ok = false,
                    ipv6Error = "TIMEOUT",
                ),
            ),
        )

        val verdict = engine.evaluate(target, dualStackSnap, probes)
        assertEquals(VerdictCategory.IP_FAMILY_BROKEN, verdict.category)
    }

    @Test
    fun `case B Gemini semantic regional restriction on HTTP 200 is NOT healthy`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "gemini.google.com")
        val probes = listOf(
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 310,
                summary = "Service restricted",
                httpPayload = HttpPayloadMetrics(
                    initialUrl = "https://gemini.google.com",
                    finalHost = "gemini.google.com",
                    statusCode = 200,
                    ttfbMs = 280,
                    rangeTested = true,
                    rangeBytesRead = 2048,
                    rangeOk = true,
                    semanticIssueDetected = true,
                    semanticSignals = listOf("Региональное ограничение сервиса"),
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.SERVICE_RESTRICTED_OR_AUTH_REQUIRED, verdict.category)
        assertFalse(verdict.category == VerdictCategory.HEALTHY)
    }

    @Test
    fun `HTTP 403 and 503 are never treated as healthy`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "example.org")
        val p403 = listOf(
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 100,
                summary = "HTTP 403",
                httpPayload = HttpPayloadMetrics("https://example.org", statusCode = 403, rangeTested = true, rangeOk = true),
            ),
        )
        val p503 = listOf(
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 100,
                summary = "HTTP 503",
                httpPayload = HttpPayloadMetrics("https://example.org", statusCode = 503, rangeTested = true, rangeOk = true),
            ),
        )

        assertEquals(VerdictCategory.SERVER_OR_PAYLOAD_FAILURE, engine.evaluate(target, baseSnapshot(), p403).category)
        assertEquals(VerdictCategory.SERVER_OR_PAYLOAD_FAILURE, engine.evaluate(target, baseSnapshot(), p503).category)
    }

    @Test
    fun `cancelled or tool error is classified as diagnostic limitation not network fault`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "ya.ru")
        val cancelled = listOf(ProbeResult(StageId.DNS, ProbeStatus.CANCELLED, 0, 10, "Cancelled"))
        val toolErr = listOf(ProbeResult(StageId.LOCAL_LINK, ProbeStatus.TOOL_ERROR, 0, 10, "Bug"))

        val vCancel = engine.evaluate(target, baseSnapshot(), cancelled)
        val vErr = engine.evaluate(target, baseSnapshot(), toolErr)
        assertEquals(VerdictCategory.DIAGNOSTIC_TOOL_LIMITATION, vCancel.category)
        assertEquals(ConfidenceLevel.INSUFFICIENT_DATA, vCancel.confidence)
        assertEquals(VerdictCategory.DIAGNOSTIC_TOOL_LIMITATION, vErr.category)
    }

    @Test
    fun `both system DNS and DoH unreachable yields honest INSUFFICIENT_DATA`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "ya.ru")
        val probes = listOf(
            ProbeResult(
                stage = StageId.DNS,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 2000,
                summary = "No DNS",
                dnsCompare = DnsCompareMetrics(
                    hostname = "ya.ru",
                    systemError = "Timeout",
                    dohControlReachable = false,
                    dohError = "Timeout",
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.UNKNOWN, verdict.category)
        assertEquals(ConfidenceLevel.INSUFFICIENT_DATA, verdict.confidence)
    }

    @Test
    fun `no active network transport returns R00_NO_NETWORK`() {
        val target = DiagnosticTarget(TargetType.NETWORK_HEALTH, "ya.ru")
        val verdict = engine.evaluate(target, baseSnapshot(transport = TransportKind.NONE), emptyList())
        assertEquals(VerdictCategory.LOCAL_LINK_DEGRADATION, verdict.category)
        assertEquals("R00_NO_NETWORK", verdict.ruleId)
    }

    @Test
    fun `traceroute ping parser handles TTL exceeded and final target reply`() {
        val ttlLine = "From 10.240.127.250 icmp_seq=1 Time to live exceeded"
        val parsedHop = PingOutputParsers.parseTraceroutePingOutput(ttlLine, "1.1.1.1")
        assertEquals("10.240.127.250", parsedHop.responderIp)
        assertTrue(parsedHop.isTtlExceeded)
        assertFalse(parsedHop.isTargetReply)

        val finalLine = "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=8.42 ms\n1 packets transmitted, 1 received, 0% packet loss"
        val parsedFinal = PingOutputParsers.parseTraceroutePingOutput(finalLine, "1.1.1.1")
        assertEquals("1.1.1.1", parsedFinal.responderIp)
        assertEquals(8.42, parsedFinal.rttMs ?: 0.0, 0.01)
        assertTrue(parsedFinal.isTargetReply)
    }

    @Test
    fun `MTU ping parser detects pass vs Frag needed with hinted MTU`() {
        val okOut = "1480 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=9.1 ms\n1 packets transmitted, 1 received, 0% packet loss"
        val ok = PingOutputParsers.parseMtuPingOutput(okOut, 0)
        assertTrue(ok.passed)
        assertFalse(ok.fragmentationNeeded)

        val fragOut = "From 192.168.1.1 icmp_seq=1 Frag needed and DF set (mtu = 1492)"
        val fail = PingOutputParsers.parseMtuPingOutput(fragOut, 1)
        assertFalse(fail.passed)
        assertTrue(fail.fragmentationNeeded)
        assertEquals(1492, fail.hintedMtu)
    }

    @Test
    fun `packet parser extracts DNS QNAME from IPv4 UDP port 53 packet`() {
        // Minimal 20B IPv4 + 8B UDP + 12B DNS header + QNAME "api.game.com"
        val dnsPayload = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            3, 'a'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(),
            4, 'g'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'e'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0, 0x00, 0x01, 0x00, 0x01,
        )
        val totalLen = 20 + 8 + dnsPayload.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45 // IPv4, IHL=20
        pkt[9] = 17   // UDP
        pkt[12] = 10; pkt[13] = 0; pkt[14] = 0; pkt[15] = 2
        pkt[16] = 8; pkt[17] = 8; pkt[18] = 8; pkt[19] = 8
        pkt[20] = 0xC0.toByte(); pkt[21] = 0x01 // srcPort
        pkt[22] = 0x00; pkt[23] = 53            // dstPort=53
        System.arraycopy(dnsPayload, 0, pkt, 28, dnsPayload.size)

        val parsed = PacketMetadataParser.parseIpv4Packet(pkt)
        assertEquals("UDP", parsed?.protocol)
        assertEquals("8.8.8.8", parsed?.dstIp)
        assertEquals(53, parsed?.dstPort)
        assertEquals("api.game.com", parsed?.dnsQueryName)
    }

    @Test
    fun `packet parser extracts TLS ClientHello SNI from IPv4 TCP packet without decryption`() {
        val host = "login.live.com".toByteArray(Charsets.US_ASCII)
        val sniExtLen = 5 + host.size
        val extTotalLen = 4 + sniExtLen
        val clientHelloBodyLen = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extTotalLen
        val tlsRecord = ByteArray(5 + 4 + clientHelloBodyLen)
        tlsRecord[0] = 0x16; tlsRecord[1] = 0x03; tlsRecord[2] = 0x01
        tlsRecord[5] = 0x01 // ClientHello
        var p = 9 + 2 + 32
        tlsRecord[p++] = 0 // session id len
        tlsRecord[p++] = 0; tlsRecord[p++] = 2 // cipher suites len = 2
        p += 2
        tlsRecord[p++] = 1; p += 1 // compression len = 1
        tlsRecord[p++] = (extTotalLen ushr 8).toByte(); tlsRecord[p++] = (extTotalLen and 0xFF).toByte()
        tlsRecord[p++] = 0; tlsRecord[p++] = 0 // extType = SNI (0x0000)
        tlsRecord[p++] = (sniExtLen ushr 8).toByte(); tlsRecord[p++] = (sniExtLen and 0xFF).toByte()
        val listLen = 3 + host.size
        tlsRecord[p++] = (listLen ushr 8).toByte(); tlsRecord[p++] = (listLen and 0xFF).toByte()
        tlsRecord[p++] = 0 // host_name
        tlsRecord[p++] = (host.size ushr 8).toByte(); tlsRecord[p++] = (host.size and 0xFF).toByte()
        System.arraycopy(host, 0, tlsRecord, p, host.size)

        val pkt = ByteArray(20 + 20 + tlsRecord.size)
        pkt[0] = 0x45; pkt[9] = 6 // IPv4 TCP
        pkt[16] = 40; pkt[17] = 126; pkt[18] = 53; pkt[19] = 13
        pkt[22] = 0x01; pkt[23] = 0xBB.toByte() // port 443
        pkt[32] = 0x50 // TCP dataOffset = 20 bytes
        System.arraycopy(tlsRecord, 0, pkt, 40, tlsRecord.size)

        val parsed = PacketMetadataParser.parseIpv4Packet(pkt)
        assertEquals("TCP", parsed?.protocol)
        assertEquals("40.126.53.13", parsed?.dstIp)
        assertEquals(443, parsed?.dstPort)
        assertEquals("login.live.com", parsed?.tlsSni)
    }

    @Test
    fun `packet parser safely ignores truncated or non-IPv4 packets`() {
        assertEquals(null, PacketMetadataParser.parseIpv4Packet(byteArrayOf(0x45, 0x00)))
        assertEquals(null, PacketMetadataParser.parseIpv4Packet(ByteArray(40) { 0x60 }))
    }

    @Test
    fun `report sanitizer masks tokens bearer headers and MAC addresses`() {
        val raw = "GET https://example.com/?token=SECRET123&ok=1 Authorization: Bearer MYJWT999 mac=aa:bb:cc:dd:ee:ff"
        val clean = ReportFormatter.sanitizeText(raw)
        assertFalse(clean.contains("SECRET123"))
        assertFalse(clean.contains("MYJWT999"))
        assertFalse(clean.contains("aa:bb:cc:dd:ee:ff"))
        assertTrue(clean.contains("token=***"))
    }

    @Test
    fun `suite parser parses default template cleanly with zero warnings`() {
        val suite = SuiteParser.parse(SuiteParser.DEFAULT_SUITE_TEMPLATE)
        assertEquals("Базовый боевой набор WhyNet", suite.name)
        assertTrue(suite.steps.size >= 14)
        assertTrue(suite.parseWarnings.isEmpty())
        assertEquals(SuiteStepKind.NETWORK, suite.steps.first().kind)
        assertTrue(suite.steps.any { it.kind == SuiteStepKind.SITE && it.target.contains("pub.fsa.gov.ru") })
        assertTrue(suite.steps.any { it.kind == SuiteStepKind.MTU })
        assertTrue(suite.steps.any { it.kind == SuiteStepKind.WIFI })
    }

    @Test
    fun `suite parser accepts plain domain lines BOM and mixed delimiters`() {
        val txt = "\uFEFF# test\n@name Мой тест\n@tag mobile_vpn\nya.ru\nhttps://pub.fsa.gov.ru/rds/declaration/view/20030406/common\nsite | login.live.com | FAIL | Ожидаем SNI\nmtu; 1.1.1.1; Проверка MTU\nwifi\n"
        val suite = SuiteParser.parse(txt)
        assertEquals("Мой тест", suite.name)
        assertEquals("mobile_vpn", suite.defaultTag)
        assertEquals(5, suite.steps.size)
        assertEquals(SuiteStepKind.SITE, suite.steps[0].kind)
        assertEquals("ya.ru", suite.steps[0].target)
        assertEquals(SuiteExpectation.FAIL, suite.steps[2].expectation)
        assertEquals(SuiteStepKind.MTU, suite.steps[3].kind)
        assertEquals("Проверка MTU", suite.steps[3].note)
        assertEquals(SuiteStepKind.WIFI, suite.steps[4].kind)
    }

    @Test
    fun `suite parser records warning on unknown step type without crashing`() {
        val suite = SuiteParser.parse("bogus_cmd | example.com\nya.ru")
        assertEquals(1, suite.steps.size)
        assertEquals(1, suite.parseWarnings.size)
    }

    @Test
    fun `app domain filter drops analytics noise and prioritizes freeze-marked domains`() {
        val candidates = listOf(
            AppDomainFilter.DomainCandidate("app-measurement.com", packetCount = 50),
            AppDomainFilter.DomainCandidate("google-analytics.com", packetCount = 40),
            AppDomainFilter.DomainCandidate("api.myapp.example", packetCount = 12),
            AppDomainFilter.DomainCandidate("match.myapp.example", packetCount = 3, seenAfterFreeze = true),
            AppDomainFilter.DomainCandidate("cdn.myapp.example", packetCount = 20),
        )
        val selected = AppDomainFilter.selectPrimaryDomains(candidates, maxCount = 3)
        assertEquals(listOf("match.myapp.example", "cdn.myapp.example", "api.myapp.example"), selected)
    }

    @Test
    fun `help link codec round-trips whynet help uri and extracts from chat text`() {
        val spec = HelpTaskSpec(
            preset = HelpPreset.CALLS_MESSENGERS,
            targets = listOf("youtube.com", "https://ya.ru/path"),
            helperName = "Сын",
            autoStart = true,
            note = "Проверь Wi-Fi дома",
        )
        val msg = HelpLinkCodec.encodeShareMessage(spec)
        val decoded = HelpLinkCodec.decodeFromAnyText(msg)
        assertEquals(HelpPreset.CALLS_MESSENGERS, decoded?.preset)
        assertEquals(listOf("youtube.com", "ya.ru"), decoded?.targets)
        assertEquals("Сын", decoded?.helperName)
        assertEquals(true, decoded?.autoStart)
        assertEquals("Проверь Wi-Fi дома", decoded?.note)

        val suite = HelpLinkCodec.toSuiteDefinition(decoded!!)
        assertTrue(suite.steps.any { it.kind == SuiteStepKind.CALLS })
        assertTrue(suite.steps.any { it.kind == SuiteStepKind.SITE && it.target == "youtube.com" })
    }

    @Test
    fun `remote session crypto encrypts and decrypts with 6-digit code and rejects wrong code`() {
        val code = "482915"
        val room1 = RemoteSessionCrypto.deriveRoomId(code)
        val room2 = RemoteSessionCrypto.deriveRoomId("482-915")
        assertEquals(room1, room2)
        assertEquals(24, room1.length)

        val secret = """{"cmd":"check_site","host":"ya.ru"}"""
        val cipher = RemoteSessionCrypto.encrypt(code, secret)
        assertFalse(cipher.contains("ya.ru"))
        assertEquals(secret, RemoteSessionCrypto.decrypt(code, cipher))
        assertEquals(null, RemoteSessionCrypto.decrypt("999999", cipher))
    }

    @Test
    fun `external control 0-5 with healthy gateway 5-5 and HTTP 200 yields HEALTHY not UNKNOWN`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "ya.ru")
        val probes = listOf(
            ProbeResult(
                stage = StageId.LOCAL_LINK,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 2000,
                summary = "Шлюз: 5/5 (8 мс) · Внешний (1.1.1.1): 0/5",
                gatewayExternal = GatewayAndExternalMetrics(
                    gatewayIp = "192.168.51.1",
                    gatewaySent = 5,
                    gatewayReceived = 5,
                    gatewayAvgMs = 8.0,
                    gatewayP95Ms = 10.0,
                    externalSent = 5,
                    externalReceived = 0,
                ),
            ),
            ProbeResult(
                stage = StageId.DNS,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 20,
                summary = "DNS OK",
                dnsCompare = DnsCompareMetrics("ya.ru", systemIpv4 = listOf("77.88.55.242"), dohControlReachable = false),
            ),
            ProbeResult(
                stage = StageId.TCP,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 18,
                summary = "TCP OK",
                tcpDualStack = TcpDualStackMetrics("ya.ru", 443, ipv4Address = "77.88.55.242", ipv4Ok = true, ipv4RttMs = 18),
            ),
            ProbeResult(
                stage = StageId.TLS_SNI,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 35,
                summary = "TLS OK",
                dualSniTls = DualSniTlsMetrics("77.88.55.242", 443, "ya.ru", targetSniOk = true, targetSniRttMs = 35),
            ),
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.SUCCESS,
                startedAtEpochMs = 0,
                durationMs = 95,
                summary = "HTTP 200",
                httpPayload = HttpPayloadMetrics("https://ya.ru", finalHost = "ya.ru", statusCode = 200, rangeTested = true, rangeBytesRead = 1024, rangeOk = true),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.HEALTHY, verdict.category)
        assertEquals("R10_HEALTHY", verdict.ruleId)
    }

    @Test
    fun `DNS empty in both system and reachable DoH yields R04_DNS_NXDOMAIN`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "appevent.cookappsgames.com")
        val probes = listOf(
            ProbeResult(
                stage = StageId.DNS,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 120,
                summary = "NXDOMAIN",
                dnsCompare = DnsCompareMetrics(
                    hostname = "appevent.cookappsgames.com",
                    systemIpv4 = emptyList(),
                    systemIpv6 = emptyList(),
                    systemError = "UnknownHostException",
                    dohProvider = "1.1.1.1",
                    dohIpv4 = emptyList(),
                    dohIpv6 = emptyList(),
                    dohControlReachable = true,
                ),
            ),
        )

        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.SERVER_OR_PAYLOAD_FAILURE, verdict.category)
        assertEquals("R04_DNS_NXDOMAIN", verdict.ruleId)
        assertEquals(StageId.DNS, verdict.primaryFailedStage)
    }

    @Test
    fun `app domain filter drops appevent unity config and ip-api noise`() {
        assertTrue(AppDomainFilter.isNoiseDomain("appevent.cookappsgames.com"))
        assertTrue(AppDomainFilter.isNoiseDomain("config.uca.cloud.unity3d.com"))
        assertTrue(AppDomainFilter.isNoiseDomain("pro.ip-api.com"))
        assertFalse(AppDomainFilter.isNoiseDomain("game-api.cookappsgames.com"))
    }

    @Test
    fun `help link codec emits clickable https url and still decodes legacy whynet scheme`() {
        val spec = HelpTaskSpec(preset = HelpPreset.WIFI_ROUTER, targets = listOf("ya.ru"), helperName = "Иван")
        val encoded = HelpLinkCodec.encodeUri(spec)
        assertTrue(encoded.startsWith("https://daemon2010-bit.github.io/help.html?"))
        val decodedNew = HelpLinkCodec.decodeFromAnyText("Привет! $encoded")
        assertEquals(HelpPreset.WIFI_ROUTER, decodedNew?.preset)
        assertEquals("Иван", decodedNew?.helperName)

        val decodedLegacy = HelpLinkCodec.decodeFromAnyText("whynet://help?v=1&preset=calls&targets=ya.ru")
        assertEquals(HelpPreset.CALLS_MESSENGERS, decodedLegacy?.preset)
        assertEquals(listOf("ya.ru"), decodedLegacy?.targets)
    }

    @Test
    fun `HTTP 405 on API domain after successful TLS is classified as reachable HEALTHY`() {
        val target = DiagnosticTarget(TargetType.WEBSITE, "config.uca.cloud.unity3d.com")
        val probes = listOf(
            ProbeResult(
                stage = StageId.HTTP_PAYLOAD,
                status = ProbeStatus.NETWORK_FAILURE,
                startedAtEpochMs = 0,
                durationMs = 300,
                summary = "HTTP 405",
                httpPayload = HttpPayloadMetrics("https://config.uca.cloud.unity3d.com", finalHost = "config.uca.cloud.unity3d.com", statusCode = 405, ttfbMs = 300),
            ),
        )
        val verdict = engine.evaluate(target, baseSnapshot(), probes)
        assertEquals(VerdictCategory.HEALTHY, verdict.category)
        assertEquals("R07_HTTP_API_REACHABLE", verdict.ruleId)
    }

    @Test
    fun `wifi channel analyzer avoids crowded 2_4 GHz channels and resolves OUI vendor`() {
        val samples = listOf(
            WifiApSample("Neighbor1", "50:FF:20:11:22:33", 2412, 2412, 2.4, 1, 20, -48),
            WifiApSample("Neighbor2", "50:C7:BF:11:22:33", 2437, 2437, 2.4, 6, 20, -52),
            WifiApSample("Neighbor3", "04:D4:C4:11:22:33", 2437, 2437, 2.4, 6, 40, -55),
        )
        val rec = WifiChannelAnalyzer.analyzeBand(samples, 2.4)
        assertEquals(11, rec.bestChannels.first().channel)
        assertTrue(rec.bestChannels.first().score >= 85)
        assertEquals("Keenetic", WifiChannelAnalyzer.lookupVendor("50:FF:20:AA:BB:CC"))
        assertEquals("TP-Link", WifiChannelAnalyzer.lookupVendor("50:C7:BF:01:02:03"))
    }

    @Test
    fun `wifi heatmap IDW interpolates smoothly and summarizes weak zones`() {
        val samples = listOf(
            Triple(0.1f, 0.5f, -50.0),
            Triple(0.9f, 0.5f, -80.0),
        )
        // В середине между -50 и -80 должно быть ровно -65
        val mid = WifiHeatmapEngine.interpolateIdw(0.5f, 0.5f, samples)!!
        assertEquals(-65.0, mid, 0.5)
        // В самой точке замера — точное значение
        val exact = WifiHeatmapEngine.interpolateIdw(0.1f, 0.5f, samples)!!
        assertEquals(-50.0, exact, 0.01)

        val plan = HeatmapFloorPlan(
            name = "Тест",
            walls = WifiHeatmapEngine.presetWalls("two_room"),
            points = listOf(
                HeatmapPoint("1", "Т1", 0.2f, 0.2f, 0L, "Home5G", 5.0, 36, -52, 4.0),
                HeatmapPoint("2", "Т2", 0.8f, 0.8f, 0L, "Home5G", 5.0, 36, -81, 18.0),
            ),
        )
        assertTrue(plan.walls.isNotEmpty())
        val summary = WifiHeatmapEngine.summarizePlan(plan)
        assertTrue(summary.contains("Т1"))
        assertTrue(summary.contains("Т2"))
        assertTrue(summary.contains("Слабый приём"))
    }

    @Test
    fun `AP Locator pulls estimated router position toward strongest RSSI corner and supports 6 GHz FSPL`() {
        val plan = HeatmapFloorPlan(
            name = "Квартира",
            points = listOf(
                HeatmapPoint(
                    id = "1", label = "Т1", x = 0.1f, y = 0.1f, capturedAtEpochMs = 0L,
                    connectedSsid = "Home6G", connectedBandGhz = 6.0, connectedChannel = 37, connectedRssiDbm = -42,
                    visibleNetworks = listOf(
                        HeatmapNetworkReading("Home6G", 6.0, 37, -42, isConnected = true, bssid = "50:ff:20:11:22:33"),
                        HeatmapNetworkReading("Neighbor", 2.4, 6, -80, isConnected = false, bssid = "50:c7:bf:aa:bb:cc"),
                    ),
                ),
                HeatmapPoint(
                    id = "2", label = "Т2", x = 0.9f, y = 0.9f, capturedAtEpochMs = 0L,
                    connectedSsid = "Home6G", connectedBandGhz = 6.0, connectedChannel = 37, connectedRssiDbm = -78,
                    visibleNetworks = listOf(
                        HeatmapNetworkReading("Home6G", 6.0, 37, -78, isConnected = true, bssid = "50:ff:20:11:22:33"),
                        HeatmapNetworkReading("Neighbor", 2.4, 6, -48, isConnected = false, bssid = "50:c7:bf:aa:bb:cc"),
                    ),
                ),
            ),
        )
        val routers = WifiHeatmapEngine.estimateRouterLocations(plan)
        assertEquals(2, routers.size)
        val home = routers.first { it.isConnected }
        val neighbor = routers.first { !it.isConnected }
        assertTrue(home.x < 0.25f && home.y < 0.25f)
        assertTrue(neighbor.x > 0.75f && neighbor.y > 0.75f)

        val rec6 = WifiChannelAnalyzer.analyzeBand(
            listOf(WifiApSample("Home6G", "50:FF:20:11:22:33", 6135, 6135, 6.0, 37, 160, -50)),
            6.0,
        )
        assertTrue(rec6.bestChannels.isNotEmpty())
        val dist = WifiChannelAnalyzer.estimateDistanceMeters(-50, 5180)
        assertTrue(dist != null && dist in 0.5..10.0)
    }

    @Test
    fun `net tools math computes CIDR subnet, port list, WoL magic packet and Bufferbloat grade`() {
        val cidr = NetToolsMath.calculateCidr("10.240.42.38/20")!!
        assertEquals("10.240.32.0", cidr.networkAddress)
        assertEquals("10.240.47.255", cidr.broadcastAddress)
        assertEquals("255.255.240.0", cidr.subnetMask)
        assertEquals(4094L, cidr.usableHosts)

        val ports = NetToolsMath.parsePortList("22, 80, 443, 8080-8082")
        assertEquals(listOf(22, 80, 443, 8080, 8081, 8082), ports)

        val wol = NetToolsMath.buildWolMagicPacket("AA:BB:CC:DD:EE:FF")
        assertEquals(102, wol?.size)
        assertEquals(0xFF.toByte(), wol?.get(0))
        assertEquals(0xAA.toByte(), wol?.get(6))
        assertEquals(null, NetToolsMath.buildWolMagicPacket("not-a-mac"))

        assertEquals("A+", NetToolsMath.gradeBufferbloat(10.0, 12.0).first)
        assertEquals("D", NetToolsMath.gradeBufferbloat(15.0, 180.0).first)
    }
}
