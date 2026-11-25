package dev.whynet.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.core.model.DiagnosticSession
import dev.whynet.core.model.StageId
import dev.whynet.core.model.VerdictCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    sessions: List<DiagnosticSession>,
    onOpenSession: (DiagnosticSession) -> Unit,
    onShareSession: (DiagnosticSession) -> Unit,
    onExportArchiveZip: () -> Unit = {},
    onDeleteSession: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val formatter = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "history_header") {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (onBack != null) {
                    Text(
                        text = "← Назад к проверке",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(vertical = 3.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "История проверок",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Локальный журнал на телефоне (${sessions.size})",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sessions.isNotEmpty()) {
                        Text(
                            text = "Очистить",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable(onClick = onClearAll)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        if (sessions.isNotEmpty()) {
            item(key = "export_zip_archive") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Все сохранённые проверки (${sessions.size}) с полными логами DNS/TLS/ТСПУ, БС и Wi-Fi в одном ZIP-архиве.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onExportArchiveZip,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("📦 Отправить ZIP-архив отчётов", maxLines = 1)
                        }
                    }
                }
            }
        }

        if (sessions.size >= 2) {
            val latest = sessions[0]
            val prev = sessions[1]
            item(key = "quick_compare") {
                CompareTwoSessionsCard(latest = latest, previous = prev)
            }
        }

        if (sessions.isEmpty()) {
            item(key = "empty_history") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Text(
                        text = "Пока нет сохранённых проверок. Запустите тест на вкладке «Проверка».",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(sessions, key = { it.sessionId }) { session ->
                val accent = when (session.verdict.category) {
                    VerdictCategory.HEALTHY -> WhyNetColors.OkGreen
                    VerdictCategory.UNKNOWN,
                    VerdictCategory.DIAGNOSTIC_TOOL_LIMITATION,
                    VerdictCategory.IP_FAMILY_BROKEN,
                    VerdictCategory.ANTIBOT_OR_CAPTCHA,
                    VerdictCategory.SERVICE_RESTRICTED_OR_AUTH_REQUIRED,
                    -> WhyNetColors.WarnAmber
                    else -> WhyNetColors.FailRed
                }
                Card(
                    onClick = { onOpenSession(session) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val locTag = when {
                                session.snapshot.cellularEnbId != null -> " · eNB ${session.snapshot.cellularEnbId}"
                                session.snapshot.wifiChannel != null -> " · к.${session.snapshot.wifiChannel}"
                                else -> ""
                            }
                            Text(
                                text = "${session.target.label} · ${session.snapshot.transport}$locTag",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = session.verdict.headline,
                                style = MaterialTheme.typography.bodySmall,
                                color = accent,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = formatter.format(Date(session.createdAtEpochMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onShareSession(session) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Поделиться")
                        }
                        IconButton(onClick = { onDeleteSession(session.sessionId) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareTwoSessionsCard(
    latest: DiagnosticSession,
    previous: DiagnosticSession,
) {
    fun stageMs(s: DiagnosticSession, id: StageId): String =
        s.probes.firstOrNull { it.stage == id }?.let { "${it.durationMs}" } ?: "—"

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Сравнение 2 последних проверок (До / После)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Сейчас (${latest.target.label}, ${latest.snapshot.transport}): ${latest.verdict.headline}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Ранее (${previous.target.label}, ${previous.snapshot.transport}): ${previous.verdict.headline}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CompareMetric("DNS (мс)", stageMs(previous, StageId.DNS), stageMs(latest, StageId.DNS), Modifier.weight(1f))
                    CompareMetric("TCP (мс)", stageMs(previous, StageId.TCP), stageMs(latest, StageId.TCP), Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CompareMetric("TLS (мс)", stageMs(previous, StageId.TLS_SNI), stageMs(latest, StageId.TLS_SNI), Modifier.weight(1f))
                    CompareMetric("HTTP (мс)", stageMs(previous, StageId.HTTP_PAYLOAD), stageMs(latest, StageId.HTTP_PAYLOAD), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CompareMetric(label: String, before: String, after: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Text(
            text = "$before → $after",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
