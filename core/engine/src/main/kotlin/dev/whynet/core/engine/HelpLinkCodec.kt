package dev.whynet.core.engine

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class HelpPreset(
    val code: String,
    val title: String,
    val description: String,
) {
    FULL_NETWORK("network", "Вся сеть и интернет", "Проверяет Wi-Fi/сотовую связь, DNS и базовые сайты"),
    CALLS_MESSENGERS("calls", "Звонки и мессенджеры", "Проверяет Telegram, звонки UDP/STUN и сеть"),
    WIFI_ROUTER("wifi", "Домашний Wi-Fi и роутер", "Проверяет шлюз роутера, DNS, MTU и Wi-Fi эфир"),
    CUSTOM_SITES("sites", "Указанные сайты", "Проверяет конкретные сайты и сервисы из списка"),
    ;

    companion object {
        fun fromCode(code: String?): HelpPreset =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: FULL_NETWORK
    }
}

data class HelpTaskSpec(
    val preset: HelpPreset = HelpPreset.FULL_NETWORK,
    val targets: List<String> = emptyList(),
    val helperName: String = "",
    val autoStart: Boolean = true,
    val note: String = "",
)

/**
 * Кодировщик/декодировщик кликабельных ссылок-заданий `https://whynet.app/help?...`
 * (распознаются как ссылки в Telegram и MAX) с обратной совместимостью с `whynet://help?...`.
 */
object HelpLinkCodec {

    const val SCHEME = "whynet"
    const val HOST = "help"
    const val WEB_BASE = "https://daemon2010-bit.github.io/help.html"
    private val LINK_REGEX = Regex(
        """(?:https?://daemon2010-bit\.github\.io/help(?:\.html)?|https?://whynet\.app/help|whynet://help)\?[^\s]+""",
        RegexOption.IGNORE_CASE,
    )

    fun encodeUri(spec: HelpTaskSpec): String {
        val params = mutableListOf<String>()
        params += "v=1"
        params += "preset=${enc(spec.preset.code)}"
        val cleanTargets = spec.targets
            .mapNotNull { AppDomainFilter.normalizeHost(it) }
            .distinct()
            .take(10)
        if (cleanTargets.isNotEmpty()) {
            params += "targets=${enc(cleanTargets.joinToString(","))}"
        }
        val helper = spec.helperName.trim().take(32)
        if (helper.isNotEmpty()) {
            params += "from=${enc(helper)}"
        }
        params += "auto=${if (spec.autoStart) "1" else "0"}"
        val note = spec.note.trim().take(120)
        if (note.isNotEmpty()) {
            params += "note=${enc(note)}"
        }
        return "$WEB_BASE?${params.joinToString("&")}"
    }

    fun encodeShareMessage(spec: HelpTaskSpec): String {
        val link = encodeUri(spec)
        val fromPart = spec.helperName.trim().takeIf { it.isNotEmpty() }?.let { " от $it" }.orEmpty()
        return buildString {
            appendLine("Проверка сети WhyNet$fromPart (${spec.preset.title}).")
            if (spec.note.isNotBlank()) {
                appendLine(spec.note.trim())
            }
            appendLine("Нажмите на ссылку (выберите «Открыть в WhyNet») или скопируйте сообщение в WhyNet -> Помощь:")
            append(link)
        }
    }

    fun encodeTaskFileContent(spec: HelpTaskSpec): String = buildString {
        appendLine("# Файл-задание WhyNet (откройте его в приложении WhyNet)")
        appendLine("WHYNET_HELP_LINK=${encodeUri(spec)}")
        appendLine()
        val suite = toSuiteDefinition(spec)
        appendLine("@name ${suite.name}")
        appendLine("@tag help")
        suite.steps.forEach { step ->
            val line = when (step.kind) {
                SuiteStepKind.NETWORK -> "network"
                SuiteStepKind.CALLS -> "calls"
                SuiteStepKind.SITE -> "site ${step.target}"
                SuiteStepKind.DNS -> "dns ${step.target}"
                SuiteStepKind.SNI -> "sni ${step.target}"
                SuiteStepKind.MTU -> "mtu ${step.target}"
                SuiteStepKind.TRACEROUTE -> "traceroute ${step.target}"
                SuiteStepKind.UDP -> "udp"
                SuiteStepKind.WIFI -> "wifi"
            }
            appendLine(line)
        }
    }

