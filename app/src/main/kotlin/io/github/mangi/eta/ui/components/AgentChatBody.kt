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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.countUncommittedLiveTokens
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
import kotlin.math.exp
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
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
    val initialBottomItemIndex = remember(visibleMessages, isCompressingContext, isWaitingForCompression) {
        visibleMessages.toTimelineEntries().size + if (isCompressingContext || isWaitingForCompression) 1 else 0
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

    val billedContextTokens = remember(messages, livePromptTokens, messageEdit) {
        if (messageEdit != null) {
            null
        } else {
            livePromptTokens ?: latestBilledContextTokens(messages)
        }
    }
    val uncommittedLiveTokens = remember(visibleMessages, billedContextTokens, messageEdit) {
        if (messageEdit != null || billedContextTokens != null) 0
        else countUncommittedLiveTokens(visibleMessages)
    }
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
    ChatImagePreviewHost(gallery = previewGallery) {
        AgentChatScaffold(
            visibleMessages = visibleMessages,
            hasMessages = visibleMessages.isNotEmpty(),
            scrollState = scrollState,
            input = input,
            draftField = draftField,
            modelPickerState = modelPickerState,
            history = history,
            billedContextTokens = billedContextTokens,
            requestOverheadTokens = requestOverheadTokens,
            billedOverheadTokens = billedOverheadTokens,
            uncommittedLiveTokens = uncommittedLiveTokens,
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
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    uncommittedLiveTokens: Int = 0,
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
    val surfaceColor = MiuixTheme.colorScheme.surface
    val frostEnabled = hasMessages && LocalBlurEnabled.current && isRuntimeShaderSupported()
    val messageBackdrop = rememberLayerBackdrop {
        // Backdrop 必须包含不透明底色，否则文字边缘模糊到透明区域时会出现黑边。
        drawRect(surfaceColor)
        drawContent()
    }

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
                messageBackdrop = messageBackdrop.takeIf { frostEnabled },
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                requestOverheadTokens = requestOverheadTokens,
                billedOverheadTokens = billedOverheadTokens,
                uncommittedLiveTokens = uncommittedLiveTokens,
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
                    .fillMaxSize()
                    .then(if (frostEnabled) Modifier.layerBackdrop(messageBackdrop) else Modifier),
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
    LaunchedEffect(scrollToMessageId, timelineEntries) {
        val target = scrollToMessageId ?: return@LaunchedEffect
        val index = timelineEntries.indexOfFirst { entry ->
            when (entry) {
                is AgentTimelineEntry.Message -> entry.message.id == target
                is AgentTimelineEntry.WorkProcess ->
                    entry.key == target || entry.messages.any { it.id == target }
            }
        }
        if (index >= 0) {
            onBottomAnchorChanged(false)
            scrollState.animateScrollToItem(index)
            onScrollToMessageConsumed()
        } else if (timelineEntries.isNotEmpty()) {
            onScrollToMessageConsumed()
        }
    }
    // 操作栏只出现在每轮对话的最终结果上，正在输出的正文保持隐藏。
    // 流式进行中当前这一轮尚未收尾，不把临时的最后一条正文标为最终结果。
    val finalResultMessageIds = remember(visibleMessages, isStreaming, isCompressingContext) {
        resolveFinalResultMessageIds(
            visibleMessages,
            isStreaming = isStreaming,
            isCompressingContext = isCompressingContext,
        )
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val fallbackStreamingStates = remember { mutableStateMapOf<String, StreamingMarkdownState>() }
    val streamingMarkdownStates = LocalStreamingMarkdownStates.current ?: fallbackStreamingStates
    LaunchedEffect(visibleMessages, streamingMarkdownStates) {
        val activeIds = visibleMessages.mapTo(mutableSetOf()) { it.id }
        streamingMarkdownStates.keys.retainAll(activeIds)
    }
    val compressingItemCount = if (isCompressingContext || isWaitingForCompression) 1 else 0
    val bottomItemIndex = timelineEntries.size + compressingItemCount
    val turnStarts = remember(timelineEntries) { timelineEntries.turnStartIndices() }
    val directionThreshold = with(LocalDensity.current) { 12.dp.toPx() }
    val directionTracker = remember(scrollState, directionThreshold) {
        ConversationNavigationDirectionTracker(directionThreshold)
    }
    var navigationDirection by remember(scrollState) { mutableStateOf(ConversationNavigationDirection.Down) }
    var turnNavigationJob by remember(scrollState) { mutableStateOf<Job?>(null) }
    DisposableEffect(scrollState) {
        onDispose { turnNavigationJob?.cancel() }
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
                    turnNavigationJob?.cancel()
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
            if (turnNavigationJob != null) null
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
            val tail = currentVisibleMessages.value.lastOrNull() as? AgentMessageUi
            val rendering = tail?.let { message ->
                streamingMarkdownStates[message.id]?.revealedContent != message.content
            } == true
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

    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = isUserScrolling || turnNavigationJob != null,
            isBottomSettling = isBottomSettling,
        )
    )
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    LaunchedEffect(
        bottomItemIndex,
        keepBottomAnchored,
        isUserScrolling,
        turnNavigationJob,
        isStreaming,
        scrollToMessageId,
    ) {
        if (shouldSnapConversationToBottom(
                isStreaming = isStreaming,
                keepBottomAnchored = keepBottomAnchored,
                isUserDragging = isUserScrolling || turnNavigationJob != null,
                hasItems = bottomItemIndex > 0,
                scrollToMessageId = scrollToMessageId,
            )
        ) {
            snapListToBottom(scrollState, bottomItemIndex)
            return@LaunchedEffect
        }
        if (shouldRequestInitialBottom(
                isStreaming = isStreaming,
                keepBottomAnchored = keepBottomAnchored,
                isUserDragging = isUserScrolling || turnNavigationJob != null,
            )
        ) {
            scrollState.requestScrollToItem(bottomItemIndex)
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
                // 输入器高度属于滚动内容的 bottom inset，而不是滚动容器高度。
                // 跟底目标应是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
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
                )
                StreamPerformanceDiagnostics.record("follow.decision", value = decision.scrollByPx.toLong())
                bottomFollowDecisions.trySend(decision)
            }
    }

    // 一个持续存在的帧时钟从当前屏幕位置追向最新目标。新字符继续到达时只更新目标，
    // 不取消并重启动画，因此速度连续；用户开始拖动后，enabled=false 会立即停止跟随。
    LaunchedEffect(scrollState, bottomFollowDecisions) {
        var remainingDistancePx = 0f
        var requestIndex: Int? = null
        var previousFrameNanos = 0L

        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.toFloat()
            requestIndex = decision.requestIndex
        }

        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f && requestIndex == null) {
                accept(bottomFollowDecisions.receive())
                previousFrameNanos = 0L
            }
            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }

            if (!shouldFollowBottom || isUserScrolling || turnNavigationJob != null) {
                remainingDistancePx = 0f
                requestIndex = null
                continue
            }

            requestIndex?.let { targetIndex ->
                scrollState.requestScrollToItem(targetIndex)
                requestIndex = null
                remainingDistancePx = 0f
                return@let
            }
            if (remainingDistancePx <= 0f) continue

            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos

            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }
            if (!shouldFollowBottom || isUserScrolling || turnNavigationJob != null || requestIndex != null || remainingDistancePx <= 0f) continue

            val step = smoothBottomFollowStep(
                distancePx = remainingDistancePx,
                elapsedSeconds = elapsedSeconds,
                density = densityScale,
            )
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    // scroll() may wait for another mutation; check ownership again.
                    if (!isUserScrolling && turnNavigationJob == null && shouldFollowBottom) {
                        consumedStep = StreamPerformanceDiagnostics.measure("follow.scroll") { scrollBy(step) }
                    }
                }
                remainingDistancePx = if (consumedStep > 0f) {
                    (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else {
                    0f
                }
            } catch (cancelled: CancellationException) {
                StreamPerformanceDiagnostics.record("follow.cancelled")
                if (!currentCoroutineContext().isActive) throw cancelled
                remainingDistancePx = 0f
            }
        }
    }

    // 滚动层保持整屏，输入器作为后绘制浮层；输入器高度进入列表的
    // afterContentPadding，确保跟到底部时最后一行停在输入器上方。
    Box(modifier = modifier.clipToBounds()) {
        val trailingWorkKey =
            (timelineEntries.lastOrNull() as? AgentTimelineEntry.WorkProcess)?.key
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
        LazyColumn(
            state = scrollState,
            verticalArrangement = if (shouldPinConversationToBottom(isStreaming, streamFilledViewport)) {
                Arrangement.Bottom
            } else {
                Arrangement.Top
            },
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(userScrollConnection)
                // Navigation already emits one explicit click/long-press haptic.
                .then(if (turnNavigationJob == null) Modifier.scrollEndHaptic() else Modifier)
                .overScrollVertical(),
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = bottomInset + 14.dp,
            ),
            overscrollEffect = null,
        ) {
            items(
                items = timelineEntries,
                key = { it.key },
                contentType = { if (it is AgentTimelineEntry.Message) "message" else "work-process" },
            ) { entry ->
                when (entry) {
                    is AgentTimelineEntry.Message -> {
                        val message = entry.message
                        ChatMessageItem(
                            message = message,
                            speechPreface = (message as? AgentMessageUi)?.let { speechPrefaces[it.id] }.orEmpty(),
                            retainedStreamingState = (message as? AgentMessageUi)
                                ?.takeIf { it.isStreaming || streamingMarkdownStates.containsKey(it.id) }
                                ?.let { agentMessage ->
                                    streamingMarkdownStates.getOrPut(agentMessage.id) {
                                        StreamingMarkdownState()
                                    }
                                },
                            onSuggestionClick = onSuggestionClick,
                            onRunTraceClick = onRunTraceClick,
                            onOpenBrowser = onOpenBrowser,
                            showBrowserShortcut = message is ToolActivityMessageUi &&
                                message.toolName == "browser_use" &&
                                message.id == currentBrowserMessageId,
                            enableLivePreview = !isStreaming,
                            showCopyAction = message !is AgentMessageUi ||
                                message.id in finalResultMessageIds,
                            showMessageActions = message.id in finalResultMessageIds,
                            messageActionsEnabled = messageActionsEnabled && !isStreaming && !isPaused,
                            branchEnabled = branchEnabled,
                            isEditing = message.id == editTargetMessageId,
                            onEditMessage = onEditMessage,
                            onDeleteMessage = onDeleteMessage,
                            onRegenerateMessage = onRegenerateMessage,
                            onBranchMessage = onBranchMessage,
                            isPaused = isPaused,
                            modifier = if (isStreaming) Modifier else Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 180),
                                placementSpec = null,
                                fadeOutSpec = null,
                            ),
                        )
                    }

                    is AgentTimelineEntry.WorkProcess -> {
                        entry.messages.forEach { message ->
                            if (message is ThinkingMessageUi && message.isStreaming) {
                                streamingMarkdownStates.getOrPut(message.id) {
                                    StreamingMarkdownState()
                                }
                            }
                        }

                        AgentWorkProcess(
                            id = entry.key,
                            messages = entry.messages,
                            onOpenBrowser = onOpenBrowser,
                            currentBrowserMessageId = currentBrowserMessageId,
                            retainedStreamingStates = streamingMarkdownStates,
                            isPaused = isPaused,
                            isTrailing = entry.key == trailingWorkKey,
                            turnStreaming = isStreaming,
                            modifier = if (isStreaming) Modifier else Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 180),
                                placementSpec = null,
                                fadeOutSpec = null,
                            ),
                        )
                    }
                }
            }
            if (isCompressingContext || isWaitingForCompression) {
                item(key = ChatContextCompressingKey) {
                    ContextCompressingIndicator(
                        waiting = isWaitingForCompression && !isCompressingContext,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = 180),
                            placementSpec = null,
                            fadeOutSpec = null,
                        ),
                    )
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

        fun navigateTurn(toEdge: Boolean) {
            // Do not queue animations on rapid taps; a new drag cancels the active jump.
            if (turnNavigationJob != null) return
            val direction = navigationDirection
            val target = conversationTurnTarget(
                turnStarts, scrollState.firstVisibleItemIndex, bottomItemIndex, direction, toEdge,
            )
            onBottomAnchorChanged(false)
            turnNavigationJob = coroutineScope.launch {
                try {
                    // Let the follow/boundary-haptic observers yield before moving the list.
                    withFrameNanos { }
                    scrollState.animateScrollToItem(target)
                    if (target == bottomItemIndex) snapListToBottom(scrollState, currentBottomItemIndex)
                    onBottomAnchorChanged(
                        direction == ConversationNavigationDirection.Down && scrollState.isConversationAtBottom(),
                    )
                } finally {
                    turnNavigationJob = null
                }
            }
        }
        val showTurnNavigation by remember(scrollState, navigationDirection, keepBottomAnchored) {
            derivedStateOf {
                !keepBottomAnchored && when (navigationDirection) {
                    ConversationNavigationDirection.Up -> scrollState.canScrollBackward
                    ConversationNavigationDirection.Down -> !scrollState.isConversationAtBottom()
                }
            }
        }
        ConversationTurnNavigationButton(
            direction = navigationDirection,
            visible = showTurnNavigation,
            onStep = { navigateTurn(toEdge = false) },
            onEdge = { navigateTurn(toEdge = true) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomInset + 12.dp),
        )
    }
}

