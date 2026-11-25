package dev.whynet.app.report

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.whynet.app.BuildConfig
import dev.whynet.core.engine.HelpLinkCodec
import dev.whynet.core.engine.HelpTaskSpec
import dev.whynet.core.model.VerdictCategory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SimpleStatusLevel {
    OK,
    WARN,
    FAIL,
}

data class SimpleHelpSummary(
    val level: SimpleStatusLevel,
    val title: String,
    val explanation: String,
    val adviceSteps: List<String>,
)

object SimpleHelpFormatter {

    fun summarizeRun(record: SuiteRunRecord): SimpleHelpSummary {
        val sessions = record.results.mapNotNull { it.session }
        val failCount = record.results.count { it.outcome == SuiteStepOutcome.FAIL || it.outcome == SuiteStepOutcome.ERROR }
        val warnCount = record.results.count { it.outcome == SuiteStepOutcome.WARN }
        val categories = sessions.map { it.verdict.category }.toSet()

        if (failCount == 0 && warnCount == 0 && record.results.isNotEmpty()) {
            return SimpleHelpSummary(
                level = SimpleStatusLevel.OK,
                title = "Интернет работает хорошо",
                explanation = "Все проверенные шаги (${record.results.size}) прошли без ошибок.",
                adviceSteps = listOf(
                    "Если конкретный сайт или приложение всё равно не открывается — отправьте отчёт помощнику, чтобы проверить именно его.",
                ),
            )
        }

        if (VerdictCategory.LOCAL_LINK_DEGRADATION in categories) {
            return SimpleHelpSummary(
                level = SimpleStatusLevel.FAIL,
                title = "Проблема с Wi-Fi или роутером",
                explanation = "Телефон не может нормально связаться с роутером или сетью.",
                adviceSteps = listOf(
                    "Выключите домашний роутер из розетки на 30 секунд и включите обратно.",
                    "Попробуйте временно переключиться на мобильный интернет (выключить Wi-Fi) и проверить снова.",
                    "Отправьте отчёт помощнику — внутри все технические детали.",
                ),
            )
        }

        if (VerdictCategory.CAPTIVE_PORTAL in categories) {
            return SimpleHelpSummary(
                level = SimpleStatusLevel.WARN,
                title = "Сеть требует авторизацию",
                explanation = "Wi-Fi подключён, но нужно нажать «Войти» на странице сети или оплатить интернет.",
                adviceSteps = listOf(
                    "Откройте браузер и пройдите вход в Wi-Fi (или проверьте баланс у провайдера).",
                ),
            )
        }

        if (VerdictCategory.DNS_FAILURE_OR_SPOOFING in categories) {
            return SimpleHelpSummary(
                level = SimpleStatusLevel.FAIL,
                title = "Сбой поиска адресов сайтов",
                explanation = "Интернет в целом есть, но сеть не находит адреса нужных сайтов.",
                adviceSteps = listOf(
                    "Перезагрузите роутер или переключитесь на мобильный интернет.",
                    "Отправьте отчёт помощнику — он подскажет, как поправить настройку DNS.",
                ),
            )
        }

        if (VerdictCategory.SNI_FILTERING_OR_DPI in categories || VerdictCategory.SERVICE_RESTRICTED_OR_AUTH_REQUIRED in categories) {
            return SimpleHelpSummary(
                level = SimpleStatusLevel.WARN,
                title = "Часть сайтов ограничена провайдером",
                explanation = "Обычный интернет работает, но некоторые сайты или сервисы блокируются по пути.",
                adviceSteps = listOf(
                    "Обычные российские сайты и роутер в порядке — перезагружать ничего не нужно.",
                    "Отправьте отчёт помощнику, чтобы он увидел, какие именно сервисы ограничены.",
                ),
            )
        }

        if (VerdictCategory.LOCAL_VPN_OR_FILTER_INTERFERENCE in categories) {
            return SimpleHelpSummary(
                level = SimpleStatusLevel.WARN,
                title = "Мешает включённый VPN или фильтр",
                explanation = "На телефоне активна программа VPN/блокировщик, которая мешает соединению.",
                adviceSteps = listOf(
                    "Попробуйте временно выключить VPN на телефоне и проверить интернет ещё раз.",
                ),
            )
        }

        return SimpleHelpSummary(
            level = if (failCount > 0) SimpleStatusLevel.FAIL else SimpleStatusLevel.WARN,
            title = if (failCount > 0) "Есть проблемы со связью ($failCount из ${record.results.size})" else "Интернет работает с перебоями",
            explanation = "Часть проверок завершилась с ошибкой или задержкой.",
            adviceSteps = listOf(
                "Отправьте отчёт помощнику — он сразу увидит точную причину по шагам.",
            ),
        )
    }

    fun shareHelpLinkText(
        context: Context,
        spec: HelpTaskSpec,
        target: ShareTarget,
    ) {
        val text = HelpLinkCodec.encodeShareMessage(spec)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Проверка сети WhyNet")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pkg = resolveTargetPackage(context, target)
        if (pkg != null) {
            runCatching {
                intent.setPackage(pkg)
                context.startActivity(intent)
                return
            }.onFailure {
                intent.setPackage(null)
            }
        }
        context.startActivity(
            Intent.createChooser(intent, "Отправить ссылку-проверку WhyNet").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    fun shareHelpTaskFile(
        context: Context,
        spec: HelpTaskSpec,
        target: ShareTarget,
    ): ExportOutcome {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val fileName = "whynet_help_$stamp.txt"
        val outFile = File(dir, fileName)
        outFile.writeText(HelpLinkCodec.encodeTaskFileContent(spec), Charsets.UTF_8)

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        val caption = HelpLinkCodec.encodeShareMessage(spec)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pkg = resolveTargetPackage(context, target)
        if (pkg != null) {
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.setPackage(pkg)
                context.startActivity(intent)
                return ExportOutcome(fileName, target.label, fallbackToChooser = false)
            }.onFailure {
                intent.setPackage(null)
            }
        }
        context.startActivity(
            Intent.createChooser(intent, "Отправить задание WhyNet (${BuildConfig.VERSION_NAME})").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        return ExportOutcome(fileName, ShareTarget.SYSTEM_CHOOSER.label, fallbackToChooser = target != ShareTarget.SYSTEM_CHOOSER)
    }

    private fun resolveTargetPackage(context: Context, target: ShareTarget): String? {
        if (target == ShareTarget.SYSTEM_CHOOSER) return null
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        val pkgs = pm.queryIntentActivities(probe, 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        return when (target) {
            ShareTarget.MAX -> listOf("ru.oneme.app").firstOrNull { it in pkgs }
                ?: pkgs.firstOrNull { it.contains("oneme", ignoreCase = true) }
            ShareTarget.TELEGRAM -> listOf(
                "org.telegram.messenger",
                "org.telegram.messenger.web",
                "org.thunderdog.challegram",
                "org.telegram.plus",
                "nekox.messenger",
                "org.forkgram.messenger",
                "tw.nekomimi.nekogram",
                "com.radolyn.ayugram",
            ).firstOrNull { it in pkgs }
                ?: pkgs.firstOrNull { it.contains("telegram", ignoreCase = true) || it.contains("challegram", ignoreCase = true) }
            ShareTarget.SYSTEM_CHOOSER -> null
        }
    }
}