    fun decodeFromAnyText(rawInput: String): HelpTaskSpec? {
        val trimmed = rawInput.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return null

        val match = LINK_REGEX.find(trimmed)
        if (match != null) {
            return decodeUri(match.value)
        }
        if (isSupportedHelpUrl(trimmed)) {
            return decodeUri(trimmed)
        }
        return null
    }

    fun decodeUri(uriString: String): HelpTaskSpec? {
        val trimmed = uriString.trim()
        if (!isSupportedHelpUrl(trimmed)) return null
        val query = trimmed.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return HelpTaskSpec()

        val map = mutableMapOf<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = dec(pair.substring(0, idx)).lowercase()
                val v = dec(pair.substring(idx + 1))
                map[k] = v
            }
        }

        val preset = HelpPreset.fromCode(map["preset"])
        val targets = map["targets"]
            ?.split(',', ';', ' ')
            ?.mapNotNull { AppDomainFilter.normalizeHost(it) }
            ?.distinct()
            ?.take(10)
            ?: emptyList()
        val helperName = map["from"].orEmpty().trim().take(32)
        val autoStart = map["auto"] != "0" && map["auto"]?.lowercase() != "false"
        val note = map["note"].orEmpty().trim().take(120)

        return HelpTaskSpec(
            preset = preset,
            targets = targets,
            helperName = helperName,
            autoStart = autoStart,
            note = note,
        )
    }

    fun toSuiteDefinition(spec: HelpTaskSpec): TestSuiteDefinition {
        val steps = mutableListOf<SuiteStep>()
        var idx = 1
        fun add(kind: SuiteStepKind, target: String = "") {
            steps += SuiteStep(index = idx++, kind = kind, target = target)
        }

        when (spec.preset) {
            HelpPreset.FULL_NETWORK -> {
                add(SuiteStepKind.NETWORK, "ya.ru")
                add(SuiteStepKind.SITE, "ya.ru")
                add(SuiteStepKind.SITE, "vk.com")
                add(SuiteStepKind.UDP, "stun.l.google.com")
            }
            HelpPreset.CALLS_MESSENGERS -> {
                add(SuiteStepKind.NETWORK, "ya.ru")
                add(SuiteStepKind.CALLS, "stun.l.google.com")
                add(SuiteStepKind.SITE, "web.telegram.org")
                add(SuiteStepKind.UDP, "stun.l.google.com")
            }
            HelpPreset.WIFI_ROUTER -> {
                add(SuiteStepKind.NETWORK, "ya.ru")
                add(SuiteStepKind.DNS, "ya.ru")
                add(SuiteStepKind.MTU, "1.1.1.1")
                add(SuiteStepKind.WIFI, "")
            }
            HelpPreset.CUSTOM_SITES -> {
                add(SuiteStepKind.NETWORK, "ya.ru")
            }
        }

        spec.targets
            .mapNotNull { AppDomainFilter.normalizeHost(it) }
            .distinct()
            .forEach { host ->
                if (steps.none { it.kind == SuiteStepKind.SITE && it.target.equals(host, ignoreCase = true) }) {
                    add(SuiteStepKind.SITE, host)
                }
            }

        val name = buildString {
            append(spec.preset.title)
            if (spec.helperName.isNotBlank()) append(" (от ${spec.helperName})")
        }
        return TestSuiteDefinition(
            name = name,
            defaultTag = "help",
            steps = steps,
        )
    }

    private fun isSupportedHelpUrl(raw: String): Boolean =
        raw.startsWith("$SCHEME://$HOST", ignoreCase = true) ||
            raw.startsWith("https://daemon2010-bit.github.io/help", ignoreCase = true) ||
            raw.startsWith("http://daemon2010-bit.github.io/help", ignoreCase = true) ||
            raw.startsWith("https://whynet.app/help", ignoreCase = true) ||
            raw.startsWith("http://whynet.app/help", ignoreCase = true)

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun dec(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
}
