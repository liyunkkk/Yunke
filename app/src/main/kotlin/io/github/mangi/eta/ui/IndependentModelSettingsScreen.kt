package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AssistantOverlaySettings
import io.github.mangi.eta.data.model.ConversationTitleSettings
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Configuration only: the existing Yunke glass-capsule overlay is unchanged. */
@Composable
internal fun IndependentModelSettingsScreen(overlay: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val titleSettings by remember { SettingsDataStore.conversationTitleSettingsFlow() }
        .collectAsState(initial = ConversationTitleSettings())
    val overlaySettings by remember { SettingsDataStore.assistantOverlaySettingsFlow() }
        .collectAsState(initial = AssistantOverlaySettings())
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val mainModelId by remember { SettingsDataStore.selectedModelIdFlow() }.collectAsState(initial = null)
    val modelId = if (overlay) overlaySettings.modelId else titleSettings.modelId
    val effort = if (overlay) overlaySettings.reasoningEffort else titleSettings.reasoningEffort
    val selectedProvider = providers.firstOrNull { p -> p.models.any { it.id == modelId } }
    val effectiveId = modelId ?: mainModelId
    val effectiveProvider = providers.firstOrNull { p -> p.isEnabled && p.models.any { it.id == effectiveId && it.isEnabled } }
    val effectiveModel = effectiveProvider?.models?.firstOrNull { it.id == effectiveId && it.isEnabled }
    val capabilities = remember(effectiveProvider, effectiveModel) {
        if (effectiveProvider != null && effectiveModel != null)
            RuntimeConfigRepository.buildRuntimeConfig(effectiveProvider, effectiveModel).reasoningCapabilities else null
    }
    val efforts = listOf<ReasoningEffort?>(null) + capabilities?.selectableEfforts.orEmpty().ifEmpty {
        if (capabilities?.mandatory == true) listOf(ReasoningEffort.DEFAULT) else listOf(ReasoningEffort.OFF)
    }
    val models = remember(providers, modelId) {
        val all = AgentModelPickerProjector.project(providers, selectedProvider?.id, modelId)
        all.copy(providerGroups = all.providerGroups.map { group ->
            group.copy(models = group.models.filter { !it.supportsImageGeneration && !it.supportsVideoGeneration })
        }.filter { it.models.isNotEmpty() })
    }
    var picker by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(model: String? = modelId, reasoning: String? = effort, enabled: Boolean = titleSettings.enabled) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                if (overlay) SettingsDataStore.setAssistantOverlaySettings(AssistantOverlaySettings(model, reasoning))
                else SettingsDataStore.setConversationTitleSettings(ConversationTitleSettings(enabled, model, reasoning))
                error = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (failure: Exception) { error = failure.message ?: "设置保存失败"
            } finally { saving = false }
        }
    }
    val title = if (overlay) "浮窗模型与思考" else "自动会话标题"
    MiuixScaffoldPage(title = title, onBack = onBack) {
        item {
            Text(if (overlay) "仅影响电源键与系统助手浮窗；主聊天和玻璃胶囊界面保持原样。"
                else "首轮成功完成并保存后，在后台提炼标题；手动命名、分支和归档操作会跳过。",
                Modifier.padding(24.dp))
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                if (!overlay) SwitchPreference(title = "自动生成标题", checked = titleSettings.enabled,
                    onCheckedChange = { save(enabled = it) })
                ArrowPreference(title = "独立模型", summary = models.selectedModel?.let { "${it.providerName} / ${it.displayName}" }
                    ?: if (modelId == null) "跟随${if (overlay) "主聊天" else "当前会话"}模型" else "所选模型已停用，将跟随默认模型",
                    onClick = { if (!saving) picker = true })
                if (modelId != null) ArrowPreference(title = "恢复跟随模型", onClick = { save(model = null) })
                WindowSpinnerPreference(title = "思考等级",
                    summary = if (overlay) "自动跟随默认思考设置；显式档位独立生效" else "自动取模型最低可用档位",
                    items = efforts.map { DropdownItem(text = it?.displayName ?: "自动") },
                    selectedIndex = efforts.indexOf(ReasoningEffort.fromWireValue(effort)).coerceAtLeast(0),
                    onSelectedIndexChange = { index -> if (!saving) save(reasoning = efforts.getOrNull(index)?.wireValue) })
            }
            error?.let { Text(it, Modifier.padding(24.dp)) }
        }
    }
    TtsModelPickerDialog(models, picker, { picker = false }, { _, id ->
        save(model = id); picker = false
    }, title)
}
