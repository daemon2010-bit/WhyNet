package dev.whynet.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whynet.app.report.HeatmapExporter
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.core.engine.HeatmapFloorPlan
import dev.whynet.core.engine.HeatmapLayer
import dev.whynet.core.engine.HeatmapPoint
import dev.whynet.core.engine.HeatmapWall
import dev.whynet.core.engine.WifiHeatmapEngine
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GRID_COLS = 12 // 12 клеток * 1.0 м = 12 м
private const val GRID_ROWS = 9  // 9 клеток * 1.0 м = 9 м (крупные квадратные ячейки 1м x 1м при 4:3)
private const val CELL_METERS = 1.0f

@Composable
fun WifiHeatmapCard(
    plan: HeatmapFloorPlan,
    selectedLayer: HeatmapLayer,
    isMeasuringPoint: Boolean,
    interfererBssids: Set<String> = emptySet(),
    onSelectLayer: (HeatmapLayer) -> Unit,
    onApplyPreset: (String, String) -> Unit,
    onPickFloorImage: () -> Unit,
    onAddWall: (HeatmapWall) -> Unit,
    onUndoWall: () -> Unit,
    onTapMeasurePoint: (Float, Float) -> Unit,
    onDeletePoint: (String) -> Unit,
    onClearPoints: () -> Unit,
    onShareHeatmapPng: () -> Unit,
) {
    var drawWallMode by remember { mutableStateOf(false) }
    var showRouters by remember { mutableStateOf(true) }
    var previewWall by remember { mutableStateOf<HeatmapWall?>(null) }
    var dragStartNorm by remember { mutableStateOf<Offset?>(null) }

    val estimatedRouters = remember(plan.points) {
        WifiHeatmapEngine.estimateRouterLocations(plan, minPoints = 2, maxRouters = 8)
    }

    val bgImageBitmap = remember(plan.backgroundImagePath) {
        val path = plan.backgroundImagePath
        if (!path.isNullOrBlank() && File(path).exists()) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        } else {
            null
        }
    }

    val summaryText = remember(plan.points) { WifiHeatmapEngine.summarizePlan(plan) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Панель настройки плана (фото или шаблоны + режим стена/точка)
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "План: ${plan.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    FilledTonalButton(
                        onClick = onPickFloorImage,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Фото плана", fontSize = 12.sp, maxLines = 1)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = !drawWallMode,
                        onClick = { drawWallMode = false },
                        label = { Text("Тап = замер Wi-Fi", fontSize = 12.sp) },
                    )
                    FilterChip(
                        selected = drawWallMode,
                        onClick = { drawWallMode = true },
                        label = { Text("Рисовать стены", fontSize = 12.sp) },
                    )
                    OutlinedButton(onClick = { onApplyPreset("studio", "1-комн.") }) {
                        Text("1-комн.", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { onApplyPreset("two_room", "2-комн.") }) {
                        Text("2-комн.", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { onApplyPreset("office", "Офис") }) {
                        Text("Офис", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { onApplyPreset("empty", "Свободный") }) {
                        Text("Пустой", fontSize = 11.sp)
                    }
                }

                // Переключатель слоёв тепловой карты
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeatmapLayer.entries.forEach { layer ->
                        FilterChip(
                            selected = selectedLayer == layer,
                            onClick = { onSelectLayer(layer) },
                            label = { Text(layer.title, fontSize = 12.sp) },
                        )
                    }
                    FilterChip(
                        selected = showRouters,
                        onClick = { showRouters = !showRouters },
                        label = { Text("Роутеры (${estimatedRouters.size})", fontSize = 12.sp) },
                    )
                }
            }
        }

        // Интерактивный холст карты помещения
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val previewLenText = previewWall?.let { pw ->
                    val dxCells = abs(pw.x2 - pw.x1) * GRID_COLS
                    val dyCells = abs(pw.y2 - pw.y1) * GRID_ROWS
                    val meters = maxOf(dxCells, dyCells) * CELL_METERS
                    val orient = if (dxCells >= dyCells) "горизонталь" else "вертикаль"
                    "Стена: ${"%.0f".format(meters)} м ($orient) - отпустите, чтобы закрепить"
                }
                Text(
                    text = when {
                        isMeasuringPoint -> "Снимаем замер Wi-Fi и пинг до шлюза..."
                        previewLenText != null -> previewLenText
                        drawWallMode -> "Проведите по сетке (1 клетка = 1 м) - стены строго по горизонтали и вертикали"
                        else -> "Встаньте в нужной комнате и нажмите на это место на плане (1 клетка = 1 м)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    lineHeight = 14.sp,
                    minLines = 2,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )

                val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                val gridBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                val wallColor = MaterialTheme.colorScheme.onSurface
                val canvasBg = MaterialTheme.colorScheme.surfaceContainerLowest

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.2.dp, gridBorderColor, RoundedCornerShape(10.dp)),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(drawWallMode, isMeasuringPoint) {
                                if (!drawWallMode) {
                                    detectTapGestures { offset ->
                                        if (!isMeasuringPoint && size.width > 0 && size.height > 0) {
                                            val nx = (offset.x / size.width).coerceIn(0.03f, 0.97f)
                                            val ny = (offset.y / size.height).coerceIn(0.03f, 0.97f)
                                            onTapMeasurePoint(nx, ny)
                                        }
                                    }
                                }
                            }
                            .pointerInput(drawWallMode) {
                                if (drawWallMode) {
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            if (size.width > 0 && size.height > 0) {
                                                val sNorm = Offset(
                                                    (start.x / size.width).coerceIn(0f, 1f),
                                                    (start.y / size.height).coerceIn(0f, 1f),
                                                )
                                                dragStartNorm = sNorm
                                                previewWall = null
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val sNorm = dragStartNorm
                                            if (sNorm != null && size.width > 0 && size.height > 0) {
                                                val eNorm = Offset(
                                                    (change.position.x / size.width).coerceIn(0f, 1f),
                                                    (change.position.y / size.height).coerceIn(0f, 1f),
                                                )
                                                previewWall = snapOrthogonalWall(sNorm, eNorm)
                                            }
                                        },
                                        onDragEnd = {
                                            previewWall?.let { onAddWall(it) }
                                            dragStartNorm = null
                                            previewWall = null
                                        },
                                        onDragCancel = {
                                            dragStartNorm = null
                                            previewWall = null
                                        },
                                    )
                                }
                            },
                    ) {
                        val w = size.width
                        val h = size.height

                        // 1. Фон: фото планировки или крупная квадратная сетка 1 м (12x9) с видимыми границами
                        if (bgImageBitmap != null) {
                            drawImage(
                                image = bgImageBitmap,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                            )
                        } else {
                            drawRect(color = canvasBg)
                            for (i in 1 until GRID_COLS) {
                                val x = w * (i / GRID_COLS.toFloat())
                                drawLine(
                                    color = gridColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, h),
                                    strokeWidth = 1.2f,
                                )
                            }
                            for (j in 1 until GRID_ROWS) {
                                val y = h * (j / GRID_ROWS.toFloat())
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, y),
                                    end = Offset(w, y),
                                    strokeWidth = 1.2f,
                                )
                            }
                            drawRect(
                                color = gridBorderColor,
                                topLeft = Offset(1f, 1f),
                                size = Size(w - 2f, h - 2f),
                                style = Stroke(width = 2.4f),
                            )
                        }

                        // 2. Тепловая карта (IDW 32x24)
                        if (plan.points.isNotEmpty()) {
                            val samples = when (selectedLayer) {
                                HeatmapLayer.SIGNAL_RSSI -> plan.points.mapNotNull { p ->
                                    p.connectedRssiDbm?.let { Triple(p.x, p.y, it.toDouble()) }
                                }
                                HeatmapLayer.BEST_NETWORK -> plan.points.mapNotNull { p ->
                                    (p.bestOverallRssiDbm ?: p.connectedRssiDbm)?.let { Triple(p.x, p.y, it.toDouble()) }
                                }
                                HeatmapLayer.GATEWAY_PING -> plan.points.mapNotNull { p ->
                                    p.gatewayRttMs?.let { Triple(p.x, p.y, it) }
                                }
                            }
                            val cols = 32
                            val rows = 24
                            val cw = w / cols
                            val ch = h / rows
                            for (r in 0 until rows) {
                                val ny = (r + 0.5f) / rows
                                for (c in 0 until cols) {
                                    val nx = (c + 0.5f) / cols
                                    val interp = WifiHeatmapEngine.interpolateIdw(nx, ny, samples) ?: continue
                                    val q = when (selectedLayer) {
                                        HeatmapLayer.GATEWAY_PING -> WifiHeatmapEngine.pingToQuality(interp)
                                        else -> WifiHeatmapEngine.rssiToQuality(interp)
                                    }
                                    drawRect(
                                        color = Color(HeatmapExporter.qualityToHeatColorArgb(q, alpha = 138)),
                                        topLeft = Offset(c * cw, r * ch),
                                        size = Size(cw + 1f, ch + 1f),
                                    )
                                }
                            }
                        }

                        // 3. Стены
                        plan.walls.forEach { wall ->
                            drawLine(
                                color = wallColor.copy(alpha = 0.85f),
                                start = Offset(wall.x1 * w, wall.y1 * h),
                                end = Offset(wall.x2 * w, wall.y2 * h),
                                strokeWidth = 5f,
                                cap = StrokeCap.Round,
                            )
                        }
                        previewWall?.let { pw ->
                            val sOff = Offset(pw.x1 * w, pw.y1 * h)
                            val eOff = Offset(pw.x2 * w, pw.y2 * h)
                            drawLine(
                                color = Color(0xFF2563EB),
                                start = sOff,
                                end = eOff,
                                strokeWidth = 6f,
                                cap = StrokeCap.Round,
                            )
                            drawCircle(color = Color(0xFF2563EB), radius = 6f, center = sOff)
                            drawCircle(color = Color(0xFF2563EB), radius = 6f, center = eOff)
                        }

                        // 4. Маркеры точек замеров
                        plan.points.forEach { pt ->
                            val center = Offset(pt.x * w, pt.y * h)
                            drawCircle(color = Color.White, radius = 9f, center = center)
                            drawCircle(color = Color(0xFF0F172A), radius = 6f, center = center)
                        }

                        // 5. Предполагаемое положение роутеров (зелёный = ваш, красный с ореолом = мешающий сосед, синий = прочий)
                        if (showRouters) {
                            estimatedRouters.forEach { ap ->
                                val cx = ap.x * w
                                val cy = ap.y * h
                                val isInterferer = !ap.isConnected && ap.bssid.lowercase() in interfererBssids
                                if (isInterferer) {
                                    drawCircle(
                                        color = Color(0x44EF4444),
                                        radius = 30f,
                                        center = Offset(cx, cy),
                                    )
                                }
                                val r = if (ap.isConnected) 14f else if (isInterferer) 13f else 10f
                                val diamond = Path().apply {
                                    moveTo(cx, cy - r)
                                    lineTo(cx + r, cy)
                                    lineTo(cx, cy + r)
                                    lineTo(cx - r, cy)
                                    close()
                                }
                                drawPath(
                                    path = diamond,
                                    color = when {
                                        ap.isConnected -> Color(0xFF22C55E)
                                        isInterferer -> Color(0xFFEF4444)
                                        else -> Color(0xFF4F8CFF)
                                    },
                                )
                                drawPath(
                                    path = diamond,
                                    color = Color.White,
                                    style = Stroke(width = 3f),
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Зелёный: отлично · Жёлтый: норма · Красный: слабо",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (drawWallMode && plan.walls.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onUndoWall,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("Отменить", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // Сводка и экспорт
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onShareHeatmapPng,
                        enabled = plan.points.isNotEmpty(),
                        modifier = Modifier.weight(1.4f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Поделиться картой", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onClearPoints,
                        enabled = plan.points.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Сбросить точки", maxLines = 1)
                    }
                }
            }
        }

        // Предполагаемое расположение роутеров (AP Locator)
        if (estimatedRouters.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Где стоят роутеры (оценка по ${plan.points.size} точкам)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Зелёный ромб - ваша точка, синий - соседские/Mesh. Чем больше точек замера по углам (от 3-5), тем точнее положение (соседние роутеры прижмутся к своей стене).",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    estimatedRouters.forEach { ap ->
                        val tag = if (ap.isConnected) "[ВАШ]" else "[AP]"
                        Text(
                            text = "$tag ${ap.ssid} · ${ap.bandGhz}ГГц к.${ap.channel} · макс. ${ap.maxRssiDbm} дБм (точек: ${ap.seenInPoints})",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (ap.isConnected) FontWeight.Bold else FontWeight.Normal,
                            color = if (ap.isConnected) WhyNetColors.OkGreen else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // Список снятых точек с подробностями (где какая сеть лучше ловит!)
        if (plan.points.isNotEmpty()) {
            plan.points.reversed().forEach { pt ->
                HeatmapPointRow(
                    point = pt,
                    onDelete = { onDeletePoint(pt.id) },
                )
            }
        }
    }
}

