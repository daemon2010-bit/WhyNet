package dev.whynet.app.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.whynet.app.BuildConfig
import dev.whynet.app.LiveLogEntry
import dev.whynet.core.engine.ReportFormatter
import dev.whynet.core.model.DiagnosticSession
import dev.whynet.core.model.NetworkSnapshot
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class IssueCategory(val title: String) {
    SITE_BROKEN_BUT_OK("Сайт не работает, но тест пишет «ОК»"),
    WRONG_DIAGNOSIS("Диагноз не совпадает с реальной причиной"),
    APP_GLITCH("Сбой / зависание / ошибка интерфейса"),
    OTHER("Другое / отправить полный лог"),
}

enum class ShareTarget(val label: String) {
    MAX("MAX"),
    TELEGRAM("Telegram"),
    SYSTEM_CHOOSER("Другой способ"),
}

data class IssueReportRequest(
    val category: IssueCategory,
    val userComment: String,
    val includeDiagnosticLogs: Boolean,
    val includeAppLogs: Boolean,
    val shareTarget: ShareTarget,
)

data class ExportOutcome(
    val fileName: String,
    val deliveredVia: String,
    val fallbackToChooser: Boolean,
)

object BugReportExporter {
    private val MAX_PACKAGES = listOf(
        "ru.oneme.app",
    )

    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "org.telegram.plus",
        "nekox.messenger",
        "org.forkgram.messenger",
        "tw.nekomimi.nekogram",
        "com.radolyn.ayugram",
    )

    fun isMaxInstalled(context: Context): Boolean = findSharePackage(context, ShareTarget.MAX) != null

    fun isTelegramInstalled(context: Context): Boolean = findSharePackage(context, ShareTarget.TELEGRAM) != null

    fun buildTxtContent(
        request: IssueReportRequest,
        liveSnapshot: NetworkSnapshot?,
        lastSession: DiagnosticSession?,
        liveLogs: List<LiveLogEntry>,
        recentSessions: List<DiagnosticSession>,
    ): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            appendLine("====================================================")
            appendLine("WhyNet — Отчёт о проблеме и диагностические логи (.txt)")
            appendLine("====================================================")
            appendLine("Сформирован: $now")
            appendLine("Версия WhyNet: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Категория проблемы: ${request.category.title}")
            if (request.userComment.isNotBlank()) {
                appendLine("Комментарий пользователя: ${request.userComment.trim()}")
            }
            appendLine("Приватность: отчёт обезличен (без паролей, токенов, SSID, BSSID/MAC и полного внешнего IP).")
            appendLine()

            if (liveSnapshot != null) {
                appendLine("--- ТЕКУЩЕЕ СОСТОЯНИЕ СЕТИ ---")
                appendLine("Транспорт: ${liveSnapshot.transport} | Validated=${liveSnapshot.isValidated} | CaptivePortal=${liveSnapshot.isCaptivePortal}")
                appendLine("Wi-Fi: band=${liveSnapshot.wifiBandGhz ?: "n/a"} ГГц, rssi=${liveSnapshot.wifiRssiDbm ?: "n/a"} дБм | Cellular=${liveSnapshot.cellularNetworkType ?: "n/a"}")
                appendLine("VPN=${liveSnapshot.activeExternalVpn} | Proxy=${liveSnapshot.systemProxy ?: "none"} | PrivateDNS=${liveSnapshot.privateDnsServerName ?: liveSnapshot.privateDnsActive}")
                appendLine("Шлюз=${liveSnapshot.gatewayIp ?: "n/a"} | LocalIPv4=${liveSnapshot.localIpv4 ?: "n/a"} | LocalIPv6=${if (liveSnapshot.localIpv6 != null) "present" else "none"} | CGNAT=${liveSnapshot.isCgnatLikely}")
                appendLine("Внешний IP (маска): ${maskIp(liveSnapshot.externalIp)}")
                appendLine()
            }

            if (request.includeDiagnosticLogs) {
                appendLine("--- ЛОГИ ПРОВЕРКИ ДОСТУПНОСТИ ---")
                if (lastSession != null) {
                    appendLine(ReportFormatter.toPlainTextReport(lastSession))
                } else {
                    appendLine("(Последней завершённой проверки в текущей сессии нет.)")
                }

                if (liveLogs.isNotEmpty()) {
                    appendLine("--- ПОШАГОВАЯ ЖИВАЯ ЛЕНТА ПРОВЕРКИ (${liveLogs.size} соб.) ---")
                    liveLogs.forEach { entry ->
                        appendLine("+${"%.2f".format(Locale.US, entry.elapsedMs / 1000.0)}s  ${entry.message}")
                    }
                    appendLine()
                }

                val otherHistory = recentSessions
                    .filter { it.sessionId != lastSession?.sessionId }
                    .take(3)
                if (otherHistory.isNotEmpty()) {
                    appendLine("--- ПРЕДЫДУЩИЕ ПРОВЕРКИ (КРАТКО, ДО 3) ---")
                    otherHistory.forEach { s ->
                        appendLine("• [${s.sessionId}] ${s.target.label} → ${s.verdict.headline} (${s.verdict.category}, ${s.verdict.confidence})")
                    }
                    appendLine()
                }
            }

            if (request.includeAppLogs) {
                val appLines = AppDiagnosticLog.snapshotLines()
                appendLine("--- ЖУРНАЛ РАБОТЫ ПРИЛОЖЕНИЯ (${appLines.size} соб.) ---")
                if (appLines.isEmpty()) {
                    appendLine("(Нет событий)")
                } else {
                    appLines.forEach { appendLine(it) }
                }
                appendLine()

                val logcatLines = AppDiagnosticLog.readOwnProcessLogcat(maxLines = 80)
                appendLine("--- LOGCAT ПРОЦЕССА WHYNET (ПОСЛЕДНИЕ ${logcatLines.size} СТРОК) ---")
                logcatLines.forEach { appendLine(it) }
            }
        }
    }

    fun writeAndShareTxt(
        context: Context,
        request: IssueReportRequest,
        txtContent: String,
        targetLabel: String?,
    ): ExportOutcome {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        pruneOldReports(reportsDir, keepCount = 10)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeHost = targetLabel
            ?.let(::transliterateCyrillic)
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9._-]"), "_")
            ?.replace(Regex("_+"), "_")
            ?.take(28)
            ?.trim('_')
            ?.takeIf { it.isNotBlank() }
        val fileName = if (safeHost != null) {
            "whynet_report_${safeHost}_$stamp.txt"
        } else {
            "whynet_report_$stamp.txt"
        }
        val outFile = File(reportsDir, fileName)
        outFile.writeText(txtContent, Charsets.UTF_8)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile,
        )

        val caption = buildString {
            append("Отчёт WhyNet (${BuildConfig.VERSION_NAME})")
            if (!targetLabel.isNullOrBlank()) append(" · $targetLabel")
            append("\nПричина: ${request.category.title}")
            if (request.userComment.isNotBlank()) {
                append("\nКомментарий: ${request.userComment.trim().take(200)}")
            }
        }

        val targetPkg = findSharePackage(context, request.shareTarget)
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (targetPkg != null) {
            AppDiagnosticLog.i("ReportExport", "Отправка $fileName (${outFile.length()} Б) напрямую в $targetPkg")
            runCatching {
                context.grantUriPermission(targetPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                baseIntent.setPackage(targetPkg)
                context.startActivity(baseIntent)
                return ExportOutcome(
                    fileName = fileName,
                    deliveredVia = request.shareTarget.label,
                    fallbackToChooser = false,
                )
            }.onFailure { err ->
                AppDiagnosticLog.w("ReportExport", "Прямой запуск $targetPkg не удался (${err.message}), открываем системный выбор")
                baseIntent.setPackage(null)
            }
        }

        val chooser = Intent.createChooser(baseIntent, "Отправить отчёт WhyNet (.txt)").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppDiagnosticLog.i("ReportExport", "Отправка $fileName (${outFile.length()} Б) через системный выбор")
        context.startActivity(chooser)
        return ExportOutcome(
            fileName = fileName,
            deliveredVia = ShareTarget.SYSTEM_CHOOSER.label,
            fallbackToChooser = request.shareTarget != ShareTarget.SYSTEM_CHOOSER,
        )
    }

    /**
     * Упаковывает все сохранённые отчёты Истории + текущую телеметрию + логи приложения в один .zip
     * для удобной пересылки на анализ.
     */
    fun exportAllSessionsZip(
        context: Context,
        sessions: List<DiagnosticSession>,
        liveSnapshot: NetworkSnapshot?,
    ): ExportOutcome {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "whynet_archive_$stamp.zip"
        val outFile = File(reportsDir, fileName)
        val dtFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            // 1. Общая сводка
            val summary = buildString {
                appendLine("WhyNet Archive Summary")
                appendLine("Generated: $stamp")
                appendLine("App: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Total sessions: ${sessions.size}")
                if (liveSnapshot != null) {
                    appendLine("Current transport: ${liveSnapshot.transport} | eNB=${liveSnapshot.cellularEnbId ?: "-"} | RSRP=${liveSnapshot.cellularRsrpDbm ?: "-"} | WiFi ch=${liveSnapshot.wifiChannel ?: "-"} (${liveSnapshot.wifiRssiDbm ?: "-"} dBm)")
                }
                appendLine()
                sessions.forEachIndexed { idx, s ->
                    val ts = dtFmt.format(Date(s.createdAtEpochMs))
                    appendLine("${idx + 1}. [$ts] ${s.target.label} (${s.snapshot.transport}) -> ${s.verdict.headline} [${s.verdict.ruleId}]")
                }
            }
            zos.putNextEntry(ZipEntry("00_summary.txt"))
            zos.write(summary.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Каждый полный отчёт отдельным файлом
            sessions.forEachIndexed { idx, s ->
                val ts = dtFmt.format(Date(s.createdAtEpochMs))
                val safeTarget = transliterateCyrillic(s.target.label)
                    .replace(Regex("[^a-z0-9._-]"), "_")
                    .take(24)
                    .trim('_')
                    .ifBlank { "check" }
                val entryName = "reports/${"%03d".format(idx + 1)}_${ts}_${safeTarget}.txt"
                val fullText = buildString {
                    appendLine(ReportFormatter.toPlainTextReport(s))
                    appendLine("--- EXTRA CELL/WIFI METADATA ---")
                    appendLine("eNB=${s.snapshot.cellularEnbId ?: "-"} TAC=${s.snapshot.cellularTac ?: "-"} Band=${s.snapshot.cellularBandLabel ?: "-"} RSRP=${s.snapshot.cellularRsrpDbm ?: "-"}")
                    appendLine("WiFi Ch=${s.snapshot.wifiChannel ?: "-"} Congestion=${s.snapshot.wifiChannelCongestionPct ?: "-"}% Interferers=${s.snapshot.wifiSameChannelInterferers ?: "-"} BestCh=${s.snapshot.wifiRecommendedChannel ?: "-"}")
                }
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(fullText.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // 3. Журнал приложения + logcat
            val appLog = buildString {
                appendLine("=== APP DIAGNOSTIC LOG ===")
                AppDiagnosticLog.snapshotLines().forEach { appendLine(it) }
                appendLine()
                appendLine("=== OWN LOGCAT ===")
                AppDiagnosticLog.readOwnProcessLogcat(maxLines = 150).forEach { appendLine(it) }
            }
            zos.putNextEntry(ZipEntry("99_app_logs.txt"))
            zos.write(appLog.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TEXT, "Архив отчётов WhyNet (${sessions.size} проверок)")
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(shareIntent, "Отправить архив отчётов WhyNet (.zip)").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        return ExportOutcome(fileName = fileName, deliveredVia = "ZIP", fallbackToChooser = false)
    }

    private fun findSharePackage(context: Context, target: ShareTarget): String? {
        if (target == ShareTarget.SYSTEM_CHOOSER) return null
        val candidates = when (target) {
            ShareTarget.MAX -> MAX_PACKAGES
            ShareTarget.TELEGRAM -> TELEGRAM_PACKAGES
            ShareTarget.SYSTEM_CHOOSER -> emptyList()
        }
        val pm = context.packageManager
        val probeIntent = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        val resolvedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(probeIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probeIntent, 0)
        }.mapNotNull { it.activityInfo?.packageName }.toSet()

        // 1. Точное совпадение по известным пакетам
        candidates.firstOrNull { it in resolvedPkgs }?.let { return it }

        // 2. Запасной поиск по подстроке среди установленных получателей ACTION_SEND
        return when (target) {
            ShareTarget.MAX -> resolvedPkgs.firstOrNull { it.contains("oneme", ignoreCase = true) }
            ShareTarget.TELEGRAM -> resolvedPkgs.firstOrNull {
                it.contains("telegram", ignoreCase = true) || it.contains("challegram", ignoreCase = true)
            }
            ShareTarget.SYSTEM_CHOOSER -> null
        }
    }

    private fun pruneOldReports(dir: File, keepCount: Int) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(keepCount).forEach { runCatching { it.delete() } }
    }

    private fun maskIp(ip: String?): String {
        if (ip.isNullOrBlank()) return "n/a"
        val parts = ip.split('.')
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.x.x" else ip.take(12) + "..."
    }

    private fun transliterateCyrillic(input: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya",
        )
        return buildString(input.length) {
            for (ch in input.lowercase(Locale.ROOT)) {
                append(map[ch] ?: ch)
            }
        }
    }
}
