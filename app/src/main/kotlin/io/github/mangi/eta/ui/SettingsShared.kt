package io.github.mangi.eta.ui

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.voice.EtaVoiceInteractionService
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.systemizer.RootManager
import io.github.mangi.eta.systemizer.SystemizerInstallResult
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import io.github.mangi.eta.ui.components.SwitchPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.window.WindowDialog

// ── 设置页共享：状态持有器与开关 helper（一级页与各二级页共用） ─────────────
// 来源：Yunke SettingsScreen.kt 底部 helper（原样搬出，只改可见性 private → internal，
// 逻辑一字未改）+ Number444 SettingsShared.kt 的 PermissionSummaryState（按 Yunke 改编）。
// 不含 maskSecretSummary / ApiKeyDialog（搜索 lane 才用）、open*Settings（三段式跳系统设置
// helper，本 lane 没有调用方）、rememberFrameworkPrefsState（本 lane 没有调用方：
// 一级页的接管入口保持无条件显示，SystemTakeoverSettingsScreen 自持 prefs 绑定）。
// 不含 settingsTopSpacing()：Yunke 的页都用 SmallTitle + Card，不需要裸卡片页的 12dp 补白。

/**
 * 超级岛支持状态（Yunke 临时版）。
 *
 * Yunke 里没有 Number444 的 `AgentIslandNotifier`（Yunke 的岛做在 1107 号前台通知里，
 * 见超级岛 lane 报告），所以本 lane 没有探针可调：[rememberPermissionSummaryState] 始终
 * 把岛状态留在 [CHECKING]，[PermissionSummaryState.unauthorizedCount] 的岛项恒为通过。
 * 岛 lane 落地后，把这个 enum 删掉，字段类型改成 `AgentIslandNotifier.SupportStatus`，
 * 并在 `rememberPermissionSummaryState` 里接上后台探测（Number444 原写法见其
 * `SettingsShared.kt:139-145` 的 `LaunchedEffect(Unit)`）。
 */
internal enum class IslandSupportStatus {
    CHECKING,
    SUPPORTED,
    NO_PERMISSION,
    UNSUPPORTED,
}

/** 权限/通知状态：一级页聚合计数共用，ON_RESUME 自动刷新。 */
internal class PermissionSummaryState internal constructor(
    overlayState: MutableState<Boolean>,
    accessibilityState: MutableState<Boolean>,
    protectionEnabledState: MutableState<Boolean>,
    islandState: MutableState<IslandSupportStatus>,
    notificationsState: MutableState<Boolean>,
) {
    var overlayGranted by overlayState
    var accessibilityGranted by accessibilityState
    var accessibilityProtectionEnabled by protectionEnabledState
    var islandStatus by islandState
    var notificationsGranted by notificationsState

    /** 悬浮窗 / 无障碍 / 超级岛三项中未授权的数量（岛不支持或检测中不计入）。 */
    val unauthorizedCount: Int
        get() = listOf(
            overlayGranted,
            accessibilityGranted || AgentAccessibilityService.isAvailable(),
            islandStatus != IslandSupportStatus.NO_PERMISSION,
        ).count { !it }
}

