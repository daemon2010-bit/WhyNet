package dev.whynet.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whynet.app.R
import dev.whynet.app.AppAutoCheckState
import dev.whynet.app.SuiteUiState
import dev.whynet.app.ToolKind
import dev.whynet.app.ToolsUiState
import dev.whynet.app.report.ShareTarget
import dev.whynet.app.report.SuiteRunRecord
import dev.whynet.app.service.AppObserverState
import dev.whynet.app.service.DropoutWatcherState
import dev.whynet.app.service.LaunchableAppInfo
import dev.whynet.app.service.SavedAppProfile
import dev.whynet.app.ui.components.BufferbloatToolView
import dev.whynet.app.ui.components.CidrCalcToolView
import dev.whynet.app.ui.components.DnsProToolView
import dev.whynet.app.ui.components.MassDnsBenchToolView
import dev.whynet.app.ui.components.PortScannerToolView
import dev.whynet.app.ui.components.ServiceLatencyToolView
import dev.whynet.app.ui.components.TestSuiteControlCard
import dev.whynet.app.ui.components.TestSuiteExportCard
import dev.whynet.app.ui.components.TestSuiteResultRow
import dev.whynet.app.ui.components.TspuTestToolView
import dev.whynet.app.ui.components.WakeOnLanToolView
import dev.whynet.app.ui.components.WhoisIpToolView
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.app.ui.theme.tr
import dev.whynet.core.probes.PingTick
import kotlin.math.abs
import kotlin.math.ceil

