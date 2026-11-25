package dev.whynet.core.engine

/**
 * Чистые Kotlin-парсеры вывода системного /system/bin/ping (iputils/toybox Android)
 * для трассировки (TTL) и проверки MTU (-M do -s <size>).
 */
object PingOutputParsers {

    private val fromIpRegex = Regex("(?i)from\\s+(?:[^\\s(]+\\s*\\()?([0-9]{1,3}(?:\\.[0-9]{1,3}){3})\\)?")
    private val rttRegex = Regex("(?i)time[=<]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms")
    private val ttlExceededRegex = Regex("(?i)time to live exceeded|exceeded")
    private val fragNeededRegex = Regex("(?i)message too long|frag needed|mtu\\s*=\\s*\\d+")
    private val mtuHintRegex = Regex("(?i)mtu\\s*=\\s*(\\d+)")
    private val receivedRegex = Regex("(?i)(\\d+)\\s+received")

    data class ParsedHop(
        val responderIp: String?,
        val rttMs: Double?,
        val isTtlExceeded: Boolean,
        val isTargetReply: Boolean,
    )

    fun parseTraceroutePingOutput(output: String, targetIp: String?): ParsedHop {
        val ip = fromIpRegex.find(output)?.groupValues?.getOrNull(1)
        val rtt = rttRegex.find(output)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        val ttlExceeded = ttlExceededRegex.containsMatchIn(output)
        val received = receivedRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val reached = !ttlExceeded && (received > 0 || (ip != null && ip == targetIp && rtt != null))
        return ParsedHop(
            responderIp = ip,
            rttMs = rtt,
            isTtlExceeded = ttlExceeded,
            isTargetReply = reached,
        )
    }

    data class ParsedMtuPing(
        val passed: Boolean,
        val fragmentationNeeded: Boolean,
        val hintedMtu: Int?,
    )

    fun parseMtuPingOutput(output: String, exitCode: Int): ParsedMtuPing {
        val fragMatch = fragNeededRegex.find(output)
        val received = receivedRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val passed = exitCode == 0 && received > 0 && fragMatch == null
        val hintedMtu = mtuHintRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return ParsedMtuPing(
            passed = passed,
            fragmentationNeeded = fragMatch != null,
            hintedMtu = hintedMtu,
        )
    }
}
