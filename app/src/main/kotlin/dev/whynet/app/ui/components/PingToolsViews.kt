package dev.whynet.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whynet.app.ToolsUiState
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.core.engine.NetToolsMath

@Composable
fun PortScannerToolView(
    state: ToolsUiState,
    onHostChange: (String) -> Unit,
    onPortSpecChange: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactToolCard {
            OutlinedTextField(
                value = state.hostInput,
                onValueChange = onHostChange,
                label = { Text("IP или домен") },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.portSpec,
                onValueChange = onPortSpecChange,
                label = { Text("Порты (например 22,80,443,8000-8010)") },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NetToolsMath.PORT_PRESETS.forEach { (label, ports) ->
                    AssistChip(
                        enabled = !state.isRunning,
                        onClick = { onPortSpecChange(ports.joinToString(",")) },
                        label = { Text(label, fontSize = 11.sp) },
                    )
                }
            }
            RunOrStopToolButton(isRunning = state.isRunning, runLabel = "Сканировать порты", onRun = onRun, onStop = onStop)
            state.liveNote?.let { note ->
                Text(note, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
        }

        state.portScanReport?.let { rep ->
            CompactToolCard {
                Text(
                    text = "${rep.targetHost} (${rep.resolvedIp}) · проверено ${rep.scannedCount} за ${rep.durationMs} мс",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                if (rep.openPorts.isEmpty()) {
                    Text(
                        text = "Открытых портов из выбранного списка не найдено.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    rep.openPorts.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "TCP ${entry.port} · ${entry.serviceName}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "OPEN · ${entry.rttMs} мс",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = WhyNetColors.OkGreen,
                            )
                        }
                    }
                }
            }
        }

        state.whoisReport?.let { w ->
            CompactToolCard {
                Text(
                    text = "WHOIS / Geo · ${w.resolvedIp}",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                w.asn?.let { KeyValLine("ASN", it) }
                w.isp?.let { KeyValLine("Провайдер", it) }
                w.country?.takeIf { it.isNotBlank() }?.let { c ->
                    KeyValLine("Локация", c + (w.city?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""))
                }
            }
        }
    }
}

@Composable
fun DnsProToolView(
    state: ToolsUiState,
    onHostChange: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactToolCard {
            OutlinedTextField(
                value = state.hostInput,
                onValueChange = onHostChange,
                label = { Text("Домен (например ya.ru)") },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            RunOrStopToolButton(isRunning = state.isRunning, runLabel = "Запросить DNS + WHOIS", onRun = onRun, onStop = onStop)
        }

        state.dnsProReport?.let { rep ->
            CompactToolCard {
                Text(
                    text = "${rep.hostname} · ${rep.provider} · ${rep.records.size} зап. (${rep.durationMs} мс)",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                rep.error?.takeIf { it.isNotBlank() }?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = WhyNetColors.FailRed)
                }
                if (rep.records.isEmpty()) {
                    Text("Записи не найдены.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    rep.records.forEach { rec ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = rec.type.padEnd(5),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = rec.value,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            rec.ttl?.let { ttl ->
                                Text(
                                    text = "${ttl}s",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.whoisReport?.let { w ->
            CompactToolCard {
                Text(
                    text = "WHOIS / Geo · ${w.resolvedIp}",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                w.asn?.let { KeyValLine("ASN", it) }
                w.isp?.let { KeyValLine("Провайдер", it) }
                w.country?.takeIf { it.isNotBlank() }?.let { c ->
                    KeyValLine("Локация", c + (w.city?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""))
                }
            }
        }
    }
}

@Composable
fun ServiceLatencyToolView(
    state: ToolsUiState,
    onHostChange: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactToolCard {
            OutlinedTextField(
                value = state.hostInput,
                onValueChange = onHostChange,
                label = { Text("Доп. домен (необязательно)") },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            RunOrStopToolButton(
                isRunning = state.isRunning,
                runLabel = "Запустить монитор (авто 2 с)",
                onRun = onRun,
                onStop = onStop,
            )
            state.liveNote?.let { note ->
                Text(note, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
        }

        if (state.serviceLatencies.isNotEmpty()) {
            CompactToolCard {
                state.serviceLatencies.forEachIndexed { idx, item ->
                    val rtt = item.rttMs
                    val col = when {
                        !item.isOk || rtt == null -> WhyNetColors.FailRed
                        rtt <= 60L -> WhyNetColors.OkGreen
                        rtt <= 160L -> WhyNetColors.WarnAmber
                        else -> WhyNetColors.FailRed
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(modifier = Modifier.size(34.dp)) {
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                modifier = Modifier.size(32.dp),
                            ) {
                                Box(
                                    modifier = Modifier.size(32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = item.code,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .align(Alignment.BottomEnd)
                                    .clip(CircleShape)
                                    .background(col),
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                text = item.name,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${item.protocol} · ${item.host}",
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = if (item.isOk && rtt != null) "$rtt мс" else "TIMEOUT",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = col,
                        )
                    }
                    if (idx < state.serviceLatencies.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }
}

@Composable
fun TspuTestToolView(
    state: ToolsUiState,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactToolCard {
            Text(
                text = "Проверяет РФ-эталон, порог 16 КБ (AWS CloudFront), Cloudflare CDN, Google, YouTube и Discord - выявляет фильтрацию ТСПУ и режим белых списков на текущей вышке/сети.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RunOrStopToolButton(isRunning = state.isRunning, runLabel = "Запустить тест ТСПУ", onRun = onRun, onStop = onStop)
            state.liveNote?.let { note ->
                Text(note, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
        }

        state.tspuReport?.let { rep ->
            val okColor = WhyNetColors.OkGreen
            val warnColor = WhyNetColors.WarnAmber
            val failColor = WhyNetColors.FailRed
            CompactToolCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Результаты контуров", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("${rep.durationMs} мс", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                rep.entries.forEach { e ->
                    val col = when (e.status) {
                        "OK" -> okColor
                        "WARN" -> warnColor
                        else -> failColor
                    }
                    val progress = when (e.status) {
                        "OK" -> 1f
                        "WARN" -> 0.55f
                        else -> if (e.detail.contains("16")) 0.28f else 0.15f
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(e.label, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text(e.detail, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = col)
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            color = col,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                val hasAnyFail = rep.entries.any { it.status == "FAIL" }
                Text(
                    text = rep.verdict,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (rep.looksLikeWhitelist || hasAnyFail) warnColor else okColor,
                )
                Text(rep.recommendation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun MassDnsBenchToolView(
    state: ToolsUiState,
    onHostChange: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactToolCard {
            OutlinedTextField(
                value = state.hostInput,
                onValueChange = onHostChange,
                label = { Text("Тестовый домен (например ya.ru)") },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            RunOrStopToolButton(isRunning = state.isRunning, runLabel = "Проверить массовые DNS", onRun = onRun, onStop = onStop)
            state.liveNote?.let { note ->
                Text(note, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
        }

        state.massDnsReport?.let { rep ->
            val maxRtt = (rep.entries.mapNotNull { it.rttMs }.maxOrNull() ?: 100L).coerceAtLeast(60L).toFloat()
            CompactToolCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Массовые DNS · ${rep.testDomain}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    rep.fastestClean?.let { best ->
                        Text("Лучший: ${best.name.substringBefore(' ')}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = WhyNetColors.OkGreen)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                rep.entries.forEach { entry ->
                    val col = when {
                        !entry.isOk -> WhyNetColors.FailRed
                        (entry.rttMs ?: 999L) <= 45L -> WhyNetColors.OkGreen
                        (entry.rttMs ?: 999L) <= 95L -> MaterialTheme.colorScheme.primary
                        else -> WhyNetColors.WarnAmber
                    }
                    val rttVal = entry.rttMs
                    val frac = if (rttVal != null) (rttVal / maxRtt).coerceIn(0.08f, 1f) else 1f
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${entry.name} (${entry.serverIp})",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = entry.note,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = col,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { frac },
                            color = col,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Text(rep.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val okEntries = rep.entries.filter { it.isOk && it.serverIp != "auto" }
                val allSameIpAndFast = okEntries.size >= 4 &&
                    okEntries.mapNotNull { it.resolvedIp }.distinct().size == 1 &&
                    okEntries.all { (it.rttMs ?: 999L) <= 20L }
                if (allSameIpAndFast) {
                    Text(
                        text = "Примечание: все DNS ответили за 10-20 мс одним IP - вероятно, активный VPN/AdGuard кэширует UDP:53 локально.",
                        fontSize = 10.sp,
                        color = WhyNetColors.WarnAmber,
                    )
                }
            }
        }
    }
}

@Composable
fun WhoisIpToolView(
    state: ToolsUiState,
    onHostChange: (String) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactToolCard {
            OutlinedTextField(
                value = state.hostInput,
                onValueChange = onHostChange,
                label = { Text("IP или домен (например 91.194.163.92)") },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )
            RunOrStopToolButton(isRunning = state.isRunning, runLabel = "Проверить WHOIS / ASN", onRun = onRun, onStop = onStop)
            state.liveNote?.let { note ->
                Text(note, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
        }

        state.whoisReport?.let { rep ->
            CompactToolCard {
                Text(
                    text = "${rep.query} -> ${rep.resolvedIp}",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                KeyValLine("IPv4", rep.resolvedIp)
                rep.asn?.let { KeyValLine("ASN", it) }
                rep.isp?.let { KeyValLine("Провайдер", it) }
                rep.org?.takeIf { it != rep.isp }?.let { KeyValLine("Организация", it) }
                rep.netName?.let { KeyValLine("Блок RDAP", it) }
                rep.cidr?.let { KeyValLine("Диапазон", it) }
                rep.country?.takeIf { it.isNotBlank() }?.let { country ->
                    KeyValLine("Локация", country + (rep.city?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""))
                }
            }
        }
    }
}

@Composable
fun BufferbloatToolView(
    state: ToolsUiState,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactToolCard {
            Text(
                text = "Измеряет скорость загрузки (5 с) и одновременно сравнивает задержку (пинг) в покое и под нагрузкой. Показывает, захлёбывается ли роутер или канал при скачивании.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RunOrStopToolButton(isRunning = state.isRunning, runLabel = "Запустить тест (5 с)", onRun = onRun, onStop = onStop)
            state.liveNote?.let { note ->
                Text(note, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
        }

        state.bufferbloatReport?.let { rep ->
            val gradeColor = when (rep.grade) {
                "A+", "A" -> WhyNetColors.OkGreen
                "B", "C" -> WhyNetColors.WarnAmber
                else -> WhyNetColors.FailRed
            }
            CompactToolCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Скорость загрузки", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "${"%.1f".format(rep.downloadMbps)} Мбит/с",
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = gradeColor.copy(alpha = 0.16f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Bufferbloat", fontSize = 10.sp, color = gradeColor)
                            Text(
                                text = rep.grade,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = gradeColor,
                            )
                        }
                    }
                }
                Text(rep.verdict, style = MaterialTheme.typography.bodySmall)
                if (rep.speedSamplesMbps.size >= 2) {
                    val speedColor = MaterialTheme.colorScheme.primary
                    val pingColor = WhyNetColors.WarnAmber
                    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    val maxSpeed = (rep.speedSamplesMbps.maxOrNull() ?: 10.0).coerceAtLeast(10.0)
                    val maxPing = (rep.loadedPingSamplesMs.maxOrNull() ?: 100.0).coerceAtLeast(60.0)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Синий: Мбит/с (макс ${"%.0f".format(maxSpeed)})", fontSize = 9.5.sp, color = speedColor)
                        Text("Оранж.: пинг мс (макс ${"%.0f".format(maxPing)})", fontSize = 9.5.sp, color = pingColor)
                    }
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                    ) {
                        val w = size.width
                        val h = size.height
                        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                            drawLine(gridColor, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1f)
                        }
                        val spPath = Path()
                        val stepS = w / (rep.speedSamplesMbps.size - 1).coerceAtLeast(1)
                        rep.speedSamplesMbps.forEachIndexed { idx, v ->
                            val x = idx * stepS
                            val y = h - ((v / maxSpeed).toFloat().coerceIn(0f, 1f) * (h - 6f))
                            if (idx == 0) spPath.moveTo(x, y) else spPath.lineTo(x, y)
                        }
                        drawPath(spPath, color = speedColor, style = Stroke(width = 3.5f, cap = StrokeCap.Round))

                        if (rep.loadedPingSamplesMs.size >= 2) {
                            val pgPath = Path()
                            val stepP = w / (rep.loadedPingSamplesMs.size - 1).coerceAtLeast(1)
                            rep.loadedPingSamplesMs.forEachIndexed { idx, v ->
                                val x = idx * stepP
                                val y = h - ((v / maxPing).toFloat().coerceIn(0f, 1f) * (h - 6f))
                                if (idx == 0) pgPath.moveTo(x, y) else pgPath.lineTo(x, y)
                            }
                            drawPath(pgPath, color = pingColor, style = Stroke(width = 2.8f, cap = StrokeCap.Round))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                if (rep.cloudflareBlocked) {
                    KeyValLine("Cloudflare", "Заблокирован (0.0 Мбит/с)")
                }
                if (rep.idleGatewayMs != null || rep.loadedGatewayMs != null) {
                    KeyValLine(
                        "Шлюз (покой / нагрузка)",
                        "${rep.idleGatewayMs?.let { "%.0f".format(it) } ?: "-"} мс / ${rep.loadedGatewayMs?.let { "%.0f".format(it) } ?: "-"} мс",
                    )
                }
                KeyValLine(
                    "Внешний (покой / нагрузка)",
                    "${rep.idleExternalMs?.let { "%.0f".format(it) } ?: "-"} мс / ${rep.loadedExternalMs?.let { "%.0f".format(it) } ?: "-"} мс",
                )
                KeyValLine("Тестовый сервер", rep.testServer)
            }
        }
    }
}

@Composable
fun WakeOnLanToolView(
    state: ToolsUiState,
    onMacChange: (String) -> Unit,
    onBroadcastChange: (String) -> Unit,
    onRun: () -> Unit,
) {
    CompactToolCard {
        Text(
            text = "Отправляет 3 UDP Magic Packet (порт 9) для удалённого включения ПК или NAS в вашей локальной сети Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.wolMac,
            onValueChange = onMacChange,
            label = { Text("MAC-адрес ПК (AA:BB:CC:DD:EE:FF)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.wolBroadcast,
            onValueChange = onBroadcastChange,
            label = { Text("Broadcast IP подсети") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onRun,
            enabled = state.wolMac.isNotBlank() && !state.isRunning,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Разбудить устройство (WoL)")
        }
        state.wolStatus?.let { status ->
            Text(
                text = status,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                color = if (status.startsWith("Ошибка")) WhyNetColors.FailRed else WhyNetColors.OkGreen,
            )
        }
    }
}

@Composable
fun CidrCalcToolView(
    cidrInput: String,
    onCidrChange: (String) -> Unit,
) {
    val calc = remember(cidrInput) { NetToolsMath.calculateCidr(cidrInput) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactToolCard {
            OutlinedTextField(
                value = cidrInput,
                onValueChange = onCidrChange,
                label = { Text("IPv4 / маска (например 192.168.51.69/24)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("24", "20", "16", "28", "30").forEach { prefix ->
                    AssistChip(
                        onClick = {
                            val base = cidrInput.substringBefore('/').ifBlank { "192.168.1.1" }
                            onCidrChange("$base/$prefix")
                        },
                        label = { Text("/$prefix", fontFamily = FontFamily.Monospace) },
                    )
                }
            }
        }

        if (calc != null) {
            CompactToolCard {
                Text(
                    text = "Подсеть ${calc.networkAddress}/${calc.prefixLength}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                KeyValLine("Адрес сети", calc.networkAddress)
                KeyValLine("Маска подсети", calc.subnetMask)
                KeyValLine("Wildcard", calc.wildcardMask)
                KeyValLine("Broadcast", calc.broadcastAddress)
                KeyValLine("Хосты", "${calc.firstUsableHost} - ${calc.lastUsableHost}")
                KeyValLine("Доступно хостов", "${calc.usableHosts} (всего ${calc.totalAddresses})")
                KeyValLine("Тип адреса", calc.addressScope)
            }
        } else {
            CompactToolCard {
                Text(
                    text = "Введите корректный IPv4 и префикс 0-32 (например 10.240.42.38/20).",
                    style = MaterialTheme.typography.bodySmall,
                    color = WhyNetColors.WarnAmber,
                )
            }
        }
    }
}

@Composable
private fun CompactToolCard(content: @Composable () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun KeyValLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RunOrStopToolButton(
    isRunning: Boolean,
    runLabel: String,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    if (!isRunning) {
        Button(
            onClick = onRun,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(runLabel, maxLines = 1)
        }
    } else {
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(containerColor = WhyNetColors.StopButtonBg, contentColor = Color.White),
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("Остановить", fontWeight = FontWeight.SemiBold)
        }
    }
}