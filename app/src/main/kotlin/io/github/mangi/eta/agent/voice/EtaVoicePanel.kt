package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CancelPresentation
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.rememberChatVoiceController
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeController
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.components.AgentChatInputBar
import io.github.mangi.eta.ui.components.EtaDropdownMenu
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.components.rememberDataUrlBitmap
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class EtaVoicePhase {
    READY,
    PROCESSING,
    ERROR,
}

internal data class AssistantConversationItem(
    val id: String,
    val title: String,
    val updatedAt: Long = 0L,
    val isCurrent: Boolean = false,
)

internal data class EtaVoiceUiState(
    val messages: List<AgentChatMessageUi> = emptyList(),
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
    val status: EtaVoiceStatus = EtaVoiceStatus.InputRequest,
    val screenContext: EtaScreenContextUiState = EtaScreenContextUiState(),
    val conversationId: String? = null,
    val conversationTitle: String = "",
    val historyConversations: List<AssistantConversationItem> = emptyList(),
    val isHistoryMenuVisible: Boolean = false,
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val modelPickerState: AgentModelPickerUiState = AgentModelPickerUiState(),
    val reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
)

/** 浮窗内的消息改写目标：删除/重新生成前先弹同款下拉确认。 */
internal data class OverlayMessageMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
)

internal sealed interface EtaVoiceStatus {
    data object InputRequest : EtaVoiceStatus
    data object Reasoning : EtaVoiceStatus
    data object Completed : EtaVoiceStatus
    data class RunningTool(val name: String) : EtaVoiceStatus
    data class Failed(val detail: String?) : EtaVoiceStatus
    data object Stopped : EtaVoiceStatus
}

internal enum class EtaScreenContextPhase {
    CAPTURING,
    AVAILABLE,
    UNAVAILABLE,
    CONSUMED,
}

internal data class EtaScreenContextUiState(
    val phase: EtaScreenContextPhase = EtaScreenContextPhase.CONSUMED,
    val previewDataUrl: String? = null,
    val selected: Boolean = false,
)

internal object EtaScreenContextStateReducer {
    fun select(
        state: EtaScreenContextUiState,
        enabled: Boolean,
        hasAttachment: Boolean,
    ): EtaScreenContextUiState =
        if (enabled && hasAttachment && state.phase == EtaScreenContextPhase.AVAILABLE) {
            state.copy(selected = true)
        } else {
            state
        }

    fun remove(
        state: EtaScreenContextUiState,
        enabled: Boolean,
    ): EtaScreenContextUiState =
        if (enabled && state.selected) state.copy(selected = false) else state

    fun consume(): EtaScreenContextUiState = EtaScreenContextUiState(
        phase = EtaScreenContextPhase.CONSUMED,
    )
}

private data class EtaVoicePanelColors(
    val content: Color,
    val contentGradientEnd: Color,
    val borderHighlight: Color,
    val input: Color,
    val inputPrimary: Color,
    val inputSecondary: Color,
    val inputTertiary: Color,
    val tertiary: Color,
    val scrim: Color,
)

@Composable
private fun rememberEtaVoicePanelColors(): EtaVoicePanelColors {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        if (dark) {
            EtaVoicePanelColors(
                content = Color(0x8C1C1F26),
                contentGradientEnd = Color(0x70111317),
                borderHighlight = Color(0x40FFFFFF),
                // Q2-A：半透明底色，让浮窗 blurBehindRadius 透出来（柔光玻璃）
                input = Color(0x59404040),
                inputPrimary = Color(0xE6FFFFFF),
                inputSecondary = Color(0x8AFFFFFF),
                inputTertiary = Color(0x4DFFFFFF),
                tertiary = Color(0x66FFFFFF),
                scrim = Color(0x4D000000),
            )
        } else {
            EtaVoicePanelColors(
                content = Color(0x8AFFFFFF),
                contentGradientEnd = Color(0x66EFF3F8),
                borderHighlight = Color(0x99FFFFFF),
                // Q2-A：半透明底色，让浮窗 blurBehindRadius 透出来（柔光玻璃）
                input = Color(0x59FFFFFF),
                inputPrimary = Color(0xE6000000),
                inputSecondary = Color(0x8A000000),
                inputTertiary = Color(0x42000000),
                tertiary = Color(0x52000000),
                scrim = Color(0x20000000),
            )
        }
    }
}