@Composable
internal fun rememberPermissionSummaryState(context: Context): PermissionSummaryState {
    val state = remember {
        PermissionSummaryState(
            overlayState = mutableStateOf(StreamPerformanceDiagnostics.measure("settings.permission.overlay.initial") { android.provider.Settings.canDrawOverlays(context) }),
            accessibilityState = mutableStateOf(StreamPerformanceDiagnostics.measure("settings.permission.accessibility.initial") { isAgentAccessibilityEnabled(context) }),
            protectionEnabledState = mutableStateOf(StreamPerformanceDiagnostics.measure("settings.permission.protection.initial") { AccessibilityProtectionClient.isEnabled(context) }),
            // 岛项：本 lane 无探针，恒为 CHECKING（不计入 unauthorizedCount），岛 lane 接线。
            islandState = mutableStateOf(IslandSupportStatus.CHECKING),
            notificationsState = mutableStateOf(
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(),
            ),
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.overlayGranted = StreamPerformanceDiagnostics.measure("settings.permission.overlay.resume") { android.provider.Settings.canDrawOverlays(context) }
                state.accessibilityGranted = StreamPerformanceDiagnostics.measure("settings.permission.accessibility.resume") { isAgentAccessibilityEnabled(context) }
                state.accessibilityProtectionEnabled =
                    StreamPerformanceDiagnostics.measure("settings.permission.protection.resume") { AccessibilityProtectionClient.isEnabled(context) }
                state.notificationsGranted = androidx.core.app.NotificationManagerCompat.from(context)
                    .areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

internal fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java
    ).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * 配置来源由调用方按能力边界传入。Hook 开关仍可能因 LSPosed 未连接而禁用；Agent
 * Runtime 开关始终使用 App 本地配置。
 * （从 SettingsScreen.kt 原样搬出；Yunke 签名没有 Number444 的 iconTint 参数，
 * 图标 tint 是 PreferenceIcon 的默认行为。）
 */
@Composable
internal fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
) {
    val enabled = prefs != null
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            PreferenceIcon(icon = icon, enabled = enabled)
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
internal fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

internal fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

internal fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "YUNKe"
    }

internal fun isEtaAssistantActive(context: Context): Boolean =
    VoiceInteractionService.isActiveService(
        context,
        ComponentName(context, EtaVoiceInteractionService::class.java),
    )

internal fun SystemizerInstallResult.toToastMessage(context: Context): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> context.getString(R.string.systemizer_already_system)
        SystemizerInstallResult.GoogleAppMissing -> context.getString(R.string.systemizer_google_missing)
        SystemizerInstallResult.UnsupportedRootManager -> context.getString(R.string.systemizer_root_manager_missing)
        SystemizerInstallResult.KernelSuMetamoduleMissing -> context.getString(R.string.systemizer_metamodule_missing)
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> context.getString(R.string.systemizer_grant_kernelsu)
            RootManager.MAGISK -> context.getString(R.string.systemizer_grant_magisk)
            RootManager.UNSUPPORTED -> context.getString(R.string.systemizer_root_denied)
        }
        is SystemizerInstallResult.InstalledRebootRequired -> context.getString(R.string.systemizer_installed)
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }

internal fun defaultDiagnosticLogFileName(): String =
    "YUNKe-诊断日志-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip"

// ── 系统化确认对话框 ─────────────────────────────────────────────────────────

@Composable
internal fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.ui_convert_google_apps_to_system_apps_0f6d89),
        summary = stringResource(R.string.ui_system_applications_have_voice_wake_up_permissions_f_0190f2),
        onDismissRequest = onDismissRequest,
    ) {
        MiuixDialogActions(
            confirmText = if (installing) {
                stringResource(R.string.status_processing)
            } else {
                stringResource(R.string.action_confirm)
            },
            cancelEnabled = !installing,
            confirmEnabled = !installing,
            onCancel = onDismissRequest,
            onConfirm = onConfirm,
        )
    }
}

@Composable
internal fun SettingsProviderEntry(
    currentProviderId: String?,
    currentModelId: String?,
    onClick: () -> Unit,
) {
    // Provider / Model 选中状态展示
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val storedProviderId by remember { RuntimeConfigRepository.selectedProviderIdFlow() }
        .collectAsState(initial = null)
    val storedModelId by remember { RuntimeConfigRepository.selectedModelIdFlow() }
        .collectAsState(initial = null)
    val selectedProviderId = currentProviderId?.takeIf { it.isNotBlank() } ?: storedProviderId
    val selectedModelId = currentModelId?.takeIf { it.isNotBlank() } ?: storedModelId
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    ArrowPreference(
        title = stringResource(R.string.ui_model_provider_e8c7f5),
        summary = providerSummary,
        startAction = {
            PreferenceIcon(
                icon = Icons.Rounded.Memory,
            )
        },
        onClick = onClick,
    )
}