@Composable
fun ToolsScreen(
    state: ToolsUiState,
    watcherState: DropoutWatcherState,
    appObserverState: AppObserverState,
    appAutoCheckState: AppAutoCheckState,
    suiteState: SuiteUiState,
    hasActiveExternalVpn: Boolean,
    onSelectTool: (ToolKind) -> Unit,
    onShowToolsMenu: () -> Unit = {},
    onHostChange: (String) -> Unit,
    onRunTool: () -> Unit,
    onStopTool: () -> Unit,
    onRequestWifiPermission: () -> Unit,
    onAppSearchChange: (String) -> Unit,
    onSelectApp: (LaunchableAppInfo) -> Unit,
    onStartAutoAppCheck: () -> Unit,
    onFinishAppCaptureNow: () -> Unit,
    onCancelAppAutoCheck: () -> Unit,
    onRunSavedAppProfile: (SavedAppProfile) -> Unit,
    onSaveCurrentAppProfile: () -> Unit,
    onDeleteSavedAppProfile: (String) -> Unit,
    onStartAppObserver: () -> Unit,
    onStopAppObserver: () -> Unit,
    onLaunchSelectedApp: (String) -> Unit,
    onMarkAppFreezeHere: () -> Unit,
    onDiagnoseCapturedDomain: (String) -> Unit,
    onUpdateSuiteEnvTag: (String) -> Unit,
    onToggleSuiteEnvChip: (String) -> Unit,
    onPickSuiteFile: () -> Unit,
    onLoadDefaultSuite: () -> Unit,
    onExportSuiteTemplate: () -> Unit,
    onStartSuite: () -> Unit,
    onStopSuite: () -> Unit,
    onExportSuiteReport: (SuiteRunRecord, ShareTarget) -> Unit,
    onOpenWifiAnalyzer: () -> Unit = {},
    onPortSpecChange: (String) -> Unit = {},
    onWolMacChange: (String) -> Unit = {},
    onWolBroadcastChange: (String) -> Unit = {},
    onCidrChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isCurrentRunning = when (state.selectedTool) {
        ToolKind.DROPOUT_WATCHER -> watcherState.isRunning
        ToolKind.APP_OBSERVER -> appObserverState.isRunning
        ToolKind.TEST_SUITE -> suiteState.isRunning
        else -> state.isRunning
    }
    val showGridMenu = state.showToolsMenu
    var showMoreTools by remember { mutableStateOf(false) }

    BackHandler(enabled = !showGridMenu) {
        onShowToolsMenu()
    }

    val primaryTools = remember {
        listOf(
            ToolKind.APP_OBSERVER,
            ToolKind.TSPU_TEST,
            ToolKind.SERVICE_LATENCY,
            ToolKind.MASS_DNS_BENCH,
            ToolKind.DROPOUT_WATCHER,
            ToolKind.TRACEROUTE,
            ToolKind.DNS_PRO,
            ToolKind.PORT_SCANNER,
            ToolKind.BUFFERBLOAT,
        )
    }
    val extraTools = remember {
        listOf(
            ToolKind.TEST_SUITE,
            ToolKind.WAKE_ON_LAN,
            ToolKind.CIDR_CALC,
            ToolKind.MTU_SEARCH,
            ToolKind.UDP_STUN,
            ToolKind.DUAL_SNI,
            ToolKind.DNS_COMPARE,
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "tools_title") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!showGridMenu) {
                    Text(
                        text = tr("← Утилиты", "← Tools"),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onShowToolsMenu() }
                            .padding(vertical = 3.dp),
                    )
                }
                Text(
                    text = if (showGridMenu) tr("Утилиты", "Tools") else state.selectedTool.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (showGridMenu) {
                        tr("Базовые сетевые проверки и тесты ТСПУ / DNS", "Core network tools, DPI & DNS checks")
                    } else {
                        state.selectedTool.subtitle
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showGridMenu) {
            items(primaryTools, key = { "tool_primary_${it.name}" }) { tool ->
                val active = when (tool) {
                    ToolKind.DROPOUT_WATCHER -> watcherState.isRunning
                    else -> state.isRunning && state.selectedTool == tool
                }
                OutlinedCard(
                    onClick = { onSelectTool(tool) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, WhyNetColors.Border),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Card(
                            shape = RoundedCornerShape(7.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.width(46.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = toolShortBadge(tool),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = when (tool) {
                                        ToolKind.TSPU_TEST -> WhyNetColors.WarnAmber
                                        ToolKind.MASS_DNS_BENCH -> WhyNetColors.OkGreen
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                    maxLines = 1,
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = tool.title,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                            Text(
                                text = tool.subtitle,
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = if (active) "● LIVE" else "›",
                            fontSize = if (active) 10.sp else 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (active) WhyNetColors.OkGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "tools_more_toggle") {
                TextButton(
                    onClick = { showMoreTools = !showMoreTools },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (showMoreTools) "▴ Скрыть дополнительные утилиты" else "▾ Дополнительно (Сценарий, WoL, CIDR, MTU, STUN)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (showMoreTools) {
                items(extraTools, key = { "tool_extra_${it.name}" }) { tool ->
                    OutlinedCard(
                        onClick = { onSelectTool(tool) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, WhyNetColors.Border.copy(alpha = 0.7f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = toolShortBadge(tool),
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = tool.title, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                Text(text = tool.subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Text("›", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            return@LazyColumn
        }

        // --- Пакетный прогон сценария из .txt-файла ---
        if (state.selectedTool == ToolKind.TEST_SUITE) {
            item(key = "suite_control_card") {
                TestSuiteControlCard(
                    state = suiteState,
                    onUpdateEnvTag = onUpdateSuiteEnvTag,
                    onToggleEnvChip = onToggleSuiteEnvChip,
                    onPickSuiteFile = onPickSuiteFile,
                    onLoadDefaultSuite = onLoadDefaultSuite,
                    onExportTemplate = onExportSuiteTemplate,
                    onStartSuite = onStartSuite,
                    onStopSuite = onStopSuite,
                )
            }

            suiteState.lastRunRecord?.let { record ->
                if (!suiteState.isRunning) {
                    item(key = "suite_export_card") {
                        TestSuiteExportCard(
                            record = record,
                            onExportReport = onExportSuiteReport,
                        )
                    }
                }
            }

            if (suiteState.results.isNotEmpty()) {
                item(key = "suite_results_header") {
                    Text(
                        text = "Результаты прогона (${suiteState.results.size}/${suiteState.suite.steps.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(suiteState.results, key = { "suite_${it.step.index}_${it.step.kind}" }) { res ->
                    TestSuiteResultRow(result = res)
                }
            }
        } else if (state.selectedTool == ToolKind.APP_OBSERVER) {
            item(key = "app_observer_card") {
                AppObserverControlCard(
                    state = state,
                    observerState = appObserverState,
                    autoCheckState = appAutoCheckState,
                    hasActiveExternalVpn = hasActiveExternalVpn,
                    onSearchChange = onAppSearchChange,
                    onSelectApp = onSelectApp,
                    onStartAutoCheck = onStartAutoAppCheck,
                    onFinishCaptureNow = onFinishAppCaptureNow,
                    onCancelAutoCheck = onCancelAppAutoCheck,
                    onRunSavedProfile = onRunSavedAppProfile,
                    onDeleteSavedProfile = onDeleteSavedAppProfile,
                    onStartManual = onStartAppObserver,
                    onStop = onStopAppObserver,
                    onLaunchApp = onLaunchSelectedApp,
                    onMarkFreeze = onMarkAppFreezeHere,
                )
            }

            if (appAutoCheckState.isRunning || appAutoCheckState.results.isNotEmpty() || appAutoCheckState.summaryText != null) {
                item(key = "app_auto_check_summary") {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Автопроверка: ${appAutoCheckState.appLabel ?: "приложение"}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            appAutoCheckState.currentStepText?.let { stepTxt ->
                                Text(
                                    text = stepTxt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (appAutoCheckState.selectedDomains.isNotEmpty()) {
                                Text(
                                    text = "Ключевые серверы: ${appAutoCheckState.selectedDomains.joinToString(", ")}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            appAutoCheckState.summaryText?.let { sum ->
                                Text(
                                    text = sum,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            appAutoCheckState.errorMessage?.let { err ->
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WhyNetColors.FailRed,
                                )
                            }
                            if (!appAutoCheckState.isRunning) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    if (appAutoCheckState.selectedDomains.isNotEmpty()) {
                                        FilledTonalButton(
                                            onClick = onSaveCurrentAppProfile,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Text("Сохранить (1 тап)", maxLines = 1)
                                        }
                                    }
                                    appAutoCheckState.lastRecord?.let { rec ->
                                        Button(
                                            onClick = { onExportSuiteReport(rec, ShareTarget.SYSTEM_CHOOSER) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                        ) {
                                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Поделиться", maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                items(appAutoCheckState.results, key = { "appauto_${it.step.index}_${it.step.kind}" }) { res ->
                    TestSuiteResultRow(result = res)
                }
            }

            if (appObserverState.dnsQueries.isNotEmpty()) {
                item(key = "observer_dns_card") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Пойманные домены (${appObserverState.dnsQueries.size}) — нажмите для 8-слойной проверки:",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                appObserverState.dnsQueries.take(15).forEach { domain ->
                                    AssistChip(
                                        onClick = { onDiagnoseCapturedDomain(domain) },
                                        label = { Text(domain, style = MaterialTheme.typography.labelSmall) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (appObserverState.endpoints.isNotEmpty()) {
                item(key = "observer_ep_header") {
                    Text(
                        text = "Попытки соединений (${appObserverState.endpoints.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(appObserverState.endpoints, key = { it.key }) { ep ->
                    Card(
                        onClick = {
                            val target = ep.domainOrSni ?: ep.dstIp
                            onDiagnoseCapturedDomain(target)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = ep.domainOrSni ?: ep.dstIp,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${ep.protocol} · ${ep.dstIp}:${ep.dstPort} · пак.: ${ep.packetCount}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (ep.seenAfterFreezeMark) {
                                Text(
                                    text = "ПОСЛЕ ЗАВИСАНИЯ",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = WhyNetColors.FailRed,
                                )
                            }
                        }
                    }
                }
            }
        } else if (state.selectedTool == ToolKind.TSPU_TEST) {
            item(key = "tool_tspu_test") {
                TspuTestToolView(
                    state = state,
                    onRun = onRunTool,
                    onStop = onStopTool,
                )
            }
        } else if (state.selectedTool == ToolKind.SERVICE_LATENCY) {
            item(key = "tool_service_latency") {
                ServiceLatencyToolView(
                    state = state,
                    onHostChange = onHostChange,
                    onRun = onRunTool,
                    onStop = onStopTool,
                )
            }
        } else if (state.selectedTool == ToolKind.MASS_DNS_BENCH) {
            item(key = "tool_mass_dns") {
                MassDnsBenchToolView(
                    state = state,
                    onHostChange = onHostChange,
                    onRun = onRunTool,
                    onStop = onStopTool,
                )
            }
        } else if (state.selectedTool == ToolKind.PORT_SCANNER) {
            item(key = "tool_port_scanner") {
                PortScannerToolView(
                    state = state,
                    onHostChange = onHostChange,
                    onPortSpecChange = onPortSpecChange,
                    onRun = onRunTool,
                    onStop = onStopTool,
                )
            }
        } else if (state.selectedTool == ToolKind.DNS_PRO) {
            item(key = "tool_dns_pro") {
                DnsProToolView(
                    state = state,
                    onHostChange = onHostChange,
                    onRun = onRunTool,
                    onStop = onStopTool,
                )
            }
        } else if (state.selectedTool == ToolKind.WHOIS_IP) {
            item(key = "tool_whois_ip") {
                WhoisIpToolView(
                    state = state,
                    onHostChange = onHostChange,
                    onRun = onRunTool,
                    onStop = onStopTool,
                )
            }
        } else if (state.selectedTool == ToolKind.BUFFERBLOAT) {
            item(key = "tool_bufferbloat") {
                BufferbloatToolView(
                    state = state,
                    onRun = onRunTool,
                    onStop = onStopTool,
                )
            }
        } else if (state.selectedTool == ToolKind.WAKE_ON_LAN) {
            item(key = "tool_wol") {
                WakeOnLanToolView(
                    state = state,
                    onMacChange = onWolMacChange,
                    onBroadcastChange = onWolBroadcastChange,
                    onRun = onRunTool,
                )
            }
        } else if (state.selectedTool == ToolKind.CIDR_CALC) {
            item(key = "tool_cidr_calc") {
                CidrCalcToolView(
                    cidrInput = state.cidrInput,
                    onCidrChange = onCidrChange,
                )
            }
        } else {
            // --- Остальные инструменты ---
            item(key = "tool_runner_card") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.selectedTool != ToolKind.UDP_STUN && state.selectedTool != ToolKind.WIFI_CHANNELS) {
                            OutlinedTextField(
                                value = state.hostInput,
                                onValueChange = onHostChange,
                                label = { Text("Хост или домен") },
                                singleLine = true,
                                enabled = !isCurrentRunning,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (!isCurrentRunning) {
                            Button(
                                onClick = onRunTool,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (state.selectedTool == ToolKind.DROPOUT_WATCHER) "Начать (в фоне)" else "Запустить",
                                )
                            }
                        } else {
                            Button(
                                onClick = onStopTool,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WhyNetColors.StopButtonBg,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Остановить", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        val note = if (state.selectedTool == ToolKind.DROPOUT_WATCHER) watcherState.lastLine else state.liveNote
                        if (!note.isNullOrBlank()) {
                            Text(
                                text = note,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (state.selectedTool == ToolKind.DROPOUT_WATCHER && watcherState.ticks.isNotEmpty()) {
                item(key = "ping_chart_card") {
                    LivePingChartCard(
                        ticks = watcherState.ticks,
                        host = watcherState.host,
                        totalSent = watcherState.totalSent,
                        totalLost = watcherState.totalLost,
                    )
                }
            }

            if (state.selectedTool == ToolKind.TRACEROUTE && state.tracerouteHops.isNotEmpty()) {
                val maxHopMs = (state.tracerouteHops.mapNotNull { it.rttMs }.maxOrNull() ?: 60.0).coerceAtLeast(30.0)
                item(key = "traceroute_header") {
                    Text(
                        text = "Хопы маршрута (${state.tracerouteHops.size}) · шкала 0..${"%.0f".format(maxHopMs)} мс",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(state.tracerouteHops, key = { "hop_${it.ttl}" }) { hop ->
                    val frac = hop.rttMs?.let { (it / maxHopMs).toFloat().coerceIn(0.06f, 1f) } ?: 0f
                    val barCol = when {
                        hop.ip == null -> WhyNetColors.WarnAmber
                        hop.reachedTarget -> WhyNetColors.OkGreen
                        (hop.rttMs ?: 0.0) > maxHopMs * 0.75 -> WhyNetColors.WarnAmber
                        else -> MaterialTheme.colorScheme.primary
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = "#${hop.ttl}",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Column {
                                        Text(
                                            text = hop.ip ?: "* * * (узел не отвечает на ICMP)",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (hop.reachedTarget) FontWeight.Bold else FontWeight.Normal,
                                        )
                                        if (!hop.note.isNullOrBlank()) {
                                            Text(
                                                text = hop.note!!,
                                                fontSize = 10.sp,
                                                color = if (hop.reachedTarget) WhyNetColors.OkGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = hop.rttMs?.let { "${"%.1f".format(it)} мс" } ?: "—",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = barCol,
                                )
                            }
                            if (frac > 0f) {
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { frac },
                                    color = barCol,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp),
                                )
                            }
                        }
                    }
                }

                if (!state.isRunning) {
                    item(key = "traceroute_conclusion") {
                        val hops = state.tracerouteHops
                        val reached = hops.any { it.reachedTarget }
                        val silentCount = hops.count { it.ip == null }
                        val firstHop = hops.firstOrNull { it.rttMs != null }
                        val lastResp = hops.lastOrNull { it.rttMs != null }
                        val maxJump = hops.zipWithNext().mapNotNull { (a, b) ->
                            val r1 = a.rttMs
                            val r2 = b.rttMs
                            if (r1 != null && r2 != null) Triple(b.ttl, b.ip ?: "хоп #${b.ttl}", r2 - r1) else null
                        }.maxByOrNull { it.third }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (reached) WhyNetColors.OkContainer else WhyNetColors.WarnContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Понятный вывод по маршруту",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (reached) WhyNetColors.OkGreen else WhyNetColors.WarnAmber,
                                )
                                val summary = buildString {
                                    if (reached && lastResp != null) {
                                        appendLine("• Пакет дошёл до цели за ${hops.size} хопов (${"%.0f".format(lastResp.rttMs)} мс).")
                                    } else {
                                        appendLine("• Цель не ответила на ICMP (последний ответивший узел: ${lastResp?.ip ?: "нет"}). Возможна фильтрация или закрытый ICMP на сервере.")
                                    }
                                    if (firstHop?.rttMs != null) {
                                        val q = if (firstHop.rttMs!! <= 35.0) "в норме" else "повышен"
                                        appendLine("• Первый узел (роутер/БС): ${"%.0f".format(firstHop.rttMs)} мс ($q).")
                                    }
                                    if (maxJump != null && maxJump.third >= 15.0) {
                                        appendLine("• Основной скачок задержки (+${"%.0f".format(maxJump.third)} мс) на хопе #${maxJump.first} (${maxJump.second}).")
                                    }
                                    if (silentCount == hops.size && hops.isNotEmpty()) {
                                        append("• Все узлы скрыты (* * *): если активен VPN или локальный фильтр (AdGuard), он перехватывает ICMP. Отключи фильтр на минуту для полной трассировки.")
                                    } else if (silentCount > 0) {
                                        append("• Узлов без ответа (* * *): $silentCount (обычно операторы скрывают промежуточные маршрутизаторы).")
                                    }
                                }.trim()
                                Text(summary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            state.lastProbe?.let { probe ->
                item(key = "tool_probe_result") {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Результат (${probe.status}, ${probe.durationMs} мс)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(text = probe.summary, style = MaterialTheme.typography.bodyMedium)
                            if (!probe.rawDetails.isNullOrBlank()) {
                                Text(
                                    text = probe.rawDetails!!,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (state.selectedTool == ToolKind.WIFI_CHANNELS) {
                if (!state.wifiError.isNullOrBlank()) {
                    item(key = "wifi_perm_card") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = WhyNetColors.WarnContainer),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = state.wifiError,
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

                if (state.wifiChannels.isNotEmpty()) {
                    val count24 = state.wifiChannels.count { it.bandGhz == 2.4 }
                    val count5 = state.wifiChannels.count { it.bandGhz == 5.0 }
                    val count6 = state.wifiChannels.count { it.bandGhz == 6.0 }
                    val busiest = state.wifiChannels
                        .filter { it.channel > 0 }
                        .groupingBy { "${it.bandGhz}ГГц к.${it.channel}" }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .joinToString { "${it.key} (${it.value})" }

                    item(key = "wifi_summary_card") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = WhyNetColors.InfoContainer),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Найдено точек: ${state.wifiChannels.size} (2.4 ГГц: $count24 · 5 ГГц: $count5 · 6 ГГц: $count6)",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = WhyNetColors.InfoBlue,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (busiest.isNotBlank()) {
                                    Text(
                                        text = "Самые загруженные каналы: $busiest",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = WhyNetColors.InfoBlue,
                                    )
                                }
                            }
                        }
                    }
                }

                items(state.wifiChannels, key = { it.uniqueKey }) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_wifi),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column {
                                    Text(entry.ssid, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "${entry.bandGhz} ГГц · канал ${entry.channel} · ${entry.bssidDisplay}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                text = "${entry.rssiDbm} дБм",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    entry.rssiDbm >= -60 -> WhyNetColors.OkGreen
                                    entry.rssiDbm >= -72 -> WhyNetColors.WarnAmber
                                    else -> WhyNetColors.FailRed
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun toolShortBadge(tool: ToolKind): String = when (tool) {
    ToolKind.TSPU_TEST -> "DPI"
    ToolKind.SERVICE_LATENCY -> "LAT"
    ToolKind.MASS_DNS_BENCH -> "DNS"
    ToolKind.DROPOUT_WATCHER -> "PING"
    ToolKind.TEST_SUITE -> "SUITE"
    ToolKind.APP_OBSERVER -> "APP"
    ToolKind.DNS_COMPARE -> "DNS~"
    ToolKind.DUAL_SNI -> "SNI"
    ToolKind.TRACEROUTE -> "TRACE"
    ToolKind.MTU_SEARCH -> "MTU"
    ToolKind.UDP_STUN -> "UDP"
    ToolKind.WIFI_CHANNELS -> "WIFI"
    ToolKind.PORT_SCANNER -> "PORTS"
    ToolKind.DNS_PRO -> "DNS+"
    ToolKind.WHOIS_IP -> "WHOIS"
    ToolKind.BUFFERBLOAT -> "SPEED"
    ToolKind.WAKE_ON_LAN -> "WOL"
    ToolKind.CIDR_CALC -> "CIDR"
}

@Composable
private fun AppObserverControlCard(
    state: ToolsUiState,
    observerState: AppObserverState,
    autoCheckState: AppAutoCheckState,
    hasActiveExternalVpn: Boolean,
    onSearchChange: (String) -> Unit,
    onSelectApp: (LaunchableAppInfo) -> Unit,
    onStartAutoCheck: () -> Unit,
    onFinishCaptureNow: () -> Unit,
    onCancelAutoCheck: () -> Unit,
    onRunSavedProfile: (SavedAppProfile) -> Unit,
    onDeleteSavedProfile: (String) -> Unit,
    onStartManual: () -> Unit,
    onStop: () -> Unit,
    onLaunchApp: (String) -> Unit,
    onMarkFreeze: () -> Unit,
) {
    var showAppPickerDialog by remember { mutableStateOf(false) }
    val filteredApps = state.installedApps.filter { app ->
        state.appSearchQuery.isBlank() ||
            app.label.contains(state.appSearchQuery, ignoreCase = true) ||
            app.packageName.contains(state.appSearchQuery, ignoreCase = true)
    }

    if (showAppPickerDialog) {
        AlertDialog(
            onDismissRequest = { showAppPickerDialog = false },
            title = { Text("Выберите приложение", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.appSearchQuery,
                        onValueChange = onSearchChange,
                        label = { Text("Поиск по названию") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isSel = state.selectedApp?.packageName == app.packageName
                            Card(
                                onClick = {
                                    onSelectApp(app)
                                    showAppPickerDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSel) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                ),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(
                                        text = app.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = app.packageName,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPickerDialog = false }) {
                    Text("Закрыть")
                }
            },
        )
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Проверить приложение",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "В 2 касания: локальный перехват (без чужих VPN-серверов) на 15 с узнаёт серверы приложения, сам отключается и сразу проверяет их напрямую через вашу сеть + UDP + MTU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (autoCheckState.savedProfiles.isNotEmpty() && !observerState.isRunning && !autoCheckState.isRunning) {
                Text(
                    text = "Сохранённые приложения (1 касание, без VPN):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    autoCheckState.savedProfiles.forEach { profile ->
                        AssistChip(
                            onClick = { onRunSavedProfile(profile) },
                            label = { Text("${profile.label} (${profile.domains.size})", maxLines = 1) },
                        )
                    }
                }
            }

            if (hasActiveExternalVpn && !observerState.isRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = WhyNetColors.WarnContainer),
                ) {
                    Text(
                        text = "Внимание: сейчас активен сторонний VPN/фильтр (например, AdGuard). На время 15-секундного поиска адресов он временно отключится.",
                        color = WhyNetColors.WarnAmber,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (!observerState.isRunning && !autoCheckState.isRunning) {
                OutlinedButton(
                    onClick = { showAppPickerDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = state.selectedApp?.let { "Приложение: ${it.label} (изменить)" }
                            ?: "Выбрать приложение из списка...",
                        maxLines = 1,
                    )
                }

                state.selectedApp?.let { app ->
                    Text(
                        text = app.packageName,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Button(
                    onClick = onStartAutoCheck,
                    enabled = state.selectedApp != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Проверить приложение (авто, 15 с)", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onStartManual,
                    enabled = state.selectedApp != null,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Только ручная запись соединений")
                }
            } else if (observerState.isRunning) {
                val timerSuffix = if (observerState.autoMode && observerState.remainingSeconds > 0) {
                    " · осталось ${observerState.remainingSeconds} с"
                } else {
                    ""
                }
                Text(
                    text = "Поиск серверов: ${observerState.targetLabel}$timerSuffix · пак.: ${observerState.totalPackets}",
                    style = MaterialTheme.typography.labelLarge,
                    color = WhyNetColors.OkGreen,
                    fontWeight = FontWeight.Bold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    observerState.targetPackage?.let { pkg ->
                        FilledTonalButton(
                            onClick = { onLaunchApp(pkg) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Открыть", maxLines = 1)
                        }
                    }
                    if (observerState.autoMode) {
                        Button(
                            onClick = onFinishCaptureNow,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Проверить сейчас", maxLines = 1)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onMarkFreeze,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Зависло здесь!", maxLines = 1)
                        }
                    }
                }

                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WhyNetColors.StopButtonBg,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Остановить", fontWeight = FontWeight.SemiBold)
                }
            } else {
                // Идёт фаза автопроверки после выключения локального VPN
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Text(
                        text = autoCheckState.currentStepText ?: "Идёт проверка серверов приложения...",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Button(
                    onClick = onCancelAutoCheck,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WhyNetColors.StopButtonBg,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Остановить проверку")
                }
            }

            if (!observerState.statusMessage.isNullOrBlank()) {
                Text(
                    text = observerState.statusMessage,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LivePingChartCard(
    ticks: List<PingTick>,
    host: String,
    totalSent: Int,
    totalLost: Int,
) {
    val sent = totalSent.coerceAtLeast(ticks.size)
    val okRtts = ticks.mapNotNull { it.externalRttMs }
    val lost = totalLost.coerceAtLeast(ticks.size - okRtts.size)
    val lossPct = if (sent == 0) 0.0 else (lost.toDouble() / sent) * 100.0
    val avg = okRtts.takeIf { it.isNotEmpty() }?.average()
    val p95 = okRtts.takeIf { it.isNotEmpty() }?.sorted()?.let { s ->
        s[(ceil(s.size * 0.95).toInt() - 1).coerceIn(0, s.lastIndex)]
    }
    val jitter = if (okRtts.size >= 2) {
        (1 until okRtts.size).sumOf { abs(okRtts[it] - okRtts[it - 1]) } / (okRtts.size - 1)
    } else null

    val lineColor = WhyNetColors.OkGreen
    val gwLineColor = WhyNetColors.InfoBlue
    val dropColor = WhyNetColors.FailRed
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "График RTT ($host)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Потери: ${"%.1f".format(lossPct)}% ($lost/$sent)",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (lost > 0) WhyNetColors.FailRed else WhyNetColors.OkGreen,
                    fontWeight = FontWeight.Bold,
                )
            }

            val maxY = ((okRtts.maxOrNull() ?: 50.0) * 1.25).coerceAtLeast(30.0)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
            ) {
                val w = size.width
                val h = size.height
                listOf(0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
                    val y = h * frac
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.5f)
                }

                val count = ticks.size.coerceAtLeast(2)
                val stepX = w / (count - 1).coerceAtLeast(1)

                val extPath = Path()
                var startedExt = false
                ticks.forEachIndexed { i, tick ->
                    val x = i * stepX
                    val rtt = tick.externalRttMs
                    if (rtt == null) {
                        drawLine(
                            color = dropColor,
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 4f,
                            cap = StrokeCap.Round,
                        )
                        startedExt = false
                    } else {
                        val y = h - ((rtt / maxY).toFloat().coerceIn(0f, 1f) * h)
                        if (!startedExt) {
                            extPath.moveTo(x, y)
                            startedExt = true
                        } else {
                            extPath.lineTo(x, y)
                        }
                        drawCircle(color = lineColor, radius = 4f, center = Offset(x, y))
                    }

                    tick.gatewayRttMs?.let { gw ->
                        val gy = h - ((gw / maxY).toFloat().coerceIn(0f, 1f) * h)
                        drawCircle(color = gwLineColor, radius = 3.5f, center = Offset(x, gy))
                    }
                }
                drawPath(
                    path = extPath,
                    color = lineColor,
                    style = Stroke(width = 4f, cap = StrokeCap.Round),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatMini("Средн.", avg?.let { "${"%.0f".format(it)} мс" } ?: "—")
                StatMini("P95", p95?.let { "${"%.0f".format(it)} мс" } ?: "—")
                StatMini("Джиттер", jitter?.let { "${"%.1f".format(it)} мс" } ?: "—")
                StatMini("Проб", "$sent")
            }
        }
    }
}

@Composable
private fun StatMini(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}