@Composable
internal fun EtaVoicePanel(
    state: EtaVoiceUiState,
    input: String,
    inputFocusRequestKey: Int,
    canOpenConversation: Boolean,
    exitRequested: Boolean,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenTranslation: () -> Unit,
    onToggleHistoryMenu: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onKeyboard: () -> Unit,
    onOpenConversation: () -> Unit,
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onAssistantSelected: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    messageLaterTurnCount: (String) -> Int?,
    onEditMessage: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    history: List<AgentModelClient.ConversationMessage>,
    autoCompressEnabled: Boolean,
    assistantId: String,
) {
    val colors = rememberEtaVoicePanelColors()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val entryProgress = remember { Animatable(0f) }
    val scrimProgress = remember { Animatable(0f) }
    val exitAlpha by animateFloatAsState(
        targetValue = if (exitRequested) 0f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "assistant_exit",
    )

    LaunchedEffect(Unit) {
        launch { entryProgress.animateTo(1f, tween(160, easing = LinearOutSlowInEasing)) }
        delay(180)
        scrimProgress.animateTo(1f, tween(280, easing = LinearOutSlowInEasing))
    }

    LaunchedEffect(inputFocusRequestKey) {
        if (inputFocusRequestKey >= 0 && state.phase != EtaVoicePhase.PROCESSING) {
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha }
            .drawBehind { drawRect(colors.scrim.copy(alpha = colors.scrim.alpha * scrimProgress.value)) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entryProgress.value
                    translationY = (1f - entryProgress.value) * with(density) { 32.dp.toPx() }
                },
        ) {
            val imeBottom = WindowInsets.ime.getBottom(density)
            val navigationBottom = WindowInsets.navigationBars.getBottom(density)
            val statusTop = WindowInsets.statusBars.getTop(density)
            val bottomInset = max(imeBottom, navigationBottom)
            val imeOverlap = (imeBottom - navigationBottom).coerceAtLeast(0)
            val maxContentHeightPx = with(density) {
                (maxHeight - 88.dp).toPx() - statusTop - navigationBottom
            }.coerceAtLeast(with(density) { 220.dp.toPx() })
            AssistantPanel(
                state = state,
                input = input,
                colors = colors,
                focusRequester = focusRequester,
                canOpenConversation = canOpenConversation,
                baseContentHeightPx = assistantBaseHeightPx(
                    messages = state.messages,
                    maxHeightPx = maxContentHeightPx,
                    density = density.density,
                ),
                maxContentHeightPx = maxContentHeightPx,
                bottomInsetPx = bottomInset,
                imeOverlapPx = imeOverlap,
                onScreenContextSelect = onScreenContextSelect,
                onScreenContextRemove = onScreenContextRemove,
                onScreenTranslation = onScreenTranslation,
                onToggleHistoryMenu = onToggleHistoryMenu,
                onSelectConversation = onSelectConversation,
                onNewConversation = onNewConversation,
                onDeleteConversation = onDeleteConversation,
                onModelSelected = onModelSelected,
                onSubmit = { text ->
                    keyboard?.hide()
                    onSubmit(text)
                },
                onStop = onStop,
                onClose = onClose,
                onKeyboard = onKeyboard,
                onOpenConversation = onOpenConversation,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onReasoningEffortChange = onReasoningEffortChange,
                onAssistantSelected = onAssistantSelected,
                onEditAssistant = onEditAssistant,
                messageLaterTurnCount = messageLaterTurnCount,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onCancelMessageEdit = onCancelMessageEdit,
                history = history,
                autoCompressEnabled = autoCompressEnabled,
                assistantId = assistantId,
            )
        }
    }
}

