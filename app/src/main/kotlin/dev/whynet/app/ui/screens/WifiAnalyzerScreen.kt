package dev.whynet.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whynet.app.R
import dev.whynet.app.WifiAnalyzerUiState
import dev.whynet.app.ui.components.WifiHeatmapCard
import dev.whynet.app.ui.theme.WhyNetColors
import androidx.compose.ui.text.style.TextOverflow
import dev.whynet.core.engine.BandRecommendation
import dev.whynet.core.engine.ChannelInterferenceReport
import dev.whynet.core.engine.HeatmapLayer
import dev.whynet.core.engine.HeatmapWall
import dev.whynet.core.engine.WifiChannelAnalyzer
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.TransportKind
import dev.whynet.core.probes.LanDeviceEntry
import dev.whynet.core.probes.LiveWifiTick
import dev.whynet.core.probes.WifiChannelEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val GRAPH_PALETTE = listOf(
    Color(0xFF2563EB),
    Color(0xFF16A34A),
    Color(0xFFD97706),
    Color(0xFF9333EA),
    Color(0xFFDC2626),
    Color(0xFF0891B2),
    Color(0xFFDB2777),
    Color(0xFF4F46E5),
)

@Composable
fun WifiAnalyzerScreen(
    state: WifiAnalyzerUiState,
    liveSnapshot: NetworkSnapshot? = null,
    onScanWifi: () -> Unit,
    onToggleLiveScan: () -> Unit,
    onRequestWifiPermission: () -> Unit,
    onStartLiveSignal: () -> Unit,
    onStopLiveSignal: () -> Unit,
    onOpenDevSettings: () -> Unit,
    onSelectHeatmapLayer: (HeatmapLayer) -> Unit,
    onApplyHeatmapPreset: (String, String) -> Unit,
    onPickFloorImage: () -> Unit,
    onAddHeatmapWall: (HeatmapWall) -> Unit,
    onUndoHeatmapWall: () -> Unit,
    onTapMeasureHeatmapPoint: (Float, Float) -> Unit,
    onDeleteHeatmapPoint: (String) -> Unit,
    onClearHeatmapPoints: () -> Unit,
    onShareHeatmapPng: () -> Unit,
    onStartLanScan: () -> Unit,
    onStopLanScan: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var selectedSubTab by remember { mutableIntStateOf(0) } // 0=Каналы, 1=Карта, 2=Сигнал, 3=Сети, 4=LAN
    var selectedBand by remember { mutableDoubleStateOf(2.4) }
    var listBandFilter by remember { mutableDoubleStateOf(0.0) } // 0 = все, 2.4, 5.0, 6.0
    var listSortMode by remember { mutableIntStateOf(0) } // 0=Сигнал, 1=Канал, 2=SSID, 3=Вендор

    BackHandler(enabled = selectedSubTab != 0) {
        selectedSubTab = 0
    }

    val activeBandRec: BandRecommendation? = when (selectedBand) {
        2.4 -> state.rec24
        5.0 -> state.rec5
        else -> state.rec6
    }
    val activeInterference: ChannelInterferenceReport? = when (selectedBand) {
        2.4 -> state.interference24
        5.0 -> state.interference5
        else -> state.interference6
    }
    val allInterfererBssids = remember(state.interference24, state.interference5, state.interference6) {
        (state.interference24?.interferers.orEmpty() +
            state.interference5?.interferers.orEmpty() +
            state.interference6?.interferers.orEmpty())
            .map { it.bssid.lowercase() }
            .toSet()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "wifi_header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onBack != null) {
                    TextButton(
                        onClick = onBack,
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("← Назад", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wi-Fi Анализатор",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val stamp = state.lastScanEpochMs?.let {
                            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it))
                        }
                        Text(
                            text = if (stamp != null) {
                                "Точек: ${state.entries.size} · скан $stamp"
                            } else {
                                "Эфир, помехи, карта роутеров, сигнал и LAN"
                            },
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onToggleLiveScan,
                            colors = if (state.isLiveScanRunning) {
                                ButtonDefaults.buttonColors(containerColor = WhyNetColors.OkGreen, contentColor = Color.White)
                            } else {
                                ButtonDefaults.filledTonalButtonColors()
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(if (state.isLiveScanRunning) "● LIVE" else "LIVE", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = onScanWifi,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        item(key = "wifi_subtabs") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val tabs = listOf(
                    "Каналы",
                    "Карта (${state.heatmapPlan.points.size})",
                    "Сигнал",
                    "Сети (${state.entries.size})",
                    "Устройства",
                )
                tabs.forEachIndexed { idx, label ->
                    FilterChip(
                        selected = selectedSubTab == idx,
                        onClick = { selectedSubTab = idx },
                        label = { Text(label, maxLines = 1, fontSize = 12.sp) },
                    )
                }
            }
        }

        if (!state.errorMessage.isNullOrBlank()) {
            item(key = "wifi_perm_error") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = WhyNetColors.WarnContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = state.errorMessage,
                            color = WhyNetColors.WarnAmber,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onRequestWifiPermission) {
                            Text("Выдать разрешение на Wi-Fi")
                        }
                    }
                }
            }
        }

        if (state.throttledHint && selectedSubTab != 2) {
            item(key = "wifi_throttle_hint") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Android ограничил частый скан (4 за 2 мин). Для обновления каждую секунду выключите «Ограничение поиска Wi-Fi» в настройках разработчика.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onOpenDevSettings) {
                            Text("Настройки", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        when (selectedSubTab) {
            0 -> {
                // --- Подвкладка 1: График перекрытия каналов и рейтинг ---
                item(key = "band_selector") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val c24 = state.entries.count { abs(it.bandGhz - 2.4) < 0.25 }
                        val c5 = state.entries.count { abs(it.bandGhz - 5.0) < 0.25 }
                        val c6 = state.entries.count { abs(it.bandGhz - 6.0) < 0.25 }
                        FilterChip(
                            selected = selectedBand == 2.4,
                            onClick = { selectedBand = 2.4 },
                            label = { Text("2.4 ГГц ($c24)") },
                        )
                        FilterChip(
                            selected = selectedBand == 5.0,
                            onClick = { selectedBand = 5.0 },
                            label = { Text("5 ГГц ($c5)") },
                        )
                        FilterChip(
                            selected = selectedBand == 6.0,
                            onClick = { selectedBand = 6.0 },
                            label = { Text("6 ГГц ($c6)") },
                        )
                    }
                }

                if (selectedBand == 6.0 && !state.supports6Ghz) {
                    item(key = "no_6ghz_hw") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = WhyNetColors.WarnContainer),
                        ) {
                            Text(
                                text = "Wi-Fi модуль этого устройства сообщил, что диапазон 6 ГГц (Wi-Fi 6E/7) аппаратно не поддерживается или отключён регионом.",
                                style = MaterialTheme.typography.bodySmall,
                                color = WhyNetColors.WarnAmber,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }

                if (liveSnapshot?.transport == TransportKind.WIFI) {
                    item(key = "wifi_speed_factors") {
                        WifiSpeedFactorsCard(
                            snapshot = liveSnapshot,
                            connectedEntry = state.entries.firstOrNull { it.isConnected },
                        )
                    }
                }

                item(key = "channel_graph_card") {
                    val bandEntries = remember(state.entries, selectedBand) {
                        state.entries.filter { abs(it.bandGhz - selectedBand) < 0.25 }
                    }
                    ChannelOverlapGraphCard(
                        bandGhz = selectedBand,
                        entries = bandEntries,
                        freeChannels = activeBandRec?.bestChannels?.map { it.channel }?.toSet().orEmpty(),
                    )
                }

                if (state.entries.isNotEmpty()) {
                    activeInterference?.let { interf ->
                        item(key = "channel_interferers_card") {
                            ChannelInterferersCard(
                                report = interf,
                                onShowOnMap = { selectedSubTab = 1 },
                            )
                        }
                    }

                    activeBandRec?.let { rec ->
                        item(key = "channel_rec_card") {
                            ChannelRecommendationCard(rec = rec)
                        }
                    }
                }
            }

            1 -> {
                // --- Подвкладка 2: Карта помещения (Wi-Fi Heatmap) ---
                item(key = "wifi_heatmap_card") {
                    WifiHeatmapCard(
                        plan = state.heatmapPlan,
                        selectedLayer = state.heatmapLayer,
                        isMeasuringPoint = state.isMeasuringHeatmapPoint,
                        interfererBssids = allInterfererBssids,
                        onSelectLayer = onSelectHeatmapLayer,
                        onApplyPreset = onApplyHeatmapPreset,
                        onPickFloorImage = onPickFloorImage,
                        onAddWall = onAddHeatmapWall,
                        onUndoWall = onUndoHeatmapWall,
                        onTapMeasurePoint = onTapMeasureHeatmapPoint,
                        onDeletePoint = onDeleteHeatmapPoint,
                        onClearPoints = onClearHeatmapPoints,
                        onShareHeatmapPng = onShareHeatmapPng,
                    )
                }
            }

            2 -> {
                // --- Подвкладка 3: Живой осциллограф RSSI и роуминг ---
                item(key = "live_signal_control") {
                    LiveWifiSignalCard(
                        isRunning = state.isLiveSignalRunning,
                        latest = state.liveTicks.lastOrNull(),
                        ticks = state.liveTicks,
                        roamingEvents = state.roamingEvents,
                        onStart = onStartLiveSignal,
                        onStop = onStopLiveSignal,
                    )
                }
            }

            3 -> {
                // --- Подвкладка 4: Список всех сетей рядом ---
                item(key = "list_band_filter") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = listBandFilter == 0.0,
                            onClick = { listBandFilter = 0.0 },
                            label = { Text("Все (${state.entries.size})") },
                        )
                        FilterChip(
                            selected = listBandFilter == 2.4,
                            onClick = { listBandFilter = 2.4 },
                            label = { Text("2.4 ГГц") },
                        )
                        FilterChip(
                            selected = listBandFilter == 5.0,
                            onClick = { listBandFilter = 5.0 },
                            label = { Text("5 ГГц") },
                        )
                        FilterChip(
                            selected = listBandFilter == 6.0,
                            onClick = { listBandFilter = 6.0 },
                            label = { Text("6 ГГц") },
                        )
                    }
                }

                item(key = "list_sort_row") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Сортировка:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        listOf("По сигналу", "По каналу", "По SSID", "По вендору").forEachIndexed { idx, lbl ->
                            FilterChip(
                                selected = listSortMode == idx,
                                onClick = { listSortMode = idx },
                                label = { Text(lbl, fontSize = 11.sp, maxLines = 1) },
                            )
                        }
                    }
                }

                val filteredBase = if (listBandFilter == 0.0) {
                    state.entries
                } else {
                    state.entries.filter { abs(it.bandGhz - listBandFilter) < 0.25 }
                }
                val filtered = when (listSortMode) {
                    1 -> filteredBase.sortedWith(compareBy<WifiChannelEntry> { it.bandGhz }.thenBy { it.channel }.thenByDescending { it.rssiDbm })
                    2 -> filteredBase.sortedWith(compareBy<WifiChannelEntry> { it.ssid.lowercase() }.thenByDescending { it.rssiDbm })
                    3 -> filteredBase.sortedWith(compareBy<WifiChannelEntry> { it.vendor.lowercase() }.thenByDescending { it.rssiDbm })
                    else -> filteredBase.sortedByDescending { it.rssiDbm }
                }

                if (filtered.isEmpty()) {
                    item(key = "wifi_empty") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Text(
                                text = "Нажмите «Скан» сверху, чтобы увидеть все Wi-Fi точки рядом, их каналы, ширину полосы и производителей.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                } else {
                    items(filtered, key = { it.uniqueKey }) { entry ->
                        WifiNetworkDetailCard(entry = entry)
                    }
                }
            }

            4 -> {
                // --- Подвкладка 5: Устройства в локальной сети (LAN-сканер) ---
                item(key = "lan_scanner_control") {
                    LanScannerControlCard(
                        isScanning = state.isScanningLan,
                        progressPct = state.lanScanPercent,
                        foundCount = state.lanDevices.size,
                        errorMessage = state.lanError,
                        onStart = onStartLanScan,
                        onStop = onStopLanScan,
                    )
                }
                if (state.lanDevices.isNotEmpty()) {
                    items(state.lanDevices, key = { it.ip }) { dev ->
                        LanDeviceCard(device = dev)
                    }
                }
            }
        }
    }
}

