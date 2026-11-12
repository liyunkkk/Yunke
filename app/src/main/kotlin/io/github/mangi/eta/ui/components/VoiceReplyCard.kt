package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.VoiceModePhase
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 语音输入态（voice mode）顶部弹出的「AI 实时回复卡片」。
 *
 * 设计要点：
 * - 这是一张纯**无状态**渲染卡片：[statusText] / [reply] / [streaming] 全部由宿主提供。
 *   **回复完成后由宿主保留原文，卡片不得自行清空**内容——本组件不持有任何 reply 的副本，
 *   不做超时清空、不因 streaming 变 false 而丢弃文字，宿主把上一次的 reply 继续传进来即保留。
 * - 高度是动态的：卡片本体走 [Modifier.animateContentSize] 弹簧动画，不设固定高度；
 *   高度上限只作用在正文区域（[maxHeightFraction] × 屏幕高度），超出后正文内部可滚动。
 * - 正文滚动「跟随底部」：只有用户没有手动上滑时才自动贴底，用户一旦上滑就停止自动跟随，
 *   滚回底部后恢复跟随（思路与 `AgentChatInputBar.VoiceTranscript` 一致）。
 * - 液态玻璃外观由同一批 `voiceGlass*` 扩展提供，本文件只做布局与动效。
 *
 * @param statusText 状态行文案，一般由 [voiceReplyPhaseLabel] 生成。
 * @param reply 已经流式到达的回复正文；为空时正文区显示占位「…」（streaming）或空白占位。
 * @param streaming 模型是否仍在输出正文，决定状态图标的脉动动画与空正文占位符。
 * @param backdrop 液态玻璃取样用的 [LayerBackdrop]，宿主用 `rememberVoiceGlassBackdrop()` 提供，
 *   为 null 时降级为普通表面色（由 `voiceGlass` 自己处理）。
 * @param maxHeightFraction 正文区域最大高度占屏幕高度的比例，默认 0.38。
 * @param modifier 宿主附加的 Modifier（位置、外边距等）。
 */
@Composable
internal fun VoiceReplyCard(
    statusText: String,
    reply: String,
    streaming: Boolean,
    backdrop: LayerBackdrop?,
    maxHeightFraction: Float = 0.38f,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(VoiceReplyCardCorner)
    val scroll = rememberScrollState()

    // resetKey：只有「本轮回复开始 / 结束」（streaming 翻转）才重置跟随状态。
    // 正文增量绝不能进 resetKey，否则用户每上滑一次都会被下一帧新内容强行拽回底部。
    val resetKey = streaming
    var follow by remember(resetKey) { mutableStateOf(true) }
    var userGesture by remember(resetKey) { mutableStateOf(false) }
    val connection = remember(scroll, resetKey) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userGesture = true
                    follow = false
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput && userGesture) follow = !scroll.canScrollForward
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (userGesture) {
                    follow = !scroll.canScrollForward
                    userGesture = false
                }
                return Velocity.Zero
            }
        }
    }
    // 新内容到达 → scroll.maxValue 变化 → 未在滚动且仍在跟随时贴到底部。
    LaunchedEffect(scroll, resetKey) {
        snapshotFlow { Triple(scroll.maxValue, follow, scroll.isScrollInProgress) }
            .collectLatest { (end, enabled, moving) ->
                if (enabled && !moving && end > 0) scroll.scrollTo(end)
            }
    }

    // 正文限高：按屏幕高度的比例算，避免键盘 + 输入栏占满后卡片压掉对话区。
    val bodyMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * maxHeightFraction)
        .coerceAtLeast(MinBodyMaxHeight)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .voiceGlassShadow(shape, elevation = 18.dp)
            // 奶白玻璃：参考图卡片内里实测 (197,199,218)，接近不透明白，只留一点点透色。
            .voiceGlass(backdrop, shape, surfaceAlpha = 0.86f)
            .voiceGlassRim(shape, alpha = 0.62f, width = 1.2.dp)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        // 顶部拖动指示条（纯装饰，不参与无障碍朗读）。
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 8.dp)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
        )

        // 状态行：16.dp 圆形状态图标 + 单行状态文字。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 合并成单个无障碍节点：图标 contentDescription 为 null，只读出状态文字一次。
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceReplyStatusBadge(streaming = streaming)
            Text(
                text = statusText,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = CardTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 正文：不限制行数，外层 heightIn 限高，超长时内部纵向滚动并跟随底部。
        // 无障碍：正文用 miuix Text 自带的文本语义，滚动容器不额外拆节点，TalkBack 可直接读到全部内容。
        val bodyText = when {
            reply.isNotEmpty() -> reply
            streaming -> PlaceholderEllipsis
            // 非 streaming 且没有内容时留空白占位：高度由 heightIn(min) 与 Text 自身行高兜住，
            // 不渲染假文字，也不抛异常。
            else -> ""
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .heightIn(min = MinBodyHeight, max = bodyMaxHeight)
                .nestedScroll(connection)
                .verticalScroll(scroll),
        ) {
            if (bodyText.isEmpty()) {
                // 空内容恒显骨架条（参考图一：聆听/识别/思考各阶段卡片均是骨架排版）。
                VoiceReplySkeleton()
            } else {
                Text(
                    text = bodyText,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 骨架条：透明度呼吸 + 宽度递减，纯视觉，不参与无障碍。 */
@Composable
private fun VoiceReplySkeleton() {
    val pulse by rememberInfiniteTransition(label = "voice_reply_skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice_reply_skeleton_pulse",
    )
    Column {
        listOf(1f, 0.72f, 0.55f, 0.62f).forEachIndexed { index, widthFactor ->
            Box(
                modifier = Modifier
                    .padding(top = if (index == 0) 0.dp else 9.dp)
                    .fillMaxWidth(widthFactor)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        MiuixTheme.colorScheme.onSurface.copy(
                            alpha = 0.10f + pulse * 0.08f,
                        ),
                    ),
            )
        }
    }
}

/**
 * 状态行左侧的 16.dp 圆形状态图标。
 *
 * [streaming] 为 true 时用无限循环的 scale/alpha 轻微脉动（不旋转，避免 GraphicEq 竖条旋转后难以辨认）；
 * 动画值只在 `graphicsLayer` 的绘制 lambda 里读取，因此脉动不会触发重组。
 */
@Composable
private fun VoiceReplyStatusBadge(streaming: Boolean) {
    val pulse by rememberInfiniteTransition(label = "voice_reply_status").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice_reply_status_pulse",
    )
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            // 图标是装饰：状态文字由右侧 Text 读出，这里不重复朗读。
            contentDescription = null,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer {
                    val t = if (streaming) pulse else 0f
                    scaleX = 1f + t * 0.18f
                    scaleY = 1f + t * 0.18f
                    alpha = if (streaming) 0.72f + t * 0.28f else 1f
                },
            tint = MiuixTheme.colorScheme.primary,
        )
    }
}

