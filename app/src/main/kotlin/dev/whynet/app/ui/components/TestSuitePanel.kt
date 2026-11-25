package dev.whynet.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.whynet.app.R
import dev.whynet.app.SuiteUiState
import dev.whynet.app.report.ShareTarget
import dev.whynet.app.report.SuiteRunRecord
import dev.whynet.app.report.SuiteStepOutcome
import dev.whynet.app.report.SuiteStepResult
import dev.whynet.app.ui.theme.WhyNetColors

private val ENV_QUICK_CHIPS = listOf(
    "Wi-Fi",
    "Мобильная",
    "VPN вкл",
    "Прокси",
    "Забитый роутер",
    "Private DNS",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TestSuiteControlCard(
    state: SuiteUiState,
    onUpdateEnvTag: (String) -> Unit,
    onToggleEnvChip: (String) -> Unit,
    onPickSuiteFile: () -> Unit,
    onLoadDefaultSuite: () -> Unit,
    onExportTemplate: () -> Unit,
    onStartSuite: () -> Unit,
    onStopSuite: () -> Unit,
) {
    val activeTokens = state.environmentTag
        .split(',', '+')
        .map { it.trim().lowercase() }
        .toSet()

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = state.suite.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Источник: ${state.sourceLabel} · Тестов: ${state.suite.steps.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = WhyNetColors.FailContainer),
                ) {
                    Text(
                        text = state.errorMessage,
                        color = WhyNetColors.FailRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (state.suite.parseWarnings.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = WhyNetColors.WarnContainer),
                ) {
                    Text(
                        text = "Предупреждения файла: ${state.suite.parseWarnings.joinToString("; ")}",
                        color = WhyNetColors.WarnAmber,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            // Кнопки загрузки / сброса / шаблона файла сценария
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    enabled = !state.isRunning,
                    onClick = onPickSuiteFile,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_report_txt),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Загрузить .txt", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    enabled = !state.isRunning,
                    onClick = onLoadDefaultSuite,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Встроенный (${17})", style = MaterialTheme.typography.labelLarge)
                }

                AssistChip(
                    enabled = !state.isRunning,
                    onClick = onExportTemplate,
                    label = { Text("Шаблон .txt") },
                )
            }

            // Метка условий (VPN / мобильная / прокси / забитый роутер)
            OutlinedTextField(
                value = state.environmentTag,
                onValueChange = onUpdateEnvTag,
                label = { Text("Условия теста (метка в отчёт)") },
                placeholder = { Text("например: Мобильная + VPN вкл") },
                singleLine = true,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ENV_QUICK_CHIPS.forEach { chip ->
                    FilterChip(
                        selected = chip.lowercase() in activeTokens,
                        enabled = !state.isRunning,
                        onClick = { onToggleEnvChip(chip) },
                        label = { Text(chip, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            if (state.isRunning) {
                val progress = if (state.suite.steps.isEmpty()) 0f
                else (state.currentIndex.toFloat() / state.suite.steps.size.toFloat()).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.currentStepTitle ?: "Выполняем серию...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LinearProgressIndicator(
                        progress = { progress.coerceAtLeast(0.04f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }

            if (!state.isRunning) {
                Button(
                    onClick = onStartSuite,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Запустить серию (${state.suite.steps.size} тестов)",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Button(
                    onClick = onStopSuite,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WhyNetColors.StopButtonBg,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Остановить (${state.currentIndex}/${state.suite.steps.size})", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun TestSuiteExportCard(
    record: SuiteRunRecord,
    onExportReport: (SuiteRunRecord, ShareTarget) -> Unit,
) {
    val ok = record.results.count { it.outcome == SuiteStepOutcome.OK }
    val fail = record.results.count { it.outcome == SuiteStepOutcome.FAIL || it.outcome == SuiteStepOutcome.ERROR }
    val warn = record.results.count { it.outcome == SuiteStepOutcome.WARN }
    val sec = "%.1f".format((record.finishedAtEpochMs - record.startedAtEpochMs).coerceAtLeast(0L) / 1000.0)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (record.wasCancelled) {
                    "Прогон остановлен (${record.results.size}/${record.suite.steps.size}) · $sec с"
                } else {
                    "Серия завершена (${record.results.size}/${record.suite.steps.size}) · $sec с"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "OK: $ok  ·  Сбой: $fail  ·  Внимание: $warn",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onExportReport(record, ShareTarget.SYSTEM_CHOOSER) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Поделиться отчётом (.txt)", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun TestSuiteResultRow(result: SuiteStepResult) {
    val (fg, bg, badge) = when (result.outcome) {
        SuiteStepOutcome.OK -> Triple(WhyNetColors.OkGreen, WhyNetColors.OkContainer, "OK")
        SuiteStepOutcome.WARN -> Triple(WhyNetColors.WarnAmber, WhyNetColors.WarnContainer, "ВНИМ")
        SuiteStepOutcome.FAIL -> Triple(WhyNetColors.FailRed, WhyNetColors.FailContainer, "СБОЙ")
        SuiteStepOutcome.ERROR -> Triple(WhyNetColors.FailRed, WhyNetColors.FailContainer, "ОШБ")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = bg),
            ) {
                Text(
                    text = badge,
                    color = fg,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "#${result.step.index} [${result.step.kind.name}] ${result.step.target.ifBlank { "Wi-Fi" }}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    text = result.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(
                text = "${result.durationMs} мс",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
