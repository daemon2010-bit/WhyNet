package dev.whynet.core.engine

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Упрощённая точка доступа Wi-Fi для чистого анализа каналов и рекомендаций (без Android-зависимостей).
 */
data class WifiApSample(
    val ssid: String,
    val bssid: String,
    val frequencyMhz: Int,
    val centerFreqMhz: Int,
    val bandGhz: Double,
    val channel: Int,
    val channelWidthMhz: Int = 20,
    val rssiDbm: Int,
    val isConnected: Boolean = false,
)

data class ChannelScore(
    val bandGhz: Double,
    val channel: Int,
    val score: Int, // 0..100 (100 = абсолютно чистый канал)
    val sameChannelCount: Int,
    val overlappingCount: Int,
    val strongestInterfererDbm: Int?,
)

data class BandRecommendation(
    val bandGhz: Double,
    val bestChannels: List<ChannelScore>,
    val allScores: List<ChannelScore>,
    val currentChannel: Int?,
    val currentChannelScore: Int?,
    val summary: String,
)

data class WifiInterfererEntry(
    val ssid: String,
    val bssid: String,
    val vendor: String,
    val channel: Int,
    val channelWidthMhz: Int,
    val rssiDbm: Int,
    val isSameChannel: Boolean,
    val impactPercent: Int,
)

data class ChannelInterferenceReport(
    val bandGhz: Double,
    val currentSsid: String?,
    val currentChannel: Int?,
    val congestionPercent: Int,
    val interferers: List<WifiInterfererEntry>,
    val recommendedChannel: Int?,
    val recommendedScore: Int?,
    val recommendationText: String,
)

/**
 * Анализатор Wi-Fi эфира (как VREM WiFi Analyzer / Ubiquiti WiFiman):
 * - оценка каналов 2.4 ГГц (1..13) с учётом перекрытия соседних каналов (ширина 20/40 МГц) и мощности (дБм);
 * - оценка каналов 5 ГГц (36..165) с учётом 20/40/80/160 МГц;
 * - локальная OUI-таблица популярных производителей роутеров.
 */
object WifiChannelAnalyzer {

