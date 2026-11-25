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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.components.LiquidGlassInkDark
import io.github.mangi.eta.ui.components.LiquidGlassInkLight
import io.github.mangi.eta.ui.components.isLiquidGlassDark
import io.github.mangi.eta.ui.components.liquidGlassSurface
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

private data class AssistantSuggestionItem(
    val icon: ImageVector,
    val prompt: String,
)

private val assistantSuggestions = listOf(
    AssistantSuggestionItem(
        icon = Icons.Rounded.Description,
        prompt = "帮我总结当前屏幕内容",
    ),
    AssistantSuggestionItem(
        icon = Icons.Rounded.TouchApp,
        prompt = "这个页面怎么操作",
    ),
    AssistantSuggestionItem(
        icon = Icons.Rounded.Info,
        prompt = "屏幕上有什么值得注意的信息",
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                val inkColor = if (dark) LiquidGlassInkDark else LiquidGlassInkLight
                val leadingIconTint = inkColor.copy(alpha = 0.62f)
                val trailingArrowTint = inkColor.copy(alpha = 0.36f)
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
                            surfaceAlpha = if (dark) 0.46f else 0.44f,
                            rimAlpha = 0.88f,
                            rimWidth = 1.dp,
                            elevation = 8.dp,
                        )
                        .clickable(enabled = visible) { onSuggestionClick(item.prompt) }
                        .padding(start = 13.dp, end = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = leadingIconTint,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = item.prompt,
                        color = inkColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    MinimalDiagonalArrow(tint = trailingArrowTint)
                }
            }
        }
    }
}

@Composable
private fun MinimalDiagonalArrow(tint: Color) {
    Canvas(modifier = Modifier.size(11.dp)) {
        val stroke = 1.6.dp.toPx()
        val pad = stroke * 0.7f
        val left = pad
        val top = pad
        val right = size.width - pad
        val bottom = size.height - pad
        drawLine(
            color = tint,
            start = Offset(left, bottom),
            end = Offset(right, top),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(left + size.width * 0.24f, top),
            end = Offset(right, top),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(right, top),
            end = Offset(right, bottom - size.height * 0.24f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
