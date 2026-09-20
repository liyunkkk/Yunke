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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CancelPresentation
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.components.OverlayAttachmentPickerButton
import io.github.mangi.eta.ui.components.PendingFileReferenceStrip
import io.github.mangi.eta.ui.components.PendingImageStrip
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.components.AgentModelPickerButton
import io.github.mangi.eta.ui.components.rememberDataUrlBitmap
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
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
                input = Color(0xF2404040),
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
                input = Color(0xF2FFFFFF),
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
    onInputChange: (String) -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenTranslation: () -> Unit,
    onToggleHistoryMenu: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onModelSelected: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
) {
    val colors = rememberEtaVoicePanelColors()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val entryProgress = remember { Animatable(0f) }
    val exitAlpha by animateFloatAsState(
        targetValue = if (exitRequested) 0f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "assistant_exit",
    )

    LaunchedEffect(Unit) {
        entryProgress.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
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
            .background(colors.scrim.copy(alpha = colors.scrim.alpha * entryProgress.value)),
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
                onInputChange = onInputChange,
                onScreenContextSelect = onScreenContextSelect,
                onScreenContextRemove = onScreenContextRemove,
                onScreenTranslation = onScreenTranslation,
                onToggleHistoryMenu = onToggleHistoryMenu,
                onSelectConversation = onSelectConversation,
                onNewConversation = onNewConversation,
                onModelSelected = onModelSelected,
                onSubmit = {
                    keyboard?.hide()
                    onSubmit()
                },
                onStop = onStop,
                onClose = onClose,
                onOpenConversation = onOpenConversation,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
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
    onInputChange: (String) -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenTranslation: () -> Unit,
    onToggleHistoryMenu: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onModelSelected: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
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
    val handoffThresholdPx = with(density) { 72.dp.toPx() }
    val directHandoffThresholdPx = with(density) { 48.dp.toPx() }
    val dismissThresholdPx = with(density) { 92.dp.toPx() }
    val handoffVelocityPx = with(density) { 900.dp.toPx() }
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
    val visibleSheetHeightPx = sheetHeightPx.coerceAtMost(
        (maxContentHeightPx - imeOverlapPx).coerceAtLeast(0f),
    )
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

    val dragByState = rememberUpdatedState<(Float) -> Float>(::dragBy)
    val finishDragState = rememberUpdatedState<(Float) -> Unit>(::finishDrag)
    val nestedScrollConnection = remember(
        baseContentHeightPx,
        maxContentHeightPx,
        canOpenConversation,
        listState,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                if (
                    available.y < 0f &&
                    canOpenConversation &&
                    current >= maxContentHeightPx * 0.88f
                ) {
                    directHandoffPullPx += -available.y
                    if (directHandoffPullPx >= directHandoffThresholdPx) {
                        triggerHandoff()
                    }
                    // 第二段上滑由父容器在 pre-scroll 阶段完整消费，避免列表或
                    // overscroll 先截走事件后，接管手势永远达不到阈值。
                    return Offset(0f, available.y)
                }
                val shouldResize = (available.y < 0f && current < maxContentHeightPx) ||
                    (available.y > 0f && current > baseContentHeightPx && !listState.canScrollBackward)
                return if (shouldResize) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y == 0f) return Offset.Zero
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                val atUpperEdge = available.y < 0f && current >= maxContentHeightPx * 0.88f
                val atLowerEdge = available.y > 0f && current <= baseContentHeightPx
                return if (atUpperEdge || atLowerEdge) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                finishDragState.value(available.y)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                finishDragState.value(available.y)
                return Velocity.Zero
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
                .height(with(density) { visibleSheetHeightPx.toDp() })
                .nestedScroll(nestedScrollConnection),
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
                        visibleMessages = state.messages,
                        scrollState = listState,
                        isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                        bottomInset = 8.dp,
                        keepBottomAnchored = keepBottomAnchored,
                        onBottomAnchorChanged = { keepBottomAnchored = it },
                        modifier = Modifier.fillMaxSize(),
                    )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        AssistantComposer(
            state = state,
            input = input,
            colors = colors,
            focusRequester = focusRequester,
            onInputChange = onInputChange,
            onScreenContextSelect = onScreenContextSelect,
            onScreenContextRemove = onScreenContextRemove,
            onScreenTranslation = onScreenTranslation,
            onToggleHistoryMenu = onToggleHistoryMenu,
            onNewConversation = onNewConversation,
            onModelSelected = onModelSelected,
            onSubmit = onSubmit,
            onStop = onStop,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
            onAttachFiles = onAttachFiles,
            onAttachFolder = onAttachFolder,
            onAttachFilePath = onAttachFilePath,
            onRemoveFileReference = onRemoveFileReference,
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
    onInputChange: (String) -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenTranslation: () -> Unit,
    onToggleHistoryMenu: () -> Unit,
    onNewConversation: () -> Unit,
    onModelSelected: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.animateContentSize(
            animationSpec = folmeSpring(damping = 0.92f, response = 0.34f),
        ),
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
        if (state.screenContext.phase != EtaScreenContextPhase.CONSUMED) {
            Spacer(Modifier.height(7.dp))
        }
        AnimatedVisibility(
            visible = state.pendingFileReferences.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)),
        ) {
            PendingFileReferenceStrip(
                references = state.pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = state.pendingImages.isNotEmpty(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(100)),
        ) {
            PendingImageStrip(
                images = state.pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AssistantInputBar(
            state = state,
            input = input,
            colors = colors,
            focusRequester = focusRequester,
            onInputChange = onInputChange,
            onModelSelected = onModelSelected,
            onSubmit = onSubmit,
            onStop = onStop,
            onAttachImage = onAttachImage,
            onAttachFiles = onAttachFiles,
            onAttachFolder = onAttachFolder,
            onAttachFilePath = onAttachFilePath,
            modifier = Modifier.fillMaxWidth(),
        )
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
                            color = colors.input.copy(alpha = 0.9f),
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
                color = colors.input.copy(alpha = 0.9f),
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

@Composable
private fun AssistantInputBar(
    state: EtaVoiceUiState,
    input: String,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onAttachImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = (input.isNotBlank() ||
        state.pendingImages.isNotEmpty() ||
        state.pendingFileReferences.isNotEmpty()) &&
        state.phase != EtaVoicePhase.PROCESSING
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .squircleBackground(colors.input, 24.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(start = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayAttachmentPickerButton(
            onAttachImage = onAttachImage,
            onAttachFiles = onAttachFiles,
            onAttachFolder = onAttachFolder,
            onAttachFilePath = onAttachFilePath,
        )
        Spacer(Modifier.size(2.dp))
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .focusRequester(focusRequester),
            enabled = state.phase != EtaVoicePhase.PROCESSING,
            textStyle = TextStyle(
                color = colors.inputPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSubmit) onSubmit() }),
            maxLines = 4,
            minLines = 1,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text = stringResource(R.string.voice_input_hint),
                            color = colors.inputTertiary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        AgentModelPickerButton(
            state = state.modelPickerState,
            isStreaming = state.phase == EtaVoicePhase.PROCESSING,
            isPaused = false,
            popupAnchorTopPx = 0,
            popupMaxHeight = 320.dp,
            onModelSelected = { _, modelId -> onModelSelected(modelId) },
        )
        if (state.phase == EtaVoicePhase.PROCESSING) {
            IconButton(
                onClick = onStop,
                minWidth = 36.dp,
                minHeight = 36.dp,
                cornerRadius = 18.dp,
                backgroundColor = MiuixTheme.colorScheme.error,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.action_stop),
                    modifier = Modifier.size(15.dp),
                    tint = Color.White,
                )
            }
        } else {
            IconButton(
                onClick = onSubmit,
                enabled = canSubmit,
                minWidth = 36.dp,
                minHeight = 36.dp,
                cornerRadius = 18.dp,
                backgroundColor = if (canSubmit) {
                    MiuixTheme.colorScheme.primary
                } else {
                    colors.inputTertiary.copy(alpha = 0.35f)
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = stringResource(R.string.voice_send),
                    modifier = Modifier.size(17.dp),
                    tint = if (canSubmit) Color.White else colors.inputSecondary,
                )
            }
        }
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
                color = colors.input.copy(alpha = 0.82f),
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
) {
    val isSelected = item.isCurrent
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
        if (isSelected) {
            Spacer(Modifier.size(8.dp))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.inputPrimary,
            )
        }
    }
}
