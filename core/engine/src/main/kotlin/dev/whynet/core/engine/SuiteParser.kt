package dev.whynet.core.engine

enum class SuiteStepKind {
    NETWORK,
    CALLS,
    SITE,
    DNS,
    SNI,
    MTU,
    TRACEROUTE,
    UDP,
    WIFI,
}

enum class SuiteExpectation {
    ANY,
    OK,
    FAIL,
}

data class SuiteStep(
    val index: Int,
    val kind: SuiteStepKind,
    val target: String,
    val expectation: SuiteExpectation = SuiteExpectation.ANY,
    val note: String = "",
)

data class TestSuiteDefinition(
    val name: String,
    val defaultTag: String = "",
    val steps: List<SuiteStep>,
    val parseWarnings: List<String> = emptyList(),
)

object SuiteParser {

    val DEFAULT_SUITE_TEMPLATE: String = """
        # Сценарий пакетного тестирования WhyNet (.txt)
        # Формат строк: тип | цель | ожидание (OK/FAIL/ANY) | заметка
        # Доступные типы: network, calls, site, dns, sni, mtu, traceroute, udp, wifi
        # Можно также просто писать домен или URL в отдельной строке (по умолчанию = site).
        @name Базовый боевой набор WhyNet
        @tag auto

        network | ya.ru | ANY | Полная проверка сети (включая UDP/STUN)
        calls | ya.ru | ANY | Проверка звонков и P2P (UDP/STUN)

        # Стандартные сайты (ожидаем OK)
        site | ya.ru | OK | Стандартный РФ
        site | vk.com | OK | Стандартный РФ
        site | ozon.ru | OK | Стандартный РФ

        # Частично проблемные / чувствительные к каналу
        site | chat.qwen.ai | ANY | ИИ-сервис (жалобы в мониторах)
        site | roblox.com | ANY | Игровой сервис
        site | web.telegram.org | ANY | Telegram Web

        # Зарубежные блокируемые / фильтруемые / с геоограничением
        site | login.live.com | ANY | Проверка фильтрации SNI/DPI
        site | instagram.com | ANY | Зарубежный (DPI/SNI)
        site | youtube.com | ANY | Проверка DNS vs DoH / DPI
        site | discord.com | ANY | Зарубежный
        site | chatgpt.com | ANY | Зарубежный ИИ (Cloudflare / 403)
        site | gemini.google.com | ANY | Зарубежный ИИ (SPA / регион)
        site | https://pub.fsa.gov.ru/rds/declaration/view/20030406/common | ANY | ФСА (SPA + API)

        # Инструментальные проверки канала
        mtu | 1.1.1.1 | ANY | Поиск MTU (проверка туннеля / VPN)
        wifi | | ANY | Скан Wi-Fi эфира (2.4/5/6 ГГц)
    """.trimIndent() + "\n"

