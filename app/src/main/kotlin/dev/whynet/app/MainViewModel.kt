package dev.whynet.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.whynet.app.report.AppDiagnosticLog
import dev.whynet.app.report.BugReportExporter
import dev.whynet.app.report.HeatmapExporter
import dev.whynet.app.report.IssueReportRequest
import dev.whynet.app.report.SimpleHelpFormatter
import dev.whynet.app.report.SuiteRunRecord
import dev.whynet.app.report.SuiteRunnerReport
import dev.whynet.app.report.SuiteStepOutcome
import dev.whynet.app.report.SuiteStepResult
import dev.whynet.app.service.AppObserverState
import dev.whynet.app.service.AppObserverStore
import dev.whynet.app.service.AppPreferencesStore
import dev.whynet.app.service.DropoutWatcherService
import dev.whynet.app.service.DropoutWatcherState
import dev.whynet.app.service.DropoutWatcherStore
import dev.whynet.app.service.InstalledAppsProvider
import dev.whynet.app.service.LaunchableAppInfo
import dev.whynet.app.service.RemoteHelpRelayClient
import dev.whynet.app.service.RemoteRole
import dev.whynet.app.service.SavedAppProfile
import dev.whynet.app.service.WhyNetObserverVpnService
import dev.whynet.app.ui.theme.AppLanguage
import dev.whynet.app.ui.theme.AppThemeMode
import dev.whynet.core.engine.AppDomainFilter
import dev.whynet.core.engine.BandRecommendation
import dev.whynet.core.engine.ChannelInterferenceReport
import dev.whynet.core.engine.HeatmapFloorPlan
import dev.whynet.core.engine.HeatmapLayer
import dev.whynet.core.engine.HeatmapNetworkReading
import dev.whynet.core.engine.HeatmapPoint
import dev.whynet.core.engine.HeatmapWall
import dev.whynet.core.engine.HelpLinkCodec
import dev.whynet.core.engine.HelpPreset
import dev.whynet.core.engine.HelpTaskSpec
import dev.whynet.core.engine.NetToolsMath
import dev.whynet.core.engine.RemoteSessionCrypto
import dev.whynet.core.engine.ReportFormatter
import dev.whynet.core.engine.SuiteParser
import dev.whynet.core.engine.SuiteStep
import dev.whynet.core.engine.SuiteStepKind
import dev.whynet.core.engine.TestSuiteDefinition
import dev.whynet.core.engine.WifiApSample
import dev.whynet.core.engine.WifiChannelAnalyzer
import dev.whynet.core.engine.WifiHeatmapEngine
import dev.whynet.core.model.DiagnosticSession
import dev.whynet.core.model.DiagnosticTarget
import dev.whynet.core.model.NetworkSnapshot
import dev.whynet.core.model.ProbeResult
import dev.whynet.core.model.ProbeStatus
import dev.whynet.core.model.StageId
import dev.whynet.core.model.TargetType
import dev.whynet.core.model.TracerouteHop
import dev.whynet.core.probes.BufferbloatReport
import dev.whynet.core.probes.DiagnosticOrchestrator
import dev.whynet.core.probes.DiagnosticRunEvent
import dev.whynet.core.probes.DnsLookupProReport
import dev.whynet.core.probes.EnvironmentCollector
import dev.whynet.core.probes.LanDeviceEntry
import dev.whynet.core.probes.LiveWifiTick
import dev.whynet.core.probes.MassDnsBenchmarkReport
import dev.whynet.core.probes.NetworkProbes
import dev.whynet.core.probes.PingToolsProbes
import dev.whynet.core.probes.PortScanReport
import dev.whynet.core.probes.ServiceLatencyEntry
import dev.whynet.core.probes.TspuContourEntry
import dev.whynet.core.probes.TspuTestReport
import dev.whynet.core.probes.WhoisIpReport
import dev.whynet.core.probes.WifiChannelEntry
import kotlinx.coroutines.flow.firstOrNull
import dev.whynet.core.storage.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QuickPreset(
    val title: String,
    val subtitle: String,
    val type: TargetType,
    val defaultHost: String,
    val defaultPort: Int = 443,
) {
    NETWORK("Вся сеть", "Шлюз, DNS, HTTPS, UDP", TargetType.NETWORK_HEALTH, "ya.ru", 443),
    WEBSITE("Сайт", "Полная проверка домена", TargetType.WEBSITE, "gemini.google.com", 443),
    CALLS_P2P("Звонки / P2P", "TCP + UDP / STUN", TargetType.P2P_CALLS, "stun.l.google.com", 443),
}

enum class ToolKind(val title: String, val subtitle: String) {
    TSPU_TEST("Тест ТСПУ / Белые списки", "SNI, порог 16 КБ, CloudFront, Cloudflare"),
    SERVICE_LATENCY("Задержка сервисов", "Живой пинг 8 сервисов с автообновлением 2 с"),
    MASS_DNS_BENCH("Проверка массовых DNS", "Яндекс, 1.1.1.1, 8.8.8.8, Quad9, AdGuard"),
    DROPOUT_WATCHER("Пинг и монитор обрывов", "Градуированный график RTT, джиттер и потери"),
    TEST_SUITE("Сценарий (.txt)", "Пакетный автопрогон тестов из файла с отчётом"),
    APP_OBSERVER("Проверить приложение", "Автопоиск серверов приложения и проверка сети"),
    DNS_COMPARE("DNS: системный vs DoH", "Проверка подмены и залипших DNS"),
    DUAL_SNI("Двойной SNI / TLS", "Отделяет DPI/фильтр от сбоя сервера"),
    TRACEROUTE("Трассировка с выводами", "Хопы маршрута, дельта задержки и итог"),
    MTU_SEARCH("Поиск MTU / PMTU", "Макс. пакет без фрагментации"),
    UDP_STUN("UDP / STUN (P2P)", "Проверка звонков и WebRTC/RustDesk"),
    WIFI_CHANNELS("Wi-Fi Анализатор", "Каналы, карта помещения, сигнал, LAN"),
    PORT_SCANNER("Порты (TCP)", "Карта открытых портов и время отклика"),
    DNS_PRO("Домен / IP (DNS + WHOIS)", "Записи A, AAAA, MX, TXT + ASN и страна"),
    WHOIS_IP("WHOIS / IP Info", "Провайдер, ASN, страна и блок IP"),
    BUFFERBLOAT("Скорость и Bufferbloat", "2-кривый график скорости и пинга под нагрузкой"),
    WAKE_ON_LAN("Wake-on-LAN", "Удалённое включение ПК по MAC"),
    CIDR_CALC("IP / CIDR калькулятор", "Маска, сеть, broadcast и хосты"),
}

data class SuiteUiState(
    val suite: TestSuiteDefinition = SuiteParser.parse(SuiteParser.DEFAULT_SUITE_TEMPLATE),
    val sourceLabel: String = "Встроенный боевой набор",
    val environmentTag: String = "",
    val isRunning: Boolean = false,
    val currentIndex: Int = 0,
    val currentStepTitle: String? = null,
    val results: List<SuiteStepResult> = emptyList(),
    val lastRunRecord: SuiteRunRecord? = null,
    val errorMessage: String? = null,
)

data class LiveLogEntry(
    val id: Int,
    val elapsedMs: Long,
    val message: String,
)

data class WifiAnalyzerUiState(
    val entries: List<WifiChannelEntry> = emptyList(),
    val rec24: BandRecommendation? = null,
    val rec5: BandRecommendation? = null,
    val rec6: BandRecommendation? = null,
    val interference24: ChannelInterferenceReport? = null,
    val interference5: ChannelInterferenceReport? = null,
    val interference6: ChannelInterferenceReport? = null,
    val supports6Ghz: Boolean = false,
    val lastScanEpochMs: Long? = null,
    val isLiveScanRunning: Boolean = false,
    val throttledHint: Boolean = false,
    val errorMessage: String? = null,
    val isLiveSignalRunning: Boolean = false,
    val liveTicks: List<LiveWifiTick> = emptyList(),
    val roamingEvents: List<String> = emptyList(),
    val heatmapPlan: HeatmapFloorPlan = HeatmapFloorPlan(),
    val heatmapLayer: HeatmapLayer = HeatmapLayer.SIGNAL_RSSI,
    val isMeasuringHeatmapPoint: Boolean = false,
    val lanDevices: List<LanDeviceEntry> = emptyList(),
    val isScanningLan: Boolean = false,
    val lanScanPercent: Int = 0,
    val lanError: String? = null,
)

data class ToolsUiState(
    val selectedTool: ToolKind = ToolKind.DROPOUT_WATCHER,
    val showToolsMenu: Boolean = true,
    val hostInput: String = "1.1.1.1",
    val isRunning: Boolean = false,
    val liveNote: String? = null,
    val lastProbe: ProbeResult? = null,
    val tracerouteHops: List<TracerouteHop> = emptyList(),
    val wifiChannels: List<WifiChannelEntry> = emptyList(),
    val wifiError: String? = null,
    val installedApps: List<LaunchableAppInfo> = emptyList(),
    val appSearchQuery: String = "",
    val selectedApp: LaunchableAppInfo? = null,
    val portSpec: String = "21,22,53,80,443,445,554,3306,3389,8080,8291,8443",
    val portScanReport: PortScanReport? = null,
    val dnsProReport: DnsLookupProReport? = null,
    val whoisReport: WhoisIpReport? = null,
    val bufferbloatReport: BufferbloatReport? = null,
    val massDnsReport: MassDnsBenchmarkReport? = null,
    val tspuReport: TspuTestReport? = null,
    val serviceLatencies: List<ServiceLatencyEntry> = emptyList(),
    val wolMac: String = "",
    val wolBroadcast: String = "192.168.1.255",
    val wolStatus: String? = null,
    val cidrInput: String = "192.168.1.1/24",
)

