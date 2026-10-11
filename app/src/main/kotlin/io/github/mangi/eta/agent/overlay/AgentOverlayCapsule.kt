package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.PaddingValues
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶部状态胶囊：收起时只显示一句当前步骤，点击展开补充、暂停与停止。
 * 位置固定在状态栏下方居中，不随 Agent 的点击位置移动，避免和目标控件重叠。
 * 状态文字只在语义变化时以上滑淡入切换，流式正文不进入胶囊。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentOverlayCapsule(
    state: AgentOverlayState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCollapsedSettled: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
    /** 点列表里的某条运行中会话 → 跳回该会话。 */
    onOpenConversation: (String) -> Unit,
    /** 长按胶囊、或点面板底部的「回到 Eta 主页」按钮 → 打开 Eta 主界面。 */
    onOpenHome: () -> Unit = {},
    /** 收起态拖动：把手指位移交给窗口层，实现自由拖动。 */
    onDragBy: (Float, Float) -> Unit = { _, _ -> },
    /** 松手：吸附到更近的一侧边缘。 */
    onDragFinished: () -> Unit = {},
    /** 点面板外任意位置：收起大面板。 */
    onCollapseRequest: () -> Unit = {},
    /** 小胶囊当前所在的屏幕位置（像素）。 */
    collapsedX: Int = 0,
    collapsedY: Int = 0,
    /** 窗口是否处于全屏展开态；全屏时才需要按上面的位置摆放面板。 */
    fullScreen: Boolean = false,
    /** 最近打开过的对话：面板最上面一栏，最多 3 条。 */
    recentOpened: List<AgentOverlayRunningSession> = emptyList(),
    unreadSessions: List<AgentOverlayRunningSession> = emptyList(),
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 长按回主页的时间戳：长按抬手紧接着的那次 onClick 要吃掉，免得回家后胶囊又被展开。
    var lastLongPressAt by remember { mutableLongStateOf(0L) }

    fun setSupplementMode(enabled: Boolean) {
        onSupplementModeChange(enabled)
        supplementMode = enabled
        if (!enabled) supplementText = ""
    }

    // 收起键盘后再切换窗口焦点，避免输入法在窗口失焦瞬间闪退或残留。
    fun leaveSupplementMode(then: () -> Unit = {}) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            setSupplementMode(false)
            then()
        }
    }

    LaunchedEffect(expanded) {
        if (!expanded && supplementMode) leaveSupplementMode()
    }

    val accent by animateColorAsState(overlayPhaseAccent(state.phase), tween(240), label = "capsule_accent")
    val statusText = state.status.localizedText()
    val expandDescription = stringResource(R.string.overlay_capsule_toggle, statusText)
    // 展开进度只驱动绘制层的透明度与位移，不参与测量；控制区用 AnimatedVisibility 只在两端各测一次。
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "capsule_expansion",
        finishedListener = { value -> if (value == 0f) onCollapsedSettled() },
    )

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { -it } +
            fadeIn(tween(160)),
        exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(140)),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart,
        ) {
            // 面板拖动偏移：拖顶部把手累积，收起后保留。
            var panelDrag by remember { mutableStateOf(Offset.Zero) }
            // 宽度跟着展开动画走：收起时是小尺寸，点开才变宽，方便点会话。
            val capsuleWidth = CapsuleCollapsedWidth +
                (CapsuleExpandedWidth - CapsuleCollapsedWidth) * expansion
            // 展开态窗口是全屏的：把面板摆在小胶囊原来待的位置，
            // 展开/收起动画都在原地播放，不会先在屏幕中间停一下再弹回去。
            // 面板宽度与位置全部夹在屏幕内：贴边、拖到角落都不会有边跑到屏幕外。
            // 面板外面还包着一层 12dp 水平内边距（见下面 Column 的 padding + width），
            // 所以它实际占位宽度是 panelWidth + 24dp。夹紧与居中都必须按这个「外宽」算，
            // 否则展开态会被挤到贴住右边缘：左边留 28dp、右边只剩 4dp，看着像被切掉。
            val panelPadPx = with(LocalDensity.current) { 12.dp.toPx() }
            val panelWidthPx = minOf(
                with(LocalDensity.current) { capsuleWidth.toPx() },
                // 再留 8px 余量：面板永远完整落在窗口内，不会被边缘裁掉。
                (constraints.maxWidth - panelPadPx * 2f - 16f).coerceAtLeast(0f),
            )
            val panelWidth = with(LocalDensity.current) { panelWidthPx.toDp() }
            val panelOuterWidthPx = panelWidthPx + panelPadPx * 2f
            // 面板本体与屏幕四边保持对称留白，靠边也不贴死。
            val panelEdgePx = with(LocalDensity.current) { 16.dp.toPx() }
            // 展开态水平居中，但位置要跟着展开进度从「胶囊原位」平滑过渡到「屏幕居中」：
            // 直接取居中值会让胶囊在展开/收起落定的那一帧从原位置瞬移到面板左缘（位置乱跳）。
            val panelStartX = if (fullScreen) {
                val centered = ((constraints.maxWidth - panelOuterWidthPx) / 2f).coerceAtLeast(0f)
                collapsedX.toFloat() + (centered - collapsedX.toFloat()) * expansion
            } else {
                0f
            }
            val panelStartY = if (fullScreen) {
                val maxHeightPx = with(LocalDensity.current) {
                    (LocalConfiguration.current.screenHeightDp * 0.30f).dp.toPx()
                }
                collapsedY.toFloat()
                    .coerceIn(
                        panelEdgePx,
                        (constraints.maxHeight - maxHeightPx - panelEdgePx).coerceAtLeast(panelEdgePx),
                    )
            } else {
                0f
            }
            // 拖动后的实际位置：按外宽夹紧，左右留白对称，拖到哪都不会贴死或被切。
            val panelMaxHeightPx = with(LocalDensity.current) {
                (LocalConfiguration.current.screenHeightDp * 0.30f).dp.toPx()
            }
            val panelX = (panelStartX + panelDrag.x).coerceIn(
                0f,
                (constraints.maxWidth - panelOuterWidthPx).coerceAtLeast(0f),
            )
            val panelY = (panelStartY + panelDrag.y).coerceIn(
                panelEdgePx,
                (constraints.maxHeight - panelMaxHeightPx - panelEdgePx).coerceAtLeast(panelEdgePx),
            )
            if (expanded) {
                // 面板外任意位置点一下就收起；面板本体画在它上面，不受影响。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCollapseRequest,
                        ),
                )
            }
            Column(
                modifier = Modifier
                    .offset { IntOffset(panelX.toInt(), panelY.toInt()) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .width(panelWidth)
                    .graphicsLayer {
                        // 点击时轻微回弹，展开后保持原尺寸；只改绘制层，不触发重新布局。
                        val press = 1f - 0.03f * (1f - expansion) * expansion * 4f
                        scaleX = press
                        scaleY = press
                    }
                    .shadow(10.dp, RoundedCornerShape(CapsuleCorner), ambientColor = accent, spotColor = accent)
                    .clip(RoundedCornerShape(CapsuleCorner))
                    .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                // 长按刚触发过：这次抬手不当作点击，免得回家后又被展开一次。
                                if (System.currentTimeMillis() - lastLongPressAt < 700L) return@combinedClickable
                                // 展开态且有没看过的结果：点一下直接跳回那条会话。
                                val target = if (expanded && state.finishedBadge > 0) {
                                    unreadSessions.firstOrNull()?.conversationId
                                } else {
                                    null
                                }
                                if (target != null) {
                                    onCollapseRequest()
                                    onOpenConversation(target)
                                } else {
                                    onToggleExpanded()
                                }
                            },
                        )
                        .semantics { contentDescription = expandDescription }
                        // 长按回主页：单独一条手势检测，只认「原地按满长按时长」。
                        // 手指提前抬起、或被拖动抢走手势（change 被消费）都不算长按。
                        .pointerInput(expanded) {
                            if (expanded) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val finishedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    var consumed = false
                                    var pressed = true
                                    while (pressed && !consumed) {
                                        val event = awaitPointerEvent()
                                        consumed = event.changes.any { it.isConsumed }
                                        pressed = event.changes.any { it.pressed }
                                    }
                                    consumed
                                }
                                android.util.Log.i("EtaOverlay", "capsule longPress finishedEarly=" + finishedEarly)
                                if (finishedEarly == null) {
                                    lastLongPressAt = System.currentTimeMillis()
                                    onOpenHome()
                                    waitForUpOrCancellation()
                                }
                            }
                        }
                        .pointerInput(expanded) {
                            // 只有收起态能拖：展开态是要点会话的，别被拖动抢走手势。
                            if (expanded) return@pointerInput
                            detectDragGestures(
                                onDragEnd = { onDragFinished() },
                                onDragCancel = { onDragFinished() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDragBy(dragAmount.x, dragAmount.y)
                                },
                            )
                        }
                        .padding(
                            start = if (expanded) 12.dp else 8.dp,
                            end = if (expanded) 16.dp else 8.dp,
                            top = 9.dp,
                            bottom = 9.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhaseIndicator(phase = state.phase, accent = accent)
                    if (state.finishedBadge > 0) {
                        Spacer(Modifier.width(8.dp))
                        CapsuleBadge(
                            count = state.finishedBadge,
                            failed = state.phase == AgentOverlayPhase.FAILED,
                        )
                    }
                    if (expanded) {
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = statusText,
                        transitionSpec = {
                            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(160))) using
                                SizeTransform(clip = true) { _, _ -> snap() }
                        },
                        label = "capsule_status",
                    ) { text ->
                        Text(
                            text = text,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ThoughtLine(
                        thought = state.thought.takeIf {
                            state.phase == AgentOverlayPhase.RUNNING || state.phase == AgentOverlayPhase.FINISHED
                        }.orEmpty(),
                    )
                    }
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium), clip = true) +
                        fadeIn(tween(140)),
                    exit = shrinkVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessHigh), clip = true) +
                        fadeOut(tween(80)),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            // 面板最高只占屏幕三成，超出时在面板内滚动，别把屏幕盖住。
                            .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.30f).dp),
                    ) {
                        // 顶部把手：拖它移动整个面板；列表本身照旧可以点。
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 8.dp)
                                .size(width = 44.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f))
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        panelDrag += dragAmount
                                    }
                                },
                        )
                        // 运行中会话下面接「最新回复」：完成但还没看过的会话，点一条就跳回去。
                        if (unreadSessions.isNotEmpty()) {
                            OverlayRecentOpenedList(
                                sessions = unreadSessions,
                                onOpenConversation = onOpenConversation,
                                title = "最新回复",
                            )
                        }
                        if (recentOpened.isNotEmpty()) {
                            OverlayRecentOpenedList(
                                sessions = recentOpened,
                                onOpenConversation = onOpenConversation,
                                title = "最近打开",
                            )
                        }
                        if (supplementMode) {
                            OverlaySupplementInput(
                                value = supplementText,
                                onValueChange = { supplementText = it },
                                onCancel = { leaveSupplementMode() },
                                onSend = {
                                    val text = supplementText.trim()
                                    if (text.isNotBlank()) leaveSupplementMode { onSupplement(text) }
                                },
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                            ) {
                                CapsuleAction(
                                    icon = Icons.Rounded.Edit,
                                    label = stringResource(R.string.overlay_supplement),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    onClick = { setSupplementMode(true) },
                                )
                                if (state.phase == AgentOverlayPhase.RUNNING) {
                                    CapsuleAction(
                                        icon = Icons.Rounded.Pause,
                                        label = stringResource(R.string.overlay_pause),
                                        tint = MiuixTheme.colorScheme.onSurface,
                                        onClick = onPause,
                                    )
                                } else if (state.phase == AgentOverlayPhase.PAUSED) {
                                    CapsuleAction(
                                        icon = Icons.Rounded.PlayArrow,
                                        label = stringResource(R.string.overlay_resume),
                                        tint = MiuixTheme.colorScheme.primary,
                                        onClick = onResume,
                                    )
                                }
                                CapsuleAction(
                                    icon = Icons.Rounded.Stop,
                                    label = stringResource(R.string.action_stop),
                                    tint = MiuixTheme.colorScheme.error,
                                    onClick = onStop,
                                )
                                // 常驻「回主页」按钮：和上面三个按钮同一行，任何状态下都在。
                                CapsuleAction(
                                    icon = Icons.Rounded.Home,
                                    label = "回到 Eta 主页",
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    onClick = {
                                        android.util.Log.i("EtaOverlay", "panel home button tapped")
                                        onCollapseRequest()
                                        onOpenHome()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 运行中是旋转的弧形光点，暂停为静止空心环，结束为实心点；只在运行时持有帧动画。 */
/** 小胶囊角标：完成未看的条数，失败用红色，最多显示 9+。 */
@Composable
private fun CapsuleBadge(count: Int, failed: Boolean) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (failed) CapsuleBadgeFailed else CapsuleBadgeOk)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count >= 9) "9+" else count.toString(),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PhaseIndicator(phase: AgentOverlayPhase, accent: Color) {
    val size = 14.dp
    if (phase != AgentOverlayPhase.RUNNING) {
        Box(
            modifier = Modifier
                .size(size)
                .drawBehind {
                    val radius = this.size.minDimension / 2f
                    if (phase == AgentOverlayPhase.PAUSED) {
                        drawCircle(accent, radius * 0.78f, style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.32f))
                    } else {
                        drawCircle(accent, radius * 0.6f)
                    }
                },
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "capsule_indicator")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
        label = "capsule_indicator_rotation",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "capsule_indicator_breathe",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .drawBehind {
                val radius = this.size.minDimension / 2f
                val center = Offset(radius, radius)
                drawCircle(accent.copy(alpha = 0.18f), radius)
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, accent), center),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(radius * 0.34f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
                drawCircle(accent.copy(alpha = breathe), radius * 0.26f, center)
            },
    )
}

