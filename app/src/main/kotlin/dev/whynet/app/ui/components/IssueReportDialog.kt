package dev.whynet.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.whynet.app.R
import dev.whynet.app.report.IssueCategory
import dev.whynet.app.report.IssueReportRequest
import dev.whynet.app.report.ShareTarget

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssueReportDialog(
    hasDiagnosticSession: Boolean,
    appLogLinesCount: Int,
    maxInstalled: Boolean,
    telegramInstalled: Boolean,
    onDismiss: () -> Unit,
    onExport: (IssueReportRequest) -> Unit,
) {
    var category by remember {
        mutableStateOf(
            if (hasDiagnosticSession) IssueCategory.SITE_BROKEN_BUT_OK else IssueCategory.APP_GLITCH,
        )
    }
    var comment by remember { mutableStateOf("") }
    var includeDiag by remember { mutableStateOf(true) }
    var includeApp by remember { mutableStateOf(true) }

    val canSend = includeDiag || includeApp

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = null,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_report_txt),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "Отчёт о проблеме (.txt)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IssueCategory.entries.forEach { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = {
                                Text(
                                    text = item.shortLabel(),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }

                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Что не так? (необязательно)") },
                    placeholder = { Text("Например: сайт не грузится, а тест пишет ОК") },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeDiag = !includeDiag },
                    ) {
                        Checkbox(checked = includeDiag, onCheckedChange = { includeDiag = it })
                        Text(
                            text = if (hasDiagnosticSession) {
                                "Логи проверки доступности"
                            } else {
                                "Снимок сети и проверка"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeApp = !includeApp },
                    ) {
                        Checkbox(checked = includeApp, onCheckedChange = { includeApp = it })
                        Text(
                            text = "Логи приложения ($appLogLinesCount соб. + logcat)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Text(
                    text = "Обезличенный .txt: без паролей, токенов, SSID, MAC и полного IP.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Button(
                    enabled = canSend,
                    onClick = {
                        onExport(
                            IssueReportRequest(
                                category = category,
                                userComment = comment,
                                includeDiagnosticLogs = includeDiag,
                                includeAppLogs = includeApp,
                                shareTarget = ShareTarget.SYSTEM_CHOOSER,
                            ),
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Поделиться (.txt)", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть")
            }
        },
    )
}

private fun IssueCategory.shortLabel(): String = when (this) {
    IssueCategory.SITE_BROKEN_BUT_OK -> "Сайт не работает, тест ОК"
    IssueCategory.WRONG_DIAGNOSIS -> "Неверная причина"
    IssueCategory.APP_GLITCH -> "Сбой приложения"
    IssueCategory.OTHER -> "Другое / лог"
}
