package dev.whynet.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import dev.whynet.app.HelpUiState
import dev.whynet.app.report.ShareTarget
import dev.whynet.app.service.RemoteRole
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.core.engine.HelpLinkCodec
import dev.whynet.core.engine.HelpPreset
import dev.whynet.core.engine.HelpTaskSpec
import dev.whynet.core.engine.RemoteSessionCrypto

/** Флаг включения сеанса помощи по 6-значному коду (можно вернуть в true при необходимости). */
private const val ENABLE_REMOTE_CODE_SESSION = false

/**
 * Экран «Помощь близкому»: объединяет все 3 варианта удалённого тестирования сети:
 * 1) Простой режим (для мамы/близких),
 * 2) Ссылки-задания whynet://help?... и файлы-задания,
 * 3) Сеанс помощи по 6-значному коду в реальном времени.
 */
@Composable
fun HelpScreen(
    state: HelpUiState,
    builtSpec: HelpTaskSpec,
    onToggleSimpleMode: (Boolean) -> Unit,
    onUpdateHelperName: (String) -> Unit,
    onUpdateBuilderPreset: (HelpPreset) -> Unit,
    onUpdateBuilderTargets: (String) -> Unit,
    onUpdateBuilderAutoStart: (Boolean) -> Unit,
    onUpdateBuilderNote: (String) -> Unit,
    onCopyHelpLink: (HelpTaskSpec) -> Unit,
    onShareHelpLink: (HelpTaskSpec, ShareTarget) -> Unit,
    onShareHelpFile: (HelpTaskSpec, ShareTarget) -> Unit,
    onUpdatePasteLink: (String) -> Unit,
    onApplyPastedLink: () -> Unit,
    onUpdateRelayUrl: (String) -> Unit,
    onUpdateRemoteInputCode: (String) -> Unit,
    onUpdateRemoteHelperHost: (String) -> Unit,
    onStartGuestRemote: () -> Unit,
    onStartHelperRemote: () -> Unit,
    onSendRemoteCommand: (String, String) -> Unit,
    onStopRemote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewLink = HelpLinkCodec.encodeUri(builtSpec)
    val remote = state.remote

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "help_header") {
            Column {
                Text(
                    text = "Помощь близкому",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Простой режим и ссылки-задания для быстрой проверки",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // --- 1. Простой режим ---
        item(key = "simple_mode_card") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "1. Простой режим (для близких)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Крупная кнопка «Запустить тест», понятный итог без терминов и кнопка «Поделиться».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = state.simpleModeEnabled,
                        onCheckedChange = onToggleSimpleMode,
                    )
                }
            }
        }

        // --- 2. Ссылка-задание https://whynet.app/help?... ---
        item(key = "help_link_card") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "2. Ссылка-задание",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Соберите проверку и отправьте близкому. По ссылке откроется WhyNet со списком проверок и одной кнопкой запуска.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        HelpPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.builderPreset == preset,
                                onClick = { onUpdateBuilderPreset(preset) },
                                label = { Text(preset.title, maxLines = 1) },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.builderTargetsText,
                        onValueChange = onUpdateBuilderTargets,
                        label = { Text("Доп. сайты через запятую (необязательно)") },
                        placeholder = { Text("youtube.com, web.telegram.org") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = state.builderNote,
                        onValueChange = onUpdateBuilderNote,
                        label = { Text("Короткая подсказка (необязательно)") },
                        placeholder = { Text("Нажми «Запустить тест» и отправь результат") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Text(
                            text = previewLink,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onShareHelpLink(builtSpec, ShareTarget.SYSTEM_CHOOSER) },
                            modifier = Modifier.weight(1.4f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Поделиться", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { onCopyHelpLink(builtSpec) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Копировать", maxLines = 1)
                        }
                    }

                    OutlinedTextField(
                        value = state.pasteLinkInput,
                        onValueChange = onUpdatePasteLink,
                        label = { Text("Вставить полученную ссылку") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!state.pasteLinkError.isNullOrBlank()) {
                        Text(
                            text = state.pasteLinkError,
                            style = MaterialTheme.typography.bodySmall,
                            color = WhyNetColors.FailRed,
                        )
                    }
                    FilledTonalButton(
                        onClick = onApplyPastedLink,
                        enabled = state.pasteLinkInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Открыть задание по ссылке")
                    }
                }
            }
        }

        // --- 3. Сеанс помощи по 6-значному коду (временно скрыт флагом ENABLE_REMOTE_CODE_SESSION) ---
        if (ENABLE_REMOTE_CODE_SESSION) {
        item(key = "remote_code_card") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "3. Сеанс помощи по 6-значному коду",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Не скрытый доступ: близкий сам открывает сеанс и диктует код (15 мин). Команды и отчёты шифруются AES-256-GCM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = remote.relayUrl,
                        onValueChange = onUpdateRelayUrl,
                        label = { Text("Адрес relay-сервера (Cloudflare Worker / VPS / LAN)") },
                        placeholder = { Text("https://whynet-relay.your-name.workers.dev") },
                        singleLine = true,
                        enabled = !remote.isActive,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (!remote.isActive) {
                        Button(
                            onClick = onStartGuestRemote,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Попросить о помощи (показать 6-значный код)")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = remote.inputCode,
                                onValueChange = onUpdateRemoteInputCode,
                                label = { Text("Код близкого (6 цифр)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(
                                onClick = onStartHelperRemote,
                                enabled = remote.inputCode.length == 6,
                                modifier = Modifier.height(54.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text("Помочь")
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = if (remote.activeRole == RemoteRole.GUEST) {
                                        "Режим: Я прошу о помощи"
                                    } else {
                                        "Режим: Я помогаю удалённо"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Код: ${RemoteSessionCrypto.formatCode(remote.sessionCode)}",
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }

                        if (remote.activeRole == RemoteRole.HELPER) {
                            OutlinedTextField(
                                value = remote.helperTargetHost,
                                onValueChange = onUpdateRemoteHelperHost,
                                label = { Text("Домен для удалённой проверки") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilledTonalButton(
                                    onClick = { onSendRemoteCommand("cmd_network", "ya.ru") },
                                    enabled = !remote.isBusy,
                                ) { Text("Вся сеть") }
                                FilledTonalButton(
                                    onClick = { onSendRemoteCommand("cmd_site", remote.helperTargetHost) },
                                    enabled = !remote.isBusy,
                                ) { Text("Сайт") }
                                FilledTonalButton(
                                    onClick = { onSendRemoteCommand("cmd_dns", remote.helperTargetHost) },
                                    enabled = !remote.isBusy,
                                ) { Text("DNS") }
                                FilledTonalButton(
                                    onClick = { onSendRemoteCommand("cmd_udp", "stun.l.google.com") },
                                    enabled = !remote.isBusy,
                                ) { Text("UDP") }
                                FilledTonalButton(
                                    onClick = { onSendRemoteCommand("cmd_mtu", "1.1.1.1") },
                                    enabled = !remote.isBusy,
                                ) { Text("MTU") }
                                FilledTonalButton(
                                    onClick = { onSendRemoteCommand("cmd_wifi", "") },
                                    enabled = !remote.isBusy,
                                ) { Text("Wi-Fi") }
                            }
                        }

                        Button(
                            onClick = onStopRemote,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WhyNetColors.StopButtonBg,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Завершить сеанс")
                        }
                    }

                    if (!remote.statusMessage.isNullOrBlank()) {
                        Text(
                            text = remote.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (remote.feed.isNotEmpty()) {
            item(key = "remote_feed_header") {
                Text(
                    text = "Лента сеанса (${remote.feed.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(remote.feed, key = { it.id }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = entry.timeLabel,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (entry.body.isNotBlank()) {
                            Text(
                                text = entry.body,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        } // ENABLE_REMOTE_CODE_SESSION
    }
}