@Composable
private fun HeatmapPointRow(
    point: HeatmapPoint,
    onDelete: () -> Unit,
) {
    val rssi = point.connectedRssiDbm
    val color = when {
        rssi == null -> MaterialTheme.colorScheme.onSurfaceVariant
        rssi >= -55 -> WhyNetColors.OkGreen
        rssi >= -67 -> Color(0xFF15803D)
        rssi >= -75 -> WhyNetColors.WarnAmber
        else -> WhyNetColors.FailRed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${point.label} · ${point.connectedSsid} (${point.connectedBandGhz ?: "-"} ГГц, к.${point.connectedChannel ?: "-"})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                val bestPart = if (!point.bestOverallSsid.isNullOrBlank()) {
                    "Лучшая здесь: ${point.bestOverallSsid} (${point.bestOverallRssiDbm} дБм, к.${point.bestOverallChannel ?: "-"})"
                } else {
                    "Видимых сетей: ${point.visibleNetworks.size}"
                }
                val pingPart = point.gatewayRttMs?.let { " · шлюз ${"%.0f".format(it)} мс" } ?: ""
                Text(
                    text = "$bestPart$pingPart",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (rssi != null) "$rssi дБм" else "—",
                    fontWeight = FontWeight.Bold,
                    color = color,
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Удалить точку", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun snapOrthogonalWall(startNorm: Offset, endNorm: Offset): HeatmapWall? {
    val gx1 = (startNorm.x.coerceIn(0f, 1f) * GRID_COLS).roundToInt().coerceIn(0, GRID_COLS)
    val gy1 = (startNorm.y.coerceIn(0f, 1f) * GRID_ROWS).roundToInt().coerceIn(0, GRID_ROWS)
    val dxCells = abs(endNorm.x - startNorm.x) * GRID_COLS
    val dyCells = abs(endNorm.y - startNorm.y) * GRID_ROWS
    return if (dxCells >= dyCells) {
        val gx2 = (endNorm.x.coerceIn(0f, 1f) * GRID_COLS).roundToInt().coerceIn(0, GRID_COLS)
        if (gx1 == gx2) null else HeatmapWall(
            x1 = gx1 / GRID_COLS.toFloat(),
            y1 = gy1 / GRID_ROWS.toFloat(),
            x2 = gx2 / GRID_COLS.toFloat(),
            y2 = gy1 / GRID_ROWS.toFloat(),
        )
    } else {
        val gy2 = (endNorm.y.coerceIn(0f, 1f) * GRID_ROWS).roundToInt().coerceIn(0, GRID_ROWS)
        if (gy1 == gy2) null else HeatmapWall(
            x1 = gx1 / GRID_COLS.toFloat(),
            y1 = gy1 / GRID_ROWS.toFloat(),
            x2 = gx1 / GRID_COLS.toFloat(),
            y2 = gy2 / GRID_ROWS.toFloat(),
        )
    }
}