/**
 * 把语音态 [phase] 映射成状态行文案（全部走 string 资源，除占位「…」外不硬编码中文字面量）。
 *
 * [streaming] 为 true（模型正在输出正文）时优先返回「正在生成回复」语义。
 * 注意：`values/voice_mode.xml`（以及 zh-Hans / zh-Hant 译本）里目前没有
 * `voice_mode_generating` 之类的键，本文件不改资源文件，故**复用**语义最接近的
 * [R.string.voice_mode_thinking]；若日后新增该键，只需在此处换掉这一行。
 */
@Composable
internal fun voiceReplyPhaseLabel(phase: VoiceModePhase, streaming: Boolean): String {
    if (streaming) {
        // 复用 thinking 文案：资源里暂无「正在生成回复」键（见上方 KDoc）。
        return stringResource(R.string.voice_mode_thinking)
    }
    return when (phase) {
        VoiceModePhase.Connecting -> stringResource(R.string.voice_mode_connecting)
        VoiceModePhase.Listening -> stringResource(R.string.voice_mode_listening)
        VoiceModePhase.Transcribing -> stringResource(R.string.voice_mode_transcribing)
        VoiceModePhase.Thinking -> stringResource(R.string.voice_mode_thinking)
        VoiceModePhase.Speaking -> stringResource(R.string.voice_mode_speaking)
        // Off / Error 沿用既有「打开语音模式」文案，与 VoiceModeStatusPanel 的错误分支保持一致。
        VoiceModePhase.Error, VoiceModePhase.Off -> stringResource(R.string.voice_mode_open)
    }
}

/** 空正文 + streaming 时的占位符（唯一的非资源字面量，属排版符号而非文案）。 */
private const val PlaceholderEllipsis = "…"

/** 正文区域的最小高度，防止 reply 为空时卡片塌成一条线。 */
// 参考图量测换算（841px 宽 ≈ 360dp / 2.336 px-per-dp）：
// 卡片 792×506px → 边距10dp、高216dp；骨架条 16px 高 ≈ 7dp、间距 22px ≈ 9dp、共 4 条。
private val MinBodyHeight = 132.dp

/** 正文区域限高的下限（`maxHeightFraction` 传了异常小值时兜底）。 */
private val MinBodyMaxHeight = 56.dp

/** 参考图卡片圆角 66px ≈ 28dp。 */
private val VoiceReplyCardCorner = 28.dp

/** 卡片正文主色：参考图的深灰蓝字色。 */
private val CardTextPrimary = Color(0xFF3A3A3C)