package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.EtaAssistantWave
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 语音输入态胶囊的圆角半径；与 [VoiceGlassCorner] 保持同一视觉语言（28.dp = 高度的一半）。 */
private val CapsuleCornerRadius = 28.dp

/** 三个操作按钮的触控区，保证 44.dp 最小可点尺寸。 */
private val CapsuleButtonTouchSize = 44.dp

/** 按钮内图标尺寸。 */
private val CapsuleButtonIconSize = 20.dp

/** 三个按钮的白色圆底（参考图样式）。 */
private val CapsuleButtonBackground = Color(0xFFF4F5F7)

/**
 * 「语音输入态」底部统一控制胶囊。
 *
 * 视觉：左侧是实时波形 + 状态文案，右侧是消息 / 键盘 / 停止三个按钮，整套材质走
 * [voiceGlass] 系列（玻璃模糊、边框高光、投影），因此宿主只需把 [backdrop] 传进来即可，
 * 组件内部不再自行创建 backdrop。
 *
 * 进度状态：波形用 [EtaAssistantWave]，`active = listening`、`level = { level }`，
 * 尺寸 44.dp × 22.dp。
 *
 * 无障碍：三个按钮各自带 `Role.Button` 语义和 contentDescription（"消息" / "键盘输入" / "停止语音"），
 * 并在点击时调用 [TouchHaptics.click] 做触感反馈；点击态不画 Ripple（`indication = null`），
 * 避免水波纹破坏玻璃质感。
 *
 * 进场 / 退场动画**不在这里实现**：宿主用 `AnimatedVisibility`（或等效的 enter/exit）
 * 包住本组件即可拿到淡入淡出 / 滑动效果。组件自身除 [MutableInteractionSource] 外不持有任何
 * 可变 `remember` 状态，所以同样的入参重组时不会闪一下再稳定。
 *
 * 安全区：胶囊自身**不加** navigationBarsPadding，由宿主统一处理底部 inset，
 * 避免与父容器的 navigationBarsPadding 叠加出双倍间距。
 *
 * @param statusText 当前语音阶段文案（正在聆听 / 识别中 / 思考中 ……）。
 * @param level 实时音量归一化值（0f..1f），供波形使用。
 * @param listening 是否处于聆听中；false 时波形静止。
 * @param backdrop 玻璃模糊用的后备层，null 时降级为纯表面色。
 * @param onMessage 点击「消息」按钮：回到消息 / 对话视图。
 * @param onKeyboard 点击「键盘」按钮：切回键盘输入。
 * @param onStop 点击「停止」按钮：结束本次语音输入。
 * @param onStopLongClick 长按「停止」按钮（可选；悬浮面板用它呼出语音模式切换菜单）。
 * @param modifier 宿主追加的布局修饰符。
 */
@Composable
internal fun VoiceControlCapsule(
    statusText: String,
    level: Float,
    listening: Boolean,
    backdrop: LayerBackdrop?,
    onMessage: () -> Unit,
    onKeyboard: () -> Unit,
    onStop: () -> Unit,
    onStopLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val shape = RoundedCornerShape(CapsuleCornerRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .voiceGlassShadow(shape)
            .voiceGlass(backdrop, shape)
            .voiceGlassRim(shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EtaAssistantWave(
                active = listening,
                level = { level },
                modifier = Modifier.size(width = 44.dp, height = 22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        VoiceCapsuleDivider()
        // 参考图：三个按钮都是白色圆形底，停止键红图标，统一用白圆底。
        VoiceCapsuleButton(
            icon = Icons.Rounded.ChatBubbleOutline,
            description = "消息",
            tint = Color(0xE6000000.toInt()),
            onClick = {
                TouchHaptics.click(view)
                onMessage()
            },
        )
        VoiceCapsuleDivider()
        VoiceCapsuleButton(
            icon = Icons.Rounded.Keyboard,
            description = "键盘输入",
            tint = Color(0xE6000000.toInt()),
            onClick = {
                TouchHaptics.click(view)
                onKeyboard()
            },
        )
        VoiceCapsuleDivider()
        VoiceCapsuleButton(
            icon = Icons.Rounded.Stop,
            description = "停止语音",
            tint = MiuixTheme.colorScheme.error,
            onClick = {
                TouchHaptics.click(view)
                onStop()
            },
            onLongClick = onStopLongClick,
        )
    }
}

/** 胶囊内部的细分隔线。 */
@Composable
private fun VoiceCapsuleDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
    )
}

/**
 * 胶囊上的图标按钮：44.dp 触控区、20.dp 图标、无 Ripple、自带按钮语义。
 * 点击反馈（触感）由调用方在 onClick 里完成，便于和业务回调先后顺序一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceCapsuleButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(CapsuleButtonTouchSize)
            .clip(CircleShape)
            .background(CapsuleButtonBackground)
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onLongClick = onLongClick,
            ) { onClick() }
            .semantics {
                role = Role.Button
                this.contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(CapsuleButtonIconSize),
            tint = tint,
        )
    }
}