@Composable
private fun LanScannerControlCard(
    isScanning: Boolean,
    progressPct: Int,
    foundCount: Int,
    errorMessage: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Устройства в сети Wi-Fi (LAN-сканер)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Ищет активные устройства в вашей подсети /24 (IP, имя, пинг и открытые сервисы) без root.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isScanning) {
                LinearProgressIndicator(
                    progress = { (progressPct / 100f).coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = WhyNetColors.StopButtonBg, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Остановить ($progressPct%)")
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (foundCount > 0) "Сканировать заново ($foundCount найдено)" else "Найти устройства в сети")
                }
            }
            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = WhyNetColors.FailRed,
                )
            }
        }
    }
}

@Composable
private fun LanDeviceCard(device: LanDeviceEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isGateway) {
                WhyNetColors.InfoContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = device.ip + (device.hostname?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Text(
                    text = device.deviceType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (device.openPorts.isNotEmpty()) {
                    Text(
                        text = "Порты: ${device.openPorts.joinToString(", ")}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = device.rttMs?.let { if (device.isSelf) "ВЫ" else "${"%.0f".format(it)} мс" } ?: "OK",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (device.isGateway || device.isSelf) WhyNetColors.InfoBlue else WhyNetColors.OkGreen,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun ChannelOverlapGraphCard(
    bandGhz: Double,
    entries: List<WifiChannelEntry>,
    freeChannels: Set<Int> = emptySet(),
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val hGridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val connectedColor = WhyNetColors.OkGreen
    val okZone = WhyNetColors.OkGreen.copy(alpha = 0.06f)
    val warnZone = WhyNetColors.WarnAmber.copy(alpha = 0.05f)
    val failZone = WhyNetColors.FailRed.copy(alpha = 0.04f)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Спектр каналов ($bandGhz ГГц)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${entries.size} точек",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (entries.isEmpty()) {
                Text(
                    text = "В диапазоне $bandGhz ГГц точек не найдено. Нажмите «Обновить».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                val minFreq = if (bandGhz < 3.0) 2400f else if (bandGhz < 5.9) 5150f else 5935f
                val maxFreq = if (bandGhz < 3.0) 2488f else if (bandGhz < 5.9) 5865f else 7125f
                val minDbm = -100f
                val maxDbm = -30f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(185.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Градуированная ось дБм слева (-30..-100 каждые 10 дБм)
                    Column(
                        modifier = Modifier
                            .width(30.dp)
                            .height(185.dp)
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End,
                    ) {
                        listOf("-30", "-40", "-50", "-60", "-70", "-80", "-90", "-100").forEach { lbl ->
                            Text(
                                text = lbl,
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    Canvas(
                        modifier = Modifier
                            .weight(1f)
                            .height(185.dp),
                    ) {
                        val leftPad = 4f
                        val rightPad = 4f
                        val topPad = 8f
                        val bottomPad = 8f
                        val w = (size.width - leftPad - rightPad).coerceAtLeast(1f)
                        val h = (size.height - topPad - bottomPad).coerceAtLeast(1f)

                        fun freqToX(f: Float): Float =
                            leftPad + ((f - minFreq) / (maxFreq - minFreq)).coerceIn(0f, 1f) * w

                        fun dbmToY(dbm: Float): Float =
                            topPad + (1f - ((dbm - minDbm) / (maxDbm - minDbm)).coerceIn(0f, 1f)) * h

                        // Цветовые зоны качества сигнала
                        val y55 = dbmToY(-55f)
                        val y75 = dbmToY(-75f)
                        val y100 = dbmToY(-100f)
                        drawRect(okZone, topLeft = Offset(leftPad, topPad), size = androidx.compose.ui.geometry.Size(w, y55 - topPad))
                        drawRect(warnZone, topLeft = Offset(leftPad, y55), size = androidx.compose.ui.geometry.Size(w, y75 - y55))
                        drawRect(failZone, topLeft = Offset(leftPad, y75), size = androidx.compose.ui.geometry.Size(w, y100 - y75))

                        // Тончайшие горизонтальные линии сетки каждые 10 дБм (-30..-90)
                        listOf(-30f, -40f, -50f, -60f, -70f, -80f, -90f).forEach { dbm ->
                            val y = dbmToY(dbm)
                            drawLine(
                                color = hGridColor,
                                start = Offset(leftPad, y),
                                end = Offset(leftPad + w, y),
                                strokeWidth = 1.0f,
                            )
                        }
                        // Вертикальные маркеры свободных каналов (зелёные линии)
                        freeChannels.forEach { ch ->
                            val fx = freqToX(WifiChannelAnalyzer.channelToCenterFreqMhz(ch, bandGhz).toFloat())
                            drawLine(
                                color = connectedColor.copy(alpha = 0.45f),
                                start = Offset(fx, topPad),
                                end = Offset(fx, topPad + h),
                                strokeWidth = 1.6f,
                            )
                        }
                        // Базовая ось снизу
                        drawLine(
                            color = gridColor,
                            start = Offset(leftPad, topPad + h),
                            end = Offset(leftPad + w, topPad + h),
                            strokeWidth = 2f,
                        )

                        // Параболы для каждой сети (до 18 сильнейших, подключённую поверх всех)
                        val ordered = entries.take(18).sortedBy { it.isConnected }
                        ordered.forEachIndexed { idx, ap ->
                            val center = (if (ap.centerFreqMhz > 0) ap.centerFreqMhz else ap.frequencyMhz).toFloat()
                            val halfW = (ap.channelWidthMhz.coerceAtLeast(20) / 2f)
                            val xStart = freqToX(center - halfW)
                            val xPeak = freqToX(center)
                            val xEnd = freqToX(center + halfW)
                            val yBase = topPad + h
                            val yPeak = dbmToY(ap.rssiDbm.toFloat())

                            val color = if (ap.isConnected) {
                                connectedColor
                            } else {
                                GRAPH_PALETTE[idx % GRAPH_PALETTE.size]
                            }

                            val fillPath = Path().apply {
                                moveTo(xStart, yBase)
                                quadraticTo(xStart + (xPeak - xStart) * 0.25f, yPeak, xPeak, yPeak)
                                quadraticTo(xEnd - (xEnd - xPeak) * 0.25f, yPeak, xEnd, yBase)
                                close()
                            }
                            drawPath(
                                path = fillPath,
                                color = color.copy(alpha = if (ap.isConnected) 0.28f else 0.14f),
                            )

                            val strokePath = Path().apply {
                                moveTo(xStart, yBase)
                                quadraticTo(xStart + (xPeak - xStart) * 0.25f, yPeak, xPeak, yPeak)
                                quadraticTo(xEnd - (xEnd - xPeak) * 0.25f, yPeak, xEnd, yBase)
                            }
                            drawPath(
                                path = strokePath,
                                color = color,
                                style = Stroke(width = if (ap.isConnected) 4.2f else 2.2f, cap = StrokeCap.Round),
                            )
                            drawCircle(color = color, radius = 3.2f, center = Offset(xPeak, yPeak))
                        }
                    }
                }

                // Градуированная шкала каналов снизу (зелёным подсвечены свободные!)
                val axisChannels = if (bandGhz < 3.0) {
                    (1..13).toList()
                } else if (bandGhz < 5.9) {
                    listOf(36, 40, 48, 52, 64, 100, 116, 132, 149, 161)
                } else {
                    listOf(5, 21, 37, 53, 85, 117, 149, 197, 229)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 34.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    axisChannels.forEach { ch ->
                        val isFree = ch in freeChannels
                        Text(
                            text = "$ch",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isFree) FontWeight.Bold else FontWeight.Normal,
                            color = if (isFree) WhyNetColors.OkGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = "Зелёные цифры - свободные каналы для выбора",
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 34.dp),
                )

                // Плотная легенда топ-5 сетей (без обрезки дБм!)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    entries.take(5).forEachIndexed { idx, ap ->
                        val color = if (ap.isConnected) connectedColor else GRAPH_PALETTE[idx % GRAPH_PALETTE.size]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(color),
                            )
                            Text(
                                text = "${if (ap.isConnected) "[ВЫ] " else ""}${ap.ssid}",
                                fontSize = 10.5.sp,
                                fontWeight = if (ap.isConnected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "к.${ap.channel} (${ap.channelWidthMhz}М) · ${ap.rssiDbm} дБм",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (ap.isConnected) FontWeight.Bold else FontWeight.Normal,
                                color = if (ap.isConnected) connectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelInterferersCard(
    report: ChannelInterferenceReport,
    onShowOnMap: () -> Unit,
) {
    val loadColor = when {
        report.congestionPercent >= 50 -> WhyNetColors.FailRed
        report.congestionPercent >= 25 -> WhyNetColors.WarnAmber
        else -> WhyNetColors.OkGreen
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val chTitle = report.currentChannel?.let { "к.$it" } ?: "—"
                val ssidPart = report.currentSsid?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                Text(
                    text = "Источники помех на канале ($chTitle$ssidPart)",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Загрузка ${report.congestionPercent}%",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = loadColor,
                )
            }

            if (report.interferers.isEmpty()) {
                Text(
                    text = "Существенных помех от других точек доступа на этом канале не обнаружено.",
                    fontSize = 10.5.sp,
                    color = WhyNetColors.OkGreen,
                )
            } else {
                report.interferers.take(5).forEach { entry ->
                    val barColor = when {
                        entry.impactPercent >= 30 -> WhyNetColors.FailRed
                        entry.impactPercent >= 15 -> WhyNetColors.WarnAmber
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${entry.ssid} (${entry.vendor} · к.${entry.channel}/${entry.channelWidthMhz}М · ${entry.rssiDbm}дБ)",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${entry.impactPercent}%",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = barColor,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { (entry.impactPercent / 100f).coerceIn(0.05f, 1f) },
                            color = barColor,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = report.recommendationText,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onShowOnMap,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("На карте →", fontSize = 10.5.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun WifiSpeedFactorsCard(
    snapshot: NetworkSnapshot,
    connectedEntry: WifiChannelEntry?,
) {
    val widthMhz = connectedEntry?.channelWidthMhz ?: 20
    val txMbps = snapshot.wifiTxLinkSpeedMbps
    val rxMbps = snapshot.wifiRxLinkSpeedMbps
    val rssi = snapshot.wifiRssiDbm
    val cong = snapshot.wifiChannelCongestionPct ?: 0
    val issueCount = listOf(
        widthMhz <= 20 && (snapshot.wifiBandGhz ?: 2.4) > 3.0,
        (rssi ?: -50) < -72,
        cong >= 45,
    ).count { it }
    val statusColor = when {
        issueCount == 0 -> WhyNetColors.OkGreen
        issueCount == 1 -> WhyNetColors.WarnAmber
        else -> WhyNetColors.FailRed
    }
    val statusLabel = when {
        issueCount == 0 -> "Оптимально"
        cong >= 45 -> "Перегрузка канала $cong%"
        (rssi ?: -50) < -72 -> "Слабый сигнал"
        else -> "Узкий канал 20 МГц"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Факторы скорости Wi-Fi",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = statusLabel,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val phyText = when {
                    rxMbps != null && txMbps != null -> "↓$rxMbps ↑$txMbps Мбит/с"
                    txMbps != null -> "$txMbps Мбит/с"
                    else -> "—"
                }
                Text(
                    text = "${snapshot.wifiStandard ?: "Wi-Fi"} · ${snapshot.wifiBandGhz ?: "-"} ГГц · $widthMhz МГц",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "PHY $phyText",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChannelRecommendationCard(rec: BandRecommendation) {
    val best = rec.bestChannels.firstOrNull()
    val bestColor = when {
        (best?.score ?: 0) >= 80 -> WhyNetColors.OkGreen
        (best?.score ?: 0) >= 55 -> WhyNetColors.WarnAmber
        else -> WhyNetColors.FailRed
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (best != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(bestColor.copy(alpha = 0.14f))
                            .border(3.dp, bestColor, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${best.channel}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = bestColor,
                            )
                            Text(
                                text = "CH",
                                fontSize = 8.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "ЛУЧШИЙ КАНАЛ (${rec.bandGhz} ГГц)",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Канал ${best.channel} · чистота ${best.score}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = bestColor,
                        )
                        Text(
                            text = rec.summary,
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (rec.allScores.isNotEmpty()) {
                val visibleScores = rec.allScores.take(8)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "ЗАГРУЗКА КАНАЛОВ ДИАПАЗОНА",
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    visibleScores.forEach { ch ->
                        val loadPct = (100 - ch.score).coerceIn(0, 100)
                        val barCol = when {
                            loadPct <= 20 -> WhyNetColors.OkGreen
                            loadPct <= 55 -> WhyNetColors.WarnAmber
                            else -> WhyNetColors.FailRed
                        }
                        val isCur = rec.currentChannel == ch.channel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (isCur) "${ch.channel}*" else "${ch.channel}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCur) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(30.dp),
                            )
                            LinearProgressIndicator(
                                progress = { (loadPct / 100f).coerceAtLeast(0.03f) },
                                color = barCol,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                            )
                            Text(
                                text = "$loadPct%",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = barCol,
                                modifier = Modifier.width(34.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WifiNetworkDetailCard(entry: WifiChannelEntry) {
    val rssiColor = when {
        entry.rssiDbm >= -55 -> WhyNetColors.OkGreen
        entry.rssiDbm >= -67 -> Color(0xFF15803D)
        entry.rssiDbm >= -75 -> WhyNetColors.WarnAmber
        else -> WhyNetColors.FailRed
    }
    val signalFrac = ((entry.rssiDbm + 95f) / 60f).coerceIn(0.05f, 1f)
    val fullBssid = entry.rawBssid.ifBlank { entry.bssidDisplay }.uppercase()
    val centerCh = if (entry.centerFreqMhz > 0 && entry.centerFreqMhz != entry.frequencyMhz) {
        when {
            entry.bandGhz < 3.0 -> (entry.centerFreqMhz - 2407) / 5
            entry.bandGhz < 5.9 -> (entry.centerFreqMhz - 5000) / 5
            else -> (entry.centerFreqMhz - 5950) / 5
        }
    } else null
    val chLabel = if (centerCh != null && centerCh != entry.channel) {
        "к.${entry.channel}($centerCh)"
    } else {
        "к.${entry.channel}"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isConnected) {
                WhyNetColors.OkContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = entry.ssid,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Card(
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Text(
                            text = entry.wifiStandard,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    if (entry.isConnected) {
                        Text(
                            text = "ПОДКЛ.",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = WhyNetColors.OkGreen,
                        )
                    }
                }
                Text(
                    text = "${entry.rssiDbm} дБм",
                    fontSize = 13.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = rssiColor,
                )
            }

            // Сегментная полоска уровня сигнала (5 делений)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                val activeSegs = (signalFrac * 5f).toInt().coerceIn(1, 5)
                for (i in 1..5) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (i <= activeSegs) rssiColor else MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$fullBssid · ${entry.vendor}",
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                entry.estimatedDistanceM?.let { dist ->
                    Text(
                        text = "${"%.1f".format(dist)}м",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val techLeft = buildList {
                    add("${entry.frequencyMhz} МГц")
                    add("${entry.channelWidthMhz} МГц")
                    add(entry.security)
                    if (entry.roamingFeatures.isNotBlank()) add(entry.roamingFeatures)
                    if (entry.supports11mcRtt) add("RTT")
                }.joinToString(" · ")
                Text(
                    text = techLeft,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$chLabel · ${WifiChannelAnalyzer.rssiQualityLabel(entry.rssiDbm)}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = rssiColor,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LiveWifiSignalCard(
    isRunning: Boolean,
    latest: LiveWifiTick?,
    ticks: List<LiveWifiTick>,
    roamingEvents: List<String>,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Живой уровень сигнала и роуминг",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Обновляется каждую секунду без ограничений Android. Пройдитесь по комнатам, чтобы найти слабые зоны и моменты переключения Mesh/репитера.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!isRunning) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Запустить живой замер (1 Гц)")
                }
            } else {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WhyNetColors.StopButtonBg,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Остановить замер", fontWeight = FontWeight.SemiBold)
                }
            }

            if (latest != null) {
                val rssi = latest.rssiDbm
                val rssiColor = when {
                    rssi == null -> MaterialTheme.colorScheme.onSurfaceVariant
                    rssi >= -55 -> WhyNetColors.OkGreen
                    rssi >= -67 -> Color(0xFF15803D)
                    rssi >= -75 -> WhyNetColors.WarnAmber
                    else -> WhyNetColors.FailRed
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = latest.ssid,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            val speedStr = if (latest.txLinkSpeedMbps != null && latest.rxLinkSpeedMbps != null) {
                                "Tx ${latest.txLinkSpeedMbps} / Rx ${latest.rxLinkSpeedMbps} Мбит/с"
                            } else {
                                "Link ${latest.linkSpeedMbps ?: "-"} Мбит/с"
                            }
                            Text(
                                text = "${latest.bandGhz ?: "-"}ГГц · к.${latest.channel ?: "-"} · ${latest.wifiStandard} · $speedStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val metaLine = buildList {
                                if (latest.bssidDisplay.isNotBlank() && latest.bssidDisplay != "—") add("AP: ${latest.bssidDisplay}")
                                latest.gatewayRttMs?.let { add("Шлюз: ${"%.0f".format(it)} мс") }
                                latest.estimatedDistanceM?.let { add("дист.: ${"%.1f".format(it)} м") }
                            }.joinToString(" · ")
                            if (metaLine.isNotBlank()) {
                                Text(
                                    text = metaLine,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (rssi != null) "$rssi дБм" else "—",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = rssiColor,
                            )
                            if (rssi != null) {
                                Text(
                                    text = WifiChannelAnalyzer.rssiQualityLabel(rssi),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = rssiColor,
                                )
                            }
                        }
                    }
                }
            }

            if (ticks.size >= 2) {
                val visible = ticks.takeLast(60)
                val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                val lineColor = WhyNetColors.InfoBlue
                val okGreen = WhyNetColors.OkGreen
                val warnAmber = WhyNetColors.WarnAmber
                val failRed = WhyNetColors.FailRed

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                ) {
                    val minDbm = -95f
                    val maxDbm = -30f
                    val w = size.width
                    val h = size.height

                    fun dbmToY(dbm: Float): Float =
                        (1f - ((dbm - minDbm) / (maxDbm - minDbm)).coerceIn(0f, 1f)) * h

                    // Пороговые линии качества (-55 отлично, -67 хорошо, -75 слабо)
                    listOf(-55f to okGreen, -67f to warnAmber, -75f to failRed).forEach { (lvl, col) ->
                        val y = dbmToY(lvl)
                        drawLine(
                            color = col.copy(alpha = 0.45f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                        )
                    }
                    drawLine(gridColor, Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1.5f)

                    val stepX = w / (visible.size - 1).coerceAtLeast(1)
                    val path = Path()
                    var started = false
                    visible.forEachIndexed { idx, tick ->
                        val r = tick.rssiDbm
                        if (r != null) {
                            val x = idx * stepX
                            val y = dbmToY(r.toFloat())
                            if (!started) {
                                path.moveTo(x, y)
                                started = true
                            } else {
                                path.lineTo(x, y)
                            }
                        } else {
                            started = false
                        }
                    }
                    drawPath(path, color = lineColor, style = Stroke(width = 3.5f, cap = StrokeCap.Round))

                    // Вторая кривая: пинг до шлюза (0..80 мс, оранжевая)
                    val pingPath = Path()
                    var pingStarted = false
                    visible.forEachIndexed { idx, tick ->
                        val rtt = tick.gatewayRttMs
                        if (rtt != null) {
                            val x = idx * stepX
                            val y = (1f - (rtt.toFloat() / 80f).coerceIn(0f, 1f)) * h
                            if (!pingStarted) {
                                pingPath.moveTo(x, y)
                                pingStarted = true
                            } else {
                                pingPath.lineTo(x, y)
                            }
                        } else {
                            pingStarted = false
                        }
                    }
                    if (pingStarted) {
                        drawPath(pingPath, color = warnAmber, style = Stroke(width = 2.6f, cap = StrokeCap.Round))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Синяя: RSSI (-55/-67/-75) · Оранж.: пинг шлюза (0-80 мс)", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${visible.size} с", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (roamingEvents.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "События роуминга / смены диапазона (${roamingEvents.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    roamingEvents.takeLast(6).reversed().forEach { ev ->
                        Text(
                            text = "• $ev",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
