// staging: TurnTickRail.kt
// 目标路径（新增）: app/src/main/kotlin/io/github/mangi/eta/ui/components/TurnTickRail.kt
// 来源: Number444/Eta TurnTickRail.kt（477 行，SHA 4c072e88，提交 35ea15df + c2c59c51）
// 改编说明（Yunke 4f8f6c45 基线）：
//  1. 全部拆掉 Kyant 液态玻璃依赖（com.kyant.backdrop、drawBackdrop/blur/lens/vibrancy、
//     LocalLiquidGlassBackdrop、胶囊玻璃 Box、胶囊内虚刻度填充）。Yunke 只有 miuix-blur，
//     没有 Kyant，原样搬会编译失败。先做无玻璃版，等液态玻璃线落地再接回。
//  2. TurnTick 增加 firstUserMessageId：Yunke 的列表渲染的是经两层投影后的 timelineRows（行），
//     行下标 != 时间线条目下标，跳转必须经消息 id 中转，不能直接拿 timelineIndex 当
//     animateScrollToItem 参数（见 PATCH_01 的 jumpToTick）。
//  3. buildTurnTicks 的标题来源与源版一致：AgentFileReferencePromptCodec.parse 剥掉文件
//     引用信封，再由 AgentFileReferencePolicy.titleSource 取标题。
//     （此前误判这两个符号在 Yunke 不存在而退化成纯文本截断，实际在
//      agent/model/AgentFileReference.kt，已按源版恢复。）
//  4. steer 补充不另开一轮：Yunke 里 steer 补充是同一轮的续跑（autoFoldedWorkKeys、
//     isWaitingForFirstModelOutput 都是这个语义），遇 isSteerSupplement() 的用户消息
//     不封轮、不改 pendingInput。
//  5. 其余逻辑原样搬运：28dp 高、每格 8dp、三档透明度、触摸放大 1.65 倍、轻点跳轮、
//     横拖刮擦每轮一次 SegmentFrequentTick、气泡卡由调用方渲染在带子 Box 之外。

package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.ui.model.isSteerSupplement
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话页横向刻度导航（Turn Tick Rail）的一轮刻度。
 *
 * 轮边界与 Number444 的 buildShareTurns 同一语义：遇到下一条用户消息即封上一轮
 *（steer 补充不封轮，它是同一轮的续跑）。[timelineIndex] 是该轮首条用户消息在
 * 时间线条目里的下标，仅用于跟随换算；跳转一律走 [firstUserMessageId] 经行下标
 * 中转（见 AgentChatBody 的 jumpToTick），不要直接拿它当 LazyColumn 下标。
 */
internal data class TurnTick(
    val timelineIndex: Int,
    val firstUserMessageId: String,
    val inputSummary: String,
    val outputSummary: String,
)

/** 从时间线条目派生刻度数据；调用方以 remember(timelineEntries) 持有结果。 */
internal fun buildTurnTicks(entries: List<AgentTimelineEntry>): List<TurnTick> {
    val ticks = ArrayList<TurnTick>()
    var pendingIndex = -1
    var pendingUserId = ""
    var pendingInput = ""
    var lastOutput = ""

    fun closeTurn() {
        if (pendingIndex >= 0) {
            ticks += TurnTick(
                timelineIndex = pendingIndex,
                firstUserMessageId = pendingUserId,
                inputSummary = pendingInput,
                outputSummary = lastOutput,
            )
        }
    }

    entries.forEachIndexed { index, entry ->
        val message = (entry as? AgentTimelineEntry.Message)?.message ?: return@forEachIndexed
        when (message) {
            is UserMessageUi -> {
                // steer 补充是同一轮续跑，不封轮、不另起一轮。
                if (message.isSteerSupplement()) return@forEachIndexed
                closeTurn()
                pendingIndex = index
                pendingUserId = message.id
                // 与源版一致：先从消息正文里剥掉 Eta 自己写的文件引用信封，
                // 再用 AgentFileReferencePolicy.titleSource 取标题（请求为空时退回首个文件名）。
                // Muse 原以为这两个符号在 Yunke 不存在，实际在 agent/model/AgentFileReference.kt 里。
                val parsed = AgentFileReferencePromptCodec.parse(message.content)
                pendingInput = AgentFileReferencePolicy.titleSource(parsed.request, parsed.references)
                    .ifBlank { message.content.trim() }
                    .collapseSummaryWhitespace(MAX_SUMMARY_CHARS)
                lastOutput = ""
            }
            is AgentMessageUi -> {
                if (message.content.isNotBlank()) {
                    lastOutput = message.content.collapseSummaryWhitespace(MAX_SUMMARY_CHARS)
                }
            }
            else -> Unit
        }
    }
    closeTurn()
    return ticks
}