private data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
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
): BottomFollowDecision {
    if (!enabled) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        sentinelBottom == null &&
            lastVisibleIndex != null &&
            lastVisibleIndex < bottomItemIndex -> BottomFollowDecision(requestIndex = bottomItemIndex)
        else -> BottomFollowDecision()
    }
}

internal fun smoothBottomFollowStep(
    distancePx: Float,
    elapsedSeconds: Float,
    density: Float,
): Float {
    if (distancePx <= 0f || elapsedSeconds <= 0f) return 0f
    if (distancePx <= BOTTOM_FOLLOW_SNAP_DISTANCE_PX) return distancePx

    val frameSeconds = elapsedSeconds.coerceAtMost(BOTTOM_FOLLOW_MAX_FRAME_SECONDS)
    val easedStep = distancePx * (1f - exp(-frameSeconds / BOTTOM_FOLLOW_RESPONSE_SECONDS))
    val speedLimitedStep = BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND * density * frameSeconds
    return min(distancePx, min(easedStep.coerceAtLeast(BOTTOM_FOLLOW_MIN_STEP_PX), speedLimitedStep))
}

internal sealed interface AgentTimelineEntry {
    val key: String

    data class Message(
        val message: AgentChatMessageUi,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class WorkProcess(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
    ) : AgentTimelineEntry
}

internal fun List<AgentChatMessageUi>.toTimelineEntries(): List<AgentTimelineEntry> = buildList {
    val workMessages = mutableListOf<AgentChatMessageUi>()

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        add(
            AgentTimelineEntry.WorkProcess(
                key = "work-${workMessages.first().id}",
                messages = workMessages.toList(),
            )
        )
        workMessages.clear()
    }

