package dev.whynet.core.engine

import kotlin.math.max
import kotlin.math.roundToInt

data class CidrCalculation(
    val input: String,
    val ipAddress: String,
    val prefixLength: Int,
    val subnetMask: String,
    val wildcardMask: String,
    val networkAddress: String,
    val broadcastAddress: String,
    val firstUsableHost: String,
    val lastUsableHost: String,
    val totalAddresses: Long,
    val usableHosts: Long,
    val addressScope: String,
)

/**
 * Офлайн-калькулятор IPv4 подсетей (CIDR), парсер портов, Wake-on-LAN и оценка Bufferbloat.
 */
object NetToolsMath {

    val PORT_PRESETS: Map<String, List<Int>> = linkedMapOf(
        "Популярные (20)" to listOf(21, 22, 23, 25, 53, 80, 110, 139, 143, 443, 445, 554, 993, 1883, 3306, 3389, 5432, 8080, 8291, 8443),
        "Веб и прокси" to listOf(80, 81, 443, 3128, 8000, 8080, 8081, 8443, 8888, 9000),
        "Удалёнка / NAS" to listOf(21, 22, 139, 445, 548, 2049, 3389, 5000, 5001, 5900, 8291),
        "Камеры / Принтеры" to listOf(80, 443, 515, 554, 631, 8000, 8080, 8554, 9100, 37777),
    )

    fun knownServiceName(port: Int): String = when (port) {
        21 -> "FTP"
        22 -> "SSH"
        23 -> "Telnet"
        25 -> "SMTP"
        53 -> "DNS"
        80 -> "HTTP"
        110 -> "POP3"
        139 -> "NetBIOS"
        143 -> "IMAP"
        443 -> "HTTPS"
        445 -> "SMB"
        515 -> "LPD"
        548 -> "AFP"
        554 -> "RTSP"
        631 -> "IPP"
        993 -> "IMAPS"
        1883 -> "MQTT"
        2049 -> "NFS"
        3128 -> "Proxy"
        3306 -> "MySQL"
        3389 -> "RDP"
        5000 -> "Synology/UPnP"
        5001 -> "Synology HTTPS"
        5432 -> "PostgreSQL"
        5900 -> "VNC"
        6379 -> "Redis"
        8000 -> "HTTP-Alt"
        8080 -> "HTTP-Proxy"
        8291 -> "MikroTik WinBox"
        8443 -> "HTTPS-Alt"
        8554 -> "RTSP-Alt"
        9100 -> "Printer RAW"
        37777 -> "Dahua DVR"
        62078 -> "Apple Lockdownd"
        else -> "TCP/$port"
    }

    /**
     * Парсит строку портов вида "22, 80, 443, 8000-8010" (до 512 уникальных портов).
     */
    fun parsePortList(spec: String, maxPorts: Int = 512): List<Int> {
        val out = LinkedHashSet<Int>()
        spec.split(',', ';', ' ').forEach { token ->
            val t = token.trim()
            if (t.isEmpty()) return@forEach
            if ('-' in t) {
                val a = t.substringBefore('-').toIntOrNull()
                val b = t.substringAfter('-').toIntOrNull()
                if (a != null && b != null) {
                    val low = minOf(a, b).coerceIn(1, 65535)
                    val high = maxOf(a, b).coerceIn(1, 65535)
                    for (p in low..high) {
                        out += p
                        if (out.size >= maxPorts) return out.toList()
                    }
                }
            } else {
                val p = t.toIntOrNull()
                if (p != null && p in 1..65535) {
                    out += p
                    if (out.size >= maxPorts) return out.toList()
                }
            }
        }
        return out.toList()
    }

