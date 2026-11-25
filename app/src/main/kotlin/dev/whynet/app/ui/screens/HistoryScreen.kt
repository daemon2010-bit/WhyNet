package dev.whynet.app.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    onExportSessionsZip: (List<DiagnosticSession>) -> Unit = {},
    onDeleteSession: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val formatter = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val filteredSessions = remember(sessions, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            sessions
        } else {
            sessions.filter { s ->
                s.target.label.lowercase().contains(q) ||
                    s.target.hostOrUrl.lowercase().contains(q) ||
                    s.verdict.headline.lowercase().contains(q) ||
                    s.snapshot.transport.name.lowercase().contains(q) ||
                    s.snapshot.wifiSsid.orEmpty().lowercase().contains(q) ||
                    (s.snapshot.cellularEnbId?.toString()?.contains(q) == true) ||
                    formatter.format(Date(s.createdAtEpochMs)).lowercase().contains(q)
            }
        }
    }

    // Убираем из выбора id уже удалённых сессий
    val validSelectedIds = remember(selectedIds, sessions) {
        val existing = sessions.map { it.sessionId }.toSet()
        selectedIds.intersect(existing)
    }
    val selectedSessions = remember(validSelectedIds, sessions) {
        sessions.filter { it.sessionId in validSelectedIds }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            text = if (searchQuery.isBlank()) {
                                "Сохранено отчётов: ${sessions.size}"
                            } else {
                                "Найдено: ${filteredSessions.size} из ${sessions.size}"
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (sessions.isNotEmpty()) {
                        Text(
                            text = "Очистить всё",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = WhyNetColors.FailRed,
                            modifier = Modifier
                                .clickable { showClearConfirm = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        if (sessions.isNotEmpty()) {
            // Строка поиска по истории отчётов
            item(key = "history_search") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(1.dp, WhyNetColors.Border, RoundedCornerShape(9.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Поиск по домену, Wi-Fi, eNB, дате, вердикту...",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Очистить поиск",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { searchQuery = "" },
                        )
                    }
                }
            }

            // Панель экспорта (всеми или выбранными отчётами) + массовые действия
            item(key = "export_zip_archive") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val hasSelection = selectedSessions.isNotEmpty()
                        val targetToExport = if (hasSelection) selectedSessions else filteredSessions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (hasSelection) {
                                    "Выбрано отчётов: ${selectedSessions.size}"
                                } else {
                                    "Отметьте нужные отчёты или отправьте все сразу"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (hasSelection) "Снять выбор" else "Выбрать все",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        selectedIds = if (hasSelection) {
                                            emptySet()
                                        } else {
                                            filteredSessions.map { it.sessionId }.toSet()
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { onExportSessionsZip(targetToExport) },
                                enabled = targetToExport.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(9.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                val btnLabel = when {
                                    hasSelection -> "📦 Отправить выбранные (${selectedSessions.size})"
                                    searchQuery.isNotBlank() -> "📦 Отправить найденные (${filteredSessions.size})"
                                    else -> "📦 Отправить все отчёты (${sessions.size})"
                                }
                                Text(btnLabel, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (hasSelection) {
                                OutlinedButton(
                                    onClick = {
                                        selectedSessions.forEach { onDeleteSession(it.sessionId) }
                                        selectedIds = emptySet()
                                    },
                                    shape = RoundedCornerShape(9.dp),
                                    border = BorderStroke(1.dp, WhyNetColors.FailRed.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    Text("Удалить", fontSize = 12.sp, color = WhyNetColors.FailRed, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (sessions.size >= 2 && searchQuery.isBlank()) {
            val latest = sessions[0]
            val prev = sessions[1]
            item(key = "quick_compare") {
                CompareTwoSessionsCard(latest = latest, previous = prev)
            }
        }

        if (filteredSessions.isEmpty()) {
            item(key = "empty_history") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Text(
                        text = if (sessions.isEmpty()) {
                            "Пока нет сохранённых проверок. Запустите тест на вкладке «Сеть»."
                        } else {
                            "По запросу «$searchQuery» отчёты не найдены."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        } else {
            items(filteredSessions, key = { it.sessionId }) { session ->
                val isChecked = session.sessionId in validSelectedIds
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
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isChecked) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) {
                                    validSelectedIds + session.sessionId
                                } else {
                                    validSelectedIds - session.sessionId
                                }
                            },
                        )
                        Spacer(Modifier.width(2.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val locTag = when {
                                session.snapshot.wifiChannel != null && session.snapshot.transport.name == "WIFI" -> " · к.${session.snapshot.wifiChannel}"
                                session.snapshot.cellularEnbId != null -> " · eNB ${session.snapshot.cellularEnbId}"
                                else -> ""
                            }
                            Text(
                                text = "${session.target.label} · ${session.snapshot.transport}$locTag",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = session.verdict.headline,
                                style = MaterialTheme.typography.bodySmall,
                                color = accent,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatter.format(Date(session.createdAtEpochMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onDeleteSession(session.sessionId) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "Удалить",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Очистить историю?") },
            text = {
                Text("Будут удалены все сохранённые отчёты (${sessions.size}) с этого устройства.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        selectedIds = emptySet()
                        onClearAll()
                    },
                ) {
                    Text("Очистить", color = WhyNetColors.FailRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Отмена")
                }
            },
        )
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
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            text = "$before -> $after",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
