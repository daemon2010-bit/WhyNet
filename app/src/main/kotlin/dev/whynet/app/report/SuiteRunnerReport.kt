package dev.whynet.app.report

import android.content.Context
import android.os.Build
import dev.whynet.app.BuildConfig
import dev.whynet.core.engine.ReportFormatter
import dev.whynet.core.engine.SuiteExpectation
import dev.whynet.core.engine.SuiteParser
import dev.whynet.core.engine.SuiteStep
import dev.whynet.core.engine.TestSuiteDefinition
import dev.whynet.core.model.DiagnosticSession
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.VerdictCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SuiteStepOutcome {
    OK,
    FAIL,
    WARN,
    ERROR,
}

data class SuiteStepResult(
    val step: SuiteStep,
    val outcome: SuiteStepOutcome,
    val durationMs: Long,
    val headline: String,
    val categoryOrCode: String,
    val confidence: String = "",
    val expectationMatched: Boolean? = null,
    val recommendations: List<String> = emptyList(),
    val detailedText: String = "",
    val session: DiagnosticSession? = null,
)

data class SuiteRunRecord(
    val suite: TestSuiteDefinition,
    val environmentTag: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val wasCancelled: Boolean,
    val snapshotBefore: NetworkSnapshot?,
    val snapshotAfter: NetworkSnapshot?,
    val results: List<SuiteStepResult>,
)

object SuiteRunnerReport {

    fun buildSuiteTxtReport(record: SuiteRunRecord): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val totalSec = "%.1f".format(Locale.US, (record.finishedAtEpochMs - record.startedAtEpochMs).coerceAtLeast(0L) / 1000.0)
        val okCount = record.results.count { it.outcome == SuiteStepOutcome.OK }
        val failCount = record.results.count { it.outcome == SuiteStepOutcome.FAIL || it.outcome == SuiteStepOutcome.ERROR }
        val warnCount = record.results.count { it.outcome == SuiteStepOutcome.WARN }

