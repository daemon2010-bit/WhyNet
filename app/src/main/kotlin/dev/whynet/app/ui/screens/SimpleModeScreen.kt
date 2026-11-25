package dev.whynet.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.whynet.app.HelpUiState
import dev.whynet.app.report.ShareTarget
import dev.whynet.app.report.SimpleStatusLevel
import dev.whynet.app.report.SuiteRunRecord
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.core.engine.HelpLinkCodec
import dev.whynet.core.engine.HelpPreset
import dev.whynet.core.engine.HelpTaskSpec
import dev.whynet.core.engine.RemoteSessionCrypto
import dev.whynet.core.engine.SuiteStepKind

private const val ENABLE_SIMPLE_REMOTE_CODE = false

/**
 * Упрощённый «Простой режим (для близких)»: крупный шрифт, список проверок,
 * одна кнопка «Запустить тест» и одна кнопка «Поделиться результатом».
 */
@Composable
fun SimpleModeScreen(
    helpState: HelpUiState,
    onStartCheck: () -> Unit,
    onStopCheck: () -> Unit,
    onExportReport: (SuiteRunRecord, ShareTarget) -> Unit,
    onStartGuestCode: () -> Unit,
    onStopGuestCode: () -> Unit,
    onExitSimpleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeTask = helpState.incomingTask ?: HelpTaskSpec(preset = HelpPreset.FULL_NETWORK)
    val plannedSteps = HelpLinkCodec.toSuiteDefinition(activeTask).steps

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "simple_header") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "WhyNet · Помощь",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Простой режим проверки",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onExitSimpleMode) {
                    Text("Обычный режим")
                }
            }
        }

        item(key = "incoming_task_banner") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Что будет проверено (${activeTask.preset.title}):",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    plannedSteps.forEach { step ->
                        val stepText = when (step.kind) {
                            SuiteStepKind.NETWORK -> "Общая работа сети, роутера и DNS"
                            SuiteStepKind.CALLS -> "Звонки и мессенджеры (UDP/STUN)"
                            SuiteStepKind.SITE -> "Доступность сайта ${step.target}"
                            SuiteStepKind.DNS -> "Проверка DNS для ${step.target}"
                            SuiteStepKind.SNI -> "Проверка TLS/SNI для ${step.target}"
                            SuiteStepKind.MTU -> "Проверка размера пакетов (MTU)"
                            SuiteStepKind.TRACEROUTE -> "Маршрут до ${step.target}"
                            SuiteStepKind.UDP -> "Голосовые звонки и P2P (UDP)"
                            SuiteStepKind.WIFI -> "Состояние Wi-Fi рядом"
                        }
                        Text(
                            text = "• $stepText",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (activeTask.note.isNotBlank()) {
                        Text(
                            text = "Подсказка: ${activeTask.note}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }

        item(key = "main_big_button") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (helpState.isRunningHelpCheck) {
                        CircularProgressIndicator(modifier = Modifier.size(44.dp))
                        Text(
                            text = helpState.helpProgressText ?: "Проверяем интернет...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedButton(
                            onClick = onStopCheck,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Остановить")
                        }
                    } else if (helpState.lastHelpRecord != null) {
                        val record = helpState.lastHelpRecord
                        Button(
                            onClick = { onExportReport(record, ShareTarget.SYSTEM_CHOOSER) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Поделиться результатом",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        OutlinedButton(
                            onClick = onStartCheck,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text("Запустить тест ещё раз")
                        }
                    } else {
                        Button(
                            onClick = onStartCheck,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Запустить тест",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = "Проверка займёт примерно 15 секунд, после чего появится кнопка «Поделиться результатом».",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        helpState.lastSimpleSummary?.let { summary ->
            item(key = "simple_summary_card") {
                val bg = when (summary.level) {
                    SimpleStatusLevel.OK -> WhyNetColors.OkContainer
                    SimpleStatusLevel.WARN -> WhyNetColors.WarnContainer
                    SimpleStatusLevel.FAIL -> WhyNetColors.FailContainer
                }
                val accent = when (summary.level) {
                    SimpleStatusLevel.OK -> WhyNetColors.OkGreen
                    SimpleStatusLevel.WARN -> WhyNetColors.WarnAmber
                    SimpleStatusLevel.FAIL -> WhyNetColors.FailRed
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = bg),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (summary.level == SimpleStatusLevel.OK) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = summary.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                            )
                        }
                        Text(
                            text = summary.explanation,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        summary.adviceSteps.forEachIndexed { idx, step ->
                            Text(
                                text = "${idx + 1}. $step",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }

        if (ENABLE_SIMPLE_REMOTE_CODE) {
            item(key = "simple_code_help") {
                val remote = helpState.remote
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "Помощь по коду (в реальном времени)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (!remote.isActive) {
                            FilledTonalButton(
                                onClick = onStartGuestCode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Показать код для помощи", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Text(
                                text = RemoteSessionCrypto.formatCode(remote.sessionCode),
                                fontSize = 36.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Button(
                                onClick = onStopGuestCode,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = WhyNetColors.StopButtonBg,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Завершить сеанс помощи")
                            }
                        }
                    }
                }
            }
        }
    }
}
