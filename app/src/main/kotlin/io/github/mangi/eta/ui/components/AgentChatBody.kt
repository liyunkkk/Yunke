package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.voice.VoiceChatSnapshot
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeController
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.latestBilledContextTokens
import io.github.mangi.eta.ui.model.canContinueDisconnectedRun
import io.github.mangi.eta.ui.model.isRetryableFailure
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress
import io.github.mangi.eta.ui.model.isSteerSupplement
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentChatBody(
    voiceController: VoiceModeController,
    messages: List<AgentChatMessageUi>,
    history: List<AgentModelClient.ConversationMessage>,
    modelPickerState: AgentModelPickerUiState,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    livePromptTokens: Int? = null,
    livePromptIsProjected: Boolean = false,
    billedHistoryTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    childContexts: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats> = emptyList(),
    compactingModelName: String = "",
    selectedContextTaskId: String? = null,
    onContextTaskSelected: ((String?) -> Unit)? = null,
    autoCompressEnabled: Boolean = false,
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit = {},
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit = {},
    isDrawerOpen: Boolean = false,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    StreamPerformanceMonitor(isStreaming)
    io.github.mangi.eta.ui.haptics.StreamingHaptics.Observe(!isPaused && !isDrawerOpen)
    SideEffect { StreamPerformanceDiagnostics.record("chat.compose", value = messages.size.toLong()) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val context = LocalContext.current
    val voiceState by voiceController.state.collectAsState()
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    val browserShortcut by remember {
        AgentBrowserSession.snapshots
            .map { snapshot ->
                Triple(snapshot.available, snapshot.lastAgentRunId, snapshot.lastAgentToolCallId)
            }
            .distinctUntilChanged()
    }.collectAsState(initial = Triple(false, null, null))
    val visibleMessages = remember(messages, messageEdit?.targetMessageId) {
        AgentConversationRevisionReducer.visibleMessagesForEdit(
            messages = messages,
            targetMessageId = messageEdit?.targetMessageId,
        ).filterNot { message ->
            message is AgentMessageUi && message.content.isBlank()
        }
    }
    LaunchedEffect(visibleMessages, isStreaming) {
        val last = visibleMessages.filterIsInstance<AgentMessageUi>().lastOrNull()
        val speechText = last?.content.orEmpty()
        voiceController.updateChat(
            VoiceChatSnapshot(
                isStreaming = isStreaming,
                lastAgentId = last?.id,
                lastAgentText = speechText,
            )
        )
    }
    val speechPlayback by io.github.mangi.eta.agent.voice.tts.SpeechPlayback.state.collectAsState()
    LaunchedEffect(visibleMessages, messageEdit?.targetMessageId, speechPlayback.owner) {
        val owner = speechPlayback.owner
        val visibleCompletedIds = visibleMessages.mapNotNull { message ->
            (message as? AgentMessageUi)?.takeIf { !it.isStreaming }?.id
        }.toSet()
        if (shouldStopOrphanSpeechPlayback(owner, messageEdit != null, visibleCompletedIds)) {
            io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stop()
        }
    }
    val initialBottomItemIndex = remember(visibleMessages, isCompressingContext, isWaitingForCompression, childContexts) {
        visibleMessages.toTimelineEntries().size + if (isCompressingContext || isWaitingForCompression || childContexts.any { it.isCompacting }) 1 else 0
    }
    val scrollState = rememberLazyListState(initialFirstVisibleItemIndex = initialBottomItemIndex)
    val currentBrowserMessageId = remember(
        visibleMessages,
        browserShortcut,
    ) {
        val (available, runId, toolCallId) = browserShortcut
        if (!available || runId == null || toolCallId == null) {
            null
        } else {
            visibleMessages.lastOrNull { message ->
                message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    val submitScrollScope = rememberCoroutineScope()
    var sentFromKeyboard by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }

    LaunchedEffect(isStreaming) {
        if (isStreaming && sentFromKeyboard) {
            keyboard?.hide()
            sentFromKeyboard = false
        }
    }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            hideChatInputIme(focusManager, keyboard, view)
        }
    }

    val billedContextTokens = remember(livePromptTokens, livePromptIsProjected, messageEdit) {
        if (messageEdit != null) {
            null
        } else {
            livePromptTokens.takeUnless { livePromptIsProjected }
        }
    }
    val projectedContextTokens = livePromptTokens.takeIf { livePromptIsProjected && messageEdit == null && isStreaming }
    val uncommittedLiveTokens = 0 // Cloud-only metering: do not scan streaming text for local estimates.
    val imageSourceCache = remember { ChatImageSourceCache() }
    val previewGallery by produceState<List<String>>(emptyList(), visibleMessages, pendingImages) {
        // This used to parse EVERY historical reply synchronously on each text delta.
        // Cancelling this producer prevents obsolete galleries from being published.
        value = withContext(Dispatchers.Default) {
            StreamPerformanceDiagnostics.measure("gallery.scan", visibleMessages.size.toLong()) {
                collectPreviewableChatImages(visibleMessages, pendingImages) { message ->
                    imageSourceCache.sources(message.id, message.content)
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAgentContextTelemetry provides AgentContextTelemetry(childContexts, modelPickerState.selectedModel?.displayName.orEmpty(), compactingModelName, selectedContextTaskId, onContextTaskSelected)
    ) {
        ChatImagePreviewHost(gallery = previewGallery) {
            AgentChatScaffold(
                collaborationConversationId = collaborationConversationId,
                visibleMessages = visibleMessages,
                hasMessages = visibleMessages.isNotEmpty(),
                scrollState = scrollState,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                billedOverheadTokens = billedOverheadTokens,
                uncommittedLiveTokens = uncommittedLiveTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                isStreaming = isStreaming,
                isPaused = isPaused,
                isCompressingContext = isCompressingContext,
                isWaitingForCompression = isWaitingForCompression,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                conversationMentions = conversationMentions,
                messageEdit = messageEdit,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = voiceController::start,
                onStopVoiceMode = voiceController::stop,
                showEmptySuggestions = !isKeyboardVisible,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = { keepBottomAnchored = it },
                onSubmit = { text ->
                    sentFromKeyboard = true
                    // 发送即重新锚定底部：用户从历史上方直接发送时，同帧内 isStreaming 与
                    // 新消息一起到位，立即回到底部并恢复后续的流式平滑跟底。
                    keepBottomAnchored = true
                    onSubmit(text)
                    submitScrollScope.launch {
                        // Cancel an old fling, then anchor the edited/replaced list after layout.
                        scrollState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) { }
                        withFrameNanos { }
                        keepBottomAnchored = true
                        val last = scrollState.layoutInfo.totalItemsCount - 1
                        if (last >= 0) scrollState.requestScrollToItem(last)
                    }
                },
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onEditMessage = onEditMessage,
                onCancelMessageEdit = onCancelMessageEdit,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onBranchMessage = onBranchMessage,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditAssistant = onEditAssistant,
                onAssistantSelected = onAssistantSelected,
                currentBrowserMessageId = currentBrowserMessageId,
                scrollToMessageId = scrollToMessageId,
                onScrollToMessageConsumed = onScrollToMessageConsumed,
                modifier = modifier,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChatScaffold(
    visibleMessages: List<AgentChatMessageUi>,
    hasMessages: Boolean,
    scrollState: LazyListState,
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    projectedContextTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    uncommittedLiveTokens: Int = 0,
    activeRunContextWindow: Int? = null,
    autoCompressEnabled: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    showEmptySuggestions: Boolean,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit = {},
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit = {},
    currentBrowserMessageId: String?,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val appearance = LocalAppearanceSettings.current
    val showMorphLoading = shouldShowMorphLoadingIndicator(
        messages = visibleMessages,
        isStreaming = isStreaming,
        isPaused = isPaused,
        isCompressingContext = isCompressingContext,
        enabled = appearance.morphLoadingIndicator,
        beforeResponseOnly = appearance.morphLoadingBeforeResponseOnly,
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(
            left = 0.dp,
            top = 0.dp,
            right = 0.dp,
            bottom = 0.dp,
        ),
        bottomBar = {
            AgentChatBottomBar(
                collaborationConversationId = collaborationConversationId,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                billedOverheadTokens = billedOverheadTokens,
                uncommittedLiveTokens = uncommittedLiveTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                showContextUsage = hasMessages,
                isStreaming = isStreaming,
                isPaused = isPaused,
                canContinueDisconnected = canContinueDisconnectedRun(visibleMessages),
                isCompressingContext = isCompressingContext,
                showMorphLoading = showMorphLoading,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
            conversationMentions = conversationMentions,
                messageEdit = messageEdit,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                onSubmit = onSubmit,
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                onEditAssistant = onEditAssistant,
                onAssistantSelected = onAssistantSelected,
            )
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        if (!hasMessages) {
            EmptyChatState(
                showSuggestions = showEmptySuggestions,
                onSuggestionClick = onSuggestionClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPadding),
            )
        } else {
            AgentConversationMessages(
                visibleMessages = visibleMessages,
                scrollState = scrollState,
                isStreaming = isStreaming,
                isPaused = isPaused,
                isCompressingContext = isCompressingContext,
                isWaitingForCompression = isWaitingForCompression,
                bottomInset = bottomPadding,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = onBottomAnchorChanged,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onBranchMessage = onBranchMessage,
                messageActionsEnabled = !isStreaming && !isPaused &&
                    !isCompressingContext &&
                    messageEdit == null,
                branchEnabled = !isCompressingContext && messageEdit == null,
                editTargetMessageId = messageEdit?.targetMessageId,
                currentBrowserMessageId = currentBrowserMessageId,
                scrollToMessageId = scrollToMessageId,
                onScrollToMessageConsumed = onScrollToMessageConsumed,
                modifier = Modifier
                    .fillMaxSize(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    bottomInset: Dp,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSuggestionClick: (String) -> Unit = {},
    onRunTraceClick: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onBranchMessage: (String) -> Unit = {},
    messageActionsEnabled: Boolean = false,
    branchEnabled: Boolean = false,
    editTargetMessageId: String? = null,
    currentBrowserMessageId: String? = null,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val timelineEntries = remember(visibleMessages) {
        StreamPerformanceDiagnostics.measure("timeline.project", visibleMessages.size.toLong()) { visibleMessages.toTimelineEntries() }
    }
    val expansionSaver = remember {
        listSaver<Map<String, Boolean>, String>(
            save = { value -> value.flatMap { (key, expanded) -> listOf(key, expanded.toString()) } },
            restore = { value -> value.chunked(2).filter { it.size == 2 }.associate { it[0] to (it[1] == "true") } },
        )
    }
    var workExpansionOverrides by rememberSaveable(stateSaver = expansionSaver) {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
    LaunchedEffect(timelineEntries) {
        val activeKeys = timelineEntries.filterIsInstance<AgentTimelineEntry.WorkProcess>().mapTo(mutableSetOf()) { it.key }
        if (workExpansionOverrides.keys.any { it !in activeKeys }) {
            workExpansionOverrides = workExpansionOverrides.filterKeys { it in activeKeys }
        }
    }
    val timelineRows = remember(timelineEntries, workExpansionOverrides, isStreaming) {
        timelineEntries.toLazyTimelineRows(workExpansionOverrides, isStreaming)
    }
    LaunchedEffect(scrollToMessageId, timelineRows) {
        val target = scrollToMessageId ?: return@LaunchedEffect
        val index = timelineRows.indexOfFirst { it.containsMessageId(target) }
        if (index >= 0) {
            onBottomAnchorChanged(false)
            scrollState.animateScrollToItem(index)
            onScrollToMessageConsumed()
        } else if (timelineRows.isNotEmpty()) {
            onScrollToMessageConsumed()
        }
    }
    // Project onto the EXACT rows consumed by LazyColumn. Expansion and late
    // records move only the footer anchor, never the message or callback owner.
    val turnFooters = remember(timelineRows, isStreaming, isCompressingContext) {
        timelineRows.turnFooters(
            isStreaming = isStreaming,
            isCompressingContext = isCompressingContext,
        )
    }
    val finalResultMessageIds = remember(turnFooters) {
        turnFooters.values.mapTo(mutableSetOf()) { it.id }
    }
    // Reveal dependencies follow the projection's existing anchors, not a
    // second ownership heuristic. A stopped notice can own actions while an
    // earlier answer in that same turn is still revealing. New user boundaries
    // and completed footer anchors prevent one turn from blocking another.
    val footerRevealMessages = remember(timelineRows, turnFooters) {
        buildMap<String, List<AgentMessageUi>> {
            val answers = mutableListOf<AgentMessageUi>()
            timelineRows.forEach { row ->
                val message = (row as? AgentTimelineRow.Message)?.message
                if (message is UserMessageUi && !message.isSteerSupplement()) answers.clear()
                if (message is AgentMessageUi && message.content.isNotBlank()) answers.add(message)
                if (row.key in turnFooters) {
                    put(row.key, answers.toList())
                    answers.clear()
                }
            }
        }
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val fallbackStreamingStates = remember { mutableStateMapOf<String, StreamingMarkdownState>() }
    val streamingMarkdownStates = LocalStreamingMarkdownStates.current ?: fallbackStreamingStates
    // Messages already on screen before this live run must not replay. A text block that
    // arrives and ends in one snapshot is absent from this set, so it still gets a typewriter.
    val settledMessageIds = remember { mutableSetOf<String>() }
    var seededSettledMessages by remember { mutableStateOf(false) }
    if (!seededSettledMessages) {
        seededSettledMessages = true
        if (isStreaming || isPaused) visibleMessages.forEach { settledMessageIds.add(it.id) }
    }
    SideEffect {
        if (!isStreaming && !isPaused) {
            settledMessageIds.clear()
            visibleMessages.forEach { settledMessageIds.add(it.id) }
        }
    }
    LaunchedEffect(visibleMessages, streamingMarkdownStates) {
        val activeIds = visibleMessages.mapTo(mutableSetOf()) { it.id }
        streamingMarkdownStates.keys.retainAll(activeIds)
    }
    val telemetry = LocalAgentContextTelemetry.current
    val compressingChildren = telemetry.children.filter { it.isCompacting }
    val compressingItemCount = if (isCompressingContext || isWaitingForCompression || compressingChildren.isNotEmpty()) 1 else 0
    val bottomItemIndex = timelineRows.size + compressingItemCount
    val userMessageTargets = remember(timelineRows) { timelineRows.lazyUserMessageIndices() }
    val directionThreshold = with(LocalDensity.current) { 12.dp.toPx() }
    val directionTracker = remember(scrollState, directionThreshold) {
        ConversationNavigationDirectionTracker(directionThreshold)
    }
    var navigationDirection by remember(scrollState) { mutableStateOf(ConversationNavigationDirection.Down) }
    var messageNavigationJob by remember(scrollState) { mutableStateOf<Job?>(null) }
    DisposableEffect(scrollState) {
        onDispose { messageNavigationJob?.cancel() }
    }
    val isUserDragging by scrollState.interactionSource.collectIsDraggedAsState()
    // 手指拖走后的惯性也算用户滚动；跟底自己的 scrollBy 不能把这个标志打开。
    var isUserScrolling by remember { mutableStateOf(false) }
    // Observe user motion synchronously, before the asynchronous drag collector
    // and before another scheduled follow frame can mutate the list position.
    val userScrollConnection = remember(scrollState, directionTracker) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    messageNavigationJob?.cancel()
                    isUserScrolling = true
                    navigationDirection = directionTracker.onScroll(available.y, userInput = true)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                directionTracker.endGesture()
                return Velocity.Zero
            }
        }
    }
    val densityScale = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()
    val currentAnchor = rememberUpdatedState(keepBottomAnchored)
    val currentStreaming = rememberUpdatedState(isStreaming)
    val currentVisibleMessages = rememberUpdatedState(visibleMessages)
    val currentDragging = rememberUpdatedState(isUserDragging)

    LaunchedEffect(scrollState) {
        snapshotFlow { currentDragging.value to scrollState.isScrollInProgress }
            .collect { (dragging, inProgress) ->
                StreamPerformanceDiagnostics.record("scroll.state", value = if (dragging) 1 else 0)
                isUserScrolling = when {
                    dragging -> true
                    !inProgress -> false
                    else -> isUserScrolling
                }
            }
    }

    var hasLeftBottom by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            if (messageNavigationJob != null) null
            else Triple(isUserScrolling, scrollState.isConversationAtBottom(), currentAnchor.value)
        }
            .distinctUntilChanged()
            .collect { state ->
                val (userScrolling, atBottom, anchored) = state ?: return@collect
                if (userScrolling && !atBottom) hasLeftBottom = true
                val next = resolveKeepBottomAnchored(
                    current = anchored,
                    isUserDragging = userScrolling,
                    isAtBottom = atBottom,
                    hasLeftBottom = hasLeftBottom,
                )
                if (next && atBottom) hasLeftBottom = false
                if (next != anchored) onBottomAnchorChanged(next)
            }
    }

    var isBottomSettling by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val rendering = hasPendingAssistantReveal(currentVisibleMessages.value) { message ->
                val retained = streamingMarkdownStates[message.id]
                retained != null && retained.revealedContent != message.content
            }
            arrayOf(currentStreaming.value, currentAnchor.value, rendering, isUserScrolling)
        }
            .distinctUntilChanged { old, new -> old.contentEquals(new) }
            .collectLatest { state ->
                val streaming = state[0] as Boolean
                val anchored = state[1] as Boolean
                val rendering = state[2] as Boolean
                val userScrolling = state[3] as Boolean
                if (!anchored || userScrolling) {
                    isBottomSettling = false
                } else if (streaming || rendering) {
                    isBottomSettling = true
                } else if (isBottomSettling) {
                    withFrameNanos { }
                    withFrameNanos { }
                    snapshotFlow { !scrollState.canScrollForward }.first { it }
                    isBottomSettling = false
                }
            }
    }

    var initialBottomPositionPending by remember(scrollState) { mutableStateOf(true) }
    val currentScrollTarget by rememberUpdatedState(scrollToMessageId)
    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = initialBottomPositionPending || isUserScrolling || messageNavigationJob != null,
            isBottomSettling = isBottomSettling,
        )
    )
    // 最底部工具展开的那一两百毫秒里不能跟底上提：上提按整段新增高度瞬间把列表抬走，
    // 和展开动画叠在一起就是弹一下。跟底滚动本身不动。
    var holdTailLift by remember { mutableStateOf(false) }
    val shouldLiftTail = shouldLiftStreamingTail(shouldFollowBottom, holdTailLift)
    LaunchedEffect(holdTailLift) {
        if (!holdTailLift) return@LaunchedEffect
        delay(220)
        holdTailLift = false
    }
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    // Initial positioning owns the list only once. New timeline items and network
    // completion must go through the continuous follow controller below.
    LaunchedEffect(scrollState) {
        try {
            val initial = snapshotFlow {
                InitialBottomPosition(
                    bottomItemIndex = currentBottomItemIndex,
                    hasLayout = scrollState.layoutInfo.visibleItemsInfo.isNotEmpty(),
                    anchored = currentAnchor.value,
                    interrupted = isUserScrolling || messageNavigationJob != null || currentScrollTarget != null,
                )
            }.first { it.ready }
            if (initial.shouldPosition) {
                StreamPerformanceDiagnostics.record("follow.initialSnap")
                snapListToBottom(scrollState, initial.bottomItemIndex) {
                    currentAnchor.value && !isUserScrolling &&
                        messageNavigationJob == null && currentScrollTarget == null
                }
            }
        } finally {
            initialBottomPositionPending = false
        }
    }

    // 流式输出及渲染收尾期间发布最新的跟底距离。历史消息中的步骤/思考展开同样会改变
    // 列表高度，但那是用户主动查看内容，不能被误判成尾部文字增长。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            BottomFollowLayout(
                enabled = shouldFollowBottom,
                bottomItemIndex = currentBottomItemIndex,
                sentinelBottom = sentinel?.let { it.offset + it.size },
                // 视口已扣除底栏高度；这里只扣列表自身的尾部留白。
                // 跟底目标仍是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                viewportSizePx = layoutInfo.viewportSize.height,
                canScrollForward = scrollState.canScrollForward,
                lastVisibleOffset = layoutInfo.visibleItemsInfo.lastOrNull()?.offset,
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val decision = resolveBottomFollowDecision(
                    enabled = layout.enabled,
                    bottomItemIndex = layout.bottomItemIndex,
                    sentinelBottom = layout.sentinelBottom,
                    viewportEnd = layout.viewportEnd,
                    lastVisibleIndex = layout.lastVisibleIndex,
                    viewportSizePx = layout.viewportSizePx,
                    canScrollForward = layout.canScrollForward,
                )
                StreamPerformanceDiagnostics.record("follow.decision", value = decision.scrollByPx.toLong())
                bottomFollowDecisions.trySend(decision)
            }
    }

    // One controller retains velocity across layout targets, including line-height
    // steps. Neither a missing sentinel nor stream completion may bypass it.
    LaunchedEffect(scrollState, bottomFollowDecisions, densityScale) {
        val motion = BottomFollowMotion()
        var remainingDistancePx = 0f
        var previousFrameNanos: Long? = null
        fun reset() {
            remainingDistancePx = 0f
            previousFrameNanos = null
            motion.reset()
        }
        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.coerceAtLeast(0).toFloat()
        }
        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f) {
                reset()
                accept(bottomFollowDecisions.receive())
            }
            while (true) {
                accept(bottomFollowDecisions.tryReceive().getOrNull() ?: break)
            }
            if (!shouldFollowBottom || isUserScrolling || messageNavigationJob != null) {
                reset()
                continue
            }
            if (remainingDistancePx <= 0f) continue
            val frameNanos = withFrameNanos { it }
            previousFrameNanos?.let {
                StreamPerformanceDiagnostics.record("follow.frameGap", frameNanos - it)
            }
            previousFrameNanos = frameNanos
            while (true) {
                accept(bottomFollowDecisions.tryReceive().getOrNull() ?: break)
            }
            if (!shouldFollowBottom || isUserScrolling || messageNavigationJob != null) {
                reset()
                continue
            }
            val step = motion.step(remainingDistancePx, frameNanos, densityScale)
            // First frame establishes real timing; zero movement is not a failed scroll.
            if (step <= 0f) continue
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    if (!isUserScrolling && messageNavigationJob == null && shouldFollowBottom) {
                        consumedStep = StreamPerformanceDiagnostics.measure("follow.scroll") { scrollBy(step) }
                        StreamPerformanceDiagnostics.record("follow.step", value = (consumedStep * 1000).toLong())
                    }
                }
                if (consumedStep > 0f) {
                    remainingDistancePx = (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else reset()
            } catch (cancelled: CancellationException) {
                StreamPerformanceDiagnostics.record("follow.cancelled")
                if (!currentCoroutineContext().isActive) throw cancelled
                reset()
            }
        }
    }

    // 输入器悬浮在会话之上：视口铺满到屏幕底，输入框四周透明、能看到后面的消息。
    // 跟底输出期间（思考/正文生成、未手动滑动），卡片/正文每长一行，跟底滚动要晚几帧
    // 才追上。这几帧不裁剪（裁剪会把卡片底边和半行字切掉），而是在绘制阶段把整个列表
    // 上提尚未追上的距离：尾部始终停在输入框上方 14dp 的静止线，底边和间距都完整可见。
    // 只有尾部不在视口内（一次性长出超过一屏）时才退回裁在静止线上。
    // 用户一拖动 shouldFollowBottom 即为 false，上提和裁剪都解除，内容可以滑到输入框后面。
    Box(
        modifier = modifier
            .clipToBounds()
            .drawWithContent {
                val lag = resolveFollowTailLag(shouldLiftTail, scrollState.followTailOverflow())
                if (lag == FollowTailLag.Unknown) {
                    val restLine = (size.height - (bottomInset + ConversationComposerGap).toPx()).coerceAtLeast(0f)
                    clipRect(bottom = restLine) { this@drawWithContent.drawContent() }
                } else {
                    drawContent()
                }
            },
    ) {
        val speechPrefaces = remember(visibleMessages, finalResultMessageIds) {
            StreamPerformanceDiagnostics.measure("timeline.prefaces", visibleMessages.size.toLong()) {
                visibleTurnSpeechPrefaces(visibleMessages, finalResultMessageIds)
            }
        }
        val isListScrollable by remember {
            derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
        }
        var streamFilledViewport by remember { mutableStateOf(false) }
        LaunchedEffect(isStreaming, isListScrollable) {
            streamFilledViewport = if (isStreaming) streamFilledViewport || isListScrollable else false
        }
        val messageActions = remember { ChatMessageActions() }
        SideEffect {
            messageActions.onSuggestionClick = onSuggestionClick
            messageActions.onRunTraceClick = onRunTraceClick
            messageActions.onOpenBrowser = onOpenBrowser
            messageActions.onEditMessage = onEditMessage
            messageActions.onDeleteMessage = onDeleteMessage
            messageActions.onRegenerateMessage = onRegenerateMessage
            messageActions.onBranchMessage = onBranchMessage
        }
        LazyColumn(
            state = scrollState,
            verticalArrangement = if (shouldPinConversationToBottom(isStreaming, streamFilledViewport)) {
                Arrangement.Bottom
            } else {
                Arrangement.Top
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 只在绘制层读取布局结果：跟底滚动每消费一段，上提量同帧减少，尾部不动。
                    translationY = -resolveFollowTailLag(shouldLiftTail, scrollState.followTailOverflow()).liftPx
                }
                .nestedScroll(userScrollConnection)
                // Navigation already emits one explicit click/long-press haptic.
                .then(if (messageNavigationJob == null) Modifier.scrollEndHaptic() else Modifier)
                .overScrollVertical(),
            // 最后一条静止时停在输入框上方 14dp；手动滑动时内容可以滚到输入框后面。
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = ConversationComposerGap + bottomInset,
            ),
            overscrollEffect = null,
        ) {
            items(
                items = timelineRows,
                key = { it.key },
                contentType = { row ->
                    when (row) {
                        is AgentTimelineRow.Message -> "message"
                        is AgentTimelineRow.WorkHeader -> "work-header"
                        is AgentTimelineRow.WorkStep -> when (row.message) {
                            is ToolActivityMessageUi -> "work-tool"
                            is ThinkingMessageUi -> "work-thinking"
                            else -> "work-summary"
                        }
                    }
                },
            ) { entry ->
                val tailRow = timelineRows.lastOrNull()
                val tailGroupKey = when (tailRow) {
                    is AgentTimelineRow.WorkStep -> tailRow.groupKey
                    is AgentTimelineRow.WorkHeader -> tailRow.key
                    else -> null
                }
                val reportsTailResize = when (entry) {
                    is AgentTimelineRow.WorkHeader -> entry.key == tailGroupKey
                    is AgentTimelineRow.WorkStep -> entry.groupKey == tailGroupKey
                    is AgentTimelineRow.Message -> entry.key == tailRow?.key
                }
                // Keep the row key/index and animate its root, including its footer.
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalTailResize provides if (reportsTailResize) ({ holdTailLift = true }) else null,
                ) {
                Column(
                    modifier = Modifier.fillMaxWidth().then(
                        when {
                            entry is AgentTimelineRow.Message -> Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 180),
                                placementSpec = null,
                                fadeOutSpec = null,
                            )
                            entry is AgentTimelineRow.WorkStep && entry.groupKey == tailGroupKey ->
                                Modifier.animateItem(
                                    fadeInSpec = null,
                                    placementSpec = tween(durationMillis = 180),
                                    fadeOutSpec = null,
                                )
                            else -> Modifier
                        },
                    ),
                ) {
                when (entry) {
                    is AgentTimelineRow.Message -> {
                        val message = entry.message
                        ChatMessageItem(
                            message = message,
                            speechPreface = (message as? AgentMessageUi)?.let { speechPrefaces[it.id] }.orEmpty(),
                            retainedStreamingState = (message as? AgentMessageUi)
                                ?.takeIf { agentMessage ->
                                    agentMessage.isStreaming ||
                                        streamingMarkdownStates.containsKey(agentMessage.id) ||
                                        (
                                            (isStreaming || isPaused) &&
                                                agentMessage.id !in settledMessageIds &&
                                                agentMessage.content.isNotEmpty()
                                            )
                                }
                                ?.let { agentMessage ->
                                    streamingMarkdownStates.getOrPut(agentMessage.id) {
                                        StreamingMarkdownState()
                                    }
                                },
                            actions = messageActions,
                            showBrowserShortcut = message is ToolActivityMessageUi &&
                                message.toolName == "browser_use" &&
                                message.id == currentBrowserMessageId,
                            enableLivePreview = !isStreaming,
                            // UserMessageBubble ignores these switches; its toolbar is unchanged.
                            showCopyAction = false,
                            showMessageActions = false,
                            messageActionsEnabled = messageActionsEnabled && !isStreaming && !isPaused,
                            branchEnabled = branchEnabled,
                            isEditing = message.id == editTargetMessageId,
                            isPaused = isPaused,
                            // Keep this modifier stable. Attaching fadeIn only after the run
                            // ends replays appearance on the already-visible answer.
                            modifier = Modifier,
                        )
                    }

                    is AgentTimelineRow.WorkHeader -> {
                        AgentWorkProcessHeader(
                            messages = entry.group.messages,
                            isPaused = isPaused,
                            expanded = entry.expanded,
                            onToggle = {
                                if (entry.key == tailGroupKey) holdTailLift = true
                                workExpansionOverrides = workExpansionOverrides + (entry.key to !entry.expanded)
                            },
                        )
                    }
                    is AgentTimelineRow.WorkStep -> {
                        val message = entry.message
                        val retainedState = if (message is ThinkingMessageUi &&
                            (message.isStreaming || streamingMarkdownStates.containsKey(message.id))) {
                            streamingMarkdownStates.getOrPut(message.id) { StreamingMarkdownState() }
                        } else null
                        WorkProcessCardSlice(
                            part = if (entry.isLast) WorkProcessCardPart.Last else WorkProcessCardPart.Middle,
                        ) {
                            ChatMessageItem(
                                message = message,
                                actions = messageActions,
                                retainedStreamingState = retainedState,
                                showBrowserShortcut = message is ToolActivityMessageUi &&
                                    message.id == currentBrowserMessageId,
                                enableLivePreview = !isStreaming,
                                compact = true,
                                isPaused = isPaused,
                                modifier = Modifier.padding(
                                    top = if (entry.isFirst) 2.dp else 0.dp,
                                    bottom = if (entry.isLast) 8.dp else 0.dp,
                                ),
                            )
                        }
                    }
                }
                turnFooters[entry.key]?.let { owner ->
                    val revealPending = footerRevealMessages[entry.key].orEmpty().any { answer ->
                        val retained = streamingMarkdownStates[answer.id]
                        answer.isStreaming ||
                            (retained != null && retained.revealedContent != answer.content) ||
                            (retained == null && (isStreaming || isPaused) && answer.id !in settledMessageIds)
                    }
                    AgentTurnFooter(
                        message = owner,
                        actions = messageActions,
                        revealPending = revealPending,
                        speechPreface = speechPrefaces[owner.id].orEmpty(),
                        messageActionsEnabled = messageActionsEnabled && !isStreaming && !isPaused,
                        branchEnabled = branchEnabled,
                    )
                }
                }
                }
            }
            if (compressingItemCount > 0) {
                item(key = ChatContextCompressingKey) {
                    Column {
                        if (isCompressingContext || isWaitingForCompression) ContextCompressingIndicator(
                            modelName = "${telemetry.compactingModelName.ifBlank { telemetry.mainModelName }}（主代理）",
                            waiting = isWaitingForCompression && !isCompressingContext,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 180),
                                placementSpec = null,
                                fadeOutSpec = null,
                            ),
                        )
                        compressingChildren.forEach { child ->
                            androidx.compose.runtime.key(child.taskId) {
                                ContextCompressingIndicator(modelName = child.contextLabel())
                            }
                        }
                    }
                }
            }
            item(key = ChatBottomSentinelKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
        }

        fun navigateUserMessage(toEdge: Boolean) {
            // Do not queue animations on rapid taps; a new drag cancels the active jump.
            if (messageNavigationJob != null) return
            val direction = navigationDirection
            val target = conversationUserMessageTarget(
                userMessageTargets, scrollState.firstVisibleItemIndex, bottomItemIndex, direction, toEdge,
                firstVisibleScrollOffset = scrollState.firstVisibleItemScrollOffset,
            )
            onBottomAnchorChanged(false)
            messageNavigationJob = coroutineScope.launch {
                try {
                    // Let the follow/boundary-haptic observers yield before moving the list.
                    withFrameNanos { }
                    // Keep one continuous motion, slowing only at the selected user-message target.
                    scrollState.animateToConversationTurn(target)
                    if (target == bottomItemIndex) snapListToBottom(scrollState, currentBottomItemIndex)
                    onBottomAnchorChanged(
                        direction == ConversationNavigationDirection.Down && scrollState.isConversationAtBottom(),
                    )
                } finally {
                    messageNavigationJob = null
                }
            }
        }
        val showMessageNavigation by remember(scrollState, navigationDirection, keepBottomAnchored) {
            derivedStateOf {
                !keepBottomAnchored && when (navigationDirection) {
                    ConversationNavigationDirection.Up -> scrollState.canScrollBackward
                    ConversationNavigationDirection.Down -> !scrollState.isConversationAtBottom()
                }
            }
        }
        ConversationTurnNavigationButton(
            direction = navigationDirection,
            visible = showMessageNavigation,
            onStep = { navigateUserMessage(toEdge = false) },
            onEdge = { navigateUserMessage(toEdge = true) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp + bottomInset),
        )
    }
}