    this@toTimelineEntries.forEach { message ->
        if (message is UserMessageUi && message.isResumeAfterCompress()) {
            return@forEach
        }
        if (message.isWorkProcessMessage()) {
            workMessages += message
        } else {
            flushWorkProcess()
            add(AgentTimelineEntry.Message(message))
        }
    }
    flushWorkProcess()
}

/** Use projected list indices, not raw message indices (work steps are grouped). */
internal fun List<AgentTimelineEntry>.turnStartIndices(): List<Int> = mapIndexedNotNull { index, entry ->
    val user = (entry as? AgentTimelineEntry.Message)?.message as? UserMessageUi
    index.takeIf { user != null && !user.isSteerSupplement() && !user.isResumeAfterCompress() }
}

private fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi || this is ToolActivityMessageUi || this is ToolSummaryMessageUi

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
    messageBackdrop: LayerBackdrop?,
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    uncommittedLiveTokens: Int = 0,
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
        if (messageBackdrop != null) {
            val blurColors = BlurDefaults.blurColors(
                blendColors = listOf(
                    BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.72f))
                ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ChatBottomFrostHeight)
                    // DstIn 让真实磨砂在顶部透明、靠近输入框时逐渐变实，消除硬裁切线。
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .textureBlur(
                        backdrop = messageBackdrop,
                        shape = RectangleShape,
                        blurRadius = 20f,
                        colors = blurColors,
                    ),
            )
        } else {
            // 空白主页沿用原来的轻微渐隐，不改变主页视觉。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MiuixTheme.colorScheme.surface,
                            ),
                        )
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MiuixTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            AgentChatInputBar(
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                requestOverheadTokens = requestOverheadTokens,
                billedOverheadTokens = billedOverheadTokens,
                uncommittedLiveTokens = uncommittedLiveTokens,
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

private val ChatBottomFrostHeight = 24.dp
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
private fun ContextCompressingIndicator(waiting: Boolean = false, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
        Text(
            text = stringResource(if (waiting) R.string.compress_conversation_waiting else R.string.compress_conversation_in_progress),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private const val BOTTOM_FOLLOW_RESPONSE_SECONDS = 0.085f
private const val BOTTOM_FOLLOW_MAX_FRAME_SECONDS = 0.05f
private const val BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND = 720f
private const val BOTTOM_FOLLOW_MIN_STEP_PX = 0.5f
private const val BOTTOM_FOLLOW_SNAP_DISTANCE_PX = 0.75f

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
) {
    repeat(3) {
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
            scrollState.scroll { scrollBy(decision.scrollByPx.toFloat()) }
        }
        if (decision.requestIndex == null && decision.scrollByPx == 0) return
        withFrameNanos { }
    }
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
