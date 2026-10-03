package io.github.mangi.eta.ui.components

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch

private val SubAgentGptSpeedIconSize = 18.dp

/** 触摸区域下限；窄屏也不会压过模型名，因为 Row 只给按钮自身宽度。 */
private val SubAgentGptSpeedMinTouchSize = 48.dp

/** 速度按钮的无障碍描述：说清当前档位与“点击切换”，刻意不引入长按语义。 */
internal fun subAgentGptSpeedDescription(name: String, mode: GptSpeedMode): String =
    "$name GPT 速度：${gptSpeedDisplayName(mode)}，点击切换速度"

/**
 * 子代理 GPT 速度按钮：仅合资格文本子代理显示，单击在原位循环速度档位。
 *
 * 动画与主会话 [ThinkingEffortChip] 完全一致，直接复用 ThinkingSpeedAnimation.kt 的策略：
 * 450ms、旋转一整圈、缩放 1→1.12→1、[GptSpeedAnimationGate] 闸门与淡粉/酒红渐变。
 * 与主会话不同的是入口为“单击”而非长按：单击只切速度，绝不打开模型选择或思考菜单。
 *
 * 只有写回 [ConversationSubAgentPreferences.WriteResult.Saved] 才播放动画并弹 Toast；
 * 禁用 / 运行中 / 过期 / 被拒绝时不写、不动画、不弹 Toast，避免假成功反馈。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentGptSpeedButton(
    profile: SubAgentProfile,
    providers: List<ProviderSetting>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val editor = LocalConversationSubAgentEditor.current
    val usable = enabled && editor?.enabled == true
    val eligible = remember(profile.isMedia, profile.providerId, profile.modelId, providers) {
        if (profile.isMedia) false else {
            val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
            val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
            supportsGptSpeedBinding(provider, model)
        }
    }
    // 非合资格（媒体子代理 / 非 GPT 模型 / 未知协议 / 缺模型）两个入口都不渲染速度按钮。
    if (!eligible) return

    // reset key 用 profileId + providerId + modelId：模型一旦切换就换一套闸门与进度，防串动画。
    val bindingKey = profile.id + "\u0000" + profile.providerId + "\u0000" + profile.modelId
    val mode = profile.gptSpeedForModel(providerId = profile.providerId, modelId = profile.modelId)
    val gate = remember(bindingKey) { GptSpeedAnimationGate() }
    val progress = remember(bindingKey) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current

    val normalColor = MaterialTheme.colorScheme.onSurface
    // 颜色由 owner 传入的 mode 决定，非 GPT 不会走到这里；渐变时长与主会话共用 450ms。
    val contentColor by animateColorAsState(
        targetValue = gptSpeedChipColor(mode, normalColor),
        animationSpec = tween(durationMillis = GptSpeedAnimationDurationMillis),
        label = "sub_agent_gpt_speed_color",
    )
    val displayedColor = if (usable) contentColor else contentColor.copy(alpha = 0.38f)

    Row(
        modifier = modifier
            .defaultMinSize(minWidth = SubAgentGptSpeedMinTouchSize, minHeight = SubAgentGptSpeedMinTouchSize)
            .semantics(mergeDescendants = true) {
                contentDescription = subAgentGptSpeedDescription(profile.name, mode)
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = usable,
                role = Role.Button,
                hapticFeedbackEnabled = false,
                onLongClick = null,
                onClick = {
                    // 动画中拒绝新的单击：不排队、不累计，避免截断当前一圈。
                    val token = gate.request()
                    if (token != 0) {
                        val result = editor?.cycleGptSpeed(
                            profile.id, profile.providerId, profile.modelId, expected = profile,
                        )
                        if (result is ConversationSubAgentPreferences.WriteResult.Saved) {
                            TouchHaptics.click(view)
                            // 档位从 Saved 快照读，避免用旧状态算出错误 Toast。
                            val savedMode = result.config.profiles.firstOrNull { it.id == profile.id }
                                ?.gptSpeedForModel(profile.providerId, profile.modelId)
                                ?: mode.next()
                            Toast.makeText(context, gptSpeedToastMessage(savedMode), Toast.LENGTH_SHORT).show()
                            scope.launch {
                                try {
                                    progress.snapTo(0f)
                                    progress.animateTo(
                                        targetValue = 1f,
                                        animationSpec = tween(
                                            durationMillis = GptSpeedAnimationDurationMillis,
                                            easing = LinearEasing,
                                        ),
                                    )
                                } finally {
                                    // 携带自己的 token：被取消的旧动画不会关掉随后新动画的闸门。
                                    gate.finish(token)
                                }
                            }
                        } else {
                            // 被拒绝：立刻释放闸门，不播放任何动画，也不留“成功”痕迹。
                            gate.finish(token)
                        }
                    }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_atom),
            contentDescription = null,
            modifier = Modifier
                .size(SubAgentGptSpeedIconSize)
                .graphicsLayer {
                    rotationZ = gptSpeedRotationDegrees(progress.value)
                    val scale = gptSpeedScale(progress.value)
                    scaleX = scale
                    scaleY = scale
                },
            tint = displayedColor,
        )
        Text(
            text = gptSpeedDisplayName(mode),
            style = MaterialTheme.typography.labelMedium,
            color = displayedColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