internal data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
    val viewportSizePx: Int,
    val canScrollForward: Boolean,
    // Keep missing-sentinel targets live while scrolling inside one very tall item.
    val lastVisibleOffset: Int?,
)

internal data class BottomFollowDecision(
    val scrollByPx: Int = 0,
    val requestIndex: Int? = null,
)

internal fun resolveBottomFollowDecision(
    enabled: Boolean,
    bottomItemIndex: Int,
    sentinelBottom: Int?,
    viewportEnd: Int,
    lastVisibleIndex: Int?,
    viewportSizePx: Int = viewportEnd,
    canScrollForward: Boolean = true,
): BottomFollowDecision {
    if (!enabled || !canScrollForward) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        sentinelBottom == null && lastVisibleIndex != null && lastVisibleIndex < bottomItemIndex ->
            BottomFollowDecision(scrollByPx = viewportSizePx.coerceAtLeast(1))
        else -> BottomFollowDecision()
    }
}

internal data class InitialBottomPosition(
    val bottomItemIndex: Int,
    val hasLayout: Boolean,
    val anchored: Boolean,
    val interrupted: Boolean,
) {
    val ready: Boolean get() = !anchored || interrupted || (bottomItemIndex > 0 && hasLayout)
    val shouldPosition: Boolean get() = ready && anchored && !interrupted
}

