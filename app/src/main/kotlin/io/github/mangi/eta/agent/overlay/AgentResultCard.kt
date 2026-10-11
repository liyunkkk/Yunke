package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Miuix 未提供语义 success 色，沿用项目既有值；失败色走主题 error
private val SuccessColor = Color(0xFF34C759)

@Composable
private fun phaseAccent(phase: AgentOverlayPhase): Color = when (phase) {
    AgentOverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
    AgentOverlayPhase.PAUSED -> Color(0xFFFF9F0A)
    AgentOverlayPhase.FINISHED -> SuccessColor
    AgentOverlayPhase.FAILED -> MiuixTheme.colorScheme.error
}

/**
 * 前台执行完成后弹出的半屏结果卡片。胶囊只负责运行态总控，终态结果仍用这张卡片展示。
 */
@Composable
internal fun AgentResultCard(
    state: AgentOverlayState,
    onClose: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    val isFailed = state.phase == AgentOverlayPhase.FAILED
    val dotColor = phaseAccent(state.phase)
    val statusText = state.status.localizedText()
    val statusLabel = stringResource(
        if (isFailed) R.string.overlay_substatus_failed else R.string.overlay_substatus_finished,
    )
    val content = state.detailText.ifBlank { statusText }
    val textColor = MiuixTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 200)),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 180),
            ) + fadeOut(animationSpec = tween(durationMillis = 180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .shadow(8.dp, RoundedCornerShape(CardDefaults.CornerRadius)),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusLabel,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = onClose,
                            backgroundColor = Color.Transparent,
                            minWidth = 32.dp,
                            minHeight = 32.dp,
                            cornerRadius = 16.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(16.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val markdownState = rememberMarkdownState(content = content, retainState = true)
                    val typography = markdownTypography(
                        h1 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = textColor),
                        h2 = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = textColor),
                        h3 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = textColor),
                        text = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        paragraph = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        ordered = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        bullet = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        list = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = textColor),
                        code = TextStyle(
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = textColor,
                        ),
                    )
                    Markdown(
                        markdownState = markdownState,
                        typography = typography,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        loading = {
                            Text(text = content, color = textColor, fontSize = 16.sp, modifier = it)
                        },
                        error = {
                            Text(text = content, color = textColor, fontSize = 16.sp, modifier = it)
                        },
                    )
                }
            }
        }
    }
}
