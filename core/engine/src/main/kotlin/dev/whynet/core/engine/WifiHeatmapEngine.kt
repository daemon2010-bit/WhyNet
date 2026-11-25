package dev.whynet.core.engine

import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

@Serializable
data class HeatmapWall(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
)

@Serializable
data class HeatmapNetworkReading(
    val ssid: String,
    val bandGhz: Double,
    val channel: Int,
    val rssiDbm: Int,
    val isConnected: Boolean = false,
    val bssid: String = "",
)

data class EstimatedApLocation(
    val key: String,
    val ssid: String,
    val bssid: String,
    val bandGhz: Double,
    val channel: Int,
    val x: Float,
    val y: Float,
    val maxRssiDbm: Int,
    val seenInPoints: Int,
    val isConnected: Boolean,
)

@Serializable
data class HeatmapPoint(
    val id: String,
    val label: String,
    val x: Float, // 0..1
    val y: Float, // 0..1
    val capturedAtEpochMs: Long,
    val connectedSsid: String,
    val connectedBandGhz: Double? = null,
    val connectedChannel: Int? = null,
    val connectedRssiDbm: Int? = null,
    val gatewayRttMs: Double? = null,
    val bestOverallSsid: String? = null,
    val bestOverallBandGhz: Double? = null,
    val bestOverallChannel: Int? = null,
    val bestOverallRssiDbm: Int? = null,
    val visibleNetworks: List<HeatmapNetworkReading> = emptyList(),
)

@Serializable
data class HeatmapFloorPlan(
    val name: String = "Моя планировка",
    val backgroundImagePath: String? = null,
    val walls: List<HeatmapWall> = emptyList(),
    val points: List<HeatmapPoint> = emptyList(),
)

enum class HeatmapLayer(val title: String) {
    SIGNAL_RSSI("Сигнал дБм"),
    BEST_NETWORK("Лучшая сеть"),
    GATEWAY_PING("Пинг шлюза"),
}

/**
 * Математическое ядро тепловой карты помещения (IDW-интерполяция, пресеты комнат, сводка).
 */
object WifiHeatmapEngine {

    /**
     * Интерполяция методом обратных расстояний (Inverse Distance Weighting).
     * Если точка совпадает с замером (<0.004), возвращает точное значение замера.
     */
    fun interpolateIdw(
        x: Float,
        y: Float,
        samples: List<Triple<Float, Float, Double>>,
        power: Double = 2.2,
    ): Double? {
        if (samples.isEmpty()) return null
        var weightedSum = 0.0
        var weightTotal = 0.0
        for ((sx, sy, value) in samples) {
            val dist = hypot((x - sx).toDouble(), (y - sy).toDouble())
            if (dist < 0.004) return value
            val w = 1.0 / dist.pow(power)
            weightedSum += w * value
            weightTotal += w
        }
        return if (weightTotal > 0.0) weightedSum / weightTotal else null
    }

    /** Нормализация RSSI (-88 дБм -> 0.0, -45 дБм -> 1.0) для цветовой шкалы. */
    fun rssiToQuality(rssiDbm: Double): Float =
        ((rssiDbm + 88.0) / 43.0).toFloat().coerceIn(0f, 1f)

    /** Нормализация пинга до роутера (60 мс -> 0.0, 2 мс -> 1.0). */
    fun pingToQuality(rttMs: Double): Float =
        (1.0 - ((rttMs - 2.0) / 58.0)).toFloat().coerceIn(0f, 1f)

    /**
     * Находит ближайшую точку замера для показа "лучшей сети" в зоне (диаграмма Вороного).
     */
    fun nearestPoint(
        x: Float,
        y: Float,
        points: List<HeatmapPoint>,
    ): HeatmapPoint? = points.minByOrNull { p -> hypot((x - p.x).toDouble(), (y - p.y).toDouble()) }

    fun presetWalls(presetId: String): List<HeatmapWall> {
        fun g(c1: Int, r1: Int, c2: Int, r2: Int) = HeatmapWall(
            x1 = c1 / 12f,
            y1 = r1 / 9f,
            x2 = c2 / 12f,
            y2 = r2 / 9f,
        )
        return when (presetId) {
            "studio" -> listOf(
                // Внешний контур 10м x 7м строго по сетке 12x9 (1 клетка = 1 м)
                g(1, 1, 11, 1),
                g(11, 1, 11, 8),
                g(11, 8, 1, 8),
                g(1, 8, 1, 1),
                // Санузел / прихожая и кухня с проёмами 1 м
                g(4, 1, 4, 4),
                g(1, 4, 3, 4),
                g(7, 1, 7, 6),
            )
            "two_room" -> listOf(
                g(1, 1, 11, 1),
                g(11, 1, 11, 8),
                g(11, 8, 1, 8),
                g(1, 8, 1, 1),
                // Две комнаты слева и справа, коридор и кухня в центре
                g(4, 1, 4, 6),
                g(8, 1, 8, 6),
                g(4, 6, 5, 6),
                g(7, 6, 8, 6),
                g(1, 6, 3, 6),
            )
            "office" -> listOf(
                g(1, 1, 11, 1),
                g(11, 1, 11, 8),
                g(11, 8, 1, 8),
                g(1, 8, 1, 1),
                // Верхние кабинеты + коридор + переговорная
                g(1, 4, 10, 4),
                g(4, 1, 4, 4),
                g(8, 1, 8, 4),
                g(6, 6, 6, 8),
            )
            else -> emptyList()
        }
    }