    fun parse(rawText: String, fallbackName: String = "Пользовательский сценарий"): TestSuiteDefinition {
        val cleaned = rawText.removePrefix("\uFEFF")
        var suiteName = fallbackName
        var defaultTag = ""
        val steps = mutableListOf<SuiteStep>()
        val warnings = mutableListOf<String>()

        cleaned.lines().forEachIndexed { lineIdx, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                return@forEachIndexed
            }
            if (line.startsWith("@name", ignoreCase = true)) {
                val v = line.substring(5).trim()
                if (v.isNotEmpty()) suiteName = v
                return@forEachIndexed
            }
            if (line.startsWith("@tag", ignoreCase = true)) {
                val v = line.substring(4).trim()
                if (v.isNotEmpty()) defaultTag = v
                return@forEachIndexed
            }

            val parts = if (line.contains('|')) {
                line.split('|').map { it.trim() }
            } else if (line.contains(';')) {
                line.split(';').map { it.trim() }
            } else {
                listOf(line)
            }

            if (parts.size == 1) {
                val token = parts[0]
                val singleKind = parseKind(token)
                if (singleKind != null) {
                    val defTarget = defaultTargetFor(singleKind)
                    steps += SuiteStep(
                        index = steps.size + 1,
                        kind = singleKind,
                        target = defTarget,
                    )
                } else {
                    // Простая строка с доменом или URL → считаем проверкой сайта
                    steps += SuiteStep(
                        index = steps.size + 1,
                        kind = SuiteStepKind.SITE,
                        target = token,
                    )
                }
                return@forEachIndexed
            }

            val kind = parseKind(parts[0])
            if (kind == null) {
                // Возможно, пользователь написал: example.com | OK | комментарий
                if (parts[0].contains('.') || parts[0].startsWith("http", ignoreCase = true)) {
                    val exp = parseExpectation(parts.getOrNull(1))
                    val note = parts.getOrNull(2).orEmpty()
                    steps += SuiteStep(
                        index = steps.size + 1,
                        kind = SuiteStepKind.SITE,
                        target = parts[0],
                        expectation = exp,
                        note = note,
                    )
                } else {
                    warnings += "Строка ${lineIdx + 1}: неизвестный тип теста '${parts[0]}'"
                }
                return@forEachIndexed
            }

            val rawTarget = parts.getOrNull(1).orEmpty().ifBlank { defaultTargetFor(kind) }
            val third = parts.getOrNull(2).orEmpty()
            val fourth = parts.getOrNull(3).orEmpty()

            val parsedExp = parseExpectationOrNull(third)
            val expectation = parsedExp ?: SuiteExpectation.ANY
            val note = when {
                parsedExp != null -> fourth
                fourth.isNotEmpty() -> "$third — $fourth"
                else -> third
            }

            steps += SuiteStep(
                index = steps.size + 1,
                kind = kind,
                target = rawTarget,
                expectation = expectation,
                note = note,
            )
        }

        return TestSuiteDefinition(
            name = suiteName,
            defaultTag = defaultTag,
            steps = steps,
            parseWarnings = warnings,
        )
    }

    private fun parseKind(raw: String): SuiteStepKind? = when (raw.trim().lowercase()) {
        "network", "all", "net", "сеть" -> SuiteStepKind.NETWORK
        "calls", "p2p", "звонки" -> SuiteStepKind.CALLS
        "site", "url", "web", "http", "сайт" -> SuiteStepKind.SITE
        "dns", "doh" -> SuiteStepKind.DNS
        "sni", "tls", "dpi" -> SuiteStepKind.SNI
        "mtu", "pmtu" -> SuiteStepKind.MTU
        "traceroute", "trace", "tracert" -> SuiteStepKind.TRACEROUTE
        "udp", "stun" -> SuiteStepKind.UDP
        "wifi", "wi-fi" -> SuiteStepKind.WIFI
        else -> null
    }

    private fun parseExpectation(raw: String?): SuiteExpectation =
        parseExpectationOrNull(raw) ?: SuiteExpectation.ANY

    private fun parseExpectationOrNull(raw: String?): SuiteExpectation? = when (raw?.trim()?.uppercase()) {
        "OK", "PASS", "SUCCESS" -> SuiteExpectation.OK
        "FAIL", "BLOCK", "BLOCKED", "ERROR" -> SuiteExpectation.FAIL
        "ANY", "*" -> SuiteExpectation.ANY
        else -> null
    }

    private fun defaultTargetFor(kind: SuiteStepKind): String = when (kind) {
        SuiteStepKind.NETWORK, SuiteStepKind.CALLS, SuiteStepKind.SITE -> "ya.ru"
        SuiteStepKind.DNS -> "youtube.com"
        SuiteStepKind.SNI -> "login.live.com"
        SuiteStepKind.MTU -> "1.1.1.1"
        SuiteStepKind.TRACEROUTE -> "ya.ru"
        SuiteStepKind.UDP -> "stun.l.google.com"
        SuiteStepKind.WIFI -> ""
    }
}