/**
 * 思考尾句：单行、从左侧裁掉旧内容，让最新的字始终可见。
 * 流式增量只改文字，不做逐字动画；行的出现与消失用高度不变的淡入淡出，避免胶囊上下跳动。
 */
@Composable
private fun ThoughtLine(thought: String) {
    val shown = thought.isNotBlank()
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "thought_alpha")
    var lastThought by remember { mutableStateOf("") }
    if (shown) lastThought = thought
    Text(
        text = lastThought,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        // 起始端省略：保留尾部最新的推理，像滚动字幕一样向前推进。
        overflow = TextOverflow.StartEllipsis,
        modifier = Modifier
            .padding(top = 1.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

/**
 * 展开胶囊后的“运行中会话”列表：点一条直接跳回那个会话。
 * 只有并发运行（不止一条）时才出现，单会话保持原来的简洁形态。
 */
/** 最近打开过的对话：最多 3 条，超出的折叠不显示，点一条直接跳回去。 */
@Composable
private fun OverlayRecentOpenedList(
    sessions: List<AgentOverlayRunningSession>,
    onOpenConversation: (String) -> Unit,
    title: String = "最近打开",
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        sessions.forEachIndexed { index, session ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpenConversation(session.conversationId) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = session.title.ifBlank { session.conversationId },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OverlayRunningSessionList(
    sessions: List<AgentOverlayRunningSession>,
    onOpenConversation: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = sessionListTitle(sessions),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        sessions.forEachIndexed { index, session ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpenConversation(session.conversationId) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 序号跟小胶囊上的角标数字对得上：1、2、3…
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (session.running) {
                                CapsuleBadgeOk.copy(alpha = 0.18f)
                            } else {
                                MiuixTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (session.running) CapsuleBadgeOk else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = session.title.ifBlank { session.conversationId },
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!session.running) {
                    Text(
                        text = "已完成",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "打开 ›",
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CapsuleAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        backgroundColor = MiuixTheme.colorScheme.surfaceContainerHigh,
        minWidth = 40.dp,
        minHeight = 34.dp,
        cornerRadius = 17.dp,
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(17.dp), tint = tint)
    }
}

private val CapsuleCorner = 22.dp

/** 角标配色：成功偏绿，失败偏红。 */
private val CapsuleBadgeOk = Color(0xFF3F9E5A)
private val CapsuleBadgeFailed = Color(0xFFD93F3F)

/** 收起时是个小胶囊：只放图标和角标，比上一版再短一点，角标仍然看得清。 */
private val CapsuleCollapsedWidth = 64.dp

/** 展开后的宽度：会话条目更好点。 */
private val CapsuleExpandedWidth = 372.dp

/** 展开后的会话列表标题：运行时说"正在运行"，跑完就说"最近会话"。 */
private fun sessionListTitle(sessions: List<AgentOverlayRunningSession>): String {
    val running = sessions.count { it.running }
    return when {
        running > 0 && running == sessions.size -> "正在运行的会话（$running）· 点一条打开"
        running > 0 -> "正在运行 $running 个 · 最近共 ${sessions.size} 条 · 点一条打开"
        else -> "最近会话（${sessions.size}）· 点一条切回"
    }
}

/** 阶段配色：运行用主色，暂停橙，完成绿，失败走主题 error。独立于光球文件，便于删除旧 Orb。 */
@Composable
private fun overlayPhaseAccent(phase: AgentOverlayPhase): Color = when (phase) {
    AgentOverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
    AgentOverlayPhase.PAUSED -> Color(0xFFFF9F0A)
    AgentOverlayPhase.FINISHED -> Color(0xFF34C759)
    AgentOverlayPhase.FAILED -> MiuixTheme.colorScheme.error
}


/** 胶囊展开后的「补充指令」输入区：一个多行输入框 + 取消/发送。 */
@Composable
private fun OverlaySupplementInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val textColor = MiuixTheme.colorScheme.onSurface
    val fieldBg = MiuixTheme.colorScheme.surfaceContainerHigh

    LaunchedEffect(Unit) {
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(fieldBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (value.isBlank()) {
                Text(
                    text = stringResource(R.string.overlay_supplement_hint),
                    color = textColor.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = textColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                maxLines = 4,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                minWidth = 44.dp,
                minHeight = 32.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                text = stringResource(R.string.overlay_send),
                onClick = onSend,
                enabled = value.isNotBlank(),
                minWidth = 44.dp,
                minHeight = 32.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