data class CheckUiState(
    val liveSnapshot: NetworkSnapshot? = null,
    val rxBytesPerSec: Long = 0L,
    val txBytesPerSec: Long = 0L,
    val rxHistory: List<Long> = emptyList(),
    val txHistory: List<Long> = emptyList(),
    val selectedPreset: QuickPreset = QuickPreset.NETWORK,
    val siteInput: String = "gemini.google.com",
    val isRunning: Boolean = false,
    val elapsedMs: Long = 0L,
    val plannedStages: List<StageId> = emptyList(),
    val currentStageLabel: String? = null,
    val activeStage: StageId? = null,
    val selectedStageDetail: StageId? = null,
    val liveLogs: List<LiveLogEntry> = emptyList(),
    val liveProbes: List<ProbeResult> = emptyList(),
    val lastSession: DiagnosticSession? = null,
    val isVerifying: Boolean = false,
    val showRawLog: Boolean = false,
    val quickContours: List<TspuContourEntry> = emptyList(),
    val isCheckingContours: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val envCollector = EnvironmentCollector(application)
    private val probes = NetworkProbes(application)
    private val pingToolsProbes = PingToolsProbes()
    private val orchestrator = DiagnosticOrchestrator(
        context = application,
        environmentCollector = envCollector,
        probes = probes,
        appVersion = BuildConfig.VERSION_NAME,
    )
    private val repository = SessionRepository(application)
    private val prefsStore = AppPreferencesStore(application)

    private val _checkState = MutableStateFlow(CheckUiState())
    val checkState: StateFlow<CheckUiState> = _checkState.asStateFlow()

    private val _toolsState = MutableStateFlow(ToolsUiState())
    val toolsState: StateFlow<ToolsUiState> = _toolsState.asStateFlow()

    private val _wifiAnalyzerState = MutableStateFlow(
        WifiAnalyzerUiState(heatmapPlan = HeatmapExporter.loadPlan(application)),
    )
    val wifiAnalyzerState: StateFlow<WifiAnalyzerUiState> = _wifiAnalyzerState.asStateFlow()

    private val _suiteState = MutableStateFlow(SuiteUiState())
    val suiteState: StateFlow<SuiteUiState> = _suiteState.asStateFlow()

    private val _appAutoCheckState = MutableStateFlow(
        AppAutoCheckState(savedProfiles = prefsStore.loadSavedAppProfiles()),
    )
    val appAutoCheckState: StateFlow<AppAutoCheckState> = _appAutoCheckState.asStateFlow()

    private val _helpState = MutableStateFlow(
        HelpUiState(
            simpleModeEnabled = prefsStore.simpleModeEnabled,
            helperName = prefsStore.helperContactName,
            remote = RemoteHelpUiState(relayUrl = prefsStore.relayServerUrl),
        ),
    )
    val helpState: StateFlow<HelpUiState> = _helpState.asStateFlow()

    val watcherState: StateFlow<DropoutWatcherState> = DropoutWatcherStore.state
    val appObserverState: StateFlow<AppObserverState> = AppObserverStore.state

    val historySessions: StateFlow<List<DiagnosticSession>> =
        repository.observeSessions().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    private var diagnosticJob: Job? = null
    private var timerJob: Job? = null
    private var toolJob: Job? = null
    private var suiteJob: Job? = null
    private var appAutoCheckJob: Job? = null
    private var helpCheckJob: Job? = null
    private var remotePollJob: Job? = null
    private var liveWifiJob: Job? = null
    private var liveWifiScanJob: Job? = null
    private var lanScanJob: Job? = null
    private var trafficJob: Job? = null
    private var lastHandledObserverAutoTs: Long = 0L
    private var remoteFeedCounter: Int = 0
    private var logCounter = 0

    private val connectivityManager =
        application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _themeMode = MutableStateFlow(AppThemeMode.fromKey(prefsStore.themeMode))
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: AppThemeMode) {
        prefsStore.themeMode = mode.name
        _themeMode.value = mode
    }

    private val _appLanguage = MutableStateFlow(AppLanguage.fromKey(prefsStore.appLanguage))
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    fun setAppLanguage(lang: AppLanguage) {
        prefsStore.appLanguage = lang.name
        _appLanguage.value = lang
    }

    private var netRefreshDebounceJob: Job? = null
    private var contoursJob: Job? = null

    private fun scheduleNetworkRefresh(delayMs: Long = 350L) {
        netRefreshDebounceJob?.cancel()
        netRefreshDebounceJob = viewModelScope.launch {
            delay(delayMs)
            refreshLiveSnapshot(fetchExternalIp = true)
        }
    }

    fun refreshQuickContours() {
        contoursJob?.cancel()
        contoursJob = viewModelScope.launch {
            _checkState.update { it.copy(isCheckingContours = true) }
            val list = runCatching { pingToolsProbes.runQuickContoursCheck() }.getOrDefault(emptyList())
            _checkState.update { it.copy(quickContours = list, isCheckingContours = false) }
        }
    }

    private val physicalNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkRefresh(350L)
        override fun onLost(network: Network) = scheduleNetworkRefresh(600L)
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scheduleNetworkRefresh(500L)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleNetworkRefresh(300L)
        }

        override fun onLost(network: Network) {
            scheduleNetworkRefresh(700L)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            scheduleNetworkRefresh(450L)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            scheduleNetworkRefresh(450L)
        }
    }

    init {
        AppDiagnosticLog.i("App", "WhyNet ${BuildConfig.VERSION_NAME} запущен")
        refreshLiveSnapshot(fetchExternalIp = true)
        refreshQuickContours()
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
        runCatching {
            val req = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(req, physicalNetworkCallback)
        }
        trafficJob = viewModelScope.launch {
            probes.observeTrafficSpeed(intervalMs = 1000L).collect { tick ->
                _checkState.update { st ->
                    st.copy(
                        rxBytesPerSec = tick.rxBytesPerSec,
                        txBytesPerSec = tick.txBytesPerSec,
                        rxHistory = (st.rxHistory + tick.rxBytesPerSec).takeLast(20),
                        txHistory = (st.txHistory + tick.txBytesPerSec).takeLast(20),
                    )
                }
            }
        }
        viewModelScope.launch {
            AppObserverStore.state.collect { obs ->
                val doneTs = obs.autoCompletedEpochMs
                if (!obs.isRunning && doneTs != null && doneTs != lastHandledObserverAutoTs) {
                    lastHandledObserverAutoTs = doneTs
                    launchAutoDiagnosticsFromObserver(obs)
                }
            }
        }
    }

    override fun onCleared() {
        remotePollJob?.cancel()
        liveWifiJob?.cancel()
        liveWifiScanJob?.cancel()
        lanScanJob?.cancel()
        trafficJob?.cancel()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        runCatching { connectivityManager.unregisterNetworkCallback(physicalNetworkCallback) }
        super.onCleared()
    }

    fun refreshLiveSnapshot(fetchExternalIp: Boolean = true) {
        viewModelScope.launch {
            val snap = envCollector.collectSnapshot(fetchExternalIp = fetchExternalIp)
            AppDiagnosticLog.i("NetSnap", "transport=${snap.transport} validated=${snap.isValidated} vpn=${snap.activeExternalVpn} gw=${snap.gatewayIp ?: "n/a"}")
            _checkState.update { it.copy(liveSnapshot = snap) }
        }
    }

    fun selectPreset(preset: QuickPreset) {
        _checkState.update {
            it.copy(
                selectedPreset = preset,
                siteInput = if (preset == QuickPreset.WEBSITE && it.siteInput.isBlank()) {
                    preset.defaultHost
                } else {
                    it.siteInput
                },
            )
        }
    }

    fun updateSiteInput(value: String) {
        _checkState.update { it.copy(siteInput = value) }
    }

    fun selectStageDetail(stage: StageId?) {
        _checkState.update {
            it.copy(selectedStageDetail = if (it.selectedStageDetail == stage) null else stage)
        }
    }

    fun toggleRawLog() {
        _checkState.update { it.copy(showRawLog = !it.showRawLog) }
    }

    fun checkDomainFromObserver(domain: String) {
        WhyNetObserverVpnService.stop(getApplication())
        _checkState.update {
            it.copy(
                selectedPreset = QuickPreset.WEBSITE,
                siteInput = domain,
            )
        }
        startCheck()
    }

    fun startCheck() {
        diagnosticJob?.cancel()
        timerJob?.cancel()
        refreshQuickContours()

        val state = _checkState.value
        val preset = state.selectedPreset
        val host = when (preset) {
            QuickPreset.WEBSITE -> state.siteInput.trim().ifBlank { preset.defaultHost }
            QuickPreset.NETWORK -> "ya.ru"
            QuickPreset.CALLS_P2P -> "ya.ru"
        }
        val target = DiagnosticTarget(
            type = preset.type,
            hostOrUrl = host,
            port = 443,
            label = if (preset == QuickPreset.WEBSITE) host else preset.title,
        )
        val planned = orchestrator.plannedStagesFor(preset.type)
        val startEpoch = System.currentTimeMillis()
        logCounter = 0
        AppDiagnosticLog.i("Diag", "Старт проверки: preset=${preset.name}, target=${target.hostOrUrl}")

        _checkState.update {
            it.copy(
                isRunning = true,
                elapsedMs = 0L,
                plannedStages = planned,
                currentStageLabel = "Запуск диагностики...",
                activeStage = StageId.ENVIRONMENT,
                selectedStageDetail = null,
                liveLogs = listOf(LiveLogEntry(++logCounter, 0L, "Старт проверки: ${target.label}")),
                liveProbes = emptyList(),
                lastSession = null,
                showRawLog = false,
            )
        }

        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(150)
                _checkState.update {
                    if (it.isRunning) it.copy(elapsedMs = System.currentTimeMillis() - startEpoch) else it
                }
            }
        }

        diagnosticJob = viewModelScope.launch {
            try {
                orchestrator.runDiagnostic(target).collect { event ->
                    when (event) {
                        is DiagnosticRunEvent.StageStarted -> {
                            _checkState.update {
                                it.copy(
                                    activeStage = event.stage,
                                    currentStageLabel = event.label,
                                )
                            }
                        }
                        is DiagnosticRunEvent.LogLine -> {
                            _checkState.update {
                                it.copy(
                                    liveLogs = (it.liveLogs + LiveLogEntry(++logCounter, event.elapsedMs, event.text)).takeLast(40),
                                )
                            }
                        }
                        is DiagnosticRunEvent.SnapshotReady -> {
                            _checkState.update { it.copy(liveSnapshot = event.snapshot) }
                        }
                        is DiagnosticRunEvent.ProbeCompleted -> {
                            AppDiagnosticLog.i("Probe", "${event.probe.stage}: ${event.probe.status} (${event.probe.durationMs}ms) ${event.probe.summary}")
                            _checkState.update { it.copy(liveProbes = it.liveProbes + event.probe) }
                        }
                        is DiagnosticRunEvent.Finished -> {
                            timerJob?.cancel()
                            val totalMs = System.currentTimeMillis() - startEpoch
                            AppDiagnosticLog.i("Diag", "Завершено за ${totalMs}ms: ${event.session.verdict.category} / ${event.session.verdict.headline}")
                            _checkState.update {
                                it.copy(
                                    isRunning = false,
                                    elapsedMs = totalMs,
                                    activeStage = null,
                                    currentStageLabel = null,
                                    lastSession = event.session,
                                    liveSnapshot = event.session.snapshot,
                                    selectedStageDetail = event.session.verdict.primaryFailedStage,
                                    liveLogs = (it.liveLogs + LiveLogEntry(++logCounter, totalMs, "Готово: ${event.session.verdict.headline}")).takeLast(40),
                                )
                            }
                            repository.save(event.session)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    AppDiagnosticLog.e("Diag", "Неожиданная ошибка диагностики", e)
                }
                throw e
            } finally {
                timerJob?.cancel()
                _checkState.update {
                    if (it.isRunning) {
                        it.copy(isRunning = false, activeStage = null, currentStageLabel = "Остановлено")
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun cancelCheck() {
        AppDiagnosticLog.w("Diag", "Проверка отменена пользователем")
        diagnosticJob?.cancel()
        timerJob?.cancel()
        _checkState.update {
            it.copy(
                isRunning = false,
                activeStage = null,
                currentStageLabel = "Проверка остановлена",
                liveLogs = it.liveLogs + LiveLogEntry(++logCounter, it.elapsedMs, "Проверка остановлена пользователем."),
            )
        }
    }

    fun verifyLastSession() {
        val session = _checkState.value.lastSession ?: return
        if (_checkState.value.isVerifying) return
        viewModelScope.launch {
            _checkState.update { it.copy(isVerifying = true) }
            try {
                val verification = orchestrator.verifyFailedStage(session)
                val updated = session.copy(verification = verification)
                _checkState.update { it.copy(lastSession = updated, isVerifying = false) }
                repository.save(updated)
            } catch (_: Exception) {
                _checkState.update { it.copy(isVerifying = false) }
            }
        }
    }

    fun buildShareReport(session: DiagnosticSession, asJson: Boolean = false): String =
        if (asJson) ReportFormatter.toSanitizedJson(session) else ReportFormatter.toPlainTextReport(session)

    // --- Экран «Инструменты» ---

    fun showToolsMenu() {
        _toolsState.update { it.copy(showToolsMenu = true) }
    }

    fun selectTool(tool: ToolKind) {
        toolJob?.cancel()
        val snap = _checkState.value.liveSnapshot
        val defaultHost = when (tool) {
            ToolKind.DROPOUT_WATCHER, ToolKind.MTU_SEARCH -> "1.1.1.1"
            ToolKind.DUAL_SNI -> "login.live.com"
            ToolKind.PORT_SCANNER -> snap?.gatewayIp ?: "192.168.1.1"
            ToolKind.WHOIS_IP -> snap?.externalIp ?: "ya.ru"
            else -> "ya.ru"
        }
        val suggestedBroadcast = snap?.localIpv4?.split('.')?.takeIf { it.size == 4 }?.let {
            "${it[0]}.${it[1]}.${it[2]}.255"
        }
        val suggestedCidr = snap?.localIpv4?.let { "$it/24" }
        _toolsState.update {
            it.copy(
                selectedTool = tool,
                showToolsMenu = false,
                hostInput = defaultHost,
                wolBroadcast = suggestedBroadcast ?: it.wolBroadcast,
                cidrInput = if (tool == ToolKind.CIDR_CALC && suggestedCidr != null) suggestedCidr else it.cidrInput,
                isRunning = false,
                lastProbe = null,
                tracerouteHops = emptyList(),
                wifiError = null,
                liveNote = null,
                wolStatus = null,
            )
        }
        if (tool == ToolKind.APP_OBSERVER && _toolsState.value.installedApps.isEmpty()) {
            loadInstalledApps()
        }
    }

    fun updatePortSpec(spec: String) {
        _toolsState.update { it.copy(portSpec = spec) }
    }

    fun updateWolMac(mac: String) {
        _toolsState.update { it.copy(wolMac = mac, wolStatus = null) }
    }

    fun updateWolBroadcast(ip: String) {
        _toolsState.update { it.copy(wolBroadcast = ip, wolStatus = null) }
    }

    fun updateCidrInput(input: String) {
        _toolsState.update { it.copy(cidrInput = input) }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = InstalledAppsProvider.loadLaunchableApps(getApplication())
            _toolsState.update { st ->
                st.copy(
                    installedApps = apps,
                    selectedApp = st.selectedApp ?: apps.firstOrNull(),
                )
            }
        }
    }

    fun updateAppSearchQuery(query: String) {
        _toolsState.update { it.copy(appSearchQuery = query) }
    }

    fun selectAppForObserver(app: LaunchableAppInfo) {
        _toolsState.update { it.copy(selectedApp = app) }
    }

    fun startAppObserverConfirmed() {
        val app = _toolsState.value.selectedApp ?: return
        WhyNetObserverVpnService.start(getApplication(), app.packageName, app.label)
    }

    fun stopAppObserver() {
        WhyNetObserverVpnService.stop(getApplication())
    }

    fun markAppFreezeHere() {
        AppObserverStore.markFreezeHere()
    }

    fun openDropoutWatcherAndStart() {
        selectTool(ToolKind.DROPOUT_WATCHER)
        runSelectedTool()
    }

    fun updateToolHost(host: String) {
        _toolsState.update { it.copy(hostInput = host) }
    }

    fun stopTool() {
        if (_toolsState.value.selectedTool == ToolKind.DROPOUT_WATCHER) {
            DropoutWatcherService.stop(getApplication())
        }
        toolJob?.cancel()
        _toolsState.update { it.copy(isRunning = false, liveNote = "Остановлено") }
    }

    fun runSelectedTool() {
        toolJob?.cancel()
        val tool = _toolsState.value.selectedTool
        val host = _toolsState.value.hostInput.trim().ifBlank { "1.1.1.1" }
            .removePrefix("https://").removePrefix("http://").substringBefore('/')

        if (tool == ToolKind.DROPOUT_WATCHER) {
            DropoutWatcherService.start(getApplication(), host)
            return
        }

        if (tool == ToolKind.WIFI_CHANNELS) {
            val res = probes.scanWifiChannels()
            _toolsState.update {
                if (res.isSuccess) {
                    it.copy(wifiChannels = res.getOrDefault(emptyList()), wifiError = null, isRunning = false)
                } else {
                    it.copy(wifiChannels = emptyList(), wifiError = res.exceptionOrNull()?.message, isRunning = false)
                }
            }
            return
        }

        if (tool == ToolKind.APP_OBSERVER || tool == ToolKind.TEST_SUITE || tool == ToolKind.CIDR_CALC) return

        _toolsState.update {
            it.copy(
                isRunning = true,
                lastProbe = null,
                tracerouteHops = emptyList(),
                liveNote = "Запуск...",
            )
        }

        toolJob = viewModelScope.launch {
            try {
                val net = envCollector.getActiveNetwork()
                val snap = envCollector.collectSnapshot(fetchExternalIp = false)
                val progress: suspend (String) -> Unit = { msg ->
                    _toolsState.update { it.copy(liveNote = msg) }
                }

                when (tool) {
                    ToolKind.DNS_COMPARE -> {
                        val result = probes.runDnsCompareProbe(host, net, onProgress = progress)
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, lastProbe = result) }
                    }
                    ToolKind.DUAL_SNI -> {
                        val dns = probes.runDnsCompareProbe(host, net, onProgress = progress).dnsCompare
                        val ip = dns?.systemIpv4?.firstOrNull() ?: dns?.dohIpv4?.firstOrNull()
                        val result = if (ip != null) {
                            probes.runDualSniTlsProbe(ip, 443, host, network = net, onProgress = progress)
                        } else {
                            ProbeResult(
                                stage = StageId.TLS_SNI,
                                status = dev.whynet.core.model.ProbeStatus.NETWORK_FAILURE,
                                startedAtEpochMs = System.currentTimeMillis(),
                                durationMs = 0,
                                summary = "Не удалось получить IP для $host",
                            )
                        }
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, lastProbe = result) }
                    }
                    ToolKind.TRACEROUTE -> {
                        val result = probes.runTracerouteProbe(
                            host = host,
                            maxHops = 15,
                            onProgress = progress,
                            onHop = { hop ->
                                _toolsState.update { st -> st.copy(tracerouteHops = st.tracerouteHops + hop) }
                            },
                        )
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, lastProbe = result) }
                    }
                    ToolKind.MTU_SEARCH -> {
                        val result = probes.runMtuProbe(
                            host = host,
                            interfaceMtu = snap.interfaceMtu,
                            onProgress = progress,
                        )
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, lastProbe = result) }
                    }
                    ToolKind.UDP_STUN -> {
                        val result = probes.runUdpStunProbe(onProgress = progress)
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, lastProbe = result) }
                    }
                    ToolKind.PORT_SCANNER -> {
                        val ports = NetToolsMath.parsePortList(_toolsState.value.portSpec)
                        val res = pingToolsProbes.scanTcpPorts(host, ports) { done, total ->
                            progress("Проверено портов: $done / $total...")
                        }
                        if (res.isSuccess) {
                            _toolsState.update { it.copy(isRunning = false, liveNote = null, portScanReport = res.getOrThrow()) }
                        } else {
                            _toolsState.update { it.copy(isRunning = false, liveNote = "Ошибка: ${res.exceptionOrNull()?.message}") }
                        }
                    }
                    ToolKind.TSPU_TEST -> {
                        progress("Проверяем контуры РФ, порог 16 КБ, CloudFront, Cloudflare, YouTube, Discord...")
                        val rep = pingToolsProbes.runTspuAndContoursTest()
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, tspuReport = rep) }
                    }
                    ToolKind.SERVICE_LATENCY -> {
                        val custom = host.takeIf { it.isNotBlank() && it != "1.1.1.1" && it != "ya.ru" }
                        var cycle = 0
                        while (isActive) {
                            cycle++
                            progress("Цикл #$cycle: замеряем отклик сервисов (обновление каждые 2 с)...")
                            val list = pingToolsProbes.probeServicesLatency(custom)
                            _toolsState.update { it.copy(serviceLatencies = list) }
                            delay(2000L)
                        }
                    }
                    ToolKind.MASS_DNS_BENCH -> {
                        progress("Опрашиваем Яндекс, AdGuard, Google, Quad9, Cloudflare и системный DNS...")
                        val rep = pingToolsProbes.runMassDnsBenchmark(host.ifBlank { "ya.ru" })
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, massDnsReport = rep) }
                    }
                    ToolKind.DNS_PRO -> {
                        progress("Запрашиваем DNS-записи и WHOIS / Geo / ASN для $host...")
                        val rep = pingToolsProbes.runDnsLookupPro(host)
                        val whois = pingToolsProbes.lookupWhoisIp(host).getOrNull()
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, dnsProReport = rep, whoisReport = whois) }
                    }
                    ToolKind.WHOIS_IP -> {
                        progress("Запрашиваем Geo/ASN и RDAP для $host...")
                        val res = pingToolsProbes.lookupWhoisIp(host)
                        if (res.isSuccess) {
                            _toolsState.update { it.copy(isRunning = false, liveNote = null, whoisReport = res.getOrThrow()) }
                        } else {
                            _toolsState.update { it.copy(isRunning = false, liveNote = "Ошибка: ${res.exceptionOrNull()?.message}") }
                        }
                    }
                    ToolKind.BUFFERBLOAT -> {
                        val res = pingToolsProbes.runBufferbloatAndSpeedTest(snap.gatewayIp, onProgress = progress)
                        if (res.isSuccess) {
                            _toolsState.update { it.copy(isRunning = false, liveNote = null, bufferbloatReport = res.getOrThrow()) }
                        } else {
                            _toolsState.update { it.copy(isRunning = false, liveNote = "Ошибка: ${res.exceptionOrNull()?.message}") }
                        }
                    }
                    ToolKind.WAKE_ON_LAN -> {
                        val res = pingToolsProbes.sendWakeOnLan(_toolsState.value.wolMac, _toolsState.value.wolBroadcast)
                        val msg = res.getOrElse { "Ошибка: ${it.message}" }
                        _toolsState.update { it.copy(isRunning = false, liveNote = null, wolStatus = msg) }
                    }
                    ToolKind.DROPOUT_WATCHER, ToolKind.TEST_SUITE, ToolKind.APP_OBSERVER, ToolKind.WIFI_CHANNELS, ToolKind.CIDR_CALC -> Unit
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    AppDiagnosticLog.e("Tools", "Ошибка инструмента ${_toolsState.value.selectedTool}", e)
                }
                _toolsState.update { it.copy(isRunning = false) }
            }
        }
    }

    // --- Пакетный прогон сценария (.txt) ---

    fun updateSuiteEnvTag(tag: String) {
        _suiteState.update { it.copy(environmentTag = tag) }
    }

    fun toggleSuiteEnvChip(chip: String) {
        _suiteState.update { st ->
            val tokens = st.environmentTag
                .split(',', '+')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            if (tokens.any { it.equals(chip, ignoreCase = true) }) {
                tokens.removeAll { it.equals(chip, ignoreCase = true) }
            } else {
                tokens.add(chip)
            }
            st.copy(environmentTag = tokens.joinToString(" + "))
        }
    }

    fun loadDefaultSuite() {
        suiteJob?.cancel()
        val parsed = SuiteParser.parse(SuiteParser.DEFAULT_SUITE_TEMPLATE)
        AppDiagnosticLog.i("Suite", "Загружен встроенный набор (${parsed.steps.size} тестов)")
        _suiteState.update {
            it.copy(
                suite = parsed,
                sourceLabel = "Встроенный боевой набор",
                isRunning = false,
                currentIndex = 0,
                currentStepTitle = null,
                results = emptyList(),
                lastRunRecord = null,
                errorMessage = null,
            )
        }
    }

    fun loadSuiteFromText(rawText: String, sourceName: String) {
        suiteJob?.cancel()
        val parsed = SuiteParser.parse(rawText, fallbackName = sourceName.substringBeforeLast('.').ifBlank { "Пользовательский сценарий" })
        if (parsed.steps.isEmpty()) {
            _suiteState.update {
                it.copy(errorMessage = "В файле '$sourceName' не найдено ни одного теста.")
            }
            return
        }
        AppDiagnosticLog.i("Suite", "Загружен файл '$sourceName': ${parsed.steps.size} тестов, предупреждений=${parsed.parseWarnings.size}")
        _suiteState.update { st ->
            st.copy(
                suite = parsed,
                sourceLabel = sourceName,
                environmentTag = if (st.environmentTag.isBlank() && parsed.defaultTag.isNotBlank() && parsed.defaultTag != "auto") {
                    parsed.defaultTag
                } else {
                    st.environmentTag
                },
                isRunning = false,
                currentIndex = 0,
                currentStepTitle = null,
                results = emptyList(),
                lastRunRecord = null,
                errorMessage = null,
            )
        }
    }

    fun stopSuiteRun() {
        AppDiagnosticLog.w("Suite", "Пакетный прогон остановлен пользователем")
        suiteJob?.cancel()
    }

    fun startSuiteRun() {
        suiteJob?.cancel()
        val st = _suiteState.value
        val suite = st.suite
        if (suite.steps.isEmpty()) {
            _suiteState.update { it.copy(errorMessage = "Сценарий пуст — загрузите файл или встроенный набор.") }
            return
        }

        val envTag = st.environmentTag.trim()
        val startedAt = System.currentTimeMillis()
        AppDiagnosticLog.i("Suite", "Старт серии '${suite.name}' (${suite.steps.size} шагов), метка='$envTag'")

        _suiteState.update {
            it.copy(
                isRunning = true,
                currentIndex = 0,
                currentStepTitle = "Снимок сети перед стартом...",
                results = emptyList(),
                lastRunRecord = null,
                errorMessage = null,
            )
        }

        suiteJob = viewModelScope.launch {
            var snapBefore: NetworkSnapshot? = null
            val collected = mutableListOf<SuiteStepResult>()
            var cancelled = false
            try {
                snapBefore = envCollector.collectSnapshot(fetchExternalIp = true)
                _checkState.update { it.copy(liveSnapshot = snapBefore) }

                for ((idx, step) in suite.steps.withIndex()) {
                    _suiteState.update {
                        it.copy(
                            currentIndex = idx + 1,
                            currentStepTitle = "${idx + 1}/${suite.steps.size}: [${step.kind.name}] ${step.target.ifBlank { "Wi-Fi" }}",
                        )
                    }
                    val stepResult = executeSuiteStep(step)
                    collected += stepResult
                    _suiteState.update { it.copy(results = collected.toList()) }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                AppDiagnosticLog.e("Suite", "Сбой серии", e)
                _suiteState.update { it.copy(errorMessage = "Ошибка выполнения: ${e.message}") }
            } finally {
                withContext(NonCancellable) {
                    val snapAfter = runCatching { envCollector.collectSnapshot(fetchExternalIp = true) }.getOrNull()
                    val record = SuiteRunRecord(
                        suite = suite,
                        environmentTag = envTag,
                        startedAtEpochMs = startedAt,
                        finishedAtEpochMs = System.currentTimeMillis(),
                        wasCancelled = cancelled,
                        snapshotBefore = snapBefore,
                        snapshotAfter = snapAfter,
                        results = collected.toList(),
                    )
                    AppDiagnosticLog.i("Suite", "Серия завершена (cancelled=$cancelled): ${collected.size}/${suite.steps.size}")
                    _suiteState.update {
                        it.copy(
                            isRunning = false,
                            currentStepTitle = if (cancelled) "Остановлено (${collected.size}/${suite.steps.size})" else "Готово (${collected.size}/${suite.steps.size})",
                            lastRunRecord = record,
                        )
                    }
                }
            }
        }
    }

    private suspend fun executeSuiteStep(step: SuiteStep): SuiteStepResult {
        val started = System.currentTimeMillis()
        val cleanHost = step.target.trim()
            .removePrefix("https://").removePrefix("http://").substringBefore('/')
            .ifBlank { "ya.ru" }
        return try {
            when (step.kind) {
                SuiteStepKind.NETWORK, SuiteStepKind.CALLS, SuiteStepKind.SITE -> {
                    val type = when (step.kind) {
                        SuiteStepKind.NETWORK -> TargetType.NETWORK_HEALTH
                        SuiteStepKind.CALLS -> TargetType.P2P_CALLS
                        SuiteStepKind.SITE -> TargetType.WEBSITE
                        else -> TargetType.WEBSITE
                    }
                    val target = DiagnosticTarget(
                        type = type,
                        hostOrUrl = step.target.ifBlank { "ya.ru" },
                        port = 443,
                        label = step.target.ifBlank { step.kind.name },
                    )
                    var finishedSession: DiagnosticSession? = null
                    orchestrator.runDiagnostic(target).collect { ev ->
                        if (ev is DiagnosticRunEvent.Finished) {
                            finishedSession = ev.session
                        }
                    }
                    val session = finishedSession
                        ?: return SuiteStepResult(
                            step = step,
                            outcome = SuiteStepOutcome.ERROR,
                            durationMs = System.currentTimeMillis() - started,
                            headline = "Сессия диагностики не завершилась",
                            categoryOrCode = "NO_SESSION",
                        )
                    val outcome = SuiteRunnerReport.outcomeFromVerdict(session.verdict.category)
                    SuiteStepResult(
                        step = step,
                        outcome = outcome,
                        durationMs = System.currentTimeMillis() - started,
                        headline = session.verdict.headline,
                        categoryOrCode = session.verdict.category.name,
                        confidence = session.verdict.confidence.name,
                        expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, outcome),
                        recommendations = session.verdict.recommendedActions.map { "${it.title}: ${it.description}" },
                        session = session,
                    )
                }

                SuiteStepKind.DNS -> {
                    val probe = probes.runDnsCompareProbe(cleanHost, envCollector.getActiveNetwork())
                    val outcome = if (probe.status == ProbeStatus.SUCCESS) SuiteStepOutcome.OK else SuiteStepOutcome.FAIL
                    SuiteStepResult(
                        step = step,
                        outcome = outcome,
                        durationMs = System.currentTimeMillis() - started,
                        headline = probe.summary,
                        categoryOrCode = probe.status.name,
                        expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, outcome),
                        detailedText = probe.rawDetails.orEmpty(),
                    )
                }

                SuiteStepKind.SNI -> {
                    val dns = probes.runDnsCompareProbe(cleanHost, envCollector.getActiveNetwork())
                    val ip = dns.dnsCompare?.systemIpv4?.firstOrNull() ?: dns.dnsCompare?.dohIpv4?.firstOrNull()
                    if (ip == null) {
                        SuiteStepResult(
                            step = step,
                            outcome = SuiteStepOutcome.FAIL,
                            durationMs = System.currentTimeMillis() - started,
                            headline = "Не удалось получить IP для $cleanHost",
                            categoryOrCode = "DNS_NO_IP",
                            expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, SuiteStepOutcome.FAIL),
                            detailedText = dns.rawDetails.orEmpty(),
                        )
                    } else {
                        val tls = probes.runDualSniTlsProbe(ipAddress = ip, port = 443, targetSni = cleanHost, network = envCollector.getActiveNetwork())
                        val outcome = if (tls.status == ProbeStatus.SUCCESS) SuiteStepOutcome.OK else SuiteStepOutcome.FAIL
                        SuiteStepResult(
                            step = step,
                            outcome = outcome,
                            durationMs = System.currentTimeMillis() - started,
                            headline = tls.summary,
                            categoryOrCode = tls.errorCode ?: tls.status.name,
                            expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, outcome),
                            detailedText = tls.rawDetails.orEmpty(),
                        )
                    }
                }

                SuiteStepKind.MTU -> {
                    val snap = envCollector.collectSnapshot(fetchExternalIp = false)
                    val probe = probes.runMtuProbe(host = cleanHost, interfaceMtu = snap.interfaceMtu)
                    val outcome = if (probe.status == ProbeStatus.SUCCESS) SuiteStepOutcome.OK else SuiteStepOutcome.WARN
                    SuiteStepResult(
                        step = step,
                        outcome = outcome,
                        durationMs = System.currentTimeMillis() - started,
                        headline = probe.summary,
                        categoryOrCode = probe.status.name,
                        expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, outcome),
                        detailedText = probe.rawDetails.orEmpty(),
                    )
                }

                SuiteStepKind.TRACEROUTE -> {
                    val hops = mutableListOf<TracerouteHop>()
                    val probe = probes.runTracerouteProbe(
                        host = cleanHost,
                        maxHops = 12,
                        onHop = { hop -> hops += hop },
                    )
                    val reached = hops.any { it.reachedTarget }
                    val outcome = if (reached || probe.status == ProbeStatus.SUCCESS) SuiteStepOutcome.OK else SuiteStepOutcome.WARN
                    val details = hops.joinToString("\n") { h ->
                        "TTL ${h.ttl}: ${h.ip ?: "*"} ${h.rttMs?.let { "${it}ms" } ?: "timeout"}${if (h.reachedTarget) " [TARGET]" else ""}"
                    }
                    SuiteStepResult(
                        step = step,
                        outcome = outcome,
                        durationMs = System.currentTimeMillis() - started,
                        headline = probe.summary,
                        categoryOrCode = if (reached) "REACHED" else "PARTIAL",
                        expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, outcome),
                        detailedText = details,
                    )
                }

                SuiteStepKind.UDP -> {
                    val probe = probes.runUdpStunProbe()
                    val outcome = if (probe.status == ProbeStatus.SUCCESS) SuiteStepOutcome.OK else SuiteStepOutcome.FAIL
                    SuiteStepResult(
                        step = step,
                        outcome = outcome,
                        durationMs = System.currentTimeMillis() - started,
                        headline = probe.summary,
                        categoryOrCode = probe.status.name,
                        expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, outcome),
                        detailedText = probe.rawDetails.orEmpty(),
                    )
                }

                SuiteStepKind.WIFI -> {
                    val res = probes.scanWifiChannels()
                    val entries = res.getOrDefault(emptyList())
                    val err = res.exceptionOrNull()?.message
                    val outcome = if (err == null) SuiteStepOutcome.OK else SuiteStepOutcome.WARN
                    val topLines = entries.take(12).joinToString("\n") { e ->
                        "${e.bandGhz}GHz ch${e.channel} (${e.frequencyMhz}MHz) ${e.rssiDbm}dBm ${e.bssidDisplay}"
                    }
                    SuiteStepResult(
                        step = step,
                        outcome = outcome,
                        durationMs = System.currentTimeMillis() - started,
                        headline = err ?: "Найдено Wi-Fi точек: ${entries.size}",
                        categoryOrCode = if (err == null) "WIFI_OK" else "WIFI_PERM_OR_ERR",
                        expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, outcome),
                        detailedText = topLines,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppDiagnosticLog.e("Suite", "Ошибка шага ${step.index} (${step.kind})", e)
            SuiteStepResult(
                step = step,
                outcome = SuiteStepOutcome.ERROR,
                durationMs = System.currentTimeMillis() - started,
                headline = "Ошибка: ${e.javaClass.simpleName}: ${e.message}",
                categoryOrCode = "EXCEPTION",
                expectationMatched = SuiteRunnerReport.evaluateExpectation(step.expectation, SuiteStepOutcome.ERROR),
            )
        }
    }

    // --- Экспорт отчёта о проблеме (.txt) ---

    fun buildIssueReportTxt(request: IssueReportRequest): Pair<String, String?> {
        val state = _checkState.value
        val content = BugReportExporter.buildTxtContent(
            request = request,
            liveSnapshot = state.liveSnapshot,
            lastSession = state.lastSession,
            liveLogs = state.liveLogs,
            recentSessions = historySessions.value,
        )
        return content to state.lastSession?.target?.label
    }

    // --- История ---

    fun openSessionFromHistory(session: DiagnosticSession) {
        _checkState.update {
            it.copy(
                plannedStages = session.probes.map { p -> p.stage },
                lastSession = session,
                liveProbes = session.probes,
                liveLogs = emptyList(),
                selectedStageDetail = session.verdict.primaryFailedStage,
                showRawLog = false,
            )
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { repository.delete(sessionId) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearAll() }
    }

    // --- Автопроверка приложения в 2–3 нажатия ---

    fun startAutoAppCheckConfirmed(autoStopSec: Int = 15): String? {
        val app = _toolsState.value.selectedApp ?: return null
        appAutoCheckJob?.cancel()
        _appAutoCheckState.update {
            it.copy(
                isRunning = false,
                appPackage = app.packageName,
                appLabel = app.label,
                selectedDomains = emptyList(),
                currentStepText = null,
                results = emptyList(),
                lastRecord = null,
                summaryText = null,
                errorMessage = null,
            )
        }
        AppDiagnosticLog.i("AppCheck", "Автопроверка '${app.label}' (${app.packageName}), захват ${autoStopSec}с")
        WhyNetObserverVpnService.start(getApplication(), app.packageName, app.label, autoStopSec = autoStopSec)
        return app.packageName
    }

    fun finishAppCaptureNow() {
        WhyNetObserverVpnService.finishAndCheckNow(getApplication())
    }

    fun cancelAppAutoCheck() {
        appAutoCheckJob?.cancel()
        WhyNetObserverVpnService.stop(getApplication())
        _appAutoCheckState.update {
            it.copy(isRunning = false, currentStepText = "Остановлено")
        }
    }

    fun runSavedAppProfile(profile: SavedAppProfile) {
        AppDiagnosticLog.i("AppCheck", "Быстрая проверка сохранённого профиля '${profile.label}' (${profile.domains.size} доменов, без VPN)")
        runAppDiagnosticsSuite(
            packageName = profile.packageName,
            label = profile.label,
            domains = profile.domains,
        )
    }

    fun saveCurrentAppProfile() {
        val st = _appAutoCheckState.value
        val pkg = st.appPackage ?: return
        val label = st.appLabel ?: pkg
        if (st.selectedDomains.isEmpty()) return
        val saved = prefsStore.saveAppProfile(
            SavedAppProfile(
                packageName = pkg,
                label = label,
                domains = st.selectedDomains,
                savedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        _appAutoCheckState.update { it.copy(savedProfiles = saved) }
    }

    fun deleteSavedAppProfile(packageName: String) {
        val updated = prefsStore.deleteAppProfile(packageName)
        _appAutoCheckState.update { it.copy(savedProfiles = updated) }
    }

    private fun launchAutoDiagnosticsFromObserver(obs: AppObserverState) {
        val pkg = obs.targetPackage ?: _toolsState.value.selectedApp?.packageName ?: "unknown"
        val label = obs.targetLabel ?: _toolsState.value.selectedApp?.label ?: pkg
        val candidates = buildList {
            obs.endpoints.forEach { ep ->
                val host = ep.domainOrSni
                if (!host.isNullOrBlank()) {
                    add(AppDomainFilter.DomainCandidate(host, ep.packetCount, ep.seenAfterFreezeMark))
                }
            }
            obs.dnsQueries.forEach { q ->
                add(AppDomainFilter.DomainCandidate(q, packetCount = 1, seenAfterFreeze = false))
            }
        }
        val selected = AppDomainFilter.selectPrimaryDomains(candidates, maxCount = 4)
        AppDiagnosticLog.i("AppCheck", "Поймано доменов для '$label': всего=${obs.dnsQueries.size}, выбрано=${selected.joinToString()}")
        runAppDiagnosticsSuite(pkg, label, selected)
    }

    private fun runAppDiagnosticsSuite(packageName: String, label: String, domains: List<String>) {
        appAutoCheckJob?.cancel()
        val steps = mutableListOf<SuiteStep>()
        var idx = 1
        if (domains.isEmpty()) {
            steps += SuiteStep(idx++, SuiteStepKind.NETWORK, "ya.ru")
        } else {
            domains.forEach { d ->
                steps += SuiteStep(idx++, SuiteStepKind.SITE, d)
            }
        }
        steps += SuiteStep(idx++, SuiteStepKind.UDP, "stun.l.google.com")
        steps += SuiteStep(idx++, SuiteStepKind.MTU, "1.1.1.1")

        val suite = TestSuiteDefinition(
            name = "Проверка приложения: $label",
            defaultTag = "app_check",
            steps = steps,
        )
        val startedAt = System.currentTimeMillis()
        _appAutoCheckState.update {
            it.copy(
                isRunning = true,
                appPackage = packageName,
                appLabel = label,
                selectedDomains = domains,
                currentStepText = if (domains.isEmpty()) {
                    "Серверы приложения не успели пойматься — проверяем общую сеть, UDP и MTU..."
                } else {
                    "Локальный VPN выключен. Проверяем ${domains.size} сервер(а) напрямую..."
                },
                results = emptyList(),
                lastRecord = null,
                summaryText = null,
                errorMessage = null,
            )
        }

        appAutoCheckJob = viewModelScope.launch {
            // Даём Android 400 мс после закрытия локального TUN на восстановление маршрутизации
            delay(400L)
            var snapBefore: NetworkSnapshot? = null
            val collected = mutableListOf<SuiteStepResult>()
            var cancelled = false
            try {
                snapBefore = envCollector.collectSnapshot(fetchExternalIp = true)
                _checkState.update { it.copy(liveSnapshot = snapBefore) }

                for ((i, step) in suite.steps.withIndex()) {
                    _appAutoCheckState.update {
                        it.copy(currentStepText = "${i + 1}/${suite.steps.size}: [${step.kind.name}] ${step.target}")
                    }
                    val res = executeSuiteStep(step)
                    collected += res
                    _appAutoCheckState.update { it.copy(results = collected.toList()) }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } catch (e: Exception) {
                _appAutoCheckState.update { it.copy(errorMessage = "Ошибка автопроверки: ${e.message}") }
            } finally {
                withContext(NonCancellable) {
                    val snapAfter = runCatching { envCollector.collectSnapshot(fetchExternalIp = true) }.getOrNull()
                    val record = SuiteRunRecord(
                        suite = suite,
                        environmentTag = "Приложение: $label",
                        startedAtEpochMs = startedAt,
                        finishedAtEpochMs = System.currentTimeMillis(),
                        wasCancelled = cancelled,
                        snapshotBefore = snapBefore,
                        snapshotAfter = snapAfter,
                        results = collected.toList(),
                    )
                    val summary = SimpleHelpFormatter.summarizeRun(record)
                    _appAutoCheckState.update {
                        it.copy(
                            isRunning = false,
                            currentStepText = if (cancelled) "Остановлено" else "Готово (${collected.size}/${suite.steps.size})",
                            lastRecord = record,
                            summaryText = "${summary.title}. ${summary.explanation}",
                        )
                    }
                }
            }
        }
    }

    // --- Простой режим, ссылки whynet://help?... и Помощь близкому ---

    fun setSimpleMode(enabled: Boolean) {
        prefsStore.simpleModeEnabled = enabled
        _helpState.update { it.copy(simpleModeEnabled = enabled) }
    }

    fun updateHelperName(name: String) {
        prefsStore.helperContactName = name
        _helpState.update { it.copy(helperName = name) }
    }

    fun updateBuilderPreset(preset: HelpPreset) {
        _helpState.update { it.copy(builderPreset = preset) }
    }

    fun updateBuilderTargets(text: String) {
        _helpState.update { it.copy(builderTargetsText = text) }
    }

    fun updateBuilderAutoStart(auto: Boolean) {
        _helpState.update { it.copy(builderAutoStart = auto) }
    }

    fun updateBuilderNote(note: String) {
        _helpState.update { it.copy(builderNote = note) }
    }

    fun updatePasteLinkInput(text: String) {
        _helpState.update { it.copy(pasteLinkInput = text, pasteLinkError = null) }
    }

    fun buildCurrentHelpTaskSpec(): HelpTaskSpec {
        val st = _helpState.value
        val targets = st.builderTargetsText
            .split(',', ';', '\n', ' ')
            .mapNotNull { AppDomainFilter.normalizeHost(it) }
            .distinct()
        return HelpTaskSpec(
            preset = st.builderPreset,
            targets = targets,
            helperName = st.helperName.ifBlank { "Помощник" },
            autoStart = st.builderAutoStart,
            note = st.builderNote.trim(),
        )
    }

    fun handleIncomingHelpText(rawText: String): Boolean {
        val parsed = HelpLinkCodec.decodeFromAnyText(rawText) ?: return false
        AppDiagnosticLog.i("HelpLink", "Получена ссылка-задание: preset=${parsed.preset.code}, targets=${parsed.targets.size}")
        prefsStore.simpleModeEnabled = true
        _helpState.update {
            it.copy(
                simpleModeEnabled = true,
                incomingTask = parsed,
                lastHelpRecord = null,
                lastSimpleSummary = null,
                pasteLinkError = null,
            )
        }
        return true
    }

    fun applyPastedHelpLink(): Boolean {
        val raw = _helpState.value.pasteLinkInput
        val ok = handleIncomingHelpText(raw)
        if (!ok) {
            _helpState.update { it.copy(pasteLinkError = "Ссылка не распознана") }
        } else {
            _helpState.update { it.copy(pasteLinkInput = "", pasteLinkError = null) }
        }
        return ok
    }

    fun clearIncomingTask() {
        _helpState.update { it.copy(incomingTask = null) }
    }

    fun stopHelpCheck() {
        helpCheckJob?.cancel()
    }

    fun startHelpCheck() {
        helpCheckJob?.cancel()
        val task = _helpState.value.incomingTask ?: HelpTaskSpec(
            preset = HelpPreset.FULL_NETWORK,
            helperName = _helpState.value.helperName,
            autoStart = true,
        )
        val suite = HelpLinkCodec.toSuiteDefinition(task)
        val startedAt = System.currentTimeMillis()

        _helpState.update {
            it.copy(
                isRunningHelpCheck = true,
                helpProgressText = "Проверяем подключение...",
                helpResults = emptyList(),
                lastHelpRecord = null,
                lastSimpleSummary = null,
            )
        }

        helpCheckJob = viewModelScope.launch {
            var snapBefore: NetworkSnapshot? = null
            val collected = mutableListOf<SuiteStepResult>()
            var cancelled = false
            try {
                snapBefore = envCollector.collectSnapshot(fetchExternalIp = true)
                _checkState.update { it.copy(liveSnapshot = snapBefore) }
                for ((i, step) in suite.steps.withIndex()) {
                    _helpState.update {
                        it.copy(helpProgressText = "Шаг ${i + 1} из ${suite.steps.size}: ${step.target.ifBlank { "Wi-Fi" }}")
                    }
                    val res = executeSuiteStep(step)
                    collected += res
                    _helpState.update { it.copy(helpResults = collected.toList()) }
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                withContext(NonCancellable) {
                    val snapAfter = runCatching { envCollector.collectSnapshot(fetchExternalIp = true) }.getOrNull()
                    val record = SuiteRunRecord(
                        suite = suite,
                        environmentTag = "Помощь близкому",
                        startedAtEpochMs = startedAt,
                        finishedAtEpochMs = System.currentTimeMillis(),
                        wasCancelled = cancelled,
                        snapshotBefore = snapBefore,
                        snapshotAfter = snapAfter,
                        results = collected.toList(),
                    )
                    val summary = SimpleHelpFormatter.summarizeRun(record)
                    _helpState.update {
                        it.copy(
                            isRunningHelpCheck = false,
                            helpProgressText = if (cancelled) "Проверка остановлена" else "Проверка завершена",
                            lastHelpRecord = record,
                            lastSimpleSummary = summary,
                        )
                    }
                }
            }
        }
    }

    // --- Сеанс помощи по 6-значному коду (Relay + AES-256-GCM) ---

    fun updateRelayUrl(url: String) {
        prefsStore.relayServerUrl = url
        _helpState.update { it.copy(remote = it.remote.copy(relayUrl = url)) }
    }

    fun updateRemoteInputCode(code: String) {
        val clean = RemoteSessionCrypto.normalizeCode(code)
        _helpState.update { it.copy(remote = it.remote.copy(inputCode = clean)) }
    }

    fun updateRemoteHelperHost(host: String) {
        _helpState.update { it.copy(remote = it.remote.copy(helperTargetHost = host)) }
    }

    fun startGuestRemoteSession() {
        val relay = _helpState.value.remote.relayUrl.trim()
        if (!relay.startsWith("http://") && !relay.startsWith("https://")) {
            _helpState.update {
                it.copy(remote = it.remote.copy(statusMessage = "Укажите адрес relay-сервера (https://... или http://...)"))
            }
            return
        }
        stopRemoteSession(notifyServer = false)
        val code = RemoteSessionCrypto.generateCode()
        val expires = System.currentTimeMillis() + 15 * 60_000L
        _helpState.update {
            it.copy(
                remote = it.remote.copy(
                    activeRole = RemoteRole.GUEST,
                    sessionCode = code,
                    isActive = true,
                    isBusy = false,
                    expiresAtEpochMs = expires,
                    statusMessage = "Сеанс открыт. Продиктуйте код ${RemoteSessionCrypto.formatCode(code)} помощнику.",
                    feed = emptyList(),
                ),
            )
        }
        startRemotePollingLoop(relay, code, RemoteRole.GUEST, expires)
    }

    fun startHelperRemoteSession() {
        val st = _helpState.value.remote
        val relay = st.relayUrl.trim()
        val code = RemoteSessionCrypto.normalizeCode(st.inputCode)
        if (!relay.startsWith("http://") && !relay.startsWith("https://")) {
            _helpState.update {
                it.copy(remote = it.remote.copy(statusMessage = "Укажите адрес relay-сервера (https://... или http://...)"))
            }
            return
        }
        if (code.length != 6) {
            _helpState.update {
                it.copy(remote = it.remote.copy(statusMessage = "Введите 6 цифр кода, который продиктовал близкий"))
            }
            return
        }
        stopRemoteSession(notifyServer = false)
        val expires = System.currentTimeMillis() + 15 * 60_000L
        _helpState.update {
            it.copy(
                remote = it.remote.copy(
                    activeRole = RemoteRole.HELPER,
                    sessionCode = code,
                    isActive = true,
                    isBusy = false,
                    expiresAtEpochMs = expires,
                    statusMessage = "Подключено к сеансу ${RemoteSessionCrypto.formatCode(code)}. Можно запускать проверки.",
                    feed = emptyList(),
                ),
            )
        }
        startRemotePollingLoop(relay, code, RemoteRole.HELPER, expires)
    }

    fun sendRemoteCommand(cmdType: String, target: String = "") {
        val st = _helpState.value.remote
        if (!st.isActive || st.activeRole != RemoteRole.HELPER) return
        val cleanTarget = target.trim().ifBlank { st.helperTargetHost.trim().ifBlank { "ya.ru" } }
        viewModelScope.launch {
            _helpState.update { it.copy(remote = it.remote.copy(isBusy = true, statusMessage = "Отправляем команду...")) }
            val res = RemoteHelpRelayClient.sendEncrypted(
                baseUrl = st.relayUrl,
                code = st.sessionCode,
                role = RemoteRole.HELPER,
                type = cmdType,
                target = cleanTarget,
                title = "Запрос: $cmdType ($cleanTarget)",
            )
            res.onSuccess {
                appendRemoteFeed(fromSelf = true, title = "Команда отправлена: $cmdType", body = cleanTarget)
                _helpState.update { it.copy(remote = it.remote.copy(isBusy = false, statusMessage = "Ждём результат с телефона близкого...")) }
            }.onFailure { err ->
                _helpState.update { it.copy(remote = it.remote.copy(isBusy = false, statusMessage = "Ошибка отправки: ${err.message}")) }
            }
        }
    }

    fun stopRemoteSession(notifyServer: Boolean = true) {
        remotePollJob?.cancel()
        remotePollJob = null
        val prev = _helpState.value.remote
        if (notifyServer && prev.isActive && prev.sessionCode.length == 6 && prev.relayUrl.isNotBlank()) {
            viewModelScope.launch {
                RemoteHelpRelayClient.closeRoom(prev.relayUrl, prev.sessionCode)
            }
        }
        _helpState.update {
            it.copy(
                remote = it.remote.copy(
                    activeRole = null,
                    isActive = false,
                    isBusy = false,
                    statusMessage = if (prev.isActive) "Сеанс помощи завершён" else prev.statusMessage,
                ),
            )
        }
    }

    private fun startRemotePollingLoop(
        relayUrl: String,
        code: String,
        role: RemoteRole,
        expiresAt: Long,
    ) {
        remotePollJob = viewModelScope.launch {
            var lastSeq = 0
            // Приветствие о входе в комнату
            RemoteHelpRelayClient.sendEncrypted(
                baseUrl = relayUrl,
                code = code,
                role = role,
                type = "hello",
                title = if (role == RemoteRole.GUEST) "Телефон готов к проверке" else "Помощник подключился",
            ).onSuccess { seq -> if (seq > lastSeq) lastSeq = seq }

            while (isActive && System.currentTimeMillis() < expiresAt) {
                val poll = RemoteHelpRelayClient.pollEncrypted(relayUrl, code, role, afterSeq = lastSeq)
                poll.onSuccess { msgs ->
                    for (m in msgs) {
                        if (m.seq > lastSeq) lastSeq = m.seq
                        if (m.sender == role.wire) continue
                        when {
                            m.type == "hello" -> {
                                appendRemoteFeed(fromSelf = false, title = m.title.ifBlank { "Собеседник на связи" })
                                _helpState.update { it.copy(remote = it.remote.copy(statusMessage = m.title.ifBlank { "Собеседник подключился" })) }
                            }
                            role == RemoteRole.GUEST && m.type.startsWith("cmd_") -> {
                                executeGuestRemoteCommand(relayUrl, code, m.type, m.target)
                            }
                            role == RemoteRole.HELPER && m.type == "result" -> {
                                appendRemoteFeed(fromSelf = false, title = m.title, body = m.body, outcome = m.outcome)
                                _helpState.update { it.copy(remote = it.remote.copy(statusMessage = "Получен результат: ${m.title}")) }
                            }
                        }
                    }
                }.onFailure { err ->
                    _helpState.update { it.copy(remote = it.remote.copy(statusMessage = "Нет связи с relay: ${err.message}")) }
                }
                delay(2000L)
            }
            if (isActive) {
                stopRemoteSession(notifyServer = true)
            }
        }
    }

    private suspend fun executeGuestRemoteCommand(
        relayUrl: String,
        code: String,
        cmdType: String,
        target: String,
    ) {
        val kind = when (cmdType) {
            "cmd_network" -> SuiteStepKind.NETWORK
            "cmd_site" -> SuiteStepKind.SITE
            "cmd_dns" -> SuiteStepKind.DNS
            "cmd_udp" -> SuiteStepKind.UDP
            "cmd_mtu" -> SuiteStepKind.MTU
            "cmd_wifi" -> SuiteStepKind.WIFI
            else -> SuiteStepKind.NETWORK
        }
        val cleanTarget = target.ifBlank { "ya.ru" }
        appendRemoteFeed(fromSelf = false, title = "Помощник запустил: ${kind.name}", body = cleanTarget)
        _helpState.update { it.copy(remote = it.remote.copy(isBusy = true, statusMessage = "Выполняется проверка: $cleanTarget...")) }

        val res = executeSuiteStep(SuiteStep(index = 1, kind = kind, target = cleanTarget))
        val body = buildString {
            appendLine(res.headline)
            if (res.recommendations.isNotEmpty()) {
                appendLine("Совет: ${res.recommendations.first()}")
            }
            if (res.detailedText.isNotBlank()) {
                append(res.detailedText.take(600))
            }
        }.trim()

        RemoteHelpRelayClient.sendEncrypted(
            baseUrl = relayUrl,
            code = code,
            role = RemoteRole.GUEST,
            type = "result",
            target = cleanTarget,
            title = "${kind.name} ($cleanTarget): ${res.outcome.name}",
            body = body,
            outcome = res.outcome.name,
        )
        appendRemoteFeed(fromSelf = true, title = "Отправлен результат: ${res.outcome.name}", body = res.headline, outcome = res.outcome.name)
        _helpState.update { it.copy(remote = it.remote.copy(isBusy = false, statusMessage = "Результат отправлен помощнику")) }
    }

    private fun appendRemoteFeed(fromSelf: Boolean, title: String, body: String = "", outcome: String = "") {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val entry = RemoteFeedEntry(
            id = ++remoteFeedCounter,
            timeLabel = time,
            fromSelf = fromSelf,
            title = title,
            body = body,
            outcome = outcome,
        )
        _helpState.update { st ->
            st.copy(remote = st.remote.copy(feed = (listOf(entry) + st.remote.feed).take(40)))
        }
    }

    // ==================== Wi-Fi Анализатор (Каналы, OUI, Живой RSSI) ====================

    fun scanWifiAnalyzer(triggerActiveScan: Boolean = true) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                probes.scanWifiChannelsDetailed(triggerActiveScan = triggerActiveScan)
            }
            if (res.isSuccess) {
                applyWifiScanReport(res.getOrThrow(), triggerActiveScan)
            } else {
                _wifiAnalyzerState.update {
                    it.copy(errorMessage = res.exceptionOrNull()?.message)
                }
            }
        }
    }

    fun toggleLiveWifiScan() {
        if (_wifiAnalyzerState.value.isLiveScanRunning) {
            stopLiveWifiScan()
        } else {
            startLiveWifiScan()
        }
    }

    fun startLiveWifiScan() {
        if (liveWifiScanJob?.isActive == true) return
        _wifiAnalyzerState.update { it.copy(isLiveScanRunning = true, errorMessage = null) }
        liveWifiScanJob = viewModelScope.launch {
            try {
                probes.observeLiveWifiScans(intervalMs = 2800L).collect { report ->
                    applyWifiScanReport(report, triggerActiveScan = true)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _wifiAnalyzerState.update { it.copy(errorMessage = e.message) }
                }
            } finally {
                _wifiAnalyzerState.update { it.copy(isLiveScanRunning = false) }
            }
        }
    }

    fun stopLiveWifiScan() {
        liveWifiScanJob?.cancel()
        liveWifiScanJob = null
        _wifiAnalyzerState.update { it.copy(isLiveScanRunning = false) }
    }

    private fun applyWifiScanReport(report: dev.whynet.core.probes.WifiScanReport, triggerActiveScan: Boolean) {
        val samples = report.entries.map { e ->
            WifiApSample(
                ssid = e.ssid,
                bssid = e.rawBssid,
                frequencyMhz = e.frequencyMhz,
                centerFreqMhz = e.centerFreqMhz,
                bandGhz = e.bandGhz,
                channel = e.channel,
                channelWidthMhz = e.channelWidthMhz,
                rssiDbm = e.rssiDbm,
                isConnected = e.isConnected,
            )
        }
        val rec24 = WifiChannelAnalyzer.analyzeBand(samples, 2.4)
        val rec5 = WifiChannelAnalyzer.analyzeBand(samples, 5.0)
        val rec6 = WifiChannelAnalyzer.analyzeBand(samples, 6.0)
        val int24 = WifiChannelAnalyzer.analyzeChannelInterferers(samples, 2.4)
        val int5 = WifiChannelAnalyzer.analyzeChannelInterferers(samples, 5.0)
        val int6 = WifiChannelAnalyzer.analyzeChannelInterferers(samples, 6.0)
        _wifiAnalyzerState.update {
            it.copy(
                entries = report.entries,
                rec24 = rec24,
                rec5 = rec5,
                rec6 = rec6,
                interference24 = int24,
                interference5 = int5,
                interference6 = int6,
                supports6Ghz = report.supports6Ghz,
                lastScanEpochMs = report.scannedAtEpochMs,
                throttledHint = false,
                errorMessage = null,
            )
        }
    }

    fun startLiveWifiSignal() {
        if (liveWifiJob?.isActive == true) return
        _wifiAnalyzerState.update { it.copy(isLiveSignalRunning = true, liveTicks = emptyList(), roamingEvents = emptyList()) }
        liveWifiJob = viewModelScope.launch {
            var lastBssid: String? = null
            var lastBand: Double? = null
            val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            val gwIp = _checkState.value.liveSnapshot?.gatewayIp
            try {
                probes.observeLiveWifiSignal(gatewayIp = gwIp, intervalMs = 1000L).collect { tick ->
                    val newEvents = mutableListOf<String>()
                    if (tick.isWifiConnected) {
                        val b = tick.rawBssid.takeIf { it.isNotBlank() }
                        val band = tick.bandGhz
                        if (lastBssid != null && b != null && !b.equals(lastBssid, ignoreCase = true)) {
                            newEvents += "${fmt.format(java.util.Date(tick.timestampMs))} Роуминг точки -> ${tick.bssidDisplay} (${band ?: "-"} ГГц, к.${tick.channel ?: "-"}, ${tick.rssiDbm} дБм)"
                        } else if (lastBand != null && band != null && lastBand != band) {
                            newEvents += "${fmt.format(java.util.Date(tick.timestampMs))} Смена диапазона: $lastBand -> $band ГГц (к.${tick.channel ?: "-"})"
                        }
                        if (b != null) lastBssid = b
                        if (band != null) lastBand = band
                    }
                    _wifiAnalyzerState.update { st ->
                        st.copy(
                            liveTicks = (st.liveTicks + tick).takeLast(120),
                            roamingEvents = if (newEvents.isEmpty()) st.roamingEvents else (st.roamingEvents + newEvents).takeLast(20),
                        )
                    }
                }
            } finally {
                _wifiAnalyzerState.update { it.copy(isLiveSignalRunning = false) }
            }
        }
    }

    fun stopLiveWifiSignal() {
        liveWifiJob?.cancel()
        liveWifiJob = null
        _wifiAnalyzerState.update { it.copy(isLiveSignalRunning = false) }
    }

    // ==================== Карта помещения (Wi-Fi Heatmap) ====================

    fun selectHeatmapLayer(layer: HeatmapLayer) {
        _wifiAnalyzerState.update { it.copy(heatmapLayer = layer) }
    }

    fun applyHeatmapPreset(presetId: String, title: String) {
        val updated = _wifiAnalyzerState.value.heatmapPlan.copy(
            name = title,
            backgroundImagePath = null,
            walls = WifiHeatmapEngine.presetWalls(presetId),
        )
        persistHeatmapPlan(updated)
    }

    fun importHeatmapFloorImage(uri: android.net.Uri) {
        viewModelScope.launch {
            val savedPath = withContext(Dispatchers.IO) {
                HeatmapExporter.importFloorImage(getApplication(), uri)
            } ?: return@launch
            val updated = _wifiAnalyzerState.value.heatmapPlan.copy(
                name = "Фото планировки",
                backgroundImagePath = savedPath,
                walls = emptyList(),
            )
            persistHeatmapPlan(updated)
        }
    }

    fun addHeatmapWall(wall: HeatmapWall) {
        val cur = _wifiAnalyzerState.value.heatmapPlan
        persistHeatmapPlan(cur.copy(walls = cur.walls + wall))
    }

    fun undoHeatmapWall() {
        val cur = _wifiAnalyzerState.value.heatmapPlan
        if (cur.walls.isNotEmpty()) {
            persistHeatmapPlan(cur.copy(walls = cur.walls.dropLast(1)))
        }
    }

    fun measureHeatmapPoint(x: Float, y: Float) {
        if (_wifiAnalyzerState.value.isMeasuringHeatmapPoint) return
        _wifiAnalyzerState.update { it.copy(isMeasuringHeatmapPoint = true) }
        viewModelScope.launch {
            try {
                val snap = envCollector.collectSnapshot(fetchExternalIp = false)
                val liveTick = probes.observeLiveWifiSignal(intervalMs = 200L, maxTicks = 1).firstOrNull()
                val gwRtt = probes.probeGatewayQuickRtt(snap.gatewayIp)
                val scanRes = withContext(Dispatchers.IO) {
                    probes.scanWifiChannelsDetailed(triggerActiveScan = false)
                }.getOrNull()
                val visible = scanRes?.entries?.take(16)?.map { e ->
                    HeatmapNetworkReading(
                        ssid = e.ssid,
                        bandGhz = e.bandGhz,
                        channel = e.channel,
                        rssiDbm = e.rssiDbm,
                        isConnected = e.isConnected,
                        bssid = e.rawBssid,
                    )
                }.orEmpty()
                val bestAp = visible.maxByOrNull { it.rssiDbm }

                val curPlan = _wifiAnalyzerState.value.heatmapPlan
                val nextNum = curPlan.points.size + 1
                val pt = HeatmapPoint(
                    id = "pt_${System.currentTimeMillis()}",
                    label = "Т$nextNum",
                    x = x,
                    y = y,
                    capturedAtEpochMs = System.currentTimeMillis(),
                    connectedSsid = liveTick?.ssid ?: "Wi-Fi",
                    connectedBandGhz = liveTick?.bandGhz ?: snap.wifiBandGhz,
                    connectedChannel = liveTick?.channel,
                    connectedRssiDbm = liveTick?.rssiDbm ?: snap.wifiRssiDbm,
                    gatewayRttMs = gwRtt,
                    bestOverallSsid = bestAp?.ssid,
                    bestOverallBandGhz = bestAp?.bandGhz,
                    bestOverallChannel = bestAp?.channel,
                    bestOverallRssiDbm = bestAp?.rssiDbm,
                    visibleNetworks = visible,
                )
                persistHeatmapPlan(curPlan.copy(points = curPlan.points + pt))
            } finally {
                _wifiAnalyzerState.update { it.copy(isMeasuringHeatmapPoint = false) }
            }
        }
    }

    fun deleteHeatmapPoint(pointId: String) {
        val cur = _wifiAnalyzerState.value.heatmapPlan
        persistHeatmapPlan(cur.copy(points = cur.points.filterNot { it.id == pointId }))
    }

    fun clearHeatmapPoints() {
        val cur = _wifiAnalyzerState.value.heatmapPlan
        persistHeatmapPlan(cur.copy(points = emptyList()))
    }

    private fun persistHeatmapPlan(plan: HeatmapFloorPlan) {
        _wifiAnalyzerState.update { it.copy(heatmapPlan = plan) }
        viewModelScope.launch(Dispatchers.IO) {
            HeatmapExporter.savePlan(getApplication(), plan)
        }
    }

    // ==================== LAN-сканер устройств ====================

    fun startLanScan() {
        lanScanJob?.cancel()
        _wifiAnalyzerState.update { it.copy(isScanningLan = true, lanScanPercent = 0, lanError = null) }
        lanScanJob = viewModelScope.launch {
            try {
                val snap = envCollector.collectSnapshot(fetchExternalIp = false)
                if (snap.localIpv4.isNullOrBlank()) {
                    _wifiAnalyzerState.update {
                        it.copy(
                            isScanningLan = false,
                            lanError = "Нет локального IPv4 (подключитесь к Wi-Fi для сканирования устройств).",
                        )
                    }
                    return@launch
                }
                val found = probes.scanLanDevices(
                    localIpv4 = snap.localIpv4,
                    gatewayIp = snap.gatewayIp,
                    onProgress = { pct ->
                        _wifiAnalyzerState.update { it.copy(lanScanPercent = pct) }
                    },
                )
                _wifiAnalyzerState.update {
                    it.copy(
                        lanDevices = found,
                        isScanningLan = false,
                        lanScanPercent = 100,
                        lanError = null,
                    )
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _wifiAnalyzerState.update {
                        it.copy(isScanningLan = false, lanError = "Ошибка сканирования: ${e.message}")
                    }
                }
            }
        }
    }

    fun stopLanScan() {
        lanScanJob?.cancel()
        lanScanJob = null
        _wifiAnalyzerState.update { it.copy(isScanningLan = false) }
    }
}
