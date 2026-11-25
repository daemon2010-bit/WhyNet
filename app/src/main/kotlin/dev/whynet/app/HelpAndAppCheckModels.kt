package dev.whynet.app

import dev.whynet.app.report.SimpleHelpSummary
import dev.whynet.app.report.SuiteRunRecord
import dev.whynet.app.report.SuiteStepResult
import dev.whynet.app.service.RemoteRole
import dev.whynet.app.service.SavedAppProfile
import dev.whynet.core.engine.HelpPreset
import dev.whynet.core.engine.HelpTaskSpec

data class AppAutoCheckState(
    val isRunning: Boolean = false,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val selectedDomains: List<String> = emptyList(),
    val currentStepText: String? = null,
    val results: List<SuiteStepResult> = emptyList(),
    val lastRecord: SuiteRunRecord? = null,
    val summaryText: String? = null,
    val errorMessage: String? = null,
    val savedProfiles: List<SavedAppProfile> = emptyList(),
)

data class RemoteFeedEntry(
    val id: Int,
    val timeLabel: String,
    val fromSelf: Boolean,
    val title: String,
    val body: String = "",
    val outcome: String = "",
)

data class RemoteHelpUiState(
    val relayUrl: String = "",
    val activeRole: RemoteRole? = null,
    val sessionCode: String = "",
    val inputCode: String = "",
    val helperTargetHost: String = "ya.ru",
    val isActive: Boolean = false,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val feed: List<RemoteFeedEntry> = emptyList(),
)

data class HelpUiState(
    val simpleModeEnabled: Boolean = false,
    val helperName: String = "",
    val incomingTask: HelpTaskSpec? = null,
    val isRunningHelpCheck: Boolean = false,
    val helpProgressText: String? = null,
    val helpResults: List<SuiteStepResult> = emptyList(),
    val lastHelpRecord: SuiteRunRecord? = null,
    val lastSimpleSummary: SimpleHelpSummary? = null,
    val builderPreset: HelpPreset = HelpPreset.FULL_NETWORK,
    val builderTargetsText: String = "",
    val builderAutoStart: Boolean = true,
    val builderNote: String = "",
    val pasteLinkInput: String = "",
    val pasteLinkError: String? = null,
    val remote: RemoteHelpUiState = RemoteHelpUiState(),
)