/**
 * 一轮对话（两条用户消息之间）里最后一条 Agent 正文视为最终结果，其余为中间步骤。
 * 流式或中途压缩期间当前轮次尚未结束，最后一轮不标记，等结束后复制按钮才出现；
 * 之前已结束轮次的最终结果不受影响。
 *
 * 追加/steering 的用户消息不算新一轮：被打断的正文和继续输出同属一段，
 * 操作栏只出现在整段结束后的最后一条。
 */
internal fun resolveFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean = false,
    isCompressingContext: Boolean = false,
): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentMessageId: String? = null
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is AgentMessageUi -> lastAgentMessageId = message.id
            is SystemNoticeMessageUi -> if (message.code.isRetryableFailure()) {
                ids.add(message.id)
                lastAgentMessageId = null
            }
            else -> Unit
        }
    }
    if (!isStreaming && !isCompressingContext) {
        lastAgentMessageId?.let(ids::add)
    }
    return ids
}

/** One traversal for all final bubbles, rather than one history scan per answer. */
internal fun visibleTurnSpeechPrefaces(
    messages: List<AgentChatMessageUi>,
    finalIds: Set<String>,
): Map<String, String> {
    if (finalIds.isEmpty()) return emptyMap()
    val result = HashMap<String, String>(finalIds.size)
    val parts = ArrayList<String>()
    for (message in messages) {
        if (message.id in finalIds) result[message.id] = parts.joinToString("\n\n")
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) parts.clear()
            is AgentMessageUi -> message.content.trim().takeIf { it.isNotBlank() }?.let(parts::add)
            else -> Unit
        }
    }
    return result
}

