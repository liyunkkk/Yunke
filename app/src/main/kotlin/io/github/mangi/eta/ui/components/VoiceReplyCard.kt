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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.VoiceModePhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 语音输入态（voice mode）顶部弹出的「AI 实时聆听与回复卡片」（iOS 26 极简无色液态玻璃 V2）。
 *
 * 设计要点：
 * - 无状态内容展示：[statusText] / [reply] / [streaming] 均由宿主提供，回复完成后宿主继续传入即保留原文。
 * - 动态高度：外层走 [Modifier.animateContentSize] 弹簧动效，正文区受 [maxHeightFraction] 限高并支持自动贴底滚动。
 * - V2 极简视觉：100% 无色通透玻璃卡片 + 极细居中抓手 + 中性呼吸微点状态胶囊 + 纯黑白渐隐声学波形 + 极简屏幕感知标签。
 */
@Composable
internal fun VoiceReplyCard(
    statusText: String,
    reply: String,
    streaming: Boolean,
    backdrop: LayerBackdrop?,
    maxHeightFraction: Float = 0.38f,
    listening: Boolean = false,
    level: Float = 0f,
    paused: Boolean = false,
    screenContextAttached: Boolean = false,
    onRemoveScreenContext: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dark = isLiquidGlassDark()
    val shape = RoundedCornerShape(VoiceReplyCardCorner)
    val scroll = rememberScrollState()

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
    LaunchedEffect(scroll, resetKey) {
        snapshotFlow { Triple(scroll.maxValue, follow, scroll.isScrollInProgress) }
            .collectLatest { (end, enabled, moving) ->
                if (enabled && !moving && end > 0) scroll.scrollTo(end)
            }
    }

    var elapsedSeconds by remember { mutableIntStateOf(0) }
    val timerRunning = (listening || streaming) && !paused
    LaunchedEffect(timerRunning) {
        if (timerRunning) {
            while (true) {
                delay(1000L)
                elapsedSeconds += 1
            }
        }
    }
    LaunchedEffect(listening, streaming, paused, reply.isEmpty()) {
        if (!listening && !streaming && !paused && reply.isEmpty()) {
            elapsedSeconds = 0
        }
    }
    val timerText = remember(elapsedSeconds) {
        val m = (elapsedSeconds / 60).toString().padStart(2, '0')
        val s = (elapsedSeconds % 60).toString().padStart(2, '0')
        "$m:$s"
    }

    val bodyMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * maxHeightFraction)
        .coerceAtLeast(MinBodyMaxHeight)

    val handleColor = if (dark) Color.White.copy(alpha = 0.28f) else Color(0xFF14171F).copy(alpha = 0.20f)
    val primaryTextColor = if (dark) LiquidGlassInkDark else LiquidGlassInkLight
    val secondaryTextColor = if (dark) LiquidGlassInkSecondaryDark else LiquidGlassInkSecondaryLight
    val badgeBg = if (dark) Color.White.copy(alpha = 0.11f) else Color(0xFF14171F).copy(alpha = 0.06f)
    val badgeBorder = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.62f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .voiceGlassShadow(shape, elevation = 14.dp)
            .voiceGlass(backdrop, shape, surfaceAlpha = VoiceGlassSurfaceAlpha)
            .voiceGlassRim(shape, alpha = VoiceGlassRimAlpha, width = 1.1.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        // 顶部极细抓手条
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 10.dp)
                .width(34.dp)
                .height(3.5.dp)
                .clip(CircleShape)
                .background(handleColor),
        )

        // 状态行：左侧极简无色中性微胶囊 + 呼吸圆点，右侧等宽数字计时器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(badgeBg)
                    .border(width = 0.75.dp, color = badgeBorder, shape = CircleShape)
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceReplyStatusDot(
                    active = (streaming || listening) && !paused,
                    tint = primaryTextColor,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = statusText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (elapsedSeconds > 0 || timerRunning) {
                Text(
                    text = timerText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = secondaryTextColor,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                )
            }
        }

        val bodyText = when {
            reply.isNotEmpty() -> reply
            streaming -> PlaceholderEllipsis
            else -> ""
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .heightIn(min = MinBodyHeight, max = bodyMaxHeight)
                .nestedScroll(connection)
                .verticalScroll(scroll),
            contentAlignment = if (bodyText.isEmpty()) Alignment.Center else Alignment.TopStart,
        ) {
            if (bodyText.isEmpty()) {
                LiquidGlassWaveform(
                    active = (listening || streaming) && !paused,
                    level = level,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = bodyText,
                        fontSize = 16.5.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryTextColor,
                    )
                    if (listening && !paused) {
                        LiquidGlassWaveform(
                            active = true,
                            level = level,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(30.dp),
                        )
                    }
                }
            }
        }

        if (screenContextAttached) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(CircleShape)
                    .background(badgeBg)
                    .border(width = 0.75.dp, color = badgeBorder, shape = CircleShape)
                    .then(
                        if (onRemoveScreenContext != null) {
                            Modifier.clickable(onClick = onRemoveScreenContext)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = secondaryTextColor,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.voice_screen_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = primaryTextColor,
                    maxLines = 1,
                )
                if (onRemoveScreenContext != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.voice_screen_remove),
                        modifier = Modifier.size(13.dp),
                        tint = secondaryTextColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceReplyStatusDot(
    active: Boolean,
    tint: Color,
) {
    val pulse by rememberInfiniteTransition(label = "voice_reply_status").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice_reply_status_pulse",
    )
    Box(
        modifier = Modifier
            .size(6.5.dp)
            .graphicsLayer {
                val t = if (active) pulse else 0f
                scaleX = if (active) 0.88f + t * 0.24f else 0.9f
                scaleY = if (active) 0.88f + t * 0.24f else 0.9f
                alpha = if (active) 0.42f + t * 0.54f else 0.36f
            }
            .clip(CircleShape)
            .background(tint),
    )
}

/**
 * 把语音态 [phase] 映射成状态行文案（全部走 string 资源，除占位「…」外不硬编码中文字面量）。
 */
@Composable
internal fun voiceReplyPhaseLabel(phase: VoiceModePhase, streaming: Boolean): String {
    if (streaming) {
        return stringResource(R.string.voice_mode_thinking)
    }
    return when (phase) {
        VoiceModePhase.Connecting -> stringResource(R.string.voice_mode_connecting)
        VoiceModePhase.Listening -> stringResource(R.string.voice_mode_listening)
        VoiceModePhase.Transcribing -> stringResource(R.string.voice_mode_transcribing)
        VoiceModePhase.Thinking -> stringResource(R.string.voice_mode_thinking)
        VoiceModePhase.Speaking -> stringResource(R.string.voice_mode_speaking)
        VoiceModePhase.Error, VoiceModePhase.Off -> stringResource(R.string.voice_mode_open)
    }
}

private const val PlaceholderEllipsis = "…"

private val MinBodyHeight = 104.dp

private val MinBodyMaxHeight = 56.dp

private val VoiceReplyCardCorner = 30.dp
