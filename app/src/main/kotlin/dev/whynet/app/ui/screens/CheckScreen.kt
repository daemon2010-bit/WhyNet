package dev.whynet.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whynet.app.BuildConfig
import dev.whynet.app.CheckUiState
import dev.whynet.app.QuickPreset
import dev.whynet.app.ui.components.NetworkTopologyMap
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.app.ui.theme.tr
import dev.whynet.core.engine.ReportFormatter
import dev.whynet.core.model.DiagnosticSession
import dev.whynet.core.model.TargetType
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.ProbeResult
import dev.whynet.core.model.RecommendedAction
import dev.whynet.core.model.StageId
import dev.whynet.core.model.TransportKind
import dev.whynet.core.model.VerdictCategory
import dev.whynet.core.probes.TspuContourEntry
import java.util.Locale

@Composable
fun CheckScreen(
    state: CheckUiState,
    onRefreshSnapshot: () -> Unit,
    onRefreshContours: () -> Unit,
    onSelectPreset: (QuickPreset) -> Unit,
    onSiteInputChange: (String) -> Unit,
    onStartCheck: () -> Unit,
    onCancelCheck: () -> Unit,
    onSelectStageDetail: (StageId?) -> Unit,
    onActionClick: (RecommendedAction) -> Unit,
    onVerifyAgain: () -> Unit,
    onToggleRawLog: () -> Unit,
    onCopyReport: (DiagnosticSession) -> Unit,
    onShareReport: (DiagnosticSession) -> Unit,
    onOpenIssueReport: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showFullTechReport by remember { mutableStateOf(false) }

    val session = state.lastSession
    val activeProbes = if (state.isRunning) state.liveProbes else session?.probes.orEmpty()
    val effectiveSnapshot = if (state.isRunning) state.liveSnapshot else (state.liveSnapshot ?: session?.snapshot)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // 1. Компактная шапка без жирности + История / Ссылка / Настройки
        item(key = "v2_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val dotColor = when (effectiveSnapshot?.transport) {
                        TransportKind.NONE, null -> WhyNetColors.FailRed
                        else -> WhyNetColors.OkGreen
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Text(
                        text = "WhyNet",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    CompactHeaderButton(text = tr("История", "History"), onClick = onOpenHistory)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, WhyNetColors.Border),
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenSettings),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "Настройки",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // 2. Плотная карточка телеметрии (4G+ / Wi-Fi + живой спарклайн трафика)
        item(key = "v2_telemetry") {
            CompactTelemetryCard(
                snapshot = effectiveSnapshot,
                rxBytesPerSec = state.rxBytesPerSec,
                txBytesPerSec = state.txBytesPerSec,
                rxHistory = state.rxHistory,
                txHistory = state.txHistory,
                onRefresh = onRefreshSnapshot,
            )
        }

        // 3. Единый фиксированный блок выбора режима без прыжков по высоте
        item(key = "v2_mode_box") {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, WhyNetColors.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ModeTabBtn(
                            label = tr("Вся сеть", "Network"),
                            selected = state.selectedPreset == QuickPreset.NETWORK,
                            enabled = !state.isRunning,
                            onClick = { onSelectPreset(QuickPreset.NETWORK) },
                            modifier = Modifier.weight(1f),
                        )
                        ModeTabBtn(
                            label = tr("Сайт", "Website"),
                            selected = state.selectedPreset == QuickPreset.WEBSITE,
                            enabled = !state.isRunning,
                            onClick = { onSelectPreset(QuickPreset.WEBSITE) },
                            modifier = Modifier.weight(1f),
                        )
                        ModeTabBtn(
                            label = tr("Приложение", "App"),
                            selected = false,
                            enabled = !state.isRunning,
                            onClick = onOpenAppCheck,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .border(1.dp, WhyNetColors.Border, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (state.selectedPreset == QuickPreset.WEBSITE) {
                                BasicTextField(
                                    value = state.siteInput,
                                    onValueChange = onSiteInputChange,
                                    singleLine = true,
                                    enabled = !state.isRunning,
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                val quickStatusText = when (effectiveSnapshot?.transport) {
                                    TransportKind.WIFI -> buildString {
                                        append("Wi-Fi")
                                        effectiveSnapshot.wifiChannel?.let { append(" к.$it") }
                                        effectiveSnapshot.wifiRssiDbm?.let { append(" · ${it}дБ") }
                                        effectiveSnapshot.wifiTxLinkSpeedMbps?.let { append(" · ${it}Мб") }
                                    }
                                    TransportKind.CELLULAR -> buildString {
                                        append(effectiveSnapshot.cellularNetworkType ?: "4G+")
                                        effectiveSnapshot.cellularBandLabel?.let { append(" $it") }
                                        effectiveSnapshot.cellularEnbId?.let { append(" · eNB $it") }
                                    }
                                    TransportKind.ETHERNET -> "Ethernet · полная проверка"
                                    else -> "Нет подключения к сети"
                                }
                                Text(
                                    text = quickStatusText,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                if (state.isRunning) onCancelCheck() else onStartCheck()
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isRunning) WhyNetColors.StopButtonBg else MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier.height(42.dp),
                        ) {
                            Text(
                                text = if (state.isRunning) tr("Стоп", "Stop") else tr("Проверить", "Check"),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (state.isRunning) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }

        // 4. Путь пакета (7 этапов) - ВЫШЕ вердикта!
        item(key = "v2_path_graph") {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, WhyNetColors.Border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = tr("Путь пакета (7 этапов)", "Packet path (7 stages)"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        val locSuffix = when (effectiveSnapshot?.transport) {
                            TransportKind.WIFI -> effectiveSnapshot.wifiChannel?.let { " · к.$it" }.orEmpty()
                            TransportKind.CELLULAR -> effectiveSnapshot.cellularEnbId?.let { " · eNB $it" }.orEmpty()
                            else -> ""
                        }
                        val durStr = when {
                            state.isRunning -> "${"%.1f".format(Locale.US, state.elapsedMs / 1000.0)} с · ${state.currentStageLabel ?: "..."}"
                            session != null -> "${"%.1f".format(Locale.US, session.probes.sumOf { it.durationMs } / 1000.0)} с$locSuffix"
                            else -> "нажми Проверить"
                        }
                        Text(
                            text = durStr,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    NetworkTopologyMap(
                        plannedStages = state.plannedStages,
                        completedProbes = activeProbes,
                        activeStage = state.activeStage,
                        selectedStage = state.selectedStageDetail,
                        isRunning = state.isRunning,
                        transportKind = effectiveSnapshot?.transport,
                        onSelectStage = { stage -> onSelectStageDetail(stage) },
                        localIpv4 = effectiveSnapshot?.localIpv4,
                        gatewayIp = effectiveSnapshot?.gatewayIp,
                        snapshot = effectiveSnapshot,
                    )

                    // Мини-подсказка по выбранному узлу на схеме
                    val selectedProbe = state.selectedStageDetail?.let { st ->
                        activeProbes.firstOrNull { it.stage == st }
                    }
                    if (selectedProbe != null) {
                        StageQuickNote(probe = selectedProbe)
                    }
                }
            }
        }

        // 5. Вердикт + Рекомендации "Что сделать" (сразу под графикой!)
        if (session != null && !state.isRunning) {
            item(key = "v2_verdict") {
                val blockedContours = state.quickContours.filter { it.status == "FAIL" }.map { it.label }
                VerdictAndAdviceCard(
                    session = session,
                    blockedContours = blockedContours,
                    onActionClick = onActionClick,
                    onShareReport = { onShareReport(session) },
                    onOpenIssueReport = onOpenIssueReport,
                )
            }

            val vpnOrFilterActive = session.snapshot.activeExternalVpn || (effectiveSnapshot?.activeExternalVpn == true)
            val isWebsiteIssue = session.target.type == TargetType.WEBSITE &&
                session.verdict.category != VerdictCategory.HEALTHY
            val isVpnInterference = session.verdict.category == VerdictCategory.LOCAL_VPN_OR_FILTER_INTERFERENCE
            if (vpnOrFilterActive && (isWebsiteIssue || isVpnInterference)) {
                item(key = "v2_adguard_vpn_helper") {
                    val cleanDomain = session.target.hostOrUrl
                        .trim()
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .substringBefore('/')
                        .substringBefore(':')
                        .lowercase()
                    AdGuardAndVpnExclusionCard(
                        domain = cleanDomain,
                        isWebsiteCheck = session.target.type == TargetType.WEBSITE,
                        onRecheck = onStartCheck,
                    )
                }
            }
        }

        // 6. Контуры доступности на этой БС
        item(key = "v2_contours") {
            ContoursCard(
                contours = state.quickContours,
                isLoading = state.isCheckingContours,
                onRefresh = onRefreshContours,
            )
        }

        // 7. Сворачиваемая полная техническая информация о проверке
        if (session != null && !state.isRunning) {
            item(key = "v2_tech_report") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, WhyNetColors.Border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFullTechReport = !showFullTechReport },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tr("Полная техническая информация", "Full technical details"),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (showFullTechReport) tr("Свернуть ▴", "Collapse ▴") else tr("Развернуть ▾", "Expand ▾"),
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        if (showFullTechReport) {
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider(color = WhyNetColors.Border)
                            Spacer(Modifier.height(6.dp))
                            val fullText = remember(session.sessionId) {
                                ReportFormatter.toPlainTextReport(session)
                            }
                            Text(
                                text = fullText,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Отчёт автоматически сохранён в «Истории» - там можно найти и отправить нужные отчёты архивом.",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactHeaderButton(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, WhyNetColors.Border),
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ModeTabBtn(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .height(26.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun CompactTelemetryCard(
    snapshot: NetworkSnapshot?,
    rxBytesPerSec: Long,
    txBytesPerSec: Long,
    rxHistory: List<Long>,
    txHistory: List<Long>,
    onRefresh: () -> Unit,
) {
    val rxColor = WhyNetColors.OkGreen
    val txColor = MaterialTheme.colorScheme.secondary
    val transportTitle = when (snapshot?.transport) {
        TransportKind.WIFI -> buildString {
            append(snapshot.wifiStandard ?: "Wi-Fi")
            snapshot.wifiSsid?.let { append(" · $it") }
            snapshot.wifiChannel?.let { append(" (к.$it)") }
        }
        TransportKind.CELLULAR -> buildString {
            append(snapshot.cellularNetworkType ?: "4G+")
            snapshot.cellularBandLabel?.let { append(" · $it") }
        }
        TransportKind.ETHERNET -> "Ethernet"
        else -> "Нет сети"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, WhyNetColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = transportTitle,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (snapshot?.activeExternalVpn == true) {
                        SmallBadge("VPN/фильтр", WhyNetColors.WarnAmber, WhyNetColors.WarnContainer)
                    }
                    if (snapshot?.isCgnatLikely == true) {
                        SmallBadge("CGNAT", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
                    }
                    if ((snapshot?.wifiChannelCongestionPct ?: 0) >= 35) {
                        SmallBadge("Помехи ${snapshot?.wifiChannelCongestionPct}%", WhyNetColors.WarnAmber, WhyNetColors.WarnContainer)
                    }
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Обновить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(onClick = onRefresh),
                    )
                }
            }

            // 2 ровные строки с гарантированным зазором между ключом и значением
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KvInline("IPv4", snapshot?.localIpv4 ?: "—", Modifier.weight(1f))
                KvInline("Внеш.IP", maskExtIp(snapshot?.externalIp), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KvInline("DNS", snapshot?.dnsServers?.firstOrNull() ?: "—", Modifier.weight(1f))
                val rightLabel = if (snapshot?.transport == TransportKind.WIFI) "Wi-Fi" else "БС"
                val rightVal = if (snapshot?.transport == TransportKind.WIFI) {
                    val rssi = snapshot.wifiRssiDbm?.let { "${it}дБ" } ?: "—"
                    val tx = snapshot.wifiTxLinkSpeedMbps?.let { "${it}Мб" } ?: ""
                    if (tx.isNotBlank()) "$rssi · $tx" else rssi
                } else {
                    val enb = snapshot?.cellularEnbId?.let { "$it" } ?: "—"
                    val rsrp = snapshot?.cellularRsrpDbm?.let { "${it}дБ" } ?: ""
                    if (rsrp.isNotBlank()) "$enb · $rsrp" else enb
                }
                KvInline(rightLabel, rightVal, Modifier.weight(1f))
            }

            HorizontalDivider(color = WhyNetColors.Border, modifier = Modifier.padding(top = 1.dp))

            // Строка живого трафика + живой 2-кривый спарклайн-график справа!
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = "↓ ${formatTrafficSpeed(rxBytesPerSec)}",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = rxColor,
                        maxLines = 1,
                    )
                    Text(
                        text = "|",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "↑ ${formatTrafficSpeed(txBytesPerSec)}",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = txColor,
                        maxLines = 1,
                    )
                }

                Canvas(
                    modifier = Modifier
                        .width(106.dp)
                        .height(18.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    val rxList = if (rxHistory.size >= 2) rxHistory else listOf(0L, rxBytesPerSec)
                    val txList = if (txHistory.size >= 2) txHistory else listOf(0L, txBytesPerSec)
                    val peak = maxOf(
                        rxList.maxOrNull() ?: 1L,
                        txList.maxOrNull() ?: 1L,
                        1024L,
                    ).toFloat()

                    val rxPath = Path()
                    val stepR = w / (rxList.size - 1).coerceAtLeast(1)
                    rxList.forEachIndexed { idx, v ->
                        val x = idx * stepR
                        val y = h - 2f - ((v / peak).coerceIn(0f, 1f) * (h - 4f))
                        if (idx == 0) rxPath.moveTo(x, y) else rxPath.lineTo(x, y)
                    }
                    drawPath(rxPath, color = rxColor, style = Stroke(width = 2.6f, cap = StrokeCap.Round))

                    val txPath = Path()
                    val stepT = w / (txList.size - 1).coerceAtLeast(1)
                    txList.forEachIndexed { idx, v ->
                        val x = idx * stepT
                        val y = h - 1.5f - ((v / peak).coerceIn(0f, 1f) * (h - 4f))
                        if (idx == 0) txPath.moveTo(x, y) else txPath.lineTo(x, y)
                    }
                    drawPath(txPath, color = txColor.copy(alpha = 0.78f), style = Stroke(width = 2.0f, cap = StrokeCap.Round))
                }
            }
        }
    }
}

@Composable
private fun KvInline(k: String, v: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = k,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = v,
            fontSize = 10.5.sp,
            letterSpacing = (-0.2).sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SmallBadge(text: String, fg: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text = text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

@Composable
private fun VerdictAndAdviceCard(
    session: DiagnosticSession,
    blockedContours: List<String> = emptyList(),
    onActionClick: (RecommendedAction) -> Unit,
    onShareReport: () -> Unit,
    onOpenIssueReport: () -> Unit,
) {
    val v = session.verdict
    val hasContourBlock = v.category == VerdictCategory.HEALTHY && blockedContours.isNotEmpty()
    val (fg, bg, tag) = when {
        hasContourBlock -> Triple(WhyNetColors.WarnAmber, WhyNetColors.WarnContainer, "ОГРАНИЧЕНИЯ")
        v.category == VerdictCategory.HEALTHY -> Triple(WhyNetColors.OkGreen, WhyNetColors.OkContainer, "OK")
        v.category == VerdictCategory.LOCAL_LINK_DEGRADATION ||
            v.category == VerdictCategory.UDP_BLOCKED_OR_NAT_RESTRICTED ||
            v.category == VerdictCategory.UNKNOWN -> Triple(WhyNetColors.WarnAmber, WhyNetColors.WarnContainer, "ВНИМАНИЕ")
        else -> Triple(WhyNetColors.FailRed, WhyNetColors.FailContainer, "ПРОБЛЕМА")
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallBadge(tag, fg, fg.copy(alpha = 0.18f))
                Text(
                    text = if (hasContourBlock) {
                        "Базовая сеть работает, но ограничены: ${blockedContours.joinToString(", ")}"
                    } else {
                        v.headline
                    },
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (hasContourBlock) {
                    "Основной маршрут (РФ) в норме, но зарубежные контуры (${blockedContours.joinToString(", ")}) режутся фильтрацией оператора (ТСПУ) на текущей сети."
                } else {
                    v.explanation
                },
                fontSize = 11.sp,
                lineHeight = 14.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Рекомендации "Что сделать" сразу под графикой!
            if (v.recommendedActions.isNotEmpty() && v.category != VerdictCategory.HEALTHY) {
                HorizontalDivider(color = fg.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 2.dp))
                val primary = v.recommendedActions.first()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onActionClick(primary) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Что сделать: ${primary.title}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = fg,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = "›", fontSize = 14.sp, color = fg)
                }
            }
        }
    }
}

@Composable
private fun ContoursCard(
    contours: List<TspuContourEntry>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, WhyNetColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("Контуры доступности на этой БС", "Availability contours"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (isLoading) tr("проверка...", "checking...") else tr("Обновить ↻", "Refresh ↻"),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(enabled = !isLoading, onClick = onRefresh),
                )
            }

            if (contours.isEmpty()) {
                Text(
                    text = if (isLoading) "Проверяем РФ, CloudFront, Cloudflare и UDP..." else "Нажми «Обновить» для быстрой пробы контуров.",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val rows = contours.chunked(2)
                rows.forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        pair.forEach { item ->
                            val stColor = when (item.status) {
                                "OK" -> WhyNetColors.OkGreen
                                "WARN" -> WhyNetColors.WarnAmber
                                else -> WhyNetColors.FailRed
                            }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .border(1.dp, WhyNetColors.Border, RoundedCornerShape(7.dp))
                                    .padding(horizontal = 7.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.label,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = item.detail,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = stColor,
                                    maxLines = 1,
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun StageQuickNote(probe: ProbeResult) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "${probe.stage.name}: ${probe.summary} (${probe.durationMs} мс)",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun AdGuardAndVpnExclusionCard(
    domain: String,
    isWebsiteCheck: Boolean,
    onRecheck: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val adguardInstalled = remember { pm.getLaunchIntentForPackage("com.adguard.android") != null }
    val adguardVpnInstalled = remember { pm.getLaunchIntentForPackage("com.adguard.vpn") != null }

    var showAdGuardDialog by remember { mutableStateOf(false) }
    var showVpnExclusionDialog by remember { mutableStateOf(false) }
    val toastDisableVpn = tr("Отключите VPN, вернитесь в WhyNet и нажмите «Проверить»", "Disable VPN, return to WhyNet and tap Check")
    val toastAdguard = tr("Домен $domain скопирован - вставьте в Белый список / HTTPS-исключения", "Copied $domain - paste into Allowlist / HTTPS exclusions")
    val toastVpn = tr("Домен $domain скопирован - добавьте его в Исключения VPN", "Copied $domain - add it to VPN Exclusions")

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WhyNet Domain", text))
    }

    fun openPackageOrSettings(pkg: String, fallbackAction: String) {
        val launch = pm.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            runCatching { context.startActivity(launch) }
        } else {
            runCatching { context.startActivity(Intent(fallbackAction)) }
                .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = tr(
                    "Обнаружен активный VPN / фильтр (AdGuard)",
                    "Active VPN / filter detected (AdGuard)",
                ),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (isWebsiteCheck && domain.isNotBlank()) {
                    tr(
                        "Сайт $domain может блокироваться фильтрами AdGuard (HTTPS-фильтрация / антитрекинг) или маршрутом VPN. Добавьте его в исключения или временно отключите VPN для перепроверки.",
                        "$domain may be blocked by AdGuard filters (HTTPS filtering / tracking protection) or VPN routing. Add it to exclusions or disable VPN to retest.",
                    )
                } else {
                    tr(
                        "На текущей сети активен VPN или локальный фильтр. Попробуйте временно отключить его и повторить проверку.",
                        "An active VPN or local filter is running. Try disabling it temporarily and re-running the check.",
                    )
                },
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isWebsiteCheck && domain.isNotBlank() && adguardInstalled) {
                Button(
                    onClick = { showAdGuardDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = tr("Добавить $domain в исключения AdGuard", "Add $domain to AdGuard exclusions"),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isWebsiteCheck && domain.isNotBlank()) {
                    OutlinedButton(
                        onClick = { showVpnExclusionDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = tr("В фильтр VPN", "VPN exclusions"),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        Toast.makeText(context, toastDisableVpn, Toast.LENGTH_LONG).show()
                        runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = tr("Отключить VPN", "Disable VPN"),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (showAdGuardDialog) {
        AlertDialog(
            onDismissRequest = { showAdGuardDialog = false },
            title = {
                Text(
                    tr("Исключение $domain в AdGuard", "Exclude $domain in AdGuard"),
                    fontSize = 16.sp,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = tr(
                            "С вашего согласия WhyNet скопирует домен $domain в буфер обмена и откроет AdGuard. Блокировщик рекламы продолжит работать везде, кроме этого сайта:",
                            "With your consent, WhyNet will copy $domain to clipboard and open AdGuard. Ad blocking will remain active on all other sites:",
                        ),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = tr(
                            "1. Настройки (шестерёнка) - Фильтрация - Белый список сайтов - «+» - Вставить ($domain).\n2. Если сайт РФ (например rzd.ru) всё ещё не грузится: Фильтрация - Сеть - HTTPS-фильтрация - Исключения - «+» - Вставить ($domain).\n3. Вернитесь в WhyNet и нажмите «Проверить».",
                            "1. Settings - Filtering - Allowlist - '+' - Paste ($domain).\n2. If still blocked: Filtering - Network - HTTPS filtering - Exclusions - '+' - Paste ($domain).\n3. Return to WhyNet and tap Check.",
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAdGuardDialog = false
                        copyToClipboard(domain)
                        Toast.makeText(context, toastAdguard, Toast.LENGTH_LONG).show()
                        openPackageOrSettings("com.adguard.android", Settings.ACTION_VPN_SETTINGS)
                    },
                ) {
                    Text(tr("Скопировать и открыть AdGuard", "Copy & open AdGuard"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdGuardDialog = false }) {
                    Text(tr("Отмена", "Cancel"))
                }
            },
        )
    }

    if (showVpnExclusionDialog) {
        AlertDialog(
            onDismissRequest = { showVpnExclusionDialog = false },
            title = {
                Text(
                    tr("Исключение $domain в VPN", "Exclude $domain in VPN"),
                    fontSize = 16.sp,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = tr(
                            "WhyNet скопирует домен $domain в буфер обмена и откроет ${if (adguardVpnInstalled) "AdGuard VPN" else "настройки VPN"}:",
                            "WhyNet will copy $domain to clipboard and open ${if (adguardVpnInstalled) "AdGuard VPN" else "VPN settings"}:",
                        ),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = tr(
                            "1. Откройте раздел «Исключения» (Split Tunneling / Белый список).\n2. Нажмите «+» и вставьте $domain, чтобы сайт шёл напрямую без VPN.\n3. Либо временно отключите VPN и повторите проверку в WhyNet.",
                            "1. Open Exclusions (Split Tunneling).\n2. Tap '+' and paste $domain so it bypasses VPN.\n3. Or disable VPN temporarily and retest in WhyNet.",
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showVpnExclusionDialog = false
                        copyToClipboard(domain)
                        Toast.makeText(context, toastVpn, Toast.LENGTH_LONG).show()
                        if (adguardVpnInstalled) {
                            openPackageOrSettings("com.adguard.vpn", Settings.ACTION_VPN_SETTINGS)
                        } else {
                            runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        }
                    },
                ) {
                    Text(tr("Скопировать и открыть VPN", "Copy & open VPN"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showVpnExclusionDialog = false }) {
                    Text(tr("Отмена", "Cancel"))
                }
            },
        )
    }
}

private fun maskExtIp(ip: String?): String {
    if (ip.isNullOrBlank()) return "—"
    val parts = ip.split('.')
    return if (parts.size == 4) "${parts[0]}.${parts[1]}.x.x" else ip.take(12)
}

private fun formatTrafficSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_000_000L -> "${"%.1f".format(Locale.US, bytesPerSec / 1_000_000.0)} МБ/с"
    bytesPerSec >= 1_000L -> "${"%.0f".format(Locale.US, bytesPerSec / 1_000.0)} КБ/с"
    else -> "${bytesPerSec} Б/с"
}
