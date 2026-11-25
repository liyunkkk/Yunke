package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.components.isLiquidGlassDark
import io.github.mangi.eta.ui.components.liquidGlassSurface
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

private data class AssistantSuggestionItem(
    val tag: String,
    val prompt: String,
    val accentLight: Color,
    val accentDark: Color,
)

private val assistantSuggestions = listOf(
    AssistantSuggestionItem(
        tag = "摘要",
        prompt = "帮我总结当前屏幕内容",
        accentLight = Color(0xFF6C4CF6),
        accentDark = Color(0xFFC4B5FD),
    ),
    AssistantSuggestionItem(
        tag = "引导",
        prompt = "这个页面怎么操作",
        accentLight = Color(0xFF0E91D6),
        accentDark = Color(0xFF7DD3FC),
    ),
    AssistantSuggestionItem(
        tag = "重点",
        prompt = "屏幕上有什么值得注意的信息",
        accentLight = Color(0xFF10A36C),
        accentDark = Color(0xFF6EE7B7),
    ),
)

@Composable
internal fun EtaAssistantSuggestions(
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    keyboardVisible: Boolean = false,
) {
    val dark = isLiquidGlassDark()
    val sidePadding by animateDpAsState(
        targetValue = if (keyboardVisible) 16.dp else 18.dp,
        animationSpec = tween(900, delayMillis = 100, easing = FastOutSlowInEasing),
        label = "assistant_suggestions_keyboard_padding",
    )

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EnterTransition.None,
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(180, easing = FastOutSlowInEasing),
        ) + fadeOut(tween(140)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = sidePadding, end = sidePadding, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            assistantSuggestions.forEachIndexed { index, item ->
                val transform = remember(item.prompt) { Animatable(0f) }
                val opacity = remember(item.prompt) { Animatable(0f) }
                LaunchedEffect(visible) {
                    if (visible) {
                        transform.snapTo(0f)
                        opacity.snapTo(0f)
                        delay(
                            900L + when (index) {
                                0 -> 200L
                                1 -> 117L
                                else -> 0L
                            },
                        )
                        coroutineScope {
                            launch {
                                transform.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 109.66f),
                                )
                            }
                            launch { opacity.animateTo(1f, tween(350)) }
                        }
                    } else {
                        coroutineScope {
                            launch { transform.animateTo(0f, tween(140)) }
                            launch { opacity.animateTo(0f, tween(140)) }
                        }
                    }
                }
                val accent = if (dark) item.accentDark else item.accentLight
                val badgeBg = (if (dark) item.accentLight else item.accentLight).copy(
                    alpha = if (dark) 0.28f else 0.15f,
                )
                val textColor = if (dark) Color(0xF2F4F7FF) else Color(0xF0141826)
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .graphicsLayer {
                            alpha = opacity.value
                            scaleX = transform.value
                            scaleY = transform.value
                            translationY = (1f - transform.value) * 18.dp.toPx()
                        }
                        .liquidGlassSurface(
                            shape = CircleShape,
                            surfaceAlpha = if (dark) 0.56f else 0.62f,
                            rimAlpha = 0.84f,
                            rimWidth = 1.dp,
                            elevation = 8.dp,
                        )
                        .clickable(enabled = visible) { onSuggestionClick(item.prompt) }
                        .padding(start = 7.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .clip(CircleShape)
                            .background(badgeBg)
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.tag,
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.prompt,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
