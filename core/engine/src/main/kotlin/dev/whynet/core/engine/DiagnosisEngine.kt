package dev.whynet.core.engine

import dev.whynet.core.model.ActionType
import dev.whynet.core.model.ConfidenceLevel
import dev.whynet.core.model.DiagnosticTarget
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.ProbeResult
import dev.whynet.core.model.ProbeStatus
import dev.whynet.core.model.RecommendedAction
import dev.whynet.core.model.StageId
import dev.whynet.core.model.TargetType
import dev.whynet.core.model.TransportKind
import dev.whynet.core.model.Verdict
import dev.whynet.core.model.VerdictCategory

/**
 * Детерминированный движок анализа сетевых проб.
 * Работает полностью локально (без ИИ), строго разделяя факты, гипотезы и ограничения.
 */
class DiagnosisEngine {

    companion object {
        const val RULES_VERSION = "1.1.0"
        private const val GATEWAY_LOSS_BAD_PERCENT = 5.0
        private const val GATEWAY_RTT_BAD_MS = 30.0
        private const val WIFI_RSSI_POOR_DBM = -75
    }

    fun evaluate(
        target: DiagnosticTarget,
        snapshot: NetworkSnapshot,
        probes: List<ProbeResult>,
        networkChangedDuringRun: Boolean = false,
    ): Verdict {
        val byStage = probes.associateBy { it.stage }
        val baseFacts = mutableListOf<String>()
        val baseUnknowns = mutableListOf<String>()

        if (networkChangedDuringRun) {
            baseUnknowns += "Во время проверки изменилась активная сеть — часть замеров могла относиться к разным интерфейсам."
        }
        if (snapshot.activeExternalVpn) {
            baseFacts += "На телефоне активен сторонний VPN или локальный фильтр трафика (TRANSPORT_VPN)."
        }
        if (!snapshot.systemProxy.isNullOrBlank()) {
            baseFacts += "Обнаружен системный прокси: ${snapshot.systemProxy}."
        }
        if (snapshot.privateDnsActive) {
            val dnsLabel = snapshot.privateDnsServerName ?: "автоматический режим"
            baseFacts += "Включён Private DNS ($dnsLabel)."
        }

        // 0. Нет активного сетевого подключения вообще
        if (snapshot.transport == TransportKind.NONE) {
            return Verdict(
                category = VerdictCategory.LOCAL_LINK_DEGRADATION,
                confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                ruleId = "R00_NO_NETWORK",
                ruleVersion = RULES_VERSION,
                headline = "На телефоне нет активного сетевого подключения",
                explanation = "Система сообщает, что ни Wi-Fi, ни мобильная сеть сейчас не подключены.",
                confirmedFacts = baseFacts + "Активный сетевой транспорт отсутствует (NONE).",
                hypotheses = listOf("Отключены Wi-Fi и передача данных, включён режим полёта или потеряна связь с точкой доступа."),
                unknowns = baseUnknowns,
                recommendedActions = listOf(
                    RecommendedAction(
                        id = "open_wifi",
                        title = "Проверить Wi-Fi и мобильную сеть",
                        description = "Откройте системные настройки сети и убедитесь, что подключение активно.",
                        type = ActionType.OPEN_NETWORK_SETTINGS,
                    ),
                ),
                primaryFailedStage = StageId.ENVIRONMENT,
            )
        }

        // 1. Captive Portal (гостевой Wi-Fi требует авторизации)
        if (snapshot.isCaptivePortal) {
            return Verdict(
                category = VerdictCategory.CAPTIVE_PORTAL,
                confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                ruleId = "R01_CAPTIVE_PORTAL",
                ruleVersion = RULES_VERSION,
                headline = "Сеть требует авторизации (Captive Portal)",
                explanation = "Телефон подключён к Wi-Fi, но сеть перехватывает запросы до прохождения входа (страница авторизации отеля/кафе/гостевого Wi-Fi).",
                confirmedFacts = baseFacts + "Android сообщил флаг NET_CAPABILITY_CAPTIVE_PORTAL.",
                hypotheses = listOf("Доступ в интернет откроется после прохождения страницы входа в сети."),
                unknowns = baseUnknowns,
                recommendedActions = listOf(
                    RecommendedAction(
                        id = "open_wifi_portal",
                        title = "Открыть настройки Wi-Fi и пройти вход",
                        description = "Нажмите на текущую Wi-Fi сеть в настройках, чтобы открыть страницу авторизации.",
                        type = ActionType.OPEN_WIFI_SETTINGS,
                    ),
                    retryAction(),
                ),
                primaryFailedStage = StageId.ENVIRONMENT,
            )
        }

        // 2. Внутренняя ошибка диагноста или отмена пользователем до завершения всех сетевых проб
        val networkProbes = probes.filter { it.stage != StageId.ENVIRONMENT }
        if (networkProbes.isNotEmpty() && networkProbes.all { it.status == ProbeStatus.CANCELLED }) {
            return Verdict(
                category = VerdictCategory.DIAGNOSTIC_TOOL_LIMITATION,
                confidence = ConfidenceLevel.INSUFFICIENT_DATA,
                ruleId = "R02_CANCELLED",
                ruleVersion = RULES_VERSION,
                headline = "Проверка остановлена до завершения",
                explanation = "Тесты были отменены, поэтому данных для вывода недостаточно.",
                confirmedFacts = baseFacts + "Пользователь остановил проверку.",
                hypotheses = emptyList(),
                unknowns = baseUnknowns + "Сетевой путь не был проверен полностью.",
                recommendedActions = listOf(retryAction("Запустить проверку заново")),
            )
        }
        if (networkProbes.isNotEmpty() && networkProbes.all { it.status == ProbeStatus.TOOL_ERROR || it.status == ProbeStatus.PERMISSION_DENIED }) {
            return Verdict(
                category = VerdictCategory.DIAGNOSTIC_TOOL_LIMITATION,
                confidence = ConfidenceLevel.INSUFFICIENT_DATA,
                ruleId = "R02_TOOL_ERROR",
                ruleVersion = RULES_VERSION,
                headline = "Не удалось выполнить измерения на устройстве",
                explanation = "Сбой произошёл внутри самого измерителя или из-за нехватки разрешений ОС — это не доказывает неисправность сети.",
                confirmedFacts = baseFacts + networkProbes.map { "${it.stage}: ${it.status} (${it.errorCode ?: it.summary})" },
                hypotheses = emptyList(),
                unknowns = baseUnknowns + "Реальное состояние сети не проверено.",
                recommendedActions = listOf(retryAction("Повторить проверку")),
            )
        }

        // 3. Проверка локального участка (шлюз + радиосигнал, кейс T34)
        val localProbe = byStage[StageId.LOCAL_LINK]
        val gwMetrics = localProbe?.gatewayExternal
        if (gwMetrics != null && gwMetrics.gatewaySent >= 3) {
            val gwLoss = gwMetrics.gatewayLossPercent
            val gwP95 = gwMetrics.gatewayP95Ms
            val wifiRssi = snapshot.wifiRssiDbm
            val poorWifi = snapshot.transport == TransportKind.WIFI &&
                wifiRssi != null && wifiRssi <= WIFI_RSSI_POOR_DBM

            if (gwLoss >= GATEWAY_LOSS_BAD_PERCENT || (gwP95 != null && gwP95 >= GATEWAY_RTT_BAD_MS && gwMetrics.externalLossPercent >= GATEWAY_LOSS_BAD_PERCENT)) {
                val strongWifiButCongested = snapshot.transport == TransportKind.WIFI &&
                    !poorWifi &&
                    ((snapshot.wifiChannelCongestionPct ?: 0) >= 35 || (snapshot.wifiSameChannelInterferers ?: 0) >= 2)
                val curCh = snapshot.wifiChannel
                val bestCh = snapshot.wifiRecommendedChannel
                val congPct = snapshot.wifiChannelCongestionPct
                val interfCount = snapshot.wifiSameChannelInterferers

                val facts = baseFacts + buildList {
                    add("Потери до локального шлюза (${gwMetrics.gatewayIp ?: "роутер"}): ${"%.1f".format(gwLoss)}% (${gwMetrics.gatewayReceived}/${gwMetrics.gatewaySent}).")
                    if (gwMetrics.externalSent > 0) {
                        add("Потери до внешнего узла: ${"%.1f".format(gwMetrics.externalLossPercent)}% (${gwMetrics.externalReceived}/${gwMetrics.externalSent}).")
                    }
                    if (gwP95 != null) add("Задержка P95 до шлюза: ${"%.0f".format(gwP95)} мс.")
                    if (snapshot.wifiRssiDbm != null) {
                        val band = snapshot.wifiBandGhz?.let { ", $it ГГц" } ?: ""
                        add("Сигнал Wi-Fi: ${snapshot.wifiRssiDbm} дБм$band.")
                    }
                    if (strongWifiButCongested) {
                        add("Канал ${curCh ?: "?"} перегружен другими точками доступа: загрузка ${congPct ?: "?"}% (пересечений: ${interfCount ?: 0}).")
                    }
                }
                val headline = if (strongWifiButCongested) {
                    "Потери на Wi-Fi из-за перегрузки канала"
                } else {
                    "Проблема на локальном участке (Wi-Fi / роутер)"
                }
                val explanation = if (strongWifiButCongested) {
                    val recPart = if (bestCh != null && bestCh != curCh) " Рекомендуется переключить точку доступа на свободный канал $bestCh или диапазон 5 ГГц." else " Рекомендуется перейти на диапазон 5 ГГц."
                    "Уровень сигнала хороший (${snapshot.wifiRssiDbm} дБм), но канал ${curCh ?: "?"} перегружен сторонними точками доступа, что вызывает потери пакетов.$recPart"
                } else {
                    "Потери или сильные задержки видны уже на пути от телефона до вашего роутера, ещё до выхода к провайдеру."
                }
                return Verdict(
                    category = VerdictCategory.LOCAL_LINK_DEGRADATION,
                    confidence = if (strongWifiButCongested) ConfidenceLevel.CONFIRMED_BY_EXPERIMENT else ConfidenceLevel.PROBABLE,
                    ruleId = if (strongWifiButCongested) "R03_WIFI_CHANNEL_INTERFERENCE" else "R03_LOCAL_GATEWAY_LOSS",
                    ruleVersion = RULES_VERSION,
                    headline = headline,
                    explanation = explanation,
                    confirmedFacts = facts,
                    hypotheses = buildList {
                        if (poorWifi) add("Слабый уровень сигнала Wi-Fi (${snapshot.wifiRssiDbm} дБм).")
                        if (strongWifiButCongested) {
                            add("Сильные помехи от соседних Wi-Fi роутеров на канале ${curCh ?: "?"} (загрузка ${congPct ?: "?"}%).")
                        } else {
                            add("Перегруженный Wi-Fi канал (помехи соседей на 2.4/5 ГГц) или сбой роутера.")
                        }
                    },
                    unknowns = baseUnknowns + "Поведение сети при подключении рядом с роутером или через мобильную сеть не измерено.",
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "switch_wifi_band",
                            title = if (strongWifiButCongested && bestCh != null && bestCh != curCh) {
                                "Переключить роутер на свободный канал $bestCh или на 5 ГГц"
                            } else {
                                "Проверить Wi-Fi диапазон (5/6 ГГц) или подойти ближе к роутеру"
                            },
                            description = if (strongWifiButCongested) {
                                "Откройте вкладку Wi-Fi -> Эфир, чтобы увидеть, какие соседние роутеры забивают канал и где они находятся на карте."
                            } else {
                                "Попробуйте переключиться на 5 ГГц или сравнить работу на мобильном интернете."
                            },
                            type = ActionType.OPEN_WIFI_SETTINGS,
                        ),
                        retryAction(),
                    ),
                    primaryFailedStage = StageId.LOCAL_LINK,
                )
            }
        }

        // 4. DNS-сбой или подмена (системный DNS не работает/подменяет, а DoH работает)
        val dnsProbe = byStage[StageId.DNS]
        val dns = dnsProbe?.dnsCompare
        if (dns != null) {
            val systemEmpty = dns.systemIpv4.isEmpty() && dns.systemIpv6.isEmpty()
            val dohHasIp = dns.dohIpv4.isNotEmpty() || dns.dohIpv6.isNotEmpty()
            if ((systemEmpty || dns.isSuspiciousSystemIp) && dohHasIp) {
                val facts = baseFacts + buildList {
                    if (dns.isSuspiciousSystemIp) {
                        add("Системный DNS вернул подозрительный/заглушечный адрес: ${(dns.systemIpv4 + dns.systemIpv6).joinToString()}.")
                    } else {
                        add("Системный DNS не смог разрешить имя ${dns.hostname} (${dns.systemError ?: "нет ответа"}).")
                    }
                    add("Контрольный DoH (${dns.dohProvider}) вернул адреса: ${(dns.dohIpv4 + dns.dohIpv6).take(3).joinToString()}.")
                }
                val stuckCorpDns = snapshot.dnsServers.any { it.startsWith("10.") || it.startsWith("127.") }
                return Verdict(
                    category = VerdictCategory.DNS_FAILURE_OR_SPOOFING,
                    confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                    ruleId = "R04_DNS_MISMATCH_OR_FAIL",
                    ruleVersion = RULES_VERSION,
                    headline = "Сбой или подмена системного DNS",
                    explanation = "Телефон не получает корректный адрес сайта через текущий DNS, но через независимый шифрованный DNS (${dns.dohProvider}) адрес находится.",
                    confirmedFacts = facts,
                    hypotheses = buildList {
                        if (stuckCorpDns) add("Обнаружены локальные/корпоративные DNS-серверы (${snapshot.dnsServers.joinToString()}), которые могут не отвечать в текущей сети.")
                        if (snapshot.privateDnsActive) add("Указанный Private DNS (${snapshot.privateDnsServerName ?: "авто"}) фильтрует домен или временно не отвечает.")
                        add("Нестабильный DNS провайдера/роутера либо DNS-фильтрация.")
                    },
                    unknowns = baseUnknowns + "На Android без root невозможно прочитать системные таблицы маршрутизации других приложений.",
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "open_private_dns",
                            title = "Проверить или сменить Private DNS",
                            description = "Откройте настройки Private DNS: попробуйте указать надёжный резолвер (например, dns.google или one.one.one.one) или временно переключить режим.",
                            type = ActionType.OPEN_PRIVATE_DNS_SETTINGS,
                            reversibleNote = "В любой момент можно вернуть «Автоматически».",
                        ),
                        retryAction(),
                    ),
                    primaryFailedStage = StageId.DNS,
                )
            }
            if (systemEmpty && dns.dohControlReachable && !dohHasIp) {
                return Verdict(
                    category = VerdictCategory.SERVER_OR_PAYLOAD_FAILURE,
                    confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                    ruleId = "R04_DNS_NXDOMAIN",
                    ruleVersion = RULES_VERSION,
                    headline = "Домен не существует (NXDOMAIN)",
                    explanation = "Ни системный DNS, ни независимый шифрованный DNS (${dns.dohProvider}) не нашли записей для домена ${dns.hostname}. Вероятно, домен не существует, устарел или в адресе есть опечатка.",
                    confirmedFacts = baseFacts + listOf(
                        "Системный DNS не нашёл записей для ${dns.hostname} (${dns.systemError ?: "пустой ответ"}).",
                        "Контрольный DoH (${dns.dohProvider}) доступен, но также не вернул IP-адресов (NXDOMAIN / NODATA).",
                    ),
                    hypotheses = listOf(
                        "Домен отключён владельцем сервиса, устарел в коде приложения или введён с опечаткой.",
                    ),
                    unknowns = baseUnknowns,
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "check_domain_typo",
                            title = "Проверить адрес домена",
                            description = "Убедитесь, что имя ${dns.hostname} указано без опечатки; если это домен приложения, он может быть устаревшим.",
                            type = ActionType.MANUAL_STEP,
                        ),
                    ),
                    primaryFailedStage = StageId.DNS,
                )
            }
            if (systemEmpty && !dns.dohControlReachable) {
                return Verdict(
                    category = VerdictCategory.UNKNOWN,
                    confidence = ConfidenceLevel.INSUFFICIENT_DATA,
                    ruleId = "R04_BOTH_DNS_FAIL",
                    ruleVersion = RULES_VERSION,
                    headline = "Нет ответа ни от системного, ни от контрольного DNS",
                    explanation = "Не удалось получить адрес домена ${dns.hostname} и одновременно не ответил контрольный DoH-сервер.",
                    confirmedFacts = baseFacts + listOf(
                        "Системный DNS: ${dns.systemError ?: "нет ответа"}.",
                        "Контрольный DoH (${dns.dohProvider}): ${dns.dohError ?: "недоступен"}.",
                    ),
                    hypotheses = listOf("Общее отсутствие выхода в интернет за пределами роутера или блокировка всех внешних соединений."),
                    unknowns = baseUnknowns + "Контрольный DNS недоступен, поэтому отделить сбой DNS от полного обрыва канала нельзя.",
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "compare_network",
                            title = "Проверить на другой сети (Wi-Fi / мобильная)",
                            description = "Переключите тип сети и повторите проверку для сравнения.",
                            type = ActionType.SWITCH_NETWORK_COMPARE,
                        ),
                        retryAction(),
                    ),
                    primaryFailedStage = StageId.DNS,
                )
            }
        }

        // 5. Раздельный сбой IPv4 vs IPv6 (одно семейство живо, другое сломано)
        val tcpProbe = byStage[StageId.TCP]
        val tcp = tcpProbe?.tcpDualStack
        if (tcp != null) {
            val v4Ok = tcp.ipv4Ok == true
            val v6Ok = tcp.ipv6Ok == true
            val v4Fail = tcp.ipv4Ok == false
            val v6Fail = tcp.ipv6Ok == false

            if (!v4Ok && !v6Ok && (v4Fail || v6Fail)) {
                val hasLocalProxyOrVpn = snapshot.activeExternalVpn || !snapshot.systemProxy.isNullOrBlank()
                return Verdict(
                    category = if (hasLocalProxyOrVpn) VerdictCategory.LOCAL_VPN_OR_FILTER_INTERFERENCE else VerdictCategory.UNKNOWN,
                    confidence = ConfidenceLevel.POSSIBLE,
                    ruleId = "R05_TCP_UNREACHABLE",
                    ruleVersion = RULES_VERSION,
                    headline = "Нет TCP-подключения к ${tcp.targetHost}:${tcp.port}",
                    explanation = "Адрес сайта определился, но установить базовое соединение на порт ${tcp.port} не удалось.",
                    confirmedFacts = baseFacts + buildList {
                        if (tcp.ipv4Address != null) add("TCP IPv4 (${tcp.ipv4Address}:${tcp.port}): ${tcp.ipv4Error ?: "сбой"}.")
                        if (tcp.ipv6Address != null) add("TCP IPv6 (${tcp.ipv6Address}:${tcp.port}): ${tcp.ipv6Error ?: "сбой"}.")
                    },
                    hypotheses = buildList {
                        if (snapshot.activeExternalVpn) add("Активный VPN или локальный фильтр блокирует подключение.")
                        if (!snapshot.systemProxy.isNullOrBlank()) add("Нерабочий системный прокси (${snapshot.systemProxy}).")
                        add("Блокировка по IP/порту на пути, сбой маршрута провайдера или сервер временно недоступен.")
                    },
                    unknowns = baseUnknowns + "Один только TCP-таймаут без сравнения с другой сетью не доказывает вину провайдера.",
                    recommendedActions = buildList {
                        if (snapshot.activeExternalVpn) {
                            add(
                                RecommendedAction(
                                    id = "check_vpn",
                                    title = "Временно приостановить VPN / фильтр",
                                    description = "Отключите активный VPN или блокировщик на 1 минуту и повторите тест.",
                                    type = ActionType.OPEN_VPN_SETTINGS,
                                    reversibleNote = "Не забудьте включить защиту обратно после проверки.",
                                ),
                            )
                        }
                        add(
                            RecommendedAction(
                                id = "compare_other_net",
                                title = "Сравнить через другую сеть",
                                description = "Проверьте тот же адрес через мобильную сеть или другой Wi-Fi.",
                                type = ActionType.SWITCH_NETWORK_COMPARE,
                            ),
                        )
                        add(retryAction())
                    },
                    primaryFailedStage = StageId.TCP,
                )
            }
        }

        // 6. Двойной SNI / TLS-тест (ключевая методика из кейсов login.live.com, AdGuard и cosmik.ru)
        val tlsProbe = byStage[StageId.TLS_SNI]
        val tls = tlsProbe?.dualSniTls
        if (tls != null && !tls.targetSniOk) {
            if (tls.neutralSniTested && tls.neutralSniOk == true) {
                // Целевой SNI рвётся, нейтральный SNI на ТОМ ЖЕ IP прошёл!
                if (snapshot.activeExternalVpn) {
                    return Verdict(
                        category = VerdictCategory.LOCAL_VPN_OR_FILTER_INTERFERENCE,
                        confidence = ConfidenceLevel.PROBABLE,
                        ruleId = "R06_SNI_SPLIT_WITH_ACTIVE_VPN",
                        ruleVersion = RULES_VERSION,
                        headline = "Соединение рвётся по имени сайта (активен VPN / фильтр)",
                        explanation = "Порт ${tls.port} на сервере ${tls.ipAddress} открыт, но TLS с именем ${tls.targetSni} обрывается, тогда как контрольный TLS с нейтральным именем (${tls.neutralSni}) на тот же IP проходит. При этом на телефоне активен VPN или локальный фильтр.",
                        confirmedFacts = baseFacts + listOf(
                            "TCP к ${tls.ipAddress}:${tls.port} установлен.",
                            "TLS с целевым SNI (${tls.targetSni}): сбой (${tls.targetSniError ?: "обрыв"}).",
                            "TLS с нейтральным SNI (${tls.neutralSni}) к тому же IP: успешно (${tls.neutralSniRttMs ?: 0} мс).",
                        ),
                        hypotheses = listOf(
                            "Локальный фильтр (например, AdGuard) или активный VPN перехватывает и рвёт TLS для этого домена.",
                            "Также возможна внешняя фильтрация по SNI на маршруте выбранного VPN.",
                        ),
                        unknowns = baseUnknowns + "Без контрольной проверки с приостановленным VPN/фильтром нельзя на 100% разделить локальный фильтр и внешний маршрут.",
                        recommendedActions = listOf(
                            RecommendedAction(
                                id = "pause_vpn_filter",
                                title = "Временно приостановить VPN / AdGuard и перепроверить",
                                description = "Если после паузы тест пройдёт — причина в правиле фильтрации или маршруте VPN. Проверьте журнал блокировщика.",
                                type = ActionType.OPEN_VPN_SETTINGS,
                                reversibleNote = "После проверки верните защиту в исходное состояние.",
                            ),
                            retryAction(),
                        ),
                        primaryFailedStage = StageId.TLS_SNI,
                    )
                }

                return Verdict(
                    category = VerdictCategory.SNI_FILTERING_OR_DPI,
                    confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                    ruleId = "R06_SNI_FILTERING_DETECTED",
                    ruleVersion = RULES_VERSION,
                    headline = "Обнаружена фильтрация соединения по имени сайта (SNI)",
                    explanation = "Сервер ${tls.ipAddress}:${tls.port} принимает TCP и отвечает на TLS с нейтральным именем (${tls.neutralSni}), но соединение с целевым именем ${tls.targetSni} обрывается на текущем сетевом пути.",
                    confirmedFacts = baseFacts + listOf(
                        "TCP к ${tls.ipAddress}:${tls.port} установлен.",
                        "TLS с целевым SNI (${tls.targetSni}): сбой (${tls.targetSniError ?: "обрыв"}).",
                        "Контрольный TLS с нейтральным SNI (${tls.neutralSni}) на тот же IP: успешно (${tls.neutralSniRttMs ?: 0} мс).",
                    ),
                    hypotheses = listOf(
                        "Вмешательство по SNI (DPI/ТСПУ) на сетевом пути текущего провайдера или роутера.",
                        "Отдельное ограничение на стороне промежуточного сетевого оборудования.",
                    ),
                    unknowns = baseUnknowns + "Смена только DNS при фильтрации по SNI обычно не решает проблему; точное место фильтрации без сравнения сетей не атрибутируется.",
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "compare_mobile_wifi",
                            title = "Сравнить через другую сеть (Wi-Fi ↔ мобильная)",
                            description = "Переключите сеть и нажмите «Проверить снова», чтобы подтвердить зависимость от текущего провайдера.",
                            type = ActionType.SWITCH_NETWORK_COMPARE,
                        ),
                        retryAction(),
                    ),
                    primaryFailedStage = StageId.TLS_SNI,
                )
            }

            if (tls.neutralSniTested && tls.neutralSniOk == false) {
                // Оба SNI упали при живом TCP (кейс cosmik.ru!)
                return Verdict(
                    category = VerdictCategory.SERVER_OR_PAYLOAD_FAILURE,
                    confidence = ConfidenceLevel.PROBABLE,
                    ruleId = "R06_BOTH_SNI_FAILED_SERVER_ISSUE",
                    ruleVersion = RULES_VERSION,
                    headline = "Вероятный сбой на стороне целевого сервера",
                    explanation = "DNS и TCP-подключение к ${tls.ipAddress}:${tls.port} проходят, но TLS-рукопожатие обрывается одинаково и для целевого имени, и для контрольного имени.",
                    confirmedFacts = baseFacts + listOf(
                        "TCP к ${tls.ipAddress}:${tls.port} установлен.",
                        "TLS с целевым SNI (${tls.targetSni}): сбой (${tls.targetSniError ?: "ошибка"}).",
                        "TLS с нейтральным SNI (${tls.neutralSni}): тоже сбой (${tls.neutralSniError ?: "ошибка"}).",
                    ),
                    hypotheses = listOf(
                        "Авария или неверная настройка веб-сервера / хостинга (как в сценарии сброса TLS сервером).",
                        "Реже — полная блокировка HTTPS на данном IP/порту.",
                    ),
                    unknowns = baseUnknowns + "Для 100% подтверждения вины сервера полезно повторить проверку через другую сеть.",
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "compare_other_network_srv",
                            title = "Перепроверить через другую сеть",
                            description = "Если ошибка повторится и на мобильной сети, проблема точно на стороне сайта.",
                            type = ActionType.SWITCH_NETWORK_COMPARE,
                        ),
                        retryAction(),
                    ),
                    primaryFailedStage = StageId.TLS_SNI,
                )
            }
        }

        // 7. HTTP, цепочка редиректов, антибот и Payload Range 0-1023
        val httpProbe = byStage[StageId.HTTP_PAYLOAD]
        val http = httpProbe?.httpPayload
        if (http != null) {
            // 7a. Сбой на дочернем домене цепочки редиректов (кейс login.microsoftonline.com -> login.live.com)
            if (!http.failedRedirectHost.isNullOrBlank()) {
                return Verdict(
                    category = VerdictCategory.REDIRECT_CHAIN_BLOCKED,
                    confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                    ruleId = "R07_REDIRECT_CHAIN_BROKEN",
                    ruleVersion = RULES_VERSION,
                    headline = "Основной сайт отвечает, но обрывается переход на ${http.failedRedirectHost}",
                    explanation = "Начальный адрес ответил перенаправлением, однако загрузка следующего узла (${http.failedRedirectHost}) завершилась ошибкой.",
                    confirmedFacts = baseFacts + buildList {
                        http.redirectChain.forEach { hop ->
                            add("Переход ${hop.host} → HTTP ${hop.statusCode} (${hop.rttMs} мс).")
                        }
                        add("Сбой на узле ${http.failedRedirectHost}: ${http.failedRedirectError ?: "нет ответа"}.")
                    },
                    hypotheses = listOf(
                        "Заблокирован или недоступен вспомогательный домен авторизации/CDN (${http.failedRedirectHost}), хотя главный домен открывается.",
                    ),
                    unknowns = baseUnknowns,
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "check_redirect_host",
                            title = "Проверить проблемный домен отдельно",
                            description = "Запустите отдельную проверку для ${http.failedRedirectHost}, чтобы определить точную причину (DNS/SNI/сервер).",
                            type = ActionType.MANUAL_STEP,
                        ),
                        retryAction(),
                    ),
                    primaryFailedStage = StageId.HTTP_PAYLOAD,
                )
            }

            // 7b. Антибот / капча / 429 (кейс Google /sorry/ и X-Yandex-Captcha)
            if (http.captchaOrAntibotDetected || http.statusCode == 429) {
                return Verdict(
                    category = VerdictCategory.ANTIBOT_OR_CAPTCHA,
                    confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                    ruleId = "R07_ANTIBOT_OR_CAPTCHA",
                    ruleVersion = RULES_VERSION,
                    headline = "Сервис включил антибот-проверку или ограничил запросы",
                    explanation = "Сетевое соединение до сайта установлено, но сам сервис вернул капчу, страницу подозрительного трафика или код ${http.statusCode ?: "ограничения"}.",
                    confirmedFacts = baseFacts + buildList {
                        if (http.statusCode != null) add("HTTP-код ответа: ${http.statusCode}.")
                        if (http.antibotSignals.isNotEmpty()) add("Признаки: ${http.antibotSignals.joinToString()}.")
                        if (snapshot.isCgnatLikely == true) add("Сеть использует общий внешний IP (CGNAT/NAT).")
                    },
                    hypotheses = listOf(
                        "Общий внешний IP (гостевой Wi-Fi, CGNAT или публичный VPN) получил временное ограничение со стороны сервиса.",
                        "Часть запросов могла идти через другой маршрут/прокси.",
                    ),
                    unknowns = baseUnknowns + "Чтобы не усилить блокировку, частые повторные запросы остановлены.",
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "switch_ip_antibot",
                            title = "Сменить сеть или временно отключить общий VPN",
                            description = "Переключитесь с гостевого Wi-Fi / публичного VPN на другую сеть и подождите 1–2 минуты.",
                            type = ActionType.SWITCH_NETWORK_COMPARE,
                        ),
                    ),
                    primaryFailedStage = StageId.HTTP_PAYLOAD,
                )
            }

            // 7c. Заголовки 200/206 есть, но реальная отдача тела Range 0-1023 рвётся (кейс Яндекс.Диск MDS)
            if (http.rangeTested && http.rangeOk == false) {
                val reqIds = http.diagnosticHeaders.entries.joinToString { "${it.key}=${it.value}" }
                return Verdict(
                    category = VerdictCategory.SERVER_OR_PAYLOAD_FAILURE,
                    confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                    ruleId = "R07_PAYLOAD_RANGE_ABORTED",
                    ruleVersion = RULES_VERSION,
                    headline = "Сайт отвечает заголовками, но обрывает передачу данных",
                    explanation = "Сервер принял запрос (HTTP ${http.statusCode ?: "?"}), однако при попытке прочитать первый килобайт содержимого соединение оборвалось (${http.rangeError ?: "EOF / close_notify"}).",
                    confirmedFacts = baseFacts + buildList {
                        add("Заголовки получены: HTTP ${http.statusCode ?: "?"} (TTFB ${http.ttfbMs ?: 0} мс).")
                        add("Тест передачи тела (Range 0-1023): прочитано ${http.rangeBytesRead} байт, ошибка: ${http.rangeError ?: "обрыв"}.")
                        if (reqIds.isNotBlank()) add("Диагностические заголовки ответа: $reqIds.")
                    },
                    hypotheses = listOf(
                        "Сбой на стороне хранилища/CDN целевого сервиса.",
                        "Агрессивный шлюз гостевой сети или DPI обрывает передачу после начала ответа.",
                    ),
                    unknowns = baseUnknowns + "Повтор через другую сеть позволит точно отделить сбой сервера от шлюза текущей сети.",
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "compare_payload_other_net",
                            title = "Проверить через другую сеть и сохранить отчёт",
                            description = "Если на другой сети тело тоже рвётся — это авария на стороне сервиса (пригодится сырой отчёт для поддержки).",
                            type = ActionType.SWITCH_NETWORK_COMPARE,
                        ),
                        retryAction(),
                    ),
                    primaryFailedStage = StageId.HTTP_PAYLOAD,
                )
            }

            // 7c-bis. Семантическое ограничение сервиса на HTTP 200 (кейс В: региональный отказ / страница недоступности)
            if (http.semanticIssueDetected) {
                return Verdict(
                    category = VerdictCategory.SERVICE_RESTRICTED_OR_AUTH_REQUIRED,
                    confidence = ConfidenceLevel.PROBABLE,
                    ruleId = "R07_SERVICE_RESTRICTED",
                    ruleVersion = RULES_VERSION,
                    headline = "Сеть работает, но сам сервис ограничил доступ",
                    explanation = "DNS, TCP, TLS и HTTP-ответ прошли успешно (HTTP ${http.statusCode ?: "?"}), однако в ответе обнаружены признаки ограничения доступа или региональной недоступности на стороне самого сервиса.",
                    confirmedFacts = baseFacts + buildList {
                        add("Сетевой путь до ${http.finalHost ?: target.hostOrUrl} исправен (HTTP ${http.statusCode ?: "?"}, TTFB ${http.ttfbMs ?: 0} мс).")
                        if (http.semanticSignals.isNotEmpty()) {
                            add("Признаки в ответе: ${http.semanticSignals.joinToString()}.")
                        }
                    },
                    hypotheses = listOf(
                        "Сервис ограничивает доступ для текущего региона/IP-диапазона или требует отдельного входа.",
                    ),
                    unknowns = baseUnknowns + "Семантические признаки зависят от актуальной вёрстки сайта и не заменяют проверку авторизованного сценария.",
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "compare_region_or_net",
                            title = "Сравнить через другую сеть или регион",
                            description = "Сетевой транспорт исправен; смена DNS не снимет региональное ограничение самого сервиса.",
                            type = ActionType.SWITCH_NETWORK_COMPARE,
                        ),
                    ),
                    primaryFailedStage = StageId.HTTP_PAYLOAD,
                )
            }

            // 7d. 403 / 5xx — не считаем функциональным успехом!
            val code = http.statusCode
            if (code != null && (code == 403 || code >= 500)) {
                return Verdict(
                    category = VerdictCategory.SERVER_OR_PAYLOAD_FAILURE,
                    confidence = ConfidenceLevel.PROBABLE,
                    ruleId = "R07_HTTP_ERROR_STATUS",
                    ruleVersion = RULES_VERSION,
                    headline = if (code >= 500) "Ошибка на стороне сервера (HTTP $code)" else "Сервер отказал в доступе (HTTP 403)",
                    explanation = if (code >= 500) {
                        "Сеть, DNS и TLS работают, но сам веб-сервер вернул внутреннюю ошибку $code."
                    } else {
                        "Соединение с сайтом установилось, но сервер вернул отказ HTTP 403. Сам по себе код 403 не доказывает региональную блокировку без проверки содержимого."
                    },
                    confirmedFacts = baseFacts + listOf(
                        "DNS, TCP и TLS пройдены.",
                        "Получен HTTP-код $code (TTFB ${http.ttfbMs ?: 0} мс).",
                    ),
                    hypotheses = if (code >= 500) {
                        listOf("Временная авария или перегрузка на стороне целевого сервиса.")
                    } else {
                        listOf("Региональное ограничение, WAF-защита сайта или требование авторизации.")
                    },
                    unknowns = baseUnknowns,
                    recommendedActions = listOf(retryAction("Проверить снова чуть позже")),
                    primaryFailedStage = StageId.HTTP_PAYLOAD,
                )
            }

            // 7e. HTTP 400/401/404/405 на корневом GET / у API-доменов означает, что сеть, DNS и TLS до сервера исправны
            if (code != null && code in setOf(400, 401, 404, 405)) {
                return Verdict(
                    category = VerdictCategory.HEALTHY,
                    confidence = ConfidenceLevel.PROBABLE,
                    ruleId = "R07_HTTP_API_REACHABLE",
                    ruleVersion = RULES_VERSION,
                    headline = "Сервер доступен по сети (HTTP $code на корень)",
                    explanation = "DNS, TCP и TLS прошли успешно, а сам сервер ответил кодом HTTP $code на тестовый GET-запрос к корню. Для API-серверов приложений это нормальное поведение.",
                    confirmedFacts = baseFacts + listOf(
                        "DNS, TCP и TLS до ${http.finalHost ?: target.hostOrUrl} пройдены.",
                        "Сервер ответил HTTP $code (TTFB ${http.ttfbMs ?: 0} мс).",
                    ),
                    hypotheses = listOf("Это служебный или API-домен, ожидающий конкретный путь или метод POST."),
                    unknowns = baseUnknowns,
                    recommendedActions = emptyList(),
                )
            }
        }

        // 8. UDP / STUN недоступен при рабочем TCP (кейс RustDesk / звонки)
        val udpProbe = byStage[StageId.UDP_STUN]
        val udp = udpProbe?.udpStun
        if (udp != null && !udp.udpReachable && (target.type == TargetType.P2P_CALLS || target.type == TargetType.NETWORK_HEALTH)) {
            return Verdict(
                category = VerdictCategory.UDP_BLOCKED_OR_NAT_RESTRICTED,
                confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                ruleId = "R08_UDP_STUN_BLOCKED",
                ruleVersion = RULES_VERSION,
                headline = "Сайты (TCP) работают, но заблокирован UDP-трафик (звонки / P2P)",
                explanation = "Обычные сайты открываются, но тестовый UDP/STUN-запрос к ${udp.stunServer} не прошёл. Из-за этого могут не работать голосовые/видеозвонки, игры и удалённый рабочий стол (RustDesk/WebRTC).",
                confirmedFacts = baseFacts + listOf(
                    "UDP/STUN (${udp.stunServer}): нет ответа (${udp.error ?: "тайм-аут"}).",
                ),
                hypotheses = listOf(
                    "Ограничения корпоративного/гостевого Wi-Fi, жёсткий NAT или фильтрация UDP провайдером.",
                    "Активный VPN/прокси не пропускает UDP-пакеты.",
                ),
                unknowns = baseUnknowns,
                recommendedActions = listOf(
                    RecommendedAction(
                        id = "compare_udp_net",
                        title = "Проверить UDP на другой сети",
                        description = "Попробуйте переключиться между Wi-Fi и мобильной сетью и повторить тест звонков/P2P.",
                        type = ActionType.SWITCH_NETWORK_COMPARE,
                    ),
                    retryAction(),
                ),
                primaryFailedStage = StageId.UDP_STUN,
            )
        }

        // 9. Один из стеков IPv4/IPv6 сломан, но второй сработал (М17: только если это семейство реально выдано сетью!)
        if (tcp != null) {
            val ipv6Broken = tcp.ipv4Ok == true &&
                tcp.ipv6Ok == false &&
                snapshot.localIpv6 != null &&
                tcp.ipv6Error?.contains("ENETUNREACH", ignoreCase = true) != true
            val ipv4Broken = tcp.ipv6Ok == true &&
                tcp.ipv4Ok == false &&
                snapshot.localIpv4 != null &&
                tcp.ipv4Error?.contains("ENETUNREACH", ignoreCase = true) != true

            if (ipv6Broken || ipv4Broken) {
                val broken = if (ipv6Broken) "IPv6" else "IPv4"
                val working = if (ipv6Broken) "IPv4" else "IPv6"
                return Verdict(
                    category = VerdictCategory.IP_FAMILY_BROKEN,
                    confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                    ruleId = "R09_SINGLE_IP_FAMILY_BROKEN",
                    ruleVersion = RULES_VERSION,
                    headline = "Доступ работает через $working, но сломан стек $broken",
                    explanation = "Сайт в целом открылся, но подключения по $broken завершаются ошибкой при наличии адреса $broken в сети — из-за этого приложения могут открываться с паузой.",
                    confirmedFacts = baseFacts + listOf(
                        "TCP IPv4: ${if (tcp.ipv4Ok == true) "OK (${tcp.ipv4RttMs} мс)" else tcp.ipv4Error ?: "сбой"}.",
                        "TCP IPv6: ${if (tcp.ipv6Ok == true) "OK (${tcp.ipv6RttMs} мс)" else tcp.ipv6Error ?: "сбой"}.",
                    ),
                    hypotheses = listOf("Некорректная маршрутизация $broken у роутера или провайдера."),
                    unknowns = baseUnknowns,
                    recommendedActions = listOf(
                        RecommendedAction(
                            id = "check_router_ipv6",
                            title = "Проверить настройки $broken на роутере",
                            description = "Если сайты «задумываются» на несколько секунд перед открытием, отключение битого $broken на роутере обычно устраняет задержки.",
                            type = ActionType.MANUAL_STEP,
                        ),
                    ),
                    primaryFailedStage = StageId.TCP,
                )
            }
        }

        // 10. Всё прошло успешно!
        val gatewayHealthy = gwMetrics == null || gwMetrics.gatewaySent == 0 || gwMetrics.gatewayLossPercent < GATEWAY_LOSS_BAD_PERCENT
        val targetReachedHttpOk = httpProbe?.status == ProbeStatus.SUCCESS && http?.statusCode in 200..399
        val nonLocalFailures = networkProbes.any {
            it.stage != StageId.LOCAL_LINK && (it.status == ProbeStatus.NETWORK_FAILURE || it.status == ProbeStatus.TIMEOUT)
        }
        val onlyExternalControlFailed = localProbe?.status == ProbeStatus.NETWORK_FAILURE &&
            gatewayHealthy &&
            !nonLocalFailures &&
            targetReachedHttpOk

        val anyFailure = networkProbes.any { it.status == ProbeStatus.NETWORK_FAILURE || it.status == ProbeStatus.TIMEOUT }
        if ((!anyFailure || onlyExternalControlFailed) && networkProbes.any { it.status == ProbeStatus.SUCCESS }) {
            val facts = baseFacts + buildList {
                if (gwMetrics != null && gwMetrics.gatewaySent > 0) {
                    add("Локальный шлюз: потери ${"%.1f".format(gwMetrics.gatewayLossPercent)}%, средн. ${gwMetrics.gatewayAvgMs?.let { "%.0f мс".format(it) } ?: "—"}.")
                }
                if (onlyExternalControlFailed && gwMetrics != null) {
                    add("Внешний контрольный узел не ответил (${gwMetrics.externalReceived}/${gwMetrics.externalSent}), но целевой сайт прошёл полностью.")
                }
                if (dns != null && dns.systemIpv4.isNotEmpty()) {
                    add("DNS: адрес получен за ${dns.systemRttMs ?: 0} мс.")
                }
                if (tls != null && tls.targetSniOk) {
                    add("TLS (${tls.tlsProtocol ?: "OK"}): рукопожатие успешно за ${tls.targetSniRttMs ?: 0} мс.")
                }
                if (http != null && http.statusCode != null) {
                    add("HTTP ${http.statusCode}, данные тела (1 КБ) получены без обрыва.")
                }
                if (udp != null && udp.udpReachable) {
                    add("UDP/STUN: доступен (${udp.rttMs ?: 0} мс).")
                }
            }
            return Verdict(
                category = VerdictCategory.HEALTHY,
                confidence = ConfidenceLevel.CONFIRMED_BY_EXPERIMENT,
                ruleId = "R10_HEALTHY",
                ruleVersion = RULES_VERSION,
                headline = "За время проверки сетевых сбоев не обнаружено",
                explanation = "Все проверенные этапы от телефона до цели прошли штатно. Короткая проверка подтверждает состояние прямо сейчас, но не исключает редкие периодические обрывы.",
                confirmedFacts = facts,
                hypotheses = listOf("Если проблема возникает внутри конкретного приложения (например, зависание загрузки или авторизации), причина может быть в кэше приложения, аккаунте или редком плавающем обрыве."),
                unknowns = baseUnknowns + "Краткий тест не ловит редкие обрывы раз в 10–30 минут — для них используйте «Ловец обрывов».",
                recommendedActions = listOf(
                    RecommendedAction(
                        id = "recheck_if_intermittent",
                        title = "Запустить «Ловец обрывов» (длительное наблюдение)",
                        description = "Откроет живой график задержки и потерь до шлюза и внешнего узла.",
                        type = ActionType.OPEN_DROPOUT_WATCHER,
                    ),
                ),
            )
        }

        // 11. Фолбэк: честный ответ «причина пока не установлена»
        val failedProbes = networkProbes.filter { it.status != ProbeStatus.SUCCESS }
        return Verdict(
            category = VerdictCategory.UNKNOWN,
            confidence = ConfidenceLevel.INSUFFICIENT_DATA,
            ruleId = "R99_UNKNOWN",
            ruleVersion = RULES_VERSION,
            headline = "Причина сбоя пока точно не установлена",
            explanation = "Часть проверок завершилась с ошибкой, но собранных данных недостаточно для однозначного обвинения конкретного узла.",
            confirmedFacts = baseFacts + failedProbes.map { "${it.stage}: ${it.summary}" },
            hypotheses = listOf("Кратковременный сбой на пути или нестандартное ограничение."),
            unknowns = baseUnknowns + "Попробуйте повторить проверку или сравнить через другую сеть и сохранить сырой отчёт.",
            recommendedActions = listOf(
                RecommendedAction(
                    id = "compare_other_net_unk",
                    title = "Сравнить через другую сеть",
                    description = "Переключите Wi-Fi ↔ мобильную сеть и повторите тест.",
                    type = ActionType.SWITCH_NETWORK_COMPARE,
                ),
                retryAction(),
            ),
            primaryFailedStage = failedProbes.firstOrNull()?.stage,
        )
    }

    private fun retryAction(title: String = "Проверить снова"): RecommendedAction = RecommendedAction(
        id = "retry_failed_stage",
        title = title,
        description = "Повторить проверку после изменения настроек, чтобы убедиться, ушла ли проблема.",
        type = ActionType.RETRY_FAILED_STAGE,
    )
}