@Composable
private fun BoxScope.AssistantPanel(
    state: EtaVoiceUiState,
    input: String,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    canOpenConversation: Boolean,
    baseContentHeightPx: Float,
    maxContentHeightPx: Float,
    bottomInsetPx: Int,
    imeOverlapPx: Int,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenTranslation: () -> Unit,
    onToggleHistoryMenu: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onKeyboard: () -> Unit,
    onOpenConversation: () -> Unit,
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onAssistantSelected: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    messageLaterTurnCount: (String) -> Int?,
    onEditMessage: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    history: List<AgentModelClient.ConversationMessage>,
    autoCompressEnabled: Boolean,
    assistantId: String,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var settledHeightPx by remember { mutableFloatStateOf(baseContentHeightPx) }
    var draggedHeightPx by remember { mutableStateOf<Float?>(null) }
    var dismissPullPx by remember { mutableFloatStateOf(0f) }
    var handoffPullPx by remember { mutableFloatStateOf(0f) }
    var directHandoffPullPx by remember { mutableFloatStateOf(0f) }
    var thresholdHapticSent by remember { mutableStateOf(false) }
    var handoffRunning by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    var messageDeleteTarget by remember { mutableStateOf<OverlayMessageMutationTarget?>(null) }
    var messageRegenerateTarget by remember { mutableStateOf<OverlayMessageMutationTarget?>(null) }
    val handoffThresholdPx = with(density) { 72.dp.toPx() }
    val directHandoffThresholdPx = with(density) { 48.dp.toPx() }
    val dismissThresholdPx = with(density) { 92.dp.toPx() }
    val handoffVelocityPx = with(density) { 900.dp.toPx() }
    // 面板态底栏与输入栏共用同一个语音控制器（浮窗为 Service，LocalContext/LocalLifecycleOwner 均可用）。
    val voiceController = rememberChatVoiceController(state.conversationId, onSubmit)
    val voiceState by voiceController.state.collectAsState()

    val hasMessages = state.messages.isNotEmpty()
    val targetHeightPx = if (hasMessages) {
        settledHeightPx.coerceIn(baseContentHeightPx, maxContentHeightPx)
    } else {
        0f
    }
    val animatedHeightPx by animateFloatAsState(
        targetValue = targetHeightPx,
        animationSpec = folmeSpring(damping = 0.9f, response = 0.38f),
        label = "assistant_content_height",
    )
    val sheetBackgroundAlpha = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = tween(durationMillis = 160, easing = LinearOutSlowInEasing),
        label = "assistant_sheet_background",
    )
    val messageRevealProgress = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = if (hasMessages) {
            tween(durationMillis = 180, delayMillis = 50, easing = LinearOutSlowInEasing)
        } else {
            tween(durationMillis = 100, easing = FastOutSlowInEasing)
        },
        label = "assistant_message_reveal",
    )
    val currentAnimatedHeight = rememberUpdatedState(animatedHeightPx)
    val sheetHeightPx = draggedHeightPx ?: animatedHeightPx
    // 输入栏（含附件/推理/助手/模型/发送）是 sheet 之下固定占位的一层。
    // 消息区必须为它预留高度，否则 IME 弹出时输入栏会被消息区顶出可视区。
    val composerReservedHeightPx = with(density) {
        val base = 136.dp
        val historyExtra = if (state.isHistoryMenuVisible) 180.dp else 0.dp
        (base + historyExtra).toPx()
    }
    val maxAvailableForSheetPx = (
        maxContentHeightPx - imeOverlapPx - composerReservedHeightPx
        ).coerceAtLeast(0f)
    val visibleSheetHeightPx = sheetHeightPx.coerceAtMost(maxAvailableForSheetPx)
    val nearFullscreen = sheetHeightPx >= maxContentHeightPx * 0.88f
    val handoffReady = canOpenConversation && nearFullscreen &&
        (handoffPullPx >= handoffThresholdPx ||
            directHandoffPullPx >= directHandoffThresholdPx)
    val sheetTranslationPx = dismissPullPx * 0.28f - handoffPullPx.coerceAtMost(
        with(density) { 28.dp.toPx() },
    ) * 0.12f

    LaunchedEffect(hasMessages, baseContentHeightPx, maxContentHeightPx) {
        settledHeightPx = if (hasMessages) {
            settledHeightPx.coerceIn(baseContentHeightPx, maxContentHeightPx)
        } else {
            0f
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) keepBottomAnchored = true
    }
    LaunchedEffect(handoffReady) {
        if (handoffReady && !thresholdHapticSent) {
            thresholdHapticSent = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!handoffReady) {
            thresholdHapticSent = false
        }
    }

    fun triggerHandoff() {
        if (handoffRunning || !canOpenConversation) return
        handoffRunning = true
        settledHeightPx = maxContentHeightPx
        draggedHeightPx = null
        scope.launch {
            delay(120)
            onOpenConversation()
        }
    }

    fun dragBy(deltaY: Float): Float {
        if (handoffRunning || state.messages.isEmpty()) return 0f
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        val requested = current - deltaY
        return when {
            requested > maxContentHeightPx -> {
                draggedHeightPx = maxContentHeightPx
                if (deltaY < 0f) handoffPullPx += -deltaY
                deltaY
            }
            requested < baseContentHeightPx -> {
                draggedHeightPx = baseContentHeightPx
                if (deltaY > 0f) dismissPullPx += deltaY
                deltaY
            }
            else -> {
                draggedHeightPx = requested
                dismissPullPx = 0f
                handoffPullPx = 0f
                deltaY
            }
        }
    }

    fun finishDrag(velocityY: Float = 0f) {
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        when {
            dismissPullPx >= dismissThresholdPx -> onClose()
            canOpenConversation && current >= maxContentHeightPx * 0.88f &&
                (handoffReady || velocityY <= -handoffVelocityPx) -> triggerHandoff()
            else -> {
                val medium = baseContentHeightPx +
                    (maxContentHeightPx - baseContentHeightPx) * 0.58f
                val anchors = floatArrayOf(baseContentHeightPx, medium, maxContentHeightPx)
                settledHeightPx = anchors.minBy { kotlin.math.abs(it - current) }
                draggedHeightPx = null
                dismissPullPx = 0f
                handoffPullPx = 0f
            }
        }
    }

    val bottomInset = with(density) { bottomInsetPx.toDp() }
    val messageRevealOffsetPx = with(density) { 12.dp.toPx() }
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .offset(y = with(density) { sheetTranslationPx.toDp() })
            .clip(sheetShape)
            // 吸收 sheet 内的空白点击，避免穿透到 scrim 触发 onClose 关掉浮窗。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .drawBehind {
                val bgAlpha = sheetBackgroundAlpha.value
                val glassBrush = Brush.verticalGradient(
                    colors = listOf(
                        colors.content.copy(alpha = colors.content.alpha * bgAlpha),
                        colors.contentGradientEnd.copy(alpha = colors.contentGradientEnd.alpha * bgAlpha),
                    ),
                )
                drawRect(brush = glassBrush)
                val rimBrush = Brush.verticalGradient(
                    colors = listOf(
                        colors.borderHighlight.copy(alpha = colors.borderHighlight.alpha * bgAlpha),
                        colors.borderHighlight.copy(alpha = colors.borderHighlight.alpha * 0.45f * bgAlpha),
                        colors.borderHighlight.copy(alpha = colors.borderHighlight.alpha * 0.2f * bgAlpha),
                    ),
                    startY = 0f,
                    endY = size.height,
                )
                drawRect(
                    brush = rimBrush,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { visibleSheetHeightPx.toDp() }),
        ) {
            DragHandle(
                colors = colors,
                modifier = Modifier.pointerInput(baseContentHeightPx, maxContentHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { draggedHeightPx = currentAnimatedHeight.value },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragBy(dragAmount)
                        },
                        onDragEnd = { finishDrag() },
                        onDragCancel = { finishDrag() },
                    )
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        alpha = messageRevealProgress.value
                        translationY = (1f - messageRevealProgress.value) * messageRevealOffsetPx
                    },
            ) {
                if (hasMessages) {
                    AgentConversationMessages(
                        // 编辑某条消息时只显示到它为止，与本体 AgentChatBody 同一口径。
                        visibleMessages = AgentConversationRevisionReducer.visibleMessagesForEdit(
                            state.messages,
                            state.messageEdit?.targetMessageId,
                        ),
                        scrollState = listState,
                        isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                        bottomInset = 8.dp,
                        keepBottomAnchored = keepBottomAnchored,
                        onBottomAnchorChanged = { keepBottomAnchored = it },
                        onEditMessage = onEditMessage,
                        onDeleteMessage = { id ->
                            messageLaterTurnCount(id)?.let { count ->
                                messageDeleteTarget = OverlayMessageMutationTarget(id, count)
                            }
                        },
                        onRegenerateMessage = { id ->
                            when (val count = messageLaterTurnCount(id)) {
                                null -> Unit
                                0 -> onRegenerateMessage(id)
                                else -> messageRegenerateTarget =
                                    OverlayMessageMutationTarget(id, count)
                            }
                        },
                        // 浮窗沿用本体同一套操作栏；分支按钮按裁决不接（branchEnabled 保持 false）。
                        messageActionsEnabled = state.phase != EtaVoicePhase.PROCESSING,
                        editTargetMessageId = state.messageEdit?.targetMessageId,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // 浮窗没有 Activity token，本体那套 WindowDialog 会抛 BadTokenException；
                // 这里用与浮窗其它菜单同款的下拉弹层做二次确认。
                val mutationTarget = messageDeleteTarget ?: messageRegenerateTarget
                if (mutationTarget != null) {
                    val isDelete = messageDeleteTarget != null
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        EtaDropdownMenu(
                            expanded = true,
                            onDismissRequest = {
                                messageDeleteTarget = null
                                messageRegenerateTarget = null
                            },
                            alignEnd = true,
                            preferAbove = true,
                            minWidth = 0.dp,
                            focusable = false,
                        ) {
                            Text(
                                text = stringResource(
                                    if (isDelete) {
                                        R.string.conversation_delete_message_title
                                    } else {
                                        R.string.conversation_regenerate_title
                                    },
                                ),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.inputPrimary,
                                modifier = Modifier.padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = 4.dp,
                                    bottom = 2.dp,
                                ),
                            )
                            Text(
                                text = if (isDelete) {
                                    if (mutationTarget.laterTurnCount == 0) {
                                        stringResource(R.string.conversation_delete_message_body)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.conversation_delete_later_turns,
                                            mutationTarget.laterTurnCount,
                                            mutationTarget.laterTurnCount,
                                        )
                                    }
                                } else {
                                    if (mutationTarget.laterTurnCount == 0) {
                                        stringResource(R.string.conversation_regenerate_current_turn)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.conversation_regenerate_later_turns,
                                            mutationTarget.laterTurnCount,
                                            mutationTarget.laterTurnCount,
                                        )
                                    }
                                },
                                fontSize = 12.sp,
                                color = colors.inputSecondary,
                                modifier = Modifier.padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    bottom = 4.dp,
                                ),
                            )
                            DropdownMenuItem(
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                text = {
                                    Text(
                                        text = stringResource(R.string.action_cancel),
                                        fontSize = 14.sp,
                                        color = colors.inputPrimary,
                                    )
                                },
                                onClick = {
                                    messageDeleteTarget = null
                                    messageRegenerateTarget = null
                                },
                            )
                            DropdownMenuItem(
                                modifier = Modifier.height(40.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                text = {
                                    Text(
                                        text = stringResource(
                                            if (isDelete) {
                                                R.string.action_delete
                                            } else {
                                                R.string.action_regenerate
                                            },
                                        ),
                                        fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    val id = mutationTarget.messageId
                                    messageDeleteTarget = null
                                    messageRegenerateTarget = null
                                    if (isDelete) onDeleteMessage(id) else onRegenerateMessage(id)
                                },
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = state.isHistoryMenuVisible,
            enter = fadeIn(tween(180, easing = LinearOutSlowInEasing)) +
                slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 4 },
            exit = fadeOut(tween(140, easing = FastOutSlowInEasing)) +
                slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { it / 4 },
        ) {
            AssistantHistorySheet(
                state = state,
                colors = colors,
                onSelectConversation = onSelectConversation,
                onNewConversation = onNewConversation,
                onDeleteConversation = onDeleteConversation,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        EtaAssistantSuggestions(
            onSuggestionClick = onSubmit,
            visible = !hasMessages,
            keyboardVisible = imeOverlapPx > 0,
        )
        if (!hasMessages) {
            AssistantPanelBar(
                onKeyboard = onKeyboard,
                onMicrophone = { voiceController.start(VoiceEntryMode.UNIVERSAL) },
            )
        }
        AssistantComposer(
            state = state,
            input = input,
            colors = colors,
            focusRequester = focusRequester,
            onScreenContextSelect = onScreenContextSelect,
            onScreenContextRemove = onScreenContextRemove,
            onScreenTranslation = onScreenTranslation,
            onToggleHistoryMenu = onToggleHistoryMenu,
            onNewConversation = onNewConversation,
            onModelSelected = onModelSelected,
            onSubmit = onSubmit,
            onStop = onStop,
            onCancelMessageEdit = onCancelMessageEdit,
            onAttachImage = onAttachImage,
            onAttachVideo = onAttachVideo,
            onRemoveImage = onRemoveImage,
            onAttachFiles = onAttachFiles,
            onAttachFolder = onAttachFolder,
            onAttachFilePath = onAttachFilePath,
            onRemoveFileReference = onRemoveFileReference,
            onReasoningEffortChange = onReasoningEffortChange,
            onAssistantSelected = onAssistantSelected,
            onEditAssistant = onEditAssistant,
            history = history,
            autoCompressEnabled = autoCompressEnabled,
            assistantId = assistantId,
            voiceController = voiceController,
            voiceState = voiceState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = bottomInset + 10.dp,
                ),
        )
    }
}

@Composable
private fun AssistantComposer(
    state: EtaVoiceUiState,
    input: String,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenTranslation: () -> Unit,
    onToggleHistoryMenu: () -> Unit,
    onNewConversation: () -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onCancelMessageEdit: () -> Unit,
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onAssistantSelected: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    history: List<AgentModelClient.ConversationMessage>,
    autoCompressEnabled: Boolean,
    assistantId: String,
    voiceController: VoiceModeController,
    voiceState: VoiceModeState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.animateContentSize(
            animationSpec = folmeSpring(damping = 0.92f, response = 0.34f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationCapsule(
                title = state.conversationTitle.ifBlank {
                    stringResource(R.string.conversation_unnamed)
                },
                isMenuVisible = state.isHistoryMenuVisible,
                enabled = state.phase != EtaVoicePhase.PROCESSING,
                colors = colors,
                onClick = onToggleHistoryMenu,
            )
            ScreenContextAttachment(
                state = state.screenContext,
                enabled = state.phase != EtaVoicePhase.PROCESSING,
                colors = colors,
                onSelect = onScreenContextSelect,
                onRemove = onScreenContextRemove,
            )
            ScreenTranslationCapsule(
                enabled = state.phase != EtaVoicePhase.PROCESSING,
                colors = colors,
                onClick = onScreenTranslation,
            )
        }
        // Q3a：间距无条件保留。上游曾把它改成「非 CONSUMED 才留」，
        // 导致发送后 phase 变 CONSUMED、间距塌陷，三胶囊被压到贴住输入框。
        Spacer(Modifier.height(7.dp))
        // 与主界面共用同一个输入栏：附件入口、推理强度、模型选择、助手切换
        // 全部走同一套组件，两处不再各写一份；附件预览条也由输入栏自己渲染。
        key(state.conversationId) {
            AgentChatInputBar(
                input = input,
                modelPickerState = state.modelPickerState,
                history = history,
                autoCompressEnabled = autoCompressEnabled,
                showContextUsage = false,
                isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                reasoningEffort = state.reasoningEffort,
                availableReasoningEfforts = state.availableReasoningEfforts,
                pendingImages = state.pendingImages,
                pendingFileReferences = state.pendingFileReferences,
                isEditingMessage = state.messageEdit != null,
                assistantId = assistantId,
                editHasLaterTurns = state.messageEdit?.hasLaterTurns == true,
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onSubmit = onSubmit,
                onStop = onStop,
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
                focusRequester = focusRequester,
                voiceState = voiceState,
                onStartVoiceMode = voiceController::start,
                onStopVoiceMode = voiceController::stop,
                overlayMode = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ScreenContextAttachment(
    state: EtaScreenContextUiState,
    enabled: Boolean,
    colors: EtaVoicePanelColors,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            (fadeIn(tween(180, easing = LinearOutSlowInEasing)) +
                slideInVertically(tween(180, easing = LinearOutSlowInEasing)) { it / 5 })
                .togetherWith(
                    fadeOut(tween(120, easing = FastOutSlowInEasing)) +
                        slideOutVertically(tween(120, easing = FastOutSlowInEasing)) { -it / 6 },
                )
        },
        contentKey = { it.phase to it.selected },
        label = "assistant_screen_context",
    ) { screenContext ->
        when {
            screenContext.phase == EtaScreenContextPhase.CONSUMED -> Unit
            screenContext.phase == EtaScreenContextPhase.AVAILABLE && screenContext.selected -> {
                SelectedScreenContext(
                    previewDataUrl = screenContext.previewDataUrl,
                    enabled = enabled,
                    colors = colors,
                    onRemove = onRemove,
                )
            }
            else -> {
                val available = screenContext.phase == EtaScreenContextPhase.AVAILABLE && enabled
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .squircleSurface(
                            color = colors.input.copy(alpha = 0.62f),
                            cornerRadius = 17.dp,
                        )
                        .squircleBorder(
                            width = 0.5.dp,
                            color = colors.borderHighlight.copy(alpha = 0.55f),
                            cornerRadius = 17.dp,
                        )
                        .clickable(enabled = available, onClick = onSelect)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (screenContext.phase) {
                        EtaScreenContextPhase.CAPTURING -> CircularProgressIndicator(
                            size = 15.dp,
                            strokeWidth = 2.dp,
                        )
                        EtaScreenContextPhase.AVAILABLE -> Icon(
                            imageVector = Icons.Rounded.DesktopWindows,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (available) colors.inputPrimary else colors.inputTertiary,
                        )
                        EtaScreenContextPhase.UNAVAILABLE -> Icon(
                            imageVector = Icons.Rounded.CancelPresentation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = colors.inputTertiary,
                        )
                        EtaScreenContextPhase.CONSUMED -> Unit
                    }
                    Spacer(Modifier.size(7.dp))
                    Text(
                        text = when (screenContext.phase) {
                            EtaScreenContextPhase.CAPTURING -> stringResource(R.string.voice_screen_preparing)
                            EtaScreenContextPhase.AVAILABLE -> stringResource(R.string.voice_screen_add)
                            EtaScreenContextPhase.UNAVAILABLE -> stringResource(R.string.voice_screen_unavailable)
                            EtaScreenContextPhase.CONSUMED -> ""
                        },
                        color = if (available) colors.inputPrimary else colors.inputSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenTranslationCapsule(
    enabled: Boolean,
    colors: EtaVoicePanelColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(34.dp)
            .squircleSurface(
                color = colors.input.copy(alpha = 0.62f),
                cornerRadius = 17.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = colors.borderHighlight.copy(alpha = 0.55f),
                cornerRadius = 17.dp,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Translate,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) colors.inputPrimary else colors.inputTertiary,
        )
        Spacer(Modifier.size(7.dp))
        Text(
            text = stringResource(R.string.screen_translation_start),
            color = if (enabled) colors.inputPrimary else colors.inputSecondary,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SelectedScreenContext(
    previewDataUrl: String?,
    enabled: Boolean,
    colors: EtaVoicePanelColors,
    onRemove: () -> Unit,
) {
    val previewBitmap = previewDataUrl?.let { rememberDataUrlBitmap(it) }
    Row(
        modifier = Modifier
            .height(60.dp)
            .squircleBackground(colors.input.copy(alpha = 0.92f), 15.dp)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        previewBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.voice_screen_preview),
                modifier = Modifier
                    .size(50.dp)
                    .squircleClip(11.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier.padding(start = 10.dp, end = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.voice_screen_title),
                color = colors.inputPrimary,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.voice_screen_context_summary),
                color = colors.inputSecondary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            minWidth = 30.dp,
            minHeight = 30.dp,
            cornerRadius = 15.dp,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.voice_screen_remove),
                modifier = Modifier.size(14.dp),
                tint = if (enabled) colors.inputSecondary else colors.inputTertiary,
            )
        }
    }
}

@Composable
private fun DragHandle(colors: EtaVoicePanelColors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(colors.tertiary),
        )
    }
}

private fun assistantBaseHeightPx(
    messages: List<AgentChatMessageUi>,
    maxHeightPx: Float,
    density: Float,
): Float {
    if (messages.isEmpty()) return 0f
    val estimatedLines = messages.sumOf { message ->
        when (message) {
            is UserMessageUi -> ceil(message.content.length / 22f).toInt().coerceAtLeast(1)
            is AgentMessageUi -> ceil(message.content.length / 24f).toInt().coerceAtLeast(1)
            is ThinkingMessageUi -> 2
            is ToolActivityMessageUi -> 2
            else -> 1
        }
    }
    val estimatedDp = 92f + estimatedLines * 23f + messages.size * 12f
    return max(230f, estimatedDp)
        .times(density)
        .coerceAtMost(maxHeightPx * 0.68f)
}


@Composable
private fun ConversationCapsule(
    title: String,
    isMenuVisible: Boolean,
    enabled: Boolean,
    colors: EtaVoicePanelColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .squircleSurface(
                color = colors.input.copy(alpha = 0.62f),
                cornerRadius = 16.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = colors.borderHighlight.copy(alpha = 0.55f),
                cornerRadius = 16.dp,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Chat,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (enabled) colors.inputPrimary else colors.inputTertiary,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = title,
            color = if (enabled) colors.inputPrimary else colors.inputSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        Spacer(Modifier.size(4.dp))
        Icon(
            imageVector = if (isMenuVisible) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) colors.inputSecondary else colors.inputTertiary,
        )
    }
}

@Composable
private fun AssistantHistorySheet(
    state: EtaVoiceUiState,
    colors: EtaVoicePanelColors,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(max = 240.dp)
            .squircleSurface(
                color = colors.content,
                cornerRadius = 20.dp,
            )
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.action_conversation_history),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.inputPrimary,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNewConversation)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = colors.inputPrimary,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.action_new_conversation),
                    fontSize = 12.sp,
                    color = colors.inputPrimary,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.inputTertiary.copy(alpha = 0.2f)),
        )
        if (state.historyConversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.conversation_empty),
                    fontSize = 13.sp,
                    color = colors.inputTertiary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp),
            ) {
                items(state.historyConversations, key = { it.id }) { item ->
                    HistoryConversationItemRow(
                        item = item,
                        colors = colors,
                        onSelect = { onSelectConversation(item.id) },
                        onDelete = { onDeleteConversation(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryConversationItemRow(
    item: AssistantConversationItem,
    colors: EtaVoicePanelColors,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val isSelected = item.isCurrent
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (isSelected) colors.input.copy(alpha = 0.85f) else Color.Transparent,
                cornerRadius = 12.dp,
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Chat,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isSelected) colors.inputPrimary else colors.inputSecondary,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            text = item.title.ifBlank { stringResource(R.string.conversation_unnamed) },
            fontSize = 13.sp,
            color = if (isSelected) colors.inputPrimary else colors.inputSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    modifier = Modifier.size(16.dp),
                    tint = colors.inputSecondary,
                )
            }
            // 与浮窗其它弹层同款：二次确认后再真正删除，避免误触。
            EtaDropdownMenu(
                expanded = confirmDelete,
                onDismissRequest = { confirmDelete = false },
                alignEnd = true,
                preferAbove = true,
                minWidth = 0.dp,
                focusable = false,
            ) {
                Text(
                    text = stringResource(R.string.conversation_delete_title),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inputPrimary,
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 2.dp,
                    ),
                )
                Text(
                    text = stringResource(R.string.conversation_delete_message),
                    fontSize = 12.sp,
                    color = colors.inputSecondary,
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 4.dp,
                    ),
                )
                DropdownMenuItem(
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    text = {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            fontSize = 14.sp,
                            color = colors.inputPrimary,
                        )
                    },
                    onClick = { confirmDelete = false },
                )
                DropdownMenuItem(
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    text = {
                        Text(
                            text = stringResource(R.string.action_delete),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                )
            }
        }
        if (isSelected) {
            Spacer(Modifier.size(4.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.inputPrimary,
            )
        }
    }
}

@Composable
private fun AssistantPanelBar(
    onKeyboard: () -> Unit,
    onMicrophone: () -> Unit,
) {
    val controls = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(400)
        controls.animateTo(1f, tween(180))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistantRoundButton(
            iconRes = R.drawable.ic_assistant_voice,
            contentDescription = stringResource(R.string.voice_tap_to_speak),
            progress = controls.value,
            enabled = controls.value >= 0.5f,
            onClick = onMicrophone,
        )
        Spacer(Modifier.size(12.dp))
        AssistantRoundButton(
            iconRes = R.drawable.ic_assistant_keyboard_float,
            contentDescription = stringResource(R.string.voice_use_keyboard),
            progress = controls.value,
            enabled = controls.value >= 0.5f,
            onClick = onKeyboard,
        )
    }
}

@Composable
private fun AssistantRoundButton(
    iconRes: Int,
    contentDescription: String,
    progress: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                alpha = progress
                scaleX = 0.8f + progress * 0.2f
                scaleY = 0.8f + progress * 0.2f
            }
            .clip(CircleShape)
            .background(Color(0xFFF4F5F7))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color(0xE6000000),
            modifier = Modifier.size(22.dp),
        )
    }
}