private fun String.collapseSummaryWhitespace(maxChars: Int): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .let { if (it.length > maxChars) it.take(maxChars) + "…" else it }

private const val MAX_SUMMARY_CHARS = 160

/** 轮数下限：少于 3 轮不显示刻度条。 */
internal const val MIN_TURN_TICKS = 3

/** 刻度条总高度（含触控余量）。 */
internal val TurnTickRailHeight = 28.dp

private val TickSlot = 8.dp
private val EdgeFadeWidth = 28.dp

/** 分档（纯数量判断）：当前轮 / 最新第 2~11 轮 / 更早。 */
private val Tier1Width = 3.dp
private val Tier1Height = 16.dp
private val Tier2Width = 2.5.dp
private val Tier2Height = 11.dp
private val Tier3Width = 2.dp
private val Tier3Height = 7.dp
private val TickBottomPadding = 4.dp

/** 手指按下时全体刻度等比放大倍数（避免被手指挡住）。 */
private const val TOUCH_SCALE = 1.65f

/** 虚刻度（延伸占位）：真实刻度带左端之外的延伸刻度，仅按住期间淡入，不可选中、纯视觉。 */
private val GhostTickWidth = 1.5.dp
private val GhostTickHeight = 5.dp
private const val GHOST_TICK_ALPHA = 0.25f

/**
 * 把列表视口位置（首可见行的浮点下标）换算成浮点轮次，用于刻度带跟随屏幕内容整体滑动。
 * 轮边界是该轮首条用户消息在行下标空间里的下标（调用方用消息 id 经 timelineRows 换算后传入）；
 * 行位置在相邻边界间线性插值。
 */
internal fun resolveVisibleTurn(
    itemPosition: Float,
    turnBoundaries: List<Int>,
    totalItems: Int,
): Float {
    if (turnBoundaries.isEmpty()) return -1f
    val lastTurn = (turnBoundaries.size - 1).toFloat()
    if (itemPosition <= turnBoundaries.first()) return 0f
    for (i in 0 until turnBoundaries.size - 1) {
        val start = turnBoundaries[i]
        val end = turnBoundaries[i + 1]
        if (itemPosition < end) {
            return i + (itemPosition - start) / (end - start)
        }
    }
    val lastSpan = (totalItems - turnBoundaries.last()).coerceAtLeast(1)
    return (lastTurn + (itemPosition - turnBoundaries.last()) / lastSpan).coerceAtMost(lastTurn)
}

/**
 * 横向刻度条：并入输入框浮层 dock、位于输入框正上方，左右边界与输入框一致。
 * 无玻璃版：刻度只用 Canvas 画，颜色走 Miuix 主题，不依赖任何 Kyant / backdrop。
 *
 * 标尺式中心锁定模型：
 * - 选中轮（蓝条）永远居中，刻度带作为整体在下方滑动
 * - 列表滚动时 [currentTurn] 跟随视口位置（浮点），刻度带随之平滑滑动
 * - 轻点刻度 → [onTap] 跳到该轮（末轮即"回到底部"，由调用方判定）
 * - 按住横向拖动 → 刻度带随手整体移动，中心蓝条为目标轮，列表实时刮擦跟随，
 *   回调 [onScrubStart] / [onScrubTick] / [onScrubEnd]
 * - 手指按下期间全体刻度等比放大（×[TOUCH_SCALE]），刻度带左端之外淡入虚刻度
 *   （延伸占位，不可选中、纯视觉，松手淡出）
 * - 刮擦期间经 [onBubbleChanged] 上报目标轮（气泡由调用方渲染在带子 Box 之外，
 *   带子 Box 会裁剪子内容溢出，气泡不能放在刻度条内部）
 */
