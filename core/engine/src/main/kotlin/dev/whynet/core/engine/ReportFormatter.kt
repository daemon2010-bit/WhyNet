package dev.whynet.core.engine

import dev.whynet.core.model.DiagnosticSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Форматирует отчёт в человекочитаемый текст и структурированный JSON
 * с автоматическим маскированием чувствительных данных (токены, пароли, MAC, личные параметры URL).
 */
object ReportFormatter {

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    private val secretParamRegex = Regex(
        pattern = "(?i)([?&](?:token|access_token|auth|key|api_key|password|pass|pwd|secret|session|cookie|sig)=)([^&\\s]+)",
    )
    private val macRegex = Regex("(?i)\\b([0-9a-f]{2}:){5}[0-9a-f]{2}\\b")
    private val bearerRegex = Regex("(?i)(Authorization:\\s*Bearer\\s+)([^\\s]+)")

    fun sanitizeText(input: String): String = input
        .replace(secretParamRegex, "$1***")
        .replace(bearerRegex, "$1***")
        .replace(macRegex, "XX:XX:XX:XX:XX:XX")

    fun sanitizeSession(session: DiagnosticSession, redactExternalIp: Boolean = true): DiagnosticSession {
        val maskedExternalIp = if (redactExternalIp && !session.snapshot.externalIp.isNullOrBlank()) {
            maskIp(session.snapshot.externalIp!!)
        } else {
            session.snapshot.externalIp
        }
        return session.copy(
            snapshot = session.snapshot.copy(externalIp = maskedExternalIp),
            probes = session.probes.map { p ->
                p.copy(
                    summary = sanitizeText(p.summary),
                    rawDetails = p.rawDetails?.let(::sanitizeText),
                    httpPayload = p.httpPayload?.copy(
                        initialUrl = sanitizeText(p.httpPayload!!.initialUrl),
                        redirectChain = p.httpPayload!!.redirectChain.map { hop ->
                            hop.copy(urlSanitized = sanitizeText(hop.urlSanitized))
                        },
                    ),
                )
            },
        )
    }

    fun toSanitizedJson(session: DiagnosticSession, redactExternalIp: Boolean = true): String {
        val safe = sanitizeSession(session, redactExternalIp)
        return prettyJson.encodeToString(safe)
    }

    fun toPlainTextReport(session: DiagnosticSession, redactExternalIp: Boolean = true): String {
        val safe = sanitizeSession(session, redactExternalIp)
        return buildString {
            appendLine("=== WhyNet — Отчёт сетевой диагностики ===")
            appendLine("Сессия: ${safe.sessionId} | Версия: ${safe.appVersion} (правила ${safe.rulesVersion})")
            appendLine("Устройство: ${safe.deviceSummary} (Android API ${safe.androidApiLevel})")
            appendLine("Цель: ${safe.target.label} (${safe.target.hostOrUrl}:${safe.target.port})")
            appendLine()
            appendLine("--- Среда ---")
            appendLine("Сеть: ${safe.snapshot.transport} | Validated=${safe.snapshot.isValidated} | CaptivePortal=${safe.snapshot.isCaptivePortal}")
            appendLine("Активный сторонний VPN: ${safe.snapshot.activeExternalVpn} | Прокси: ${safe.snapshot.systemProxy ?: "нет"}")
            appendLine("Private DNS: ${if (safe.snapshot.privateDnsActive) safe.snapshot.privateDnsServerName ?: "авто" else "выкл"}")
            if (safe.snapshot.dnsServers.isNotEmpty()) {
                appendLine("DNS интерфейса: ${safe.snapshot.dnsServers.joinToString()}")
            }
            if (safe.snapshot.wifiRssiDbm != null) {
                appendLine("Wi-Fi: ${safe.snapshot.wifiRssiDbm} дБм, ${safe.snapshot.wifiBandGhz ?: "?"} ГГц, link ${safe.snapshot.wifiLinkSpeedMbps ?: "?"} Мбит/с")
            }
            if (safe.snapshot.externalIp != null) {
                appendLine("Внешний IP: ${safe.snapshot.externalIp} | CGNAT: ${safe.snapshot.isCgnatLikely ?: "неизвестно"}")
            }
            appendLine()
            appendLine("--- Вердикт ---")
            appendLine("[${safe.verdict.category} | ${safe.verdict.confidence} | ${safe.verdict.ruleId}]")
            appendLine(safe.verdict.headline)
            appendLine(safe.verdict.explanation)
            if (safe.verdict.confirmedFacts.isNotEmpty()) {
                appendLine("Установлено фактом:")
                safe.verdict.confirmedFacts.forEach { appendLine("  • $it") }
            }
            if (safe.verdict.hypotheses.isNotEmpty()) {
                appendLine("Вероятные причины:")
                safe.verdict.hypotheses.forEach { appendLine("  • $it") }
            }
            if (safe.verdict.unknowns.isNotEmpty()) {
                appendLine("Ограничения / неизвестно:")
                safe.verdict.unknowns.forEach { appendLine("  • $it") }
            }
            appendLine()
            appendLine("--- Этапы ---")
            safe.probes.forEach { p ->
                appendLine("[${p.stage}] ${p.status} (${p.durationMs} мс) — ${p.summary}")
                if (!p.rawDetails.isNullOrBlank()) {
                    appendLine("    Детали: ${p.rawDetails}")
                }
            }
        }
    }

    private fun maskIp(ip: String): String {
        val parts = ip.split(".")
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.x.x" else "masked-ip"
    }
}
