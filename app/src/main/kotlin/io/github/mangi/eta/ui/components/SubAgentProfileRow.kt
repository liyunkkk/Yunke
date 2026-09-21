package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.TtsModelPickerDialog
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerProjector

/** Shared gestures and model/thinking dialogs for settings and conversation controls. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentProfileRow(
    profile: SubAgentProfile,
    providers: List<ProviderSetting>,
    enabled: Boolean = true,
    settings: Boolean = false,
) {
    val view = LocalView.current
    val currentEnabled by rememberUpdatedState(enabled)
    var modelPicker by remember(profile.id) { mutableStateOf(false) }
    var thinkingPicker by remember(profile.id) { mutableStateOf(false) }
    var rolePicker by remember(profile.id) { mutableStateOf(false) }
    var tierPicker by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) { modelPicker = false; thinkingPicker = false; rolePicker = false; tierPicker = false } }
    LaunchedEffect(profile.role) { rolePicker = false; tierPicker = false; thinkingPicker = false; modelPicker = false }
    val config = remember(profile.providerId, profile.modelId, profile.role, providers) {
        val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
        if (provider == null || model == null || model.supportsSpeechSynthesis ||
            !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) null
        else runCatching { RuntimeConfigRepository.buildRuntimeConfig(provider, model) }.getOrNull()
    }
    val canThink = config != null && !profile.isMedia
    val effective = config?.let { SubAgentPreferences.applyReasoning(profile, it).effectiveReasoningEffort }
    Column(verticalArrangement = Arrangement.spacedBy(if (settings) 0.dp else 12.dp)) {
        if (settings) {
            SubAgentSettingRow("模型", config?.let { it.modelDisplayName.ifBlank { it.model } }
                    ?: if (profile.modelId.isBlank()) "选择模型" else "模型不可用",
                Icons.Rounded.ViewInAr, "${profile.name}模型", enabled = enabled, badge = config?.providerName,
                onClick = { if (currentEnabled) { TouchHaptics.click(view); modelPicker = true } },
                onLongClick = { if (currentEnabled && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } })
            Box(Modifier.fillMaxWidth()) {
                SubAgentSettingRow("职责", profile.roleLabel, Icons.Rounded.Assignment,
                    "选择${profile.name}职责", enabled = enabled, dropdown = true,
                    onClick = { if (currentEnabled) { TouchHaptics.click(view); rolePicker = !rolePicker } })
                if (enabled) DropdownMenu(rolePicker, { rolePicker = false },
                    modifier = Modifier.width(220.dp).selectableGroup(), shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp, shadowElevation = 3.dp) {
                    listOf("implementation" to "执行", "review" to "审查／总结", "image_generation" to "图片生成", "video_generation" to "视频生成").forEach { (role, label) ->
                        SubAgentSelectionItem(label, profile.role == role) {
                            if (currentEnabled) SubAgentPreferences.update(profile.id) { it.withRole(role) }
                            rolePicker = false
                        }
                    }
                }
            }
            if (profile.supportsTaskTier) Box(Modifier.fillMaxWidth()) {
                SubAgentSettingRow("任务分工", profile.tier?.label ?: "未设置分工", Icons.Rounded.AccountTree,
                    "设置${profile.name}任务分工", enabled = enabled, dropdown = true,
                    onClick = { if (currentEnabled) { TouchHaptics.click(view); tierPicker = !tierPicker } })
                if (enabled) DropdownMenu(tierPicker, { tierPicker = false },
                    modifier = Modifier.width(200.dp).selectableGroup(), shape = RoundedCornerShape(12.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp, shadowElevation = 3.dp) {
                    SubAgentTaskTier.entries.forEach { tier ->
                        SubAgentSelectionItem(tier.label, profile.tier == tier) {
                            if (currentEnabled) TouchHaptics.click(view)
                            if (currentEnabled) SubAgentPreferences.update(profile.id) { latest ->
                                if (latest.supportsTaskTier) latest.copy(tier = tier) else latest
                            }
                            tierPicker = false
                        }
                    }
                }
            }
            if (!profile.isMedia) SubAgentSettingRow("思考深度", effective?.displayName ?: "未启用",
                Icons.Rounded.AutoAwesome, "调整${profile.name}思考深度", enabled = enabled && canThink,
                onClick = { if (currentEnabled && canThink) { TouchHaptics.click(view); thinkingPicker = true } })
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).heightIn(min = 64.dp)
                    .semantics { contentDescription = "${profile.name}模型" }
                    .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        role = Role.Button, enabled = enabled, hapticFeedbackEnabled = false,
                        onClickLabel = "选择${profile.name}模型", onLongClickLabel = "调整${profile.name}思考深度",
                        onClick = { if (currentEnabled) { TouchHaptics.click(view); modelPicker = true } },
                        onLongClick = { if (currentEnabled && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } }),
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(config?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (profile.modelId.isBlank()) "无" else "模型不可用",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (config != null) Text(config.providerName + if (canThink) " · 思考 ${effective?.displayName}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (profile.supportsTaskTier) SubAgentTaskTierButton(profile.name, profile.tier, enabled,
                    { tier -> if (currentEnabled) SubAgentPreferences.update(profile.id) { it.copy(tier = tier) } }, compact = true)
                else androidx.compose.material3.IconButton(enabled = enabled,
                    onClick = { if (currentEnabled) { TouchHaptics.click(view); modelPicker = true } }) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, "选择${profile.name}模型",
                        Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (enabled && modelPicker) {
        val all = AgentModelPickerProjector.project(providers, profile.providerId, profile.modelId)
        val models = all.copy(providerGroups = all.providerGroups.map { group ->
            group.copy(models = group.models.filter { profile.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) })
        }.filter { it.models.isNotEmpty() })
        TtsModelPickerDialog(models, true, { modelPicker = false }, { provider, model ->
            if (currentEnabled) SubAgentPreferences.saveModel(profile.id, ModelFeatureSelection(true, provider, model))
            modelPicker = false
        }, "选择${profile.name}模型", onClearSelection = {
            if (currentEnabled) SubAgentPreferences.saveModel(profile.id, ModelFeatureSelection(true, "", ""))
            modelPicker = false
        }, highlightSelection = true)
    }
    if (enabled && thinkingPicker && config != null && canThink) {
        val effort = requireNotNull(effective)
        val options = config.reasoningCapabilities?.selectableEfforts.orEmpty()
        ThinkingEffortPickerDialog(true, effort, options.ifEmpty { listOf(effort) }, { thinkingPicker = false }, { next ->
            if (currentEnabled && next in options) SubAgentPreferences.update(profile.id) { it.copy(reasoning = next) }
        }, description = "${profile.name} · 仅影响此代理")
    }
}