@Composable
internal fun TurnTickRail(
    ticks: List<TurnTick>,
    currentTurn: State<Float>,
    onTap: (TurnTick) -> Unit,
    onScrubStart: () -> Unit,
    onScrubTick: (TurnTick) -> Unit,
    onScrubEnd: (atLatest: Boolean) -> Unit,
    onBubbleChanged: (TurnTick?) -> Unit,
    // Eta Mod：可见性——平时不常驻。调用方按上一条/下一条按钮的逻辑
    // （showMessageNavigation：离开底部才出现，回到锚定底部即消失）上报；
    // 这里只做 250ms 淡入淡出，不自己判断滚动状态。
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (ticks.size < MIN_TURN_TICKS) return

    // 淡入淡出：与底部栏刻度带的高度/透明度动画同为 250ms tween，同步起落。
    val railAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "turnTickRailAlpha",
    )

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val slotPx = with(density) { TickSlot.toPx() }
    val edgeFadePx = with(density) { EdgeFadeWidth.toPx() }
    val tickBottomPadPx = with(density) { TickBottomPadding.toPx() }
    val tier1WidthPx = with(density) { Tier1Width.toPx() }
    val tier1HeightPx = with(density) { Tier1Height.toPx() }
    val tier2WidthPx = with(density) { Tier2Width.toPx() }
    val tier2HeightPx = with(density) { Tier2Height.toPx() }
    val tier3WidthPx = with(density) { Tier3Width.toPx() }
    val tier3HeightPx = with(density) { Tier3Height.toPx() }
    val ghostTickWidthPx = with(density) { GhostTickWidth.toPx() }
    val ghostTickHeightPx = with(density) { GhostTickHeight.toPx() }

    var railWidthPx by remember { mutableFloatStateOf(0f) }
    // >=0 表示正在刮擦（手指驱动的浮点轮次）；<0 表示跟随列表视口。
    var scrubTurn by remember { mutableFloatStateOf(-1f) }
    var scrubTarget by remember { mutableIntStateOf(-1) }
    var touching by remember { mutableStateOf(false) }
    val touchAnim by animateFloatAsState(
        targetValue = if (touching) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "turnTickTouch",
    )

    val currentTicks by rememberUpdatedState(ticks)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnScrubStart by rememberUpdatedState(onScrubStart)
    val currentOnScrubTick by rememberUpdatedState(onScrubTick)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val currentOnBubbleChanged by rememberUpdatedState(onBubbleChanged)

    fun displayTurn(): Float = when {
        scrubTurn >= 0f -> scrubTurn
        currentTurn.value >= 0f -> currentTurn.value.coerceIn(0f, currentTicks.lastIndex.toFloat())
        else -> currentTicks.lastIndex.toFloat()
    }

    fun fireScrubTarget() {
        val index = scrubTurn.roundToInt().coerceIn(0, currentTicks.lastIndex)
        if (index == scrubTarget) return
        scrubTarget = index
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        currentOnScrubTick(currentTicks[index])
        currentOnBubbleChanged(currentTicks[index])
    }

    val primaryColor = MiuixTheme.colorScheme.primary
    val tickColor = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TurnTickRailHeight)
            .graphicsLayer { alpha = railAlpha },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { railWidthPx = it.width.toFloat() }
            .pointerInput(slotPx, visible) {
                // 隐藏时不接手势：淡出后刻度条不可点、不可刮，避免透明层挡住输入框。
                // 刮擦期间调用方强制 visible=true，key 不会在手势中途翻转。
                if (!visible) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    touching = true
                    val startTurn = displayTurn()
                    var dragDistance = 0f
                    var scrubbing = false
                    val dragChange = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        if (!scrubbing) {
                            scrubbing = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnScrubStart()
                        }
                        dragDistance += overSlop
                        change.consume()
                    }
                    if (dragChange == null) {
                        // 未过滑动阈值即松手：轻点跳轮。
                        touching = false
                        val center = railWidthPx / 2f
                        val index = (displayTurn() + (down.position.x - center) / slotPx)
                            .roundToInt()
                            .coerceIn(0, currentTicks.lastIndex)
                        haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                        currentOnTap(currentTicks[index])
                        return@awaitEachGesture
                    }
                    scrubTurn = (startTurn - dragDistance / slotPx)
                        .coerceIn(0f, currentTicks.lastIndex.toFloat())
                    fireScrubTarget()
                    while (true) {
                        val continued = drag(dragChange.id) { dragEvent ->
                            dragDistance += dragEvent.position.x - dragEvent.previousPosition.x
                            scrubTurn = (startTurn - dragDistance / slotPx)
                                .coerceIn(0f, currentTicks.lastIndex.toFloat())
                            fireScrubTarget()
                            dragEvent.consume()
                        }
                        if (!continued) break
                    }
                    val atLatest = scrubTarget == currentTicks.lastIndex
                    touching = false
                    scrubTarget = -1
                    scrubTurn = -1f
                    currentOnBubbleChanged(null)
                    currentOnScrubEnd(atLatest)
                }
            },
        ) {
            val count = currentTicks.size
            if (count == 0 || size.width <= 0f) return@Canvas
            val windowed = count * slotPx > size.width
            val center = size.width / 2f
            val turn = when {
                scrubTurn >= 0f -> scrubTurn
                currentTurn.value >= 0f -> currentTurn.value.coerceIn(0f, (count - 1).toFloat())
                else -> (count - 1).toFloat()
            }
            val highlight = turn.roundToInt().coerceIn(0, count - 1)
            val scale = 1f + (TOUCH_SCALE - 1f) * touchAnim
            val baseline = size.height - tickBottomPadPx

            // 虚刻度：真实刻度带左端之外的延伸占位（更低一级，不可选中，纯视觉），
            // 仅按住期间淡入，松手淡出。
            val ghostWidth = ghostTickWidthPx * scale
            val ghostHeight = ghostTickHeightPx * scale
            val bandStart = center + (0f - turn) * slotPx
            fun drawGhost(x: Float, alphaScale: Float, fadeLeft: Float, fadeRight: Float) {
                var alpha = GHOST_TICK_ALPHA * alphaScale
                alpha *= ((x - fadeLeft) / edgeFadePx).coerceIn(0f, 1f)
                alpha *= ((fadeRight - x) / edgeFadePx).coerceIn(0f, 1f)
                if (alpha <= 0.004f) return
                drawRoundRect(
                    color = tickColor,
                    topLeft = Offset(x - ghostWidth / 2f, baseline - ghostHeight),
                    size = Size(ghostWidth, ghostHeight),
                    cornerRadius = CornerRadius(ghostWidth / 2f),
                    alpha = alpha,
                )
            }
            if (touchAnim > 0.01f && count > 0) {
                val leftSlots = ((bandStart - slotPx) / slotPx).toInt().coerceIn(0, 12)
                for (k in 1..leftSlots) {
                    val x = bandStart - k * slotPx
                    val fadeLeft = if (windowed) 0f else Float.NEGATIVE_INFINITY
                    val fadeRight = if (windowed) size.width else Float.POSITIVE_INFINITY
                    drawGhost(x, touchAnim, fadeLeft, fadeRight)
                }
            }

            for (i in 0 until count) {
                val x = center + (i - turn) * slotPx
                if (x < -slotPx || x > size.width + slotPx) continue
                val tier = when {
                    i == highlight -> 1
                    i >= count - 11 -> 2
                    else -> 3
                }
                var alpha = when (tier) {
                    1 -> 1f
                    2 -> 0.75f
                    else -> 0.5f
                }
                // 两端 28dp alpha 渐隐（窗口化时）。
                if (windowed) {
                    alpha *= (x / edgeFadePx).coerceIn(0f, 1f)
                    alpha *= ((size.width - x) / edgeFadePx).coerceIn(0f, 1f)
                }
                if (alpha <= 0.004f) continue
                val (baseWidth, baseHeight) = when (tier) {
                    1 -> tier1WidthPx to tier1HeightPx
                    2 -> tier2WidthPx to tier2HeightPx
                    else -> tier3WidthPx to tier3HeightPx
                }
                val tickWidth = baseWidth * scale
                val tickHeight = baseHeight * scale
                drawRoundRect(
                    color = if (tier == 1) primaryColor else tickColor,
                    topLeft = Offset(x - tickWidth / 2f, baseline - tickHeight),
                    size = Size(tickWidth, tickHeight),
                    cornerRadius = CornerRadius(tickWidth / 2f),
                    alpha = alpha,
                )
            }
        }
    }
}

/**
 * 拖动专属气泡卡片（纯内容）：加粗输入摘要（≤2 行）+ 常规输出摘要（≤3 行）。
 * 定位由调用方负责——必须渲染在带子 Box 之外（带子 Box 会裁剪子内容溢出）。
 */
@Composable
internal fun TurnTickBubble(
    tick: TurnTick,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 260.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (tick.inputSummary.isNotBlank()) {
            Text(
                text = tick.inputSummary,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tick.outputSummary.isNotBlank()) {
            if (tick.inputSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
            }
            Text(
                text = tick.outputSummary,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
