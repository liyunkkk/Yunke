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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
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
 * 语音输入态（voice mode）顶部弹出的「AI 实时聆听与回复卡片」（Liquid Glass V1）。
 *
 * 设计要点：
 * - 无状态内容展示：[statusText] / [reply] / [streaming] 均由宿主提供，回复完成后宿主继续传入即保留原文。
 * - 动态高度：外层走 [Modifier.animateContentSize] 弹簧动效，正文区受 [maxHeightFraction] 限高并支持自动贴底滚动。
 * - 第一版液态玻璃视觉：紧凑通透卡片 + 顶部拖拽条 + 极光紫状态角标与实时计时 + 多频呼吸声纹（替代灰骨架）+ 可选屏幕上下文芯片。
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

    val handleColor = if (dark) Color.White.copy(alpha = 0.28f) else Color(0xFF242C3E).copy(alpha = 0.24f)
    val primaryTextColor = if (dark) Color(0xFFF4F7FF) else Color(0xFF141824)
    val secondaryTextColor = if (dark) Color(0xFFA8B3CC) else Color(0xFF566078)
    val badgeBg = if (dark) Color(0xFF7C5CFA).copy(alpha = 0.28f) else Color(0xFF6C4CF6).copy(alpha = 0.14f)
    val badgeFg = if (dark) Color(0xFFE0D7FF) else Color(0xFF5C3EE6)

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
            .voiceGlass(backdrop, shape, surfaceAlpha = VoiceGlassSurfaceAlpha)
            .voiceGlassRim(shape, alpha = VoiceGlassRimAlpha, width = 1.2.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        // 顶部拖拽指示条
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 10.dp)
                .width(38.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(handleColor),
        )

        // 状态角标行：左极光紫玻璃胶囊 + 右实时计时器
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
                    .padding(horizontal = 11.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceReplyStatusSparkle(
                    active = (streaming || listening) && !paused,
                    tint = badgeFg,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = badgeFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (elapsedSeconds > 0 || timerRunning) {
                Text(
                    text = timerText,
                    fontSize = 13.sp,
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
                        .height(42.dp),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = bodyText,
                        fontSize = 17.sp,
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
                    .height(30.dp)
                    .liquidGlassSurface(
                        shape = CircleShape,
                        surfaceAlpha = if (dark) 0.48f else 0.56f,
                        rimAlpha = 0.72f,
                        rimWidth = 0.9.dp,
                        elevation = 3.dp,
                    )
                    .then(
                        if (onRemoveScreenContext != null) {
                            Modifier.clickable(onClick = onRemoveScreenContext)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF16A34A)),
                )
                Spacer(Modifier.width(7.dp))
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
private fun VoiceReplyStatusSparkle(
    active: Boolean,
    tint: Color,
) {
    val pulse by rememberInfiniteTransition(label = "voice_reply_status").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice_reply_status_pulse",
    )
    Icon(
        imageVector = Icons.Rounded.AutoAwesome,
        contentDescription = null,
        modifier = Modifier
            .size(14.dp)
            .graphicsLayer {
                val t = if (active) pulse else 0f
                scaleX = 1f + t * 0.18f
                scaleY = 1f + t * 0.18f
                alpha = if (active) 0.76f + t * 0.24f else 1f
            },
        tint = tint,
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

private val VoiceReplyCardCorner = 28.dp