/** Visible assistant bubbles in the same turn, excluding collapsed thinking. */
internal fun visibleTurnSpeechPreface(
    messages: List<AgentChatMessageUi>,
    finalId: String,
): String {
    val parts = ArrayList<String>()
    for (message in messages) {
        if (message.id == finalId) break
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) parts.clear()
            is AgentMessageUi -> message.content.trim().takeIf { it.isNotBlank() }?.let(parts::add)
            else -> Unit
        }
    }
    return parts.joinToString("\n\n")
}


@Composable
private fun AgentChatBottomBar(
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    projectedContextTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    uncommittedLiveTokens: Int = 0,
    activeRunContextWindow: Int? = null,
    autoCompressEnabled: Boolean,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
    isCompressingContext: Boolean = false,
    showMorphLoading: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    onSubmit: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit = {},
) {
    val drawerBlocksIme = LocalConversationDrawerBlocksIme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (drawerBlocksIme) Modifier else Modifier.imePadding()),
    ) {
        // 输入框周围保持透明：消息列表延伸到底栏之后，只有输入框本体不透明。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            AgentChatInputBar(
                collaborationConversationId = collaborationConversationId,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                billedOverheadTokens = billedOverheadTokens,
                uncommittedLiveTokens = uncommittedLiveTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                showContextUsage = showContextUsage,
                isStreaming = isStreaming,
                isPaused = isPaused,
                canContinueDisconnected = canContinueDisconnected,
                isCompressingContext = isCompressingContext,
                showMorphLoading = showMorphLoading,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
            conversationMentions = conversationMentions,
                isEditingMessage = messageEdit != null,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                editHasLaterTurns = messageEdit?.hasLaterTurns == true,
                onSubmit = onSubmit,
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                onEditAssistant = onEditAssistant,
                onAssistantSelected = onAssistantSelected,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun shouldShowMorphLoadingIndicator(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    enabled: Boolean,
    beforeResponseOnly: Boolean,
): Boolean {
    if (!enabled || !isStreaming || isPaused || isCompressingContext) return false
    if (!beforeResponseOnly) return true
    return isWaitingForFirstModelOutput(messages)
}

internal fun isWaitingForFirstModelOutput(messages: List<AgentChatMessageUi>): Boolean {
    val lastUserIndex = messages.indexOfLast { message ->
        (message is UserMessageUi && !message.isSteerSupplement()) ||
            (message is SystemNoticeMessageUi && message.code.isRetryableFailure())
    }
    if (lastUserIndex < 0) return false
    return messages.asSequence()
        .drop(lastUserIndex + 1)
        .none(::isModelOutputMessage)
}

private fun isModelOutputMessage(message: AgentChatMessageUi): Boolean = when (message) {
    is ThinkingMessageUi -> true
    is ToolActivityMessageUi -> true
    is ToolSummaryMessageUi -> true
    is AgentMessageUi -> message.content.isNotBlank()
    else -> false
}

private val ChatBackToBottomButtonSlot = 52.dp

private const val ChatBottomSentinelKey = "agent-chat-bottom-sentinel"
private const val ChatContextCompressingKey = "agent-chat-context-compressing"

@Composable
private fun ContextCompressingIndicator(waiting: Boolean = false, modelName: String = "", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
        Text(
            text = if (waiting) stringResource(R.string.compress_conversation_waiting) else
                "${modelName.ifBlank { "主代理" }} • 正在压缩上下文",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

internal fun resolveKeepBottomAnchored(
    current: Boolean,
    isUserDragging: Boolean,
    isAtBottom: Boolean,
    hasLeftBottom: Boolean = false,
): Boolean = when {
    // 手指一开始拖就停跟底；流式长高让 sentinel 离开视口时不能当成用户上滑。
    isUserDragging -> false
    // 只有真正滑离过底部再回来，才重新贴底，避免轻触后被跟底拽回去。
    isAtBottom && (current || hasLeftBottom) -> true
    else -> current
}

internal fun shouldPinConversationToBottom(
    isStreaming: Boolean,
    isScrollable: Boolean,
): Boolean = !(isStreaming && isScrollable)

internal fun resolveBottomFollowEnabled(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    isBottomSettling: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored && !isUserDragging

internal fun shouldRequestInitialBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
): Boolean = isStreaming && keepBottomAnchored && !isUserDragging

internal fun shouldSnapConversationToBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    hasItems: Boolean,
    scrollToMessageId: String? = null,
): Boolean = hasItems &&
    keepBottomAnchored &&
    !isUserDragging &&
    !isStreaming &&
    scrollToMessageId == null

internal fun resolveConversationBottomSnap(
    bottomItemIndex: Int,
    lastVisibleIndex: Int?,
    lastVisibleBottom: Int?,
    viewportEnd: Int,
): BottomFollowDecision {
    if (lastVisibleIndex == null || lastVisibleBottom == null || lastVisibleIndex < bottomItemIndex) {
        return BottomFollowDecision(requestIndex = bottomItemIndex)
    }
    return BottomFollowDecision(scrollByPx = lastVisibleBottom - viewportEnd)
}

private suspend fun snapListToBottom(
    scrollState: LazyListState,
    bottomItemIndex: Int,
    canPosition: () -> Boolean = { true },
) {
    repeat(3) {
        if (!canPosition()) return
        val layout = scrollState.layoutInfo
        val lastVisible = layout.visibleItemsInfo.lastOrNull()
        val viewportEnd = layout.viewportEndOffset - layout.afterContentPadding
        val decision = resolveConversationBottomSnap(
            bottomItemIndex = bottomItemIndex,
            lastVisibleIndex = lastVisible?.index,
            lastVisibleBottom = lastVisible?.let { it.offset + it.size },
            viewportEnd = viewportEnd,
        )
        decision.requestIndex?.let { scrollState.scrollToItem(it) }
        if (decision.scrollByPx != 0) {
            scrollState.scroll { if (canPosition()) scrollBy(decision.scrollByPx.toFloat()) }
        }
        if (decision.requestIndex == null && decision.scrollByPx == 0) return
        withFrameNanos { }
    }
}

/** 尾部哨兵超出静止线的像素；哨兵不在可见项中时返回 null（尾部位置未知）。 */
private fun LazyListState.followTailOverflow(): Int? {
    val info = layoutInfo
    val sentinel = info.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey } ?: return null
    return sentinel.offset + sentinel.size - (info.viewportEndOffset - info.afterContentPadding)
}

internal data class FollowTailLag(val liftPx: Float, val unknown: Boolean = false) {
    companion object {
        val None = FollowTailLag(0f)
        val Unknown = FollowTailLag(0f, unknown = true)
    }
}

/**
 * 跟底输出时，跟底滚动尚未追上的尾部超出量改为绘制上提，让尾部停在静止线上。
 * 不跟底（用户拖动、浏览历史、输出结束）时不做任何处理；尾部不可见时交给静止线裁剪兜底。
 */
/** 用户正在展开最底部工具时不上提，避免整段高度在一帧里把列表抬走。 */
internal fun shouldLiftStreamingTail(followingOutput: Boolean, holdingUserExpansion: Boolean): Boolean =
    followingOutput && !holdingUserExpansion

internal fun resolveFollowTailLag(following: Boolean, tailOverflowPx: Int?): FollowTailLag = when {
    !following -> FollowTailLag.None
    tailOverflowPx == null -> FollowTailLag.Unknown
    tailOverflowPx <= 0 -> FollowTailLag.None
    else -> FollowTailLag(tailOverflowPx.toFloat())
}

private fun LazyListState.isConversationAtBottom(): Boolean {
    val info = layoutInfo
    val sentinel = info.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
    return if (sentinel == null) {
        !canScrollForward
    } else {
        val viewportEnd = info.viewportEndOffset - info.afterContentPadding
        sentinel.offset + sentinel.size <= viewportEnd + 8
    }
}

@Composable
private fun EmptyChatState(
    showSuggestions: Boolean,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        SuggestionItem(
            title = stringResource(R.string.ui_analyze_current_screen_ebf08f),
            icon = Icons.Rounded.DocumentScanner,
            prompt = stringResource(R.string.suggestion_analyze_screen_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_open_wechat_6b2c28),
            icon = Icons.Rounded.RocketLaunch,
            prompt = stringResource(R.string.suggestion_open_wechat_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_browse_the_web_da7afb),
            icon = Icons.Rounded.Language,
            prompt = stringResource(R.string.suggestion_browse_web_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_check_memory_pressure_2d9600),
            icon = Icons.Rounded.Terminal,
            prompt = stringResource(R.string.suggestion_memory_pressure_prompt),
        ),
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.ui_how_can_i_help_you_e75391),
                style = MiuixTheme.textStyles.headline1,
                color = MiuixTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(30.dp))

            AnimatedVisibility(
                visible = showSuggestions,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 220)
                ) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { it / 3 },
                ),
                exit = fadeOut(
                    animationSpec = tween(durationMillis = 130)
                ) + slideOutVertically(
                    animationSpec = tween(durationMillis = 180),
                    targetOffsetY = { it / 4 },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    suggestions.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { item ->
                                SuggestionCard(
                                    item = item,
                                    onClick = { onSuggestionClick(item.prompt) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    item: SuggestionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surface)
            .border(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(9.dp))
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

private data class SuggestionItem(
    val title: String,
    val icon: ImageVector,
    val prompt: String,
)

internal fun shouldStopOrphanSpeechPlayback(
    owner: String?,
    messageEditActive: Boolean,
    visibleCompletedAgentIds: Set<String>,
): Boolean {
    if (owner.isNullOrBlank()) return false
    if (owner == "tts-preview" || owner.startsWith("voice-mode-")) return false
    return messageEditActive || owner !in visibleCompletedAgentIds
}

/** 最后一条消息静止时与输入框上沿的间距；跟底输出时正文也被裁在这条线上。 */
private val ConversationComposerGap = 14.dp