    /**
     * Вычисляет параметры IPv4 подсети для строки вида "192.168.51.69/24" или "10.240.42.38/20".
     */
    fun calculateCidr(rawInput: String): CidrCalculation? {
        val clean = rawInput.trim()
        val ipPart = clean.substringBefore('/').trim()
        val prefixPart = if ('/' in clean) clean.substringAfter('/').trim().toIntOrNull() else 24
        if (prefixPart == null || prefixPart !in 0..32) return null

        val octets = ipPart.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null

        val ipInt: Long = ((octets[0].toLong() shl 24) or
            (octets[1].toLong() shl 16) or
            (octets[2].toLong() shl 8) or
            octets[3].toLong()) and 0xFFFFFFFFL

        val maskInt: Long = if (prefixPart == 0) 0L else (0xFFFFFFFFL shl (32 - prefixPart)) and 0xFFFFFFFFL
        val wildcardInt: Long = maskInt.inv() and 0xFFFFFFFFL
        val networkInt: Long = ipInt and maskInt
        val broadcastInt: Long = networkInt or wildcardInt
        val total: Long = 1L shl (32 - prefixPart)
        val usable: Long = when (prefixPart) {
            32 -> 1L
            31 -> 2L
            else -> max(0L, total - 2L)
        }
        val firstUsable = if (prefixPart >= 31) networkInt else networkInt + 1L
        val lastUsable = if (prefixPart >= 31) broadcastInt else broadcastInt - 1L

        val scope = when {
            octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168) -> "Локальная (RFC 1918 Private)"
            octets[0] == 100 && octets[1] in 64..127 -> "Операторский NAT (CGNAT RFC 6598)"
            octets[0] == 127 -> "Loopback"
            octets[0] == 169 && octets[1] == 254 -> "Link-Local (APIPA)"
            else -> "Публичный IPv4"
        }

        return CidrCalculation(
            input = "$ipPart/$prefixPart",
            ipAddress = longToIpv4(ipInt),
            prefixLength = prefixPart,
            subnetMask = longToIpv4(maskInt),
            wildcardMask = longToIpv4(wildcardInt),
            networkAddress = longToIpv4(networkInt),
            broadcastAddress = longToIpv4(broadcastInt),
            firstUsableHost = longToIpv4(firstUsable),
            lastUsableHost = longToIpv4(lastUsable),
            totalAddresses = total,
            usableHosts = usable,
            addressScope = scope,
        )
    }

    /**
     * Формирует 102-байтный Magic Packet для Wake-on-LAN (6x 0xFF + 16x MAC).
     */
    fun buildWolMagicPacket(macInput: String): ByteArray? {
        val hex = macInput.trim().replace(":", "").replace("-", "").replace(".", "").uppercase()
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val macBytes = ByteArray(6) { idx ->
            hex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (rep in 0 until 16) {
            System.arraycopy(macBytes, 0, packet, 6 + rep * 6, 6)
        }
        return packet
    }

    /**
     * Выставляет грейд Bufferbloat (A+ .. F) по приросту задержки под нагрузкой (как Waveform Bufferbloat).
     */
    fun gradeBufferbloat(idleMs: Double, loadedMs: Double): Pair<String, String> {
        val delta = (loadedMs - idleMs).coerceAtLeast(0.0).roundToInt()
        return when {
            delta <= 5 -> "A+" to "Отлично (+$delta мс): буфер не раздувается, звонки и игры идеальны"
            delta <= 20 -> "A" to "Хорошо (+$delta мс): стабильный канал под нагрузкой"
            delta <= 50 -> "B" to "Норма (+$delta мс): небольшая задержка при скачивании"
            delta <= 120 -> "C" to "Заметный Bufferbloat (+$delta мс): в играх/звонках возможны лаги при загрузке"
            delta <= 250 -> "D" to "Сильный Bufferbloat (+$delta мс): стоит включить SQM/QoS на роутере"
            else -> "F" to "Критический Bufferbloat (+$delta мс): роутер или канал сильно захлёбываются под нагрузкой"
        }
    }

    private fun longToIpv4(v: Long): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
}
