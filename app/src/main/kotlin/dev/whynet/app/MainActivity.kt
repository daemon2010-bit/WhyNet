package dev.whynet.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.whynet.app.report.AppDiagnosticLog
import dev.whynet.app.report.BugReportExporter
import dev.whynet.app.report.HeatmapExporter
import dev.whynet.app.report.IssueReportRequest
import dev.whynet.app.report.ShareTarget
import dev.whynet.app.report.SimpleHelpFormatter
import dev.whynet.app.report.SuiteRunRecord
import dev.whynet.app.report.SuiteRunnerReport
import dev.whynet.app.ui.components.IssueReportDialog
import dev.whynet.app.ui.screens.CheckScreen
import dev.whynet.app.ui.screens.HistoryScreen
import dev.whynet.app.ui.screens.ToolsScreen
import dev.whynet.app.ui.screens.WifiAnalyzerScreen
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.whynet.app.service.DropoutWatcherService
import dev.whynet.app.ui.theme.AppLanguage
import dev.whynet.app.ui.theme.AppThemeMode
import dev.whynet.app.ui.theme.WhyNetColors
import dev.whynet.app.ui.theme.WhyNetTheme
import dev.whynet.app.ui.theme.tr
import dev.whynet.core.engine.HelpLinkCodec
import dev.whynet.core.engine.HelpTaskSpec
import dev.whynet.core.model.ActionType
import dev.whynet.core.model.DiagnosticSession
import dev.whynet.core.model.RecommendedAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppTab {
    data object Check : AppTab
    data object Wifi : AppTab
    data object Tools : AppTab
    data object History : AppTab
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val appLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
            WhyNetTheme(themeMode = themeMode, language = appLanguage) {
                WhyNetAppShell(
                    viewModel = viewModel,
                    themeMode = themeMode,
                    appLanguage = appLanguage,
                    onSelectTheme = viewModel::setThemeMode,
                    onSelectLanguage = viewModel::setAppLanguage,
                    onOpenSystemAction = ::handleSystemAction,
                    onCopyReport = ::copySessionReport,
                    onShareReport = ::shareSessionReport,
                    onExportArchiveZip = ::exportArchiveZip,
                    onExportIssueReport = ::exportIssueReportTxt,
                    onExportSuiteReport = ::exportSuiteReportTxt,
                    onExportSuiteTemplate = ::exportSuiteTemplateTxt,
                    onReadSuiteUri = ::readTextFromUri,
                    onLaunchApp = ::launchTargetApp,
                    onCopyHelpLink = ::copyHelpLink,
                    onShareHelpLink = ::shareHelpLink,
                    onShareHelpFile = ::shareHelpFile,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val dataStr = intent?.dataString ?: return
        if (viewModel.handleIncomingHelpText(dataStr)) {
            Toast.makeText(this, "Открыто задание помощи WhyNet", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyHelpLink(spec: HelpTaskSpec) {
        val link = HelpLinkCodec.encodeUri(spec)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WhyNet Help Link", link))
        Toast.makeText(this, "Ссылка на проверку скопирована", Toast.LENGTH_SHORT).show()
    }

    private fun shareHelpLink(spec: HelpTaskSpec, target: ShareTarget) {
        runCatching {
            SimpleHelpFormatter.shareHelpLinkText(this, spec, target)
        }.onFailure { err ->
            Toast.makeText(this, "Не удалось отправить ссылку: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareHelpFile(spec: HelpTaskSpec, target: ShareTarget) {
        runCatching {
            SimpleHelpFormatter.shareHelpTaskFile(this, spec, target)
        }.onFailure { err ->
            Toast.makeText(this, "Не удалось отправить файл: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleSystemAction(action: RecommendedAction) {
        val intent = when (action.type) {
            ActionType.OPEN_WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
            ActionType.OPEN_VPN_SETTINGS -> Intent(Settings.ACTION_VPN_SETTINGS)
            ActionType.OPEN_PRIVATE_DNS_SETTINGS,
            ActionType.OPEN_NETWORK_SETTINGS,
            ActionType.SWITCH_NETWORK_COMPARE,
            -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            else -> null
        }
        if (intent != null) {
            runCatching { startActivity(intent) }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        } else {
            Toast.makeText(this, action.description, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchTargetApp(packageName: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            startActivity(launch)
        } else {
            Toast.makeText(this, "Не удалось открыть $packageName", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copySessionReport(session: DiagnosticSession) {
        val report = viewModel.buildShareReport(session, asJson = false)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WhyNet Report", report))
        Toast.makeText(this, "Обезличенный отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun shareSessionReport(session: DiagnosticSession) {
        val report = viewModel.buildShareReport(session, asJson = false)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WhyNet: ${session.target.label}")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(shareIntent, "Поделиться отчётом WhyNet"))
    }

    private fun exportArchiveZip(sessions: List<DiagnosticSession>) {
        lifecycleScope.launch {
            val res = runCatching {
                withContext(Dispatchers.IO) {
                    BugReportExporter.exportAllSessionsZip(
                        context = this@MainActivity,
                        sessions = sessions,
                        liveSnapshot = viewModel.checkState.value.liveSnapshot,
                    )
                }
            }
            res.onSuccess { out ->
                Toast.makeText(this@MainActivity, "Архив ${out.fileName} готов (${sessions.size} отчётов)", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Ошибка архивации: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportIssueReportTxt(request: IssueReportRequest) {
        lifecycleScope.launch {
            val result = runCatching {
                val (content, targetLabel) = withContext(Dispatchers.IO) {
                    viewModel.buildIssueReportTxt(request)
                }
                BugReportExporter.writeAndShareTxt(
                    context = this@MainActivity,
                    request = request,
                    txtContent = content,
                    targetLabel = targetLabel,
                )
            }
            result.onSuccess { outcome ->
                val msg = if (outcome.fallbackToChooser) {
                    "Файл ${outcome.fileName} готов (${request.shareTarget.label} не найден, выберите приложение)"
                } else {
                    "Файл ${outcome.fileName} передан в ${outcome.deliveredVia}"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                AppDiagnosticLog.e("ReportExport", "Ошибка формирования .txt отчёта", err)
                Toast.makeText(this@MainActivity, "Не удалось сформировать отчёт: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportSuiteReportTxt(record: SuiteRunRecord, target: ShareTarget) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    SuiteRunnerReport.exportSuiteReportTxt(this@MainActivity, record, target)
                }
            }
            result.onSuccess { outcome ->
                val msg = if (outcome.fallbackToChooser) {
                    "Отчёт ${outcome.fileName} готов (${target.label} не найден, выберите приложение)"
                } else {
                    "Отчёт ${outcome.fileName} передан в ${outcome.deliveredVia}"
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                AppDiagnosticLog.e("SuiteExport", "Ошибка экспорта отчёта серии", err)
                Toast.makeText(this@MainActivity, "Ошибка экспорта: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportSuiteTemplateTxt() {
        runCatching {
            SuiteRunnerReport.exportTemplateTxt(this, ShareTarget.SYSTEM_CHOOSER)
        }.onFailure { err ->
            Toast.makeText(this, "Не удалось выгрузить шаблон: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readTextFromUri(uri: Uri): Pair<String, String>? = runCatching {
        var displayName = "suite.txt"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) displayName = cursor.getString(idx) ?: displayName
            }
        }
        val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return@runCatching null
        displayName to text
    }.getOrNull()
}

@Composable
private fun WhyNetAppShell(
    viewModel: MainViewModel,
    themeMode: AppThemeMode,
    appLanguage: AppLanguage,
    onSelectTheme: (AppThemeMode) -> Unit,
    onSelectLanguage: (AppLanguage) -> Unit,
    onOpenSystemAction: (RecommendedAction) -> Unit,
    onCopyReport: (DiagnosticSession) -> Unit,
    onShareReport: (DiagnosticSession) -> Unit,
    onExportArchiveZip: (List<DiagnosticSession>) -> Unit,
    onExportIssueReport: (IssueReportRequest) -> Unit,
    onExportSuiteReport: (SuiteRunRecord, ShareTarget) -> Unit,
    onExportSuiteTemplate: () -> Unit,
    onReadSuiteUri: (Uri) -> Pair<String, String>?,
    onLaunchApp: (String) -> Unit,
    onCopyHelpLink: (HelpTaskSpec) -> Unit,
    onShareHelpLink: (HelpTaskSpec, ShareTarget) -> Unit,
    onShareHelpFile: (HelpTaskSpec, ShareTarget) -> Unit,
) {
    val context = LocalContext.current
    val checkState by viewModel.checkState.collectAsStateWithLifecycle()
    val wifiAnalyzerState by viewModel.wifiAnalyzerState.collectAsStateWithLifecycle()
    val toolsState by viewModel.toolsState.collectAsStateWithLifecycle()
    val watcherState by viewModel.watcherState.collectAsStateWithLifecycle()
    val appObserverState by viewModel.appObserverState.collectAsStateWithLifecycle()
    val appAutoCheckState by viewModel.appAutoCheckState.collectAsStateWithLifecycle()
    val suiteState by viewModel.suiteState.collectAsStateWithLifecycle()
    val helpState by viewModel.helpState.collectAsStateWithLifecycle()
    val history by viewModel.historySessions.collectAsStateWithLifecycle()

    val backStack = remember { mutableStateListOf<AppTab>(AppTab.Check) }
    val currentTab = backStack.lastOrNull() ?: AppTab.Check
    var showIssueReportDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var pendingAutoAppCheck by remember { mutableStateOf(false) }
    var lastBackPressMs by remember { mutableStateOf(0L) }

    fun navigateToTab(tab: AppTab) {
        if (tab == AppTab.Check) {
            backStack.clear()
            backStack.add(AppTab.Check)
            return
        }
        if (backStack.lastOrNull() == tab) return
        if (backStack.isEmpty()) {
            backStack.add(AppTab.Check)
        }
        backStack.removeAll { it == tab }
        backStack.add(tab)
    }

    fun popBackStack(): Boolean {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            return true
        }
        return false
    }

    val wifiPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshLiveSnapshot()
        viewModel.scanWifiAnalyzer(triggerActiveScan = true)
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.runSelectedTool()
    }

    val floorImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.importHeatmapFloorImage(uri)
            Toast.makeText(context, "План помещения загружен", Toast.LENGTH_SHORT).show()
        }
    }

    val suiteFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val loaded = onReadSuiteUri(uri)
            if (loaded != null) {
                if (viewModel.handleIncomingHelpText(loaded.second)) {
                    Toast.makeText(context, "Открыто задание помощи из ${loaded.first}", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.loadSuiteFromText(loaded.second, loaded.first)
                    Toast.makeText(context, "Загружен сценарий: ${loaded.first}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Не удалось прочитать файл сценария", Toast.LENGTH_LONG).show()
            }
        }
    }

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            if (pendingAutoAppCheck) {
                pendingAutoAppCheck = false
                val pkg = viewModel.startAutoAppCheckConfirmed()
                if (pkg != null) onLaunchApp(pkg)
            } else {
                viewModel.startAppObserverConfirmed()
            }
        } else {
            pendingAutoAppCheck = false
            Toast.makeText(context, "Разрешение на локальный VPN не выдано", Toast.LENGTH_SHORT).show()
        }
    }

    fun startAppObserverWithVpnConsent(autoMode: Boolean) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            pendingAutoAppCheck = autoMode
            vpnPrepareLauncher.launch(prepareIntent)
        } else {
            if (autoMode) {
                val pkg = viewModel.startAutoAppCheckConfirmed()
                if (pkg != null) onLaunchApp(pkg)
            } else {
                viewModel.startAppObserverConfirmed()
            }
        }
    }

    fun runToolWithOptionalNotifPerm() {
        if (toolsState.selectedTool == ToolKind.DROPOUT_WATCHER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        viewModel.runSelectedTool()
    }

    BackHandler(enabled = !showSettingsDialog && !showIssueReportDialog) {
        if (!popBackStack()) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressMs < 2000L) {
                (context as? Activity)?.finish()
            } else {
                lastBackPressMs = now
                Toast.makeText(context, "Нажмите «Назад» ещё раз для выхода", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .navigationBarsPadding(),
            ) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val netSel = currentTab == AppTab.Check || currentTab == AppTab.History
                    val wifiSel = currentTab == AppTab.Wifi
                    val toolsSel = currentTab == AppTab.Tools

                    CompactNavTab(
                        label = tr("Сеть", "Network"),
                        selected = netSel,
                        onClick = { navigateToTab(AppTab.Check) },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_net_check),
                                contentDescription = null,
                                tint = if (netSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    CompactNavTab(
                        label = "Wi-Fi",
                        selected = wifiSel,
                        onClick = {
                            navigateToTab(AppTab.Wifi)
                            if (wifiAnalyzerState.entries.isEmpty()) {
                                viewModel.scanWifiAnalyzer(triggerActiveScan = false)
                            }
                        },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_wifi),
                                contentDescription = null,
                                tint = if (wifiSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    CompactNavTab(
                        label = tr("Утилиты", "Tools"),
                        selected = toolsSel,
                        onClick = {
                            viewModel.showToolsMenu()
                            navigateToTab(AppTab.Tools)
                        },
                        icon = {
                            Icon(
                                Icons.Rounded.Build,
                                contentDescription = null,
                                tint = if (toolsSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (watcherState.isRunning) {
                Surface(
                    color = WhyNetColors.OkContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.selectTool(ToolKind.DROPOUT_WATCHER)
                            navigateToTab(AppTab.Tools)
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "● Ловец обрывов: ${watcherState.lastLine ?: watcherState.host}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = WhyNetColors.OkGreen,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            color = WhyNetColors.StopButtonBg,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    DropoutWatcherService.stop(context)
                                    viewModel.stopTool()
                                },
                        ) {
                            Text(
                                text = "Стоп",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = WhyNetColors.OkGreen.copy(alpha = 0.35f))
            }

            NavDisplay(
                backStack = backStack,
                modifier = Modifier.weight(1f),
                onBack = {
                    if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                },
            entryProvider = entryProvider {
                entry<AppTab.Check> {
                    CheckScreen(
                        state = checkState,
                        onRefreshSnapshot = { viewModel.refreshLiveSnapshot(fetchExternalIp = true) },
                        onRefreshContours = viewModel::refreshQuickContours,
                        onSelectPreset = viewModel::selectPreset,
                        onSiteInputChange = viewModel::updateSiteInput,
                        onStartCheck = viewModel::startCheck,
                        onCancelCheck = viewModel::cancelCheck,
                        onSelectStageDetail = viewModel::selectStageDetail,
                        onActionClick = { action ->
                            if (action.type == ActionType.OPEN_DROPOUT_WATCHER) {
                                viewModel.openDropoutWatcherAndStart()
                                navigateToTab(AppTab.Tools)
                            } else {
                                onOpenSystemAction(action)
                            }
                        },
                        onVerifyAgain = viewModel::verifyLastSession,
                        onToggleRawLog = viewModel::toggleRawLog,
                        onCopyReport = onCopyReport,
                        onShareReport = onShareReport,
                        onOpenIssueReport = { showIssueReportDialog = true },
                        onOpenHistory = { navigateToTab(AppTab.History) },
                        onOpenSettings = { showSettingsDialog = true },
                        onOpenAppCheck = {
                            viewModel.selectTool(ToolKind.APP_OBSERVER)
                            navigateToTab(AppTab.Tools)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                entry<AppTab.Wifi> {
                    WifiAnalyzerScreen(
                        state = wifiAnalyzerState,
                        liveSnapshot = checkState.liveSnapshot,
                        onScanWifi = { viewModel.scanWifiAnalyzer(triggerActiveScan = true) },
                        onToggleLiveScan = viewModel::toggleLiveWifiScan,
                        onRequestWifiPermission = {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                arrayOf(
                                    Manifest.permission.NEARBY_WIFI_DEVICES,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                )
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            wifiPermLauncher.launch(perms)
                        },
                        onStartLiveSignal = viewModel::startLiveWifiSignal,
                        onStopLiveSignal = viewModel::stopLiveWifiSignal,
                        onOpenDevSettings = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            }.onFailure {
                                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                            }
                        },
                        onSelectHeatmapLayer = viewModel::selectHeatmapLayer,
                        onApplyHeatmapPreset = viewModel::applyHeatmapPreset,
                        onPickFloorImage = { floorImagePickerLauncher.launch("image/*") },
                        onAddHeatmapWall = viewModel::addHeatmapWall,
                        onUndoHeatmapWall = viewModel::undoHeatmapWall,
                        onTapMeasureHeatmapPoint = viewModel::measureHeatmapPoint,
                        onDeleteHeatmapPoint = viewModel::deleteHeatmapPoint,
                        onClearHeatmapPoints = viewModel::clearHeatmapPoints,
                        onShareHeatmapPng = {
                            runCatching {
                                HeatmapExporter.exportAndSharePng(
                                    context = context,
                                    plan = wifiAnalyzerState.heatmapPlan,
                                    layer = wifiAnalyzerState.heatmapLayer,
                                )
                            }.onFailure { err ->
                                Toast.makeText(context, "Ошибка экспорта PNG: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        onStartLanScan = viewModel::startLanScan,
                        onStopLanScan = viewModel::stopLanScan,
                        onBack = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                entry<AppTab.Tools> {
                    ToolsScreen(
                        state = toolsState,
                        watcherState = watcherState,
                        appObserverState = appObserverState,
                        appAutoCheckState = appAutoCheckState,
                        suiteState = suiteState,
                        hasActiveExternalVpn = checkState.liveSnapshot?.activeExternalVpn == true,
                        onSelectTool = viewModel::selectTool,
                        onShowToolsMenu = viewModel::showToolsMenu,
                        onHostChange = viewModel::updateToolHost,
                        onRunTool = ::runToolWithOptionalNotifPerm,
                        onStopTool = viewModel::stopTool,
                        onRequestWifiPermission = {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                arrayOf(
                                    Manifest.permission.NEARBY_WIFI_DEVICES,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                )
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            wifiPermLauncher.launch(perms)
                        },
                        onAppSearchChange = viewModel::updateAppSearchQuery,
                        onSelectApp = viewModel::selectAppForObserver,
                        onStartAutoAppCheck = { startAppObserverWithVpnConsent(autoMode = true) },
                        onFinishAppCaptureNow = viewModel::finishAppCaptureNow,
                        onCancelAppAutoCheck = viewModel::cancelAppAutoCheck,
                        onRunSavedAppProfile = viewModel::runSavedAppProfile,
                        onSaveCurrentAppProfile = {
                            viewModel.saveCurrentAppProfile()
                            Toast.makeText(context, "Серверы приложения сохранены для 1-тап проверки", Toast.LENGTH_SHORT).show()
                        },
                        onDeleteSavedAppProfile = viewModel::deleteSavedAppProfile,
                        onStartAppObserver = { startAppObserverWithVpnConsent(autoMode = false) },
                        onStopAppObserver = viewModel::stopAppObserver,
                        onLaunchSelectedApp = onLaunchApp,
                        onMarkAppFreezeHere = viewModel::markAppFreezeHere,
                        onDiagnoseCapturedDomain = { domain ->
                            backStack.clear()
                            backStack.add(AppTab.Check)
                            viewModel.checkDomainFromObserver(domain)
                        },
                        onUpdateSuiteEnvTag = viewModel::updateSuiteEnvTag,
                        onToggleSuiteEnvChip = viewModel::toggleSuiteEnvChip,
                        onPickSuiteFile = { suiteFileLauncher.launch(arrayOf("text/*", "*/*")) },
                        onLoadDefaultSuite = viewModel::loadDefaultSuite,
                        onExportSuiteTemplate = onExportSuiteTemplate,
                        onStartSuite = viewModel::startSuiteRun,
                        onStopSuite = viewModel::stopSuiteRun,
                        onExportSuiteReport = onExportSuiteReport,
                        onOpenWifiAnalyzer = {
                            if (backStack.lastOrNull() != AppTab.Wifi) {
                                backStack.add(AppTab.Wifi)
                            }
                            if (wifiAnalyzerState.entries.isEmpty()) {
                                viewModel.scanWifiAnalyzer(triggerActiveScan = false)
                            }
                        },
                        onPortSpecChange = viewModel::updatePortSpec,
                        onWolMacChange = viewModel::updateWolMac,
                        onWolBroadcastChange = viewModel::updateWolBroadcast,
                        onCidrChange = viewModel::updateCidrInput,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                entry<AppTab.History> {
                    HistoryScreen(
                        sessions = history,
                        onOpenSession = { session ->
                            viewModel.openSessionFromHistory(session)
                            backStack.clear()
                            backStack.add(AppTab.Check)
                        },
                        onExportSessionsZip = { list -> onExportArchiveZip(list) },
                        onDeleteSession = viewModel::deleteSession,
                        onClearAll = viewModel::clearHistory,
                        onBack = {
                            if (backStack.size > 1) {
                                backStack.removeAt(backStack.lastIndex)
                            } else {
                                backStack.clear()
                                backStack.add(AppTab.Check)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
        }
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text(tr("Настройки WhyNet", "WhyNet Settings")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = tr("Тема оформления", "Theme"),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onSelectTheme(mode) },
                                label = { Text(tr(mode.title, mode.titleEn)) },
                            )
                        }
                    }
                    Text(
                        text = tr("Язык / Language", "Language / Язык"),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AppLanguage.entries.forEach { lang ->
                            FilterChip(
                                selected = appLanguage == lang,
                                onClick = { onSelectLanguage(lang) },
                                label = { Text(lang.title) },
                            )
                        }
                    }
                    Text(
                        text = tr(
                            "• Автообновление при смене Wi-Fi / 4G+: Вкл\n• Автоопределение БС (eNB) и помех Wi-Fi: Вкл\n• Сервер теста скорости: Авто (РФ + Cloudflare)",
                            "• Auto-refresh on Wi-Fi / 4G+ switch: On\n• Auto cell tower (eNB) & Wi-Fi load: On\n• Speed test server: Auto (RU + Cloudflare)",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text(tr("Готово", "Done"))
                }
            },
        )
    }

    if (showIssueReportDialog) {
        val maxInstalled = remember(showIssueReportDialog) { BugReportExporter.isMaxInstalled(context) }
        val telegramInstalled = remember(showIssueReportDialog) { BugReportExporter.isTelegramInstalled(context) }
        val appLogCount = remember(showIssueReportDialog) { AppDiagnosticLog.snapshotLines().size }
        IssueReportDialog(
            hasDiagnosticSession = checkState.lastSession != null,
            appLogLinesCount = appLogCount,
            maxInstalled = maxInstalled,
            telegramInstalled = telegramInstalled,
            onDismiss = { showIssueReportDialog = false },
            onExport = { req ->
                showIssueReportDialog = false
                onExportIssueReport(req)
            },
        )
    }
}

@Composable
private fun CompactNavTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        icon()
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
