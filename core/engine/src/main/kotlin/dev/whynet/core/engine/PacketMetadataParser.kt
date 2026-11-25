package dev.whynet.core.engine

/**
 * Безопасный парсер метаданных сырых IPv4-пакетов (без расшифровки HTTPS).
 * Извлекает протокол (TCP/UDP), адреса, порты, DNS QNAME (UDP:53) и TLS ClientHello SNI.
 */
object PacketMetadataParser {

    data class ParsedPacket(
        val protocol: String,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val dnsQueryName: String? = null,
        val tlsSni: String? = null,
        val payloadLength: Int = 0,
    )

    fun parseIpv4Packet(packet: ByteArray, length: Int = packet.size): ParsedPacket? {
        if (length < 20 || packet.size < length) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null

        val protoNum = packet[9].toInt() and 0xFF
        val srcIp = formatIpv4(packet, 12)
        val dstIp = formatIpv4(packet, 16)

        return when (protoNum) {
            17 -> { // UDP
                if (length < ihl + 8) return null
                val srcPort = readU16(packet, ihl)
                val dstPort = readU16(packet, ihl + 2)
                val payloadStart = ihl + 8
                val payloadLen = (length - payloadStart).coerceAtLeast(0)
                val dnsName = if (dstPort == 53 && payloadLen >= 13) {
                    parseDnsQueryName(packet, payloadStart, length)
                } else {
                    null
                }
                ParsedPacket(
                    protocol = "UDP",
                    srcIp = srcIp,
                    srcPort = srcPort,
                    dstIp = dstIp,
                    dstPort = dstPort,
                    dnsQueryName = dnsName,
                    payloadLength = payloadLen,
                )
            }
            6 -> { // TCP
                if (length < ihl + 20) return null
                val srcPort = readU16(packet, ihl)
                val dstPort = readU16(packet, ihl + 2)
                val tcpHeaderLen = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
                if (tcpHeaderLen < 20 || length < ihl + tcpHeaderLen) return null
                val payloadStart = ihl + tcpHeaderLen
                val payloadLen = (length - payloadStart).coerceAtLeast(0)
                val sni = if (payloadLen > 45) {
                    parseTlsClientHelloSni(packet, payloadStart, length)
                } else {
                    null
                }
                ParsedPacket(
                    protocol = "TCP",
                    srcIp = srcIp,
                    srcPort = srcPort,
                    dstIp = dstIp,
                    dstPort = dstPort,
                    tlsSni = sni,
                    payloadLength = payloadLen,
                )
            }
            else -> null
        }
    }

    fun parseDnsQueryName(buf: ByteArray, offset: Int, limit: Int): String? {
        if (offset + 12 >= limit) return null
        var pos = offset + 12
        val labels = mutableListOf<String>()
        var guard = 0
        while (pos < limit && guard++ < 64) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) != 0) break // pointer not expected in QNAME
            pos++
            if (pos + len > limit) return null
            labels += String(buf, pos, len, Charsets.US_ASCII)
            pos += len
        }
        return labels.takeIf { it.isNotEmpty() }?.joinToString(".")?.lowercase()
    }

    fun parseTlsClientHelloSni(buf: ByteArray, offset: Int, limit: Int): String? {
        if (offset + 45 > limit) return null
        // TLS Record: Handshake (0x16), TLS 1.x (0x03)
        if ((buf[offset].toInt() and 0xFF) != 0x16) return null
        if ((buf[offset + 1].toInt() and 0xFF) != 0x03) return null
        // Handshake type: ClientHello (0x01)
        if ((buf[offset + 5].toInt() and 0xFF) != 0x01) return null

        var pos = offset + 9 // start of ClientHello body
        pos += 2 + 32 // version + random
        if (pos >= limit) return null

        val sessionIdLen = buf[pos].toInt() and 0xFF
        pos += 1 + sessionIdLen
        if (pos + 2 > limit) return null

        val cipherSuitesLen = readU16(buf, pos)
        pos += 2 + cipherSuitesLen
        if (pos + 1 > limit) return null

        val compLen = buf[pos].toInt() and 0xFF
        pos += 1 + compLen
        if (pos + 2 > limit) return null

        val extTotalLen = readU16(buf, pos)
        pos += 2
        val extEnd = (pos + extTotalLen).coerceAtMost(limit)

        while (pos + 4 <= extEnd) {
            val extType = readU16(buf, pos)
            val extLen = readU16(buf, pos + 2)
            pos += 4
            if (pos + extLen > extEnd) break

            if (extType == 0x0000 && extLen >= 5) { // SNI extension
                val nameType = buf[pos + 2].toInt() and 0xFF
                val nameLen = readU16(buf, pos + 3)
                if (nameType == 0 && pos + 5 + nameLen <= extEnd && nameLen in 1..253) {
                    return String(buf, pos + 5, nameLen, Charsets.US_ASCII).lowercase()
                }
            }
            pos += extLen
        }
        return null
    }

    private fun readU16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun formatIpv4(buf: ByteArray, offset: Int): String =
        "${buf[offset].toInt() and 0xFF}.${buf[offset + 1].toInt() and 0xFF}.${buf[offset + 2].toInt() and 0xFF}.${buf[offset + 3].toInt() and 0xFF}"
}