    val CHANNELS_24_NON_OVERLAPPING = listOf(1, 6, 11)
    val CHANNELS_24_ALL = (1..13).toList()
    val CHANNELS_5_COMMON = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 132, 136, 140, 149, 153, 157, 161, 165)
    // PSC (Preferred Scanning Channels) в 6 ГГц (Wi-Fi 6E / Wi-Fi 7)
    val CHANNELS_6_PSC = listOf(5, 21, 37, 53, 69, 85, 101, 117, 133, 149, 165, 181, 197, 213, 229)

    fun analyzeBand(
        samples: List<WifiApSample>,
        bandGhz: Double,
        ignoreConnectedSelf: Boolean = true,
    ): BandRecommendation {
        val bandSamples = samples.filter { abs(it.bandGhz - bandGhz) < 0.25 }
        val connected = bandSamples.firstOrNull { it.isConnected }
        val interferers = if (ignoreConnectedSelf && connected != null) {
            bandSamples.filterNot { it.isConnected }
        } else {
            bandSamples
        }

        val candidateChannels = when {
            bandGhz < 3.0 -> CHANNELS_24_ALL
            bandGhz < 5.9 -> CHANNELS_5_COMMON
            else -> CHANNELS_6_PSC
        }

        val scores = candidateChannels.map { ch ->
            scoreChannel(ch, bandGhz, interferers)
        }

        val ranked = if (bandGhz < 3.0) {
            // На 2.4 ГГц приоритет непересекающимся каналам 1, 6, 11 (плюс 13 если он сильно лучше)
            scores.sortedWith(
                compareByDescending<ChannelScore> { it.score }
                    .thenByDescending { it.channel in CHANNELS_24_NON_OVERLAPPING }
                    .thenBy { it.overlappingCount },
            )
        } else {
            scores.sortedWith(
                compareByDescending<ChannelScore> { it.score }
                    .thenBy { it.sameChannelCount }
                    .thenBy { it.channel },
            )
        }

        val best = ranked.take(3)
        val curCh = connected?.channel?.takeIf { it > 0 }
        val curScore = curCh?.let { c -> scores.firstOrNull { it.channel == c }?.score }

        val top = best.firstOrNull()
        val summary = when {
            bandSamples.isEmpty() -> "В диапазоне $bandGhz ГГц сети не обнаружены."
            top == null -> "Недостаточно данных для рекомендации."
            curCh != null && curScore != null -> {
                if (curScore >= 80 || curCh == top.channel) {
                    "Ваш канал $curCh хороший (оценка $curScore/100). Лучший вариант: канал ${top.channel} (${top.score}/100)."
                } else {
                    "Канал $curCh перегружен ($curScore/100). Рекомендуем переключить роутер на канал ${top.channel} (${top.score}/100)."
                }
            }
            else -> "Лучшие свободные каналы ($bandGhz ГГц): ${best.joinToString { "${it.channel} (${it.score}/100)" }}."
        }

        return BandRecommendation(
            bandGhz = bandGhz,
            bestChannels = best,
            allScores = scores,
            currentChannel = curCh,
            currentChannelScore = curScore,
            summary = summary,
        )
    }

    /**
     * Подробный анализ: какие именно соседние роутеры забивают текущий канал (или самый загруженный канал диапазона),
     * с расчётом доли помехи (%) для каждой мешающей точки.
     */
    fun analyzeChannelInterferers(
        samples: List<WifiApSample>,
        bandGhz: Double,
        explicitChannel: Int? = null,
    ): ChannelInterferenceReport {
        val rec = analyzeBand(samples, bandGhz, ignoreConnectedSelf = true)
        val bandSamples = samples.filter { abs(it.bandGhz - bandGhz) < 0.25 }
        val connected = bandSamples.firstOrNull { it.isConnected }
        val targetChannel = explicitChannel ?: connected?.channel?.takeIf { it > 0 }
            ?: bandSamples.maxByOrNull { it.rssiDbm }?.channel
        val best = rec.bestChannels.firstOrNull()

        if (targetChannel == null) {
            return ChannelInterferenceReport(
                bandGhz = bandGhz,
                currentSsid = connected?.ssid,
                currentChannel = null,
                congestionPercent = 0,
                interferers = emptyList(),
                recommendedChannel = best?.channel,
                recommendedScore = best?.score,
                recommendationText = "Нет данных о текущем канале.",
            )
        }

        val targetFreq = channelToCenterFreqMhz(targetChannel, bandGhz)
        val others = if (connected != null) {
            bandSamples.filterNot { it.isConnected || it.bssid.equals(connected.bssid, ignoreCase = true) }
        } else {
            bandSamples
        }

        data class RawHit(val ap: WifiApSample, val weight: Double, val isSame: Boolean)
        val hits = mutableListOf<RawHit>()
        for (ap in others) {
            val apCenter = if (ap.centerFreqMhz > 0) ap.centerFreqMhz else ap.frequencyMhz
            val halfWidth = (ap.channelWidthMhz.coerceAtLeast(20) / 2.0) + 10.0
            val diffMhz = abs(targetFreq - apCenter).toDouble()
            if (diffMhz < halfWidth) {
                val overlapRatio = (1.0 - (diffMhz / halfWidth)).coerceIn(0.15, 1.0)
                val strengthWeight = ((ap.rssiDbm + 96).coerceIn(4, 60) / 60.0)
                val isSame = ap.channel == targetChannel || diffMhz <= 5.0
                val w = (if (isSame) 26.0 else 20.0 * overlapRatio) * strengthWeight
                hits += RawHit(ap, w, isSame)
            }
        }

        val chScore = rec.allScores.firstOrNull { it.channel == targetChannel }?.score ?: 100
        val congestion = (100 - chScore).coerceIn(0, 100)
        val totalW = hits.sumOf { it.weight }.coerceAtLeast(0.01)

        val entries = hits.sortedByDescending { it.weight }.take(8).map { h ->
            val share = ((h.weight / totalW) * congestion).roundToInt().coerceIn(1, congestion.coerceAtLeast(1))
            WifiInterfererEntry(
                ssid = h.ap.ssid.ifBlank { "<Скрытая сеть>" },
                bssid = h.ap.bssid,
                vendor = lookupVendor(h.ap.bssid),
                channel = h.ap.channel,
                channelWidthMhz = h.ap.channelWidthMhz,
                rssiDbm = h.ap.rssiDbm,
                isSameChannel = h.isSame,
                impactPercent = share,
            )
        }

        val recText = when {
            entries.isEmpty() -> "Канал $targetChannel свободен - существенных помех от других точек доступа нет."
            best != null && best.channel != targetChannel && congestion >= 25 -> {
                val bandHint = if (bandGhz < 3.0) " или перейдите на диапазон 5 ГГц" else ""
                "Рекомендуется переключить точку доступа с канала $targetChannel на свободный канал ${best.channel} (загрузка ${100 - best.score}%)$bandHint."
            }
            congestion >= 40 -> {
                if (bandGhz < 3.0) {
                    "Высокая загрузка эфира ($congestion%) - рекомендуется перейти на диапазон 5 ГГц или 6 ГГц."
                } else {
                    "Высокая загрузка эфира ($congestion%) - выберите менее занятый канал в настройках точки доступа."
                }
            }
            else ->
                "Уровень помех умеренный ($congestion%), текущий канал $targetChannel подходит для стабильной работы."
        }

        return ChannelInterferenceReport(
            bandGhz = bandGhz,
            currentSsid = connected?.ssid,
            currentChannel = targetChannel,
            congestionPercent = congestion,
            interferers = entries,
            recommendedChannel = best?.channel,
            recommendedScore = best?.score,
            recommendationText = recText,
        )
    }

    private fun scoreChannel(
        channel: Int,
        bandGhz: Double,
        interferers: List<WifiApSample>,
    ): ChannelScore {
        val candidateFreq = channelToCenterFreqMhz(channel, bandGhz)
        var penalty = 0.0
        var sameCount = 0
        var overlapCount = 0
        var maxInterfererRssi: Int? = null

        for (ap in interferers) {
            val apCenter = if (ap.centerFreqMhz > 0) ap.centerFreqMhz else ap.frequencyMhz
            val halfWidth = (ap.channelWidthMhz.coerceAtLeast(20) / 2.0) + 10.0 // +10 МГц полуширина нашего 20 МГц канала
            val diffMhz = abs(candidateFreq - apCenter).toDouble()

            if (diffMhz < halfWidth) {
                val overlapRatio = (1.0 - (diffMhz / halfWidth)).coerceIn(0.15, 1.0)
                // Сигнал -45 дБм мешает намного сильнее, чем -88 дБм
                val strengthWeight = ((ap.rssiDbm + 96).coerceIn(4, 60) / 60.0)
                val isSame = ap.channel == channel || diffMhz <= 5.0
                if (isSame) {
                    sameCount++
                    penalty += 26.0 * strengthWeight
                } else {
                    overlapCount++
                    // Соседний перекрывающий канал на 2.4 ГГц создаёт ACI-помехи (хуже, чем кажется)
                    penalty += 20.0 * overlapRatio * strengthWeight
                }
                if (maxInterfererRssi == null || ap.rssiDbm > maxInterfererRssi) {
                    maxInterfererRssi = ap.rssiDbm
                }
            }
        }

        // Небольшой штраф за промежуточные каналы на 2.4 ГГц (кроме 1, 6, 11)
        if (bandGhz < 3.0 && channel !in CHANNELS_24_NON_OVERLAPPING && interferers.isNotEmpty()) {
            penalty += 4.0
        }

        val finalScore = (100.0 - penalty).roundToInt().coerceIn(0, 100)
        return ChannelScore(
            bandGhz = bandGhz,
            channel = channel,
            score = finalScore,
            sameChannelCount = sameCount,
            overlappingCount = overlapCount,
            strongestInterfererDbm = maxInterfererRssi,
        )
    }

    fun channelToCenterFreqMhz(channel: Int, bandGhz: Double): Int = when {
        bandGhz < 3.0 -> if (channel == 14) 2484 else 2407 + channel * 5
        bandGhz < 5.9 -> 5000 + channel * 5
        else -> 5950 + channel * 5
    }

    fun rssiQualityLabel(rssiDbm: Int): String = when {
        rssiDbm >= -55 -> "Отлично"
        rssiDbm >= -67 -> "Хорошо"
        rssiDbm >= -75 -> "Удовлетв."
        else -> "Слабо"
    }

    /**
     * Расчётная дистанция до точки доступа (в метрах) по формуле затухания в свободном пространстве (FSPL).
     * В помещении стены дают дополнительное затухание, поэтому оценка ориентировочная.
     */
    fun estimateDistanceMeters(rssiDbm: Int, freqMhz: Int): Double? {
        if (freqMhz <= 0 || rssiDbm >= 0 || rssiDbm < -115) return null
        val exp = (27.55 - (20.0 * kotlin.math.log10(freqMhz.toDouble())) + abs(rssiDbm).toDouble()) / 20.0
        val meters = Math.pow(10.0, exp)
        return meters.coerceIn(0.3, 150.0)
    }

    /**
     * Быстрое офлайн-определение производителя точки доступа по первым 3 байтам BSSID (OUI).
     */
    fun lookupVendor(bssid: String?): String {
        if (bssid.isNullOrBlank()) return "Неизвестно"
        val clean = bssid.uppercase().replace("-", ":").trim()
        val parts = clean.split(":")
        if (parts.size < 3) return "Неизвестно"
        val firstByte = parts[0].toIntOrNull(16)
        if (firstByte != null && (firstByte and 0x02) != 0) {
            return "Локальный/Mesh MAC"
        }
        val prefix = "${parts[0]}:${parts[1]}:${parts[2]}"
        return OUI_MAP[prefix] ?: "OUI $prefix"
    }

    private val OUI_MAP: Map<String, String> = mapOf(
        // Cisco Systems / Meraki
        "00:BE:75" to "Cisco Systems",
        "4C:77:6D" to "Cisco Systems",
        "00:26:CB" to "Cisco Systems",
        "00:3A:98" to "Cisco Systems",
        "00:6B:F1" to "Cisco Systems",
        "00:A3:8E" to "Cisco Systems",
        "00:D7:8F" to "Cisco Systems",
        "00:F6:63" to "Cisco Systems",
        "10:F3:11" to "Cisco Systems",
        "1C:DF:0F" to "Cisco Systems",
        "2C:3F:38" to "Cisco Systems",
        "34:DB:FD" to "Cisco Systems",
        "38:ED:18" to "Cisco Systems",
        "40:CE:24" to "Cisco Systems",
        "50:06:04" to "Cisco Systems",
        "58:AC:78" to "Cisco Systems",
        "68:CA:E4" to "Cisco Systems",
        "70:69:5A" to "Cisco Systems",
        "70:DF:2F" to "Cisco Systems",
        "74:A2:E6" to "Cisco Systems",
        "7C:F1:7E" to "Cisco Systems",
        "84:B8:02" to "Cisco Systems",
        "88:1D:FC" to "Cisco Systems",
        "A0:EC:F9" to "Cisco Systems",
        "B0:AA:77" to "Cisco Systems",
        "BC:67:1C" to "Cisco Systems",
        "C4:71:FE" to "Cisco Systems",
        "CC:46:D6" to "Cisco Systems",
        "D4:6D:50" to "Cisco Systems",
        "E0:5F:B9" to "Cisco Systems",
        "F4:CF:E2" to "Cisco Systems",
        "0C:8D:DB" to "Cisco Meraki",
        "34:56:FE" to "Cisco Meraki",
        "88:15:44" to "Cisco Meraki",
        "AC:17:C8" to "Cisco Meraki",
        "E0:55:3D" to "Cisco Meraki",
        // Aruba / HPE / Ruckus / Juniper Mist
        "00:0B:86" to "Aruba (HPE)",
        "00:1A:1E" to "Aruba (HPE)",
        "00:24:6C" to "Aruba (HPE)",
        "18:64:72" to "Aruba (HPE)",
        "20:4C:03" to "Aruba (HPE)",
        "24:DE:C6" to "Aruba (HPE)",
        "40:E3:D6" to "Aruba (HPE)",
        "6C:F3:7F" to "Aruba (HPE)",
        "80:8D:B7" to "Aruba (HPE)",
        "9C:1C:12" to "Aruba (HPE)",
        "AC:A3:1E" to "Aruba (HPE)",
        "B4:5D:50" to "Aruba (HPE)",
        "D8:C7:C8" to "Aruba (HPE)",
        "00:25:C4" to "Ruckus",
        "2C:E6:CC" to "Ruckus",
        "58:93:96" to "Ruckus",
        "74:91:1A" to "Ruckus",
        "C4:10:8A" to "Ruckus",
        "5C:5B:35" to "Juniper Mist",
        "D4:20:B0" to "Juniper Mist",
        // Keenetic / ZyXEL
        "50:FF:20" to "Keenetic",
        "E4:18:6B" to "Keenetic",
        "CC:5D:4E" to "ZyXEL / Keenetic",
        "5C:F4:AB" to "ZyXEL / Keenetic",
        "EC:43:F6" to "ZyXEL / Keenetic",
        "B0:B2:DC" to "ZyXEL",
        "00:19:CB" to "ZyXEL",
        "40:4A:03" to "ZyXEL",
        "D8:EC:E5" to "ZyXEL",
        // TP-Link / Mercusys
        "50:C7:BF" to "TP-Link",
        "C0:25:E9" to "TP-Link",
        "F4:F2:6D" to "TP-Link",
        "AC:84:C6" to "TP-Link",
        "B0:95:75" to "TP-Link",
        "98:DA:C4" to "TP-Link",
        "E8:48:B8" to "TP-Link",
        "14:EB:B6" to "TP-Link",
        "3C:84:6A" to "TP-Link",
        "D8:07:B6" to "TP-Link",
        "A4:2B:B0" to "TP-Link",
        "00:31:92" to "TP-Link",
        "10:27:F5" to "TP-Link",
        "18:D6:C7" to "TP-Link",
        "30:DE:4B" to "TP-Link",
        "54:AF:97" to "TP-Link",
        "60:32:B1" to "TP-Link",
        "78:8C:B5" to "TP-Link",
        "90:9A:4A" to "TP-Link",
        "B0:BE:76" to "TP-Link",
        "C0:06:C3" to "TP-Link",
        "E4:FA:C4" to "TP-Link",
        "38:6B:1C" to "Mercusys / TP-Link",
        "48:22:54" to "Mercusys",
        // ASUS
        "04:D4:C4" to "ASUS",
        "1C:87:2C" to "ASUS",
        "2C:FD:A1" to "ASUS",
        "38:D5:47" to "ASUS",
        "50:46:5D" to "ASUS",
        "70:8B:CD" to "ASUS",
        "B0:6E:BF" to "ASUS",
        "F0:2F:74" to "ASUS",
        "08:60:6E" to "ASUS",
        "24:4B:FE" to "ASUS",
        "3C:7C:3F" to "ASUS",
        "7C:10:C9" to "ASUS",
        "AC:9E:17" to "ASUS",
        "D4:5D:64" to "ASUS",
        // MikroTik
        "4C:5E:0C" to "MikroTik",
        "64:D1:54" to "MikroTik",
        "74:4D:28" to "MikroTik",
        "B8:69:F4" to "MikroTik",
        "C4:AD:34" to "MikroTik",
        "D4:01:C3" to "MikroTik",
        "DC:2C:6E" to "MikroTik",
        "E4:8D:8C" to "MikroTik",
        "08:55:31" to "MikroTik",
        "18:FD:74" to "MikroTik",
        "2C:C8:1B" to "MikroTik",
        "48:A9:8A" to "MikroTik",
        "78:9A:18" to "MikroTik",
        // Huawei / Honor / ZTE
        "00:E0:FC" to "Huawei",
        "24:09:95" to "Huawei",
        "48:46:FB" to "Huawei",
        "70:72:3C" to "Huawei",
        "88:E3:AB" to "Huawei",
        "C8:D1:5E" to "Huawei",
        "E0:24:7F" to "Huawei",
        "F4:63:1F" to "Huawei",
        "04:F9:38" to "Huawei",
        "10:44:00" to "Huawei",
        "28:31:52" to "Huawei",
        "4C:1F:CC" to "Huawei",
        "60:DE:44" to "Huawei",
        "80:B6:86" to "Huawei",
        "9C:28:EF" to "Huawei",
        "AC:85:3D" to "Huawei",
        "D4:6A:A8" to "Huawei",
        "00:1E:73" to "ZTE",
        "2C:26:C5" to "ZTE",
        "34:E0:CF" to "ZTE",
        "54:22:F8" to "ZTE",
        "74:B5:7E" to "ZTE",
        "9C:D2:4B" to "ZTE",
        "CC:7B:35" to "ZTE",
        "F4:B8:A7" to "ZTE",
        // Xiaomi / Redmi
        "28:6C:07" to "Xiaomi",
        "34:CE:00" to "Xiaomi",
        "50:64:2B" to "Xiaomi",
        "64:09:80" to "Xiaomi",
        "78:11:DC" to "Xiaomi",
        "88:C3:97" to "Xiaomi",
        "9C:9D:7E" to "Xiaomi",
        "F0:B4:29" to "Xiaomi",
        "04:CF:8C" to "Xiaomi",
        "28:D1:27" to "Xiaomi",
        "44:23:7C" to "Xiaomi",
        "54:48:E6" to "Xiaomi",
        "7C:49:EB" to "Xiaomi",
        "8C:BE:BE" to "Xiaomi",
        "EC:41:18" to "Xiaomi",
        // D-Link / Tenda / Netgear
        "1C:7E:E5" to "D-Link",
        "C8:BE:19" to "D-Link",
        "F0:7D:68" to "D-Link",
        "14:D6:4D" to "D-Link",
        "28:10:7B" to "D-Link",
        "B8:A3:86" to "D-Link",
        "C8:3A:35" to "Tenda",
        "CC:2D:21" to "Tenda",
        "E8:65:D4" to "Tenda",
        "04:95:E6" to "Tenda",
        "50:0F:F5" to "Tenda",
        "B4:0F:3B" to "Tenda",
        "20:E5:2A" to "Netgear",
        "A0:40:A0" to "Netgear",
        "E8:FC:AF" to "Netgear",
        "28:C6:8E" to "Netgear",
        "C0:3F:0E" to "Netgear",
        // Ubiquiti
        "04:18:D6" to "Ubiquiti",
        "18:E8:29" to "Ubiquiti",
        "24:5A:4C" to "Ubiquiti",
        "68:D7:9A" to "Ubiquiti",
        "74:83:C2" to "Ubiquiti",
        "F0:9F:C2" to "Ubiquiti",
        "FC:EC:DA" to "Ubiquiti",
        "70:A7:41" to "Ubiquiti",
        "78:45:58" to "Ubiquiti",
        "80:2A:A8" to "Ubiquiti",
        "AC:8B:A9" to "Ubiquiti",
        "D0:21:F9" to "Ubiquiti",
        "E0:63:DA" to "Ubiquiti",
        // Российские ISP-роутеры (Eltex, Sercomm, Sagemcom, Qtech, Iskratel)
        "A8:F9:4B" to "Eltex",
        "E0:D9:E3" to "Eltex",
        "A4:29:40" to "Eltex",
        "C0:4A:00" to "TP-Link",
        "00:1E:69" to "Sercomm (ISP)",
        "44:D4:54" to "Sercomm (ISP)",
        "74:9D:79" to "Sercomm (ISP)",
        "90:4D:4A" to "Sagemcom (ISP)",
        "CC:33:BB" to "Sagemcom (ISP)",
        "00:1F:CE" to "Qtech",
        "00:25:9C" to "Linksys",
        // Apple / Samsung / Espressif IoT
        "10:A3:B8" to "Apple",
        "F0:18:98" to "Apple",
        "00:1C:B3" to "Apple",
        "3C:06:30" to "Apple",
        "A4:83:E7" to "Apple",
        "24:F5:AA" to "Samsung",
        "5C:3C:27" to "Samsung",
        "A0:CC:2B" to "Samsung",
        "24:0A:C4" to "Espressif (IoT)",
        "30:AE:A4" to "Espressif (IoT)",
        "A4:CF:12" to "Espressif (IoT)",
        "DC:4F:22" to "Espressif (IoT)",
    )
}
