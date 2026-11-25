package dev.whynet.core.engine

/**
 * Фильтрует и ранжирует домены, перехваченные локальным наблюдателем приложения,
 * отсеивая рекламные/аналитические/служебные SDK-домены.
 */
object AppDomainFilter {

    private val NOISE_SUBSTRINGS = listOf(
        "google-analytics",
        "googletagmanager",
        "googlesyndication",
        "doubleclick.net",
        "googleadservices",
        "app-measurement.com",
        "crashlytics",
        "firebase",
        "appsflyer",
        "adjust.com",
        "branch.io",
        "sentry.io",
        "flurry.com",
        "unityads",
        "applovin",
        "ironsrc",
        "vungle",
        "admob",
        "chartboost",
        "mopub",
        "inmobi",
        "metrica.yandex",
        "an.yandex.ru",
        "mc.yandex.ru",
        "ads.vk.com",
        "top-fwz1.mail.ru",
        "facebook.net",
        "graph.facebook.com",
        "connect.facebook.net",
        "amplitude.com",
        "mixpanel.com",
        "datadoghq",
        "bugsnag",
        "newrelic",
        "onesignal.com",
        "pushwoosh",
        "appevent.",
    )

    private val NOISE_EXACT = setOf(
        "clients3.google.com",
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "play.googleapis.com",
        "android.clients.google.com",
        "time.android.com",
        "time.google.com",
        "config.uca.cloud.unity3d.com",
        "pro.ip-api.com",
    )

    data class DomainCandidate(
        val host: String,
        val packetCount: Int = 1,
        val seenAfterFreeze: Boolean = false,
    )

    fun isNoiseDomain(rawHost: String): Boolean {
        val h = normalizeHost(rawHost) ?: return true
        if (h in NOISE_EXACT) return true
        return NOISE_SUBSTRINGS.any { h.contains(it) }
    }

    fun normalizeHost(raw: String): String? {
        val cleaned = raw.trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trim('.')
        if (cleaned.isBlank() || !cleaned.contains('.')) return null
        if (cleaned.any { it.isWhitespace() }) return null
        return cleaned
    }

    /**
     * Выбирает до [maxCount] наиболее важных доменов приложения.
     * Если все домены оказались в списке шума — возвращает самые активные исходные,
     * чтобы автопроверка не осталась пустой.
     */
    fun selectPrimaryDomains(
        candidates: List<DomainCandidate>,
        maxCount: Int = 4,
    ): List<String> {
        if (candidates.isEmpty() || maxCount <= 0) return emptyList()

        val merged = LinkedHashMap<String, DomainCandidate>()
        for (c in candidates) {
            val h = normalizeHost(c.host) ?: continue
            val prev = merged[h]
            if (prev == null) {
                merged[h] = DomainCandidate(h, c.packetCount.coerceAtLeast(1), c.seenAfterFreeze)
            } else {
                merged[h] = DomainCandidate(
                    host = h,
                    packetCount = prev.packetCount + c.packetCount.coerceAtLeast(1),
                    seenAfterFreeze = prev.seenAfterFreeze || c.seenAfterFreeze,
                )
            }
        }

        val comparator = compareByDescending<DomainCandidate> { it.seenAfterFreeze }
            .thenByDescending { it.packetCount }
            .thenBy { it.host.length }

        val clean = merged.values
            .filterNot { isNoiseDomain(it.host) }
            .sortedWith(comparator)
            .map { it.host }
            .take(maxCount)

        if (clean.isNotEmpty()) return clean

        return merged.values
            .sortedWith(comparator)
            .map { it.host }
            .take(maxCount)
    }
}