        return buildString {
            appendLine("======================================================================")
            appendLine("WhyNet — ОТЧЁТ ПАКЕТНОГО ПРОГОНА СЦЕНАРИЯ (.txt)")
            appendLine("======================================================================")
            appendLine("Сценарий: ${record.suite.name}")
            appendLine("Метка условий: ${record.environmentTag.ifBlank { "(не указана)" }}")
            appendLine("Статус прогона: ${if (record.wasCancelled) "ПРЕРВАН ПОЛЬЗОВАТЕЛЕМ" else "ЗАВЕРШЁН"} (${record.results.size}/${record.suite.steps.size} тестов за ${totalSec} с)")
            appendLine("Итог: OK=$okCount | FAIL/ERR=$failCount | WARN=$warnCount")
            appendLine("Начало: ${fmt.format(Date(record.startedAtEpochMs))}")
            appendLine("Конец:  ${fmt.format(Date(record.finishedAtEpochMs))}")
            appendLine("Версия WhyNet: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Приватность: обезличен (без паролей, токенов, SSID, MAC и полного внешнего IP).")
            appendLine()

            appendLine("--- СНИМОК СЕТИ ДО ПРОГОНА ---")
            appendLine(formatSnapshot(record.snapshotBefore))
            appendLine("--- СНИМОК СЕТИ ПОСЛЕ ПРОГОНА ---")
            appendLine(formatSnapshot(record.snapshotAfter))
            if (record.snapshotBefore != null && record.snapshotAfter != null &&
                (record.snapshotBefore.transport != record.snapshotAfter.transport ||
                    record.snapshotBefore.activeExternalVpn != record.snapshotAfter.activeExternalVpn ||
                    record.snapshotBefore.gatewayIp != record.snapshotAfter.gatewayIp)
            ) {
                appendLine("!!! ВНИМАНИЕ: Параметры сети/VPN изменились во время прогона !!!")
            }
            appendLine()

            if (record.suite.parseWarnings.isNotEmpty()) {
                appendLine("--- ПРЕДУПРЕЖДЕНИЯ ПАРСЕРА СЦЕНАРИЯ ---")
                record.suite.parseWarnings.forEach { appendLine("• $it") }
                appendLine()
            }

            appendLine("--- СВОДНАЯ ТАБЛИЦА РЕЗУЛЬТАТОВ ---")
            record.results.forEach { r ->
                val expFlag = when (r.expectationMatched) {
                    true -> " [exp:OK]"
                    false -> " [exp:MISMATCH!]"
                    null -> ""
                }
                val notePart = if (r.step.note.isNotBlank()) " (${r.step.note})" else ""
                appendLine(
                    "#%02d [%s] %s%s => %s (%d ms) | %s | %s%s".format(
                        Locale.US,
                        r.step.index,
                        r.step.kind.name,
                        r.step.target.ifBlank { "-" },
                        notePart,
                        r.outcome.name,
                        r.durationMs,
                        r.categoryOrCode,
                        r.headline,
                        expFlag,
                    ),
                )
            }
            appendLine()

            appendLine("======================================================================")
            appendLine("ПОДРОБНЫЙ ПРОТОКОЛ ПО КАЖДОМУ ТЕСТУ (ФАКТЫ, ВЕРДИКТЫ, РЕКОМЕНДАЦИИ)")
            appendLine("======================================================================")
            record.results.forEach { r ->
                appendLine()
                appendLine("### Тест #%02d: [%s] %s".format(Locale.US, r.step.index, r.step.kind.name, r.step.target.ifBlank { "-" }))
                if (r.step.note.isNotBlank()) appendLine("Заметка: ${r.step.note}")
                appendLine("Ожидание: ${r.step.expectation.name} | Итог: ${r.outcome.name} | Время: ${r.durationMs} мс | Код/Категория: ${r.categoryOrCode}${if (r.confidence.isNotBlank()) " (${r.confidence})" else ""}")
                appendLine("Заголовок: ${r.headline}")
                if (r.recommendations.isNotEmpty()) {
                    appendLine("Выданные рекомендации:")
                    r.recommendations.forEach { rec -> appendLine("  - $rec") }
                }
                if (r.session != null) {
                    appendLine(ReportFormatter.toPlainTextReport(r.session))
                } else if (r.detailedText.isNotBlank()) {
                    appendLine(ReportFormatter.sanitizeText(r.detailedText))
                }
            }

            appendLine()
            appendLine("======================================================================")
            val appLines = AppDiagnosticLog.snapshotLines()
            appendLine("ЖУРНАЛ РАБОТЫ ПРИЛОЖЕНИЯ (${appLines.size} соб.)")
            appendLine("======================================================================")
            appLines.forEach { appendLine(it) }
            appendLine()
            val logcatLines = AppDiagnosticLog.readOwnProcessLogcat(maxLines = 60)
            appendLine("--- LOGCAT ПРОЦЕССА WHYNET (${logcatLines.size} строк) ---")
            logcatLines.forEach { appendLine(it) }
        }
    }

    fun exportSuiteReportTxt(
        context: Context,
        record: SuiteRunRecord,
        shareTarget: ShareTarget,
    ): ExportOutcome {
        val txt = buildSuiteTxtReport(record)
        val tagSlug = record.environmentTag
            .ifBlank { record.snapshotBefore?.transport?.name ?: "suite" }
            .lowercase(Locale.US)
        val fakeReq = IssueReportRequest(
            category = IssueCategory.OTHER,
            userComment = "Пакетный прогон: ${record.suite.name} (${record.environmentTag.ifBlank { "без метки" }})",
            includeDiagnosticLogs = true,
            includeAppLogs = true,
            shareTarget = shareTarget,
        )
        return BugReportExporter.writeAndShareTxt(
            context = context,
            request = fakeReq,
            txtContent = txt,
            targetLabel = "suite_$tagSlug",
        )
    }

    fun exportTemplateTxt(
        context: Context,
        shareTarget: ShareTarget = ShareTarget.SYSTEM_CHOOSER,
    ): ExportOutcome {
        val fakeReq = IssueReportRequest(
            category = IssueCategory.OTHER,
            userComment = "Шаблон сценария пакетного тестирования WhyNet (.txt)",
            includeDiagnosticLogs = false,
            includeAppLogs = false,
            shareTarget = shareTarget,
        )
        return BugReportExporter.writeAndShareTxt(
            context = context,
            request = fakeReq,
            txtContent = SuiteParser.DEFAULT_SUITE_TEMPLATE,
            targetLabel = "suite_template",
        )
    }

    fun evaluateExpectation(expectation: SuiteExpectation, outcome: SuiteStepOutcome): Boolean? = when (expectation) {
        SuiteExpectation.ANY -> null
        SuiteExpectation.OK -> outcome == SuiteStepOutcome.OK
        SuiteExpectation.FAIL -> outcome == SuiteStepOutcome.FAIL || outcome == SuiteStepOutcome.WARN
    }

    fun outcomeFromVerdict(category: VerdictCategory): SuiteStepOutcome = when (category) {
        VerdictCategory.HEALTHY -> SuiteStepOutcome.OK
        VerdictCategory.DIAGNOSTIC_TOOL_LIMITATION,
        VerdictCategory.UNKNOWN,
        VerdictCategory.ANTIBOT_OR_CAPTCHA,
        VerdictCategory.SERVICE_RESTRICTED_OR_AUTH_REQUIRED,
        -> SuiteStepOutcome.WARN
        else -> SuiteStepOutcome.FAIL
    }

    private fun formatSnapshot(s: NetworkSnapshot?): String {
        if (s == null) return "(нет снимка)"
        val extMask = s.externalIp?.let { ip ->
            val p = ip.split('.')
            if (p.size == 4) "${p[0]}.${p[1]}.x.x" else ip.take(12) + "..."
        } ?: "n/a"
        return buildString {
            append("Транспорт=${s.transport} | Validated=${s.isValidated} | Captive=${s.isCaptivePortal}")
            append(" | WiFi=${s.wifiBandGhz ?: "-"}GHz/${s.wifiRssiDbm ?: "-"}dBm | Cell=${s.cellularNetworkType ?: "-"}")
            append("\nVPN=${s.activeExternalVpn} | Proxy=${s.systemProxy ?: "none"} | PrivateDNS=${s.privateDnsServerName ?: s.privateDnsActive}")
            append(" | GW=${s.gatewayIp ?: "n/a"} | IPv4=${s.localIpv4 ?: "n/a"} | IPv6=${if (s.localIpv6 != null) "yes" else "no"} | CGNAT=${s.isCgnatLikely} | ExtIP=$extMask")
        }
    }
}
