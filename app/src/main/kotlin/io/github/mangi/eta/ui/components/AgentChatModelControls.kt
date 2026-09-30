package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceIndicator
import io.github.mangi.eta.ui.pages.providers.hasBalanceIndicatorContent
import io.github.mangi.eta.ui.pages.providers.activityLifecycleOwnerOrNull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.kimi.KimiSubAgentStatus
import io.github.mangi.eta.agent.kimi.KimiSubAgentStatusAccess
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.repository.ProviderBalanceStore
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceAmount
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.defaultExpandedModelProviderIds
import io.github.mangi.eta.ui.model.formatContextUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentModelPickerButton(
    conversationId: String? = null,
    state: AgentModelPickerUiState,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    collaborationTaskRunning: Boolean = isStreaming,
    popupAnchorTopPx: Int,
    popupMaxHeight: Dp,
    onModelSelected: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Draft tokens are real owners too: a nullable conversationId cannot fence a popup.
    val owner = LocalConversationSubAgentEditor.current?.owner
    val popup = remember(owner) { OwnerBoundPopup(owner) }
    val currentPopup by androidx.compose.runtime.rememberUpdatedState(popup)
    val menuState = remember(owner) { EtaMenuState() }
    var menuTicket by remember(popup) { mutableStateOf<Any?>(null) }
    var dialogTicket by remember(popup) { mutableStateOf<Any?>(null) }
    val capturedDialogTicket = dialogTicket
    ConversationCollaborationDialog(
        show = popup.isCurrent(capturedDialogTicket, currentPopup),
        enabled = false, // The dialog reads the scoped repository, never this legacy argument.
        taskRunning = collaborationTaskRunning,
        ownerMatches = { popup.isCurrent(capturedDialogTicket, currentPopup) },
        onEnabledChange = {},
        onDismiss = {
            popup.dispatch(capturedDialogTicket, currentPopup) {
                popup.dismiss()
                dialogTicket = null
            }
        },
    )
    var expandedProviderIds by remember(owner) { mutableStateOf(emptySet<String>()) }
    var modelQuery by remember(owner) { mutableStateOf("") }
    val selected = state.selectedModel
    val pickerAvailable = (!isStreaming || isPaused) && state.providerGroups.isNotEmpty()
    LaunchedEffect(pickerAvailable) {
        if (!pickerAvailable) {
            popup.dismiss()
            menuTicket = null
            dialogTicket = null
            menuState.dismiss()
        }
    }
    val currentModel = selected?.displayName ?: stringResource(R.string.model_not_selected)
    val switchModelDescription = stringResource(R.string.model_switch_current, currentModel)
    Box(modifier = modifier) {
        ChatInputNonFocusableIconButton(
            onClick = {
                if (!pickerAvailable || owner == null) return@ChatInputNonFocusableIconButton
                expandedProviderIds = defaultExpandedModelProviderIds(state.selectedModel)
                modelQuery = ""
                menuState.onAnchorClick()
                menuTicket = if (menuState.expanded) popup.open() else {
                    popup.dismiss()
                    null
                }
            },
            contentDescription = switchModelDescription,
            onLongClick = {
                if (owner != null) {
                    menuState.dismiss()
                    menuTicket = null
                    dialogTicket = popup.open()
                }
            },
        ) {
            ModelBrandMark(
                modelId = selected?.modelId,
                sourceType = selected?.providerSourceType,
                size = ChatInputActionIconSize,
                modifier = Modifier.graphicsLayer(alpha = if (pickerAvailable) 1f else 0.38f),
            )
        }
        val capturedMenuTicket = menuTicket
        EtaDropdownMenu(
            expanded = menuState.expanded && pickerAvailable && popup.isCurrent(capturedMenuTicket, currentPopup),
            onDismissRequest = {
                popup.dispatch(capturedMenuTicket, currentPopup) {
                    popup.dismiss()
                    menuTicket = null
                    menuState.dismiss()
                }
            },
            alignEnd = true,
            preferAbove = true,
            focusable = true,
            minWidth = 220.dp,
            maxWidth = 220.dp,
            maxHeight = popupMaxHeight,
        ) {
            ModelPickerPopupContent(
                state = state,
                query = modelQuery,
                onQueryChange = { modelQuery = it },
                expandedProviderIds = expandedProviderIds,
                onProviderExpandedChange = { providerId, expanded ->
                    popup.dispatch(capturedMenuTicket, currentPopup) {
                        expandedProviderIds = if (expanded) setOf(providerId) else emptySet()
                    }
                },
                onModelSelected = { providerId, modelId ->
                    popup.dispatch(capturedMenuTicket, currentPopup) {
                        if (!state.isChanging && pickerAvailable) onModelSelected(providerId, modelId)
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelPickerPopupContent(
    state: AgentModelPickerUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    expandedProviderIds: Set<String>,
    onProviderExpandedChange: (String, Boolean) -> Unit,
    onModelSelected: (String, String) -> Unit,
) {
    val balanceStates by ProviderBalanceStore.states.collectAsState()
    val balanceContext = LocalContext.current
    LaunchedEffect(Unit) {
        balanceContext.activityLifecycleOwnerOrNull()?.lifecycleScope?.let { scope ->
            ProviderBalanceStore.start(scope)
            ProviderBalanceStore.requestRefresh(scope)
        }
    }
    ModelPickerSearchField(query = query, onQueryChange = onQueryChange)
    val needle = query.trim()
    val groups = if (needle.isEmpty()) {
        state.providerGroups
    } else {
        state.providerGroups.mapNotNull { group ->
            val providerHit = group.providerName.contains(needle, ignoreCase = true)
            val models = if (providerHit) {
                group.models
            } else {
                group.models.filter { model ->
                    model.displayName.contains(needle, ignoreCase = true) ||
                        model.modelId.contains(needle, ignoreCase = true)
                }
            }
            if (models.isEmpty()) null else group.copy(models = models)
        }
    }
    if (groups.isEmpty()) {
        Text(
            text = stringResource(R.string.model_search_empty),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        return
    }
    groups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
            }
            val expanded = needle.isNotEmpty() || group.providerId in expandedProviderIds
            ModelProviderGroupHeader(
                name = group.providerName,
                expanded = expanded,
                balance = balanceStates[group.providerId],
                onClick = {
                    if (needle.isEmpty()) onProviderExpandedChange(group.providerId, !expanded)
                },
            )
            if (expanded) {
                group.models.forEach { model ->
                    ModelPickerRow(
                        model = model,
                        selected = model.id == state.selectedModel?.id && model.providerId == state.selectedModel?.providerId,
                        onClick = { onModelSelected(model.providerId, model.id) },
                    )
                }
            }
    }
}

@Composable
private fun ModelPickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Box(modifier = Modifier.padding(start = 6.dp).weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.model_search_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModelProviderGroupHeader(
    name: String,
    expanded: Boolean,
    balance: ProviderBalanceState? = null,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "model_provider_arrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (hasBalanceIndicatorContent(balance)) {
            Spacer(modifier = Modifier.width(8.dp))
            ProviderBalanceIndicator(state = balance)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) {
                stringResource(R.string.model_collapse_provider, name)
            } else {
                stringResource(R.string.model_expand_provider, name)
            },
            modifier = Modifier
                .size(15.dp)
                .graphicsLayer { rotationZ = arrowRotation },
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

@Composable
private fun ModelPickerRow(
    model: AgentModelOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (selected) {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
                cornerRadius = 12.dp,
            )
            .clickable {
                TouchHaptics.click(view)
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.displayName,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.ui_current_model_a0af8f),
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}

@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    sendBlocked: Boolean = false,
    popupMaxHeight: Dp = 360.dp,
    modifier: Modifier = Modifier,
) {
    val menuState = rememberEtaMenuState()
    val selectorState = rememberEtaMenuState()
    val telemetry = LocalAgentContextTelemetry.current
    val owner = LocalConversationSubAgentEditor.current?.owner
    var localSelectedTaskId by remember(owner) { mutableStateOf<String?>(null) }
    val selectedTaskId = if (telemetry.onTaskSelected != null) telemetry.selectedTaskId else localSelectedTaskId
    fun selectTask(id: String?) {
        if (telemetry.onTaskSelected != null) telemetry.onTaskSelected.invoke(id) else localSelectedTaskId = id
    }
    val child = telemetry.children.firstOrNull { it.taskId == selectedTaskId }
    val kimiContext = LocalContext.current
    val kimiBinding = kimiConversationBinding(owner)
    var kimiStatus by remember(kimiBinding) { mutableStateOf<KimiSubAgentStatus?>(null) }
    // 只在弹层打开时按需刷新一次；无运行中的 Kimi 会话时不启动、显示「未启动」。
    LaunchedEffect(menuState.expanded, kimiBinding) {
        if (!menuState.expanded || kimiBinding == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            kimiStatus = KimiSubAgentStatusAccess.readStatus(kimiContext, kimiBinding)
        }
    }
    LaunchedEffect(telemetry.children, selectedTaskId, owner) {
        // Controlled selection belongs to the owner reducer: a refresh effect must not
        // replay an old null selection over a newer explicit user choice.
        if (telemetry.onTaskSelected == null && selectedTaskId != null && child == null) {
            localSelectedTaskId = null
        }
    }
    val displayedUsage = child?.cloudContextUsage() ?: usage
    val selectedLabel = child?.contextLabel() ?: telemetry.mainModelName.ifBlank { "主代理" }
    val progress = displayedUsage.progress
    val progressColor = when {
        progress == null -> MiuixTheme.colorScheme.onSurfaceVariantActions
        progress >= 0.95f -> StatusError
        progress >= 0.80f -> StatusWarning
        else -> MiuixTheme.colorScheme.primary
    }
    val locale = LocalConfiguration.current.locales[0]
    val summary = child?.cloudContextSummary(locale) ?: formatContextUsage(
        usage = displayedUsage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        locale = locale,
    )
    val detail = when {
        sendBlocked && child == null -> "$summary\n${stringResource(R.string.context_window_send_blocked)}"
        child?.role == "video_generation" -> "${child.contextStatusLabel()}\n视频生成任务不提供对话上下文统计。"
        child?.role == "image_generation" -> "${child.contextStatusLabel()}\n图片生成任务不提供对话上下文统计。"
        child != null -> "$summary\n${child.contextStatusLabel()} · 独立任务上下文"
        else -> summary
    }
    val usageDescription = stringResource(
        R.string.context_usage_description,
        summary.replace('\n', ' '),
    )
    val keepIme = rememberKeepImeWhenOpeningMenu()
    Box(modifier = modifier) {
        ChatInputNonFocusableIconButton(
            onClick = {
                keepIme()
                selectorState.dismiss()
                menuState.onAnchorClick()
            },
            longClickLabel = "选择上下文统计对象",
            onLongClick = {
                keepIme()
                menuState.dismiss()
                selectorState.onAnchorClick()
            },
        ) {
            CircularProgressIndicator(
                progress = progress ?: 0f,
                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                    foregroundColor = progressColor,
                    disabledForegroundColor = progressColor,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                ),
                strokeWidth = 2.5.dp,
                size = ChatInputActionIconSize,
                modifier = Modifier.semantics {
                    contentDescription = "$selectedLabel · $usageDescription"
                },
            )
        }
        EtaDropdownMenu(
            expanded = menuState.expanded,
            onDismissRequest = menuState::dismiss,
            alignEnd = true,
            preferAbove = true,
            focusable = false,
            minWidth = 0.dp,
            maxWidth = 220.dp,
        ) {
            Text(
                text = selectedLabel,
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Text(
                text = detail,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
            HorizontalDivider(color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f))
            Text(
                text = "Kimi Code",
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
            )
            Text(
                text = kimiStatus?.let { "${it.model ?: "跟随默认模型"} · ${it.statusLabel()} · ${it.contextSummary(locale)}" }
                    ?: "未启动",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            )
        }
        EtaDropdownMenu(
            expanded = selectorState.expanded,
            onDismissRequest = selectorState::dismiss,
            alignEnd = true, preferAbove = true, focusable = false,
            minWidth = 220.dp, maxWidth = 260.dp, maxHeight = popupMaxHeight,
        ) {
            Text("上下文统计", style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            ContextTargetRow("${telemetry.mainModelName.ifBlank { "主代理" }}（主代理）", child == null) {
                selectTask(null); selectorState.dismiss()
            }
            telemetry.children.forEach { target ->
                ContextTargetRow("${target.contextLabel()} · ${target.contextStatusLabel()}", selectedTaskId == target.taskId) {
                    selectTask(target.taskId); selectorState.dismiss()
                }
            }
        }
    }
}

@Composable
private fun ModelBrandMark(
    modelId: String?,
    sourceType: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val logo = modelOrProviderBrandLogoRes(modelId, sourceType)
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(MiuixTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Dns,
                contentDescription = null,
                modifier = Modifier.size(size * 0.56f),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ContextTargetRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
        .squircleSurface(color = if (selected) MiuixTheme.colorScheme.surfaceContainerHigh else Color.Transparent, cornerRadius = 12.dp)
        .clickable { TouchHaptics.click(view); onClick() }
        .padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MiuixTheme.textStyles.body2, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, contentDescription = "当前统计对象", modifier = Modifier.size(18.dp))
    }
}