    /**
     * Оценка вероятного местоположения роутеров (AP Locator) по замерам в разных точках плана.
     * Использует экспоненциальный вес мощности (дБм - логарифмическая шкала): чем сильнее сигнал
     * в точке, тем ближе к ней расположен роутер. Для высокой точности желательно >= 3 точек замера.
     */
    fun estimateRouterLocations(
        plan: HeatmapFloorPlan,
        minPoints: Int = 2,
        maxRouters: Int = 8,
    ): List<EstimatedApLocation> {
        if (plan.points.size < minPoints) return emptyList()

        data class ApObs(
            val x: Float,
            val y: Float,
            val reading: HeatmapNetworkReading,
        )

        val grouped = LinkedHashMap<String, MutableList<ApObs>>()
        for (pt in plan.points) {
            if (pt.visibleNetworks.isNotEmpty()) {
                for (net in pt.visibleNetworks) {
                    val key = if (net.bssid.isNotBlank()) {
                        net.bssid.lowercase()
                    } else {
                        "${net.ssid}|${net.bandGhz}|${net.channel}"
                    }
                    grouped.getOrPut(key) { mutableListOf() }.add(ApObs(pt.x, pt.y, net))
                }
            } else if (pt.connectedRssiDbm != null) {
                val synthetic = HeatmapNetworkReading(
                    ssid = pt.connectedSsid,
                    bandGhz = pt.connectedBandGhz ?: 2.4,
                    channel = pt.connectedChannel ?: 0,
                    rssiDbm = pt.connectedRssiDbm,
                    isConnected = true,
                )
                val key = "${synthetic.ssid}|${synthetic.bandGhz}|${synthetic.channel}"
                grouped.getOrPut(key) { mutableListOf() }.add(ApObs(pt.x, pt.y, synthetic))
            }
        }

        val result = mutableListOf<EstimatedApLocation>()
        for ((key, obsList) in grouped) {
            if (obsList.size < minPoints) continue
            var sumW = 0.0
            var wx = 0.0
            var wy = 0.0
            for (obs in obsList) {
                // Логарифмический вес: каждые +10 дБм дают существенное притяжение к этой точке
                val w = 10.0.pow((obs.reading.rssiDbm + 100.0) / 14.0)
                sumW += w
                wx += obs.x * w
                wy += obs.y * w
            }
            if (sumW <= 0.0) continue
            val strongest = obsList.maxBy { it.reading.rssiDbm }.reading
            val isConn = obsList.any { it.reading.isConnected }
            result += EstimatedApLocation(
                key = key,
                ssid = strongest.ssid,
                bssid = strongest.bssid,
                bandGhz = strongest.bandGhz,
                channel = strongest.channel,
                x = (wx / sumW).toFloat().coerceIn(0.04f, 0.96f),
                y = (wy / sumW).toFloat().coerceIn(0.04f, 0.96f),
                maxRssiDbm = strongest.rssiDbm,
                seenInPoints = obsList.size,
                isConnected = isConn,
            )
        }

        return result
            .sortedWith(
                compareByDescending<EstimatedApLocation> { it.isConnected }
                    .thenByDescending { it.maxRssiDbm }
                    .thenByDescending { it.seenInPoints },
            )
            .take(maxRouters)
    }

    fun summarizePlan(plan: HeatmapFloorPlan): String {
        if (plan.points.isEmpty()) {
            return "Тапните по плану в разных комнатах, чтобы снять замеры и построить тепловую карту."
        }
        val rssiPoints = plan.points.filter { it.connectedRssiDbm != null }
        val best = rssiPoints.maxByOrNull { it.connectedRssiDbm!! }
        val worst = rssiPoints.minByOrNull { it.connectedRssiDbm!! }
        val avgRssi = rssiPoints.takeIf { it.isNotEmpty() }?.map { it.connectedRssiDbm!! }?.average()?.roundToInt()
        val pings = plan.points.mapNotNull { it.gatewayRttMs }
        val avgPing = pings.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val weakZones = rssiPoints.filter { it.connectedRssiDbm!! <= -75 }

        return buildString {
            append("Точек замера: ${plan.points.size}")
            if (avgRssi != null) append(" · средн. $avgRssi дБм")
            if (avgPing != null) append(" · пинг шлюза $avgPing мс")
            if (best != null && worst != null && best.id != worst.id) {
                append("\nЛучшая зона: ${best.label} (${best.connectedRssiDbm} дБм) · Слабая зона: ${worst.label} (${worst.connectedRssiDbm} дБм)")
            }
            if (weakZones.isNotEmpty()) {
                append("\nСлабый приём (<= -75 дБм) в точках: ${weakZones.joinToString { it.label }} — сюда стоит перенести роутер или добавить Mesh-узел.")
            }
        }
    }
}
