package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.EtaAssistantWave
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 语音输入态胶囊的圆角半径；与 [VoiceGlassCorner] 保持同一视觉语言（28.dp = 高度的一半）。 */
private val CapsuleCornerRadius = 32.dp

/** 三个操作按钮的触控区，保证 44.dp 最小可点尺寸。 */
private val CapsuleButtonTouchSize = 44.dp

/** 按钮内图标尺寸。 */
private val CapsuleButtonIconSize = 20.dp

/** 按钮玻璃圆的直径；触控区仍按 44dp 由外层保证（参考图量测 ~96px ≈ 41dp）。 */
private val CapsuleButtonVisualSize = 42.dp

/** 圆钮与分割线之间的间隔：两侧各 8dp，圆钮间距 16dp（参考图 ~32px ≈ 14dp）。 */
private val CapsuleButtonGap = 8.dp

/** 圆钮图标色：近黑，和参考图一致。 */
private val CapsuleIconTint = Color(0xE6000000.toInt())

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
            .height(64.dp)
            .voiceGlassShadow(shape, elevation = 16.dp)
            // 参考图胶囊几乎全透，颜色全靠背后壁纸晕染。
            .voiceGlass(backdrop, shape, surfaceAlpha = 0.34f)
            .voiceGlassRim(shape, alpha = 0.78f, width = 1.2.dp)
            .padding(start = 21.dp, end = 13.dp),
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
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.35f),
                        offset = Offset(0f, 1f),
                        blurRadius = 3f,
                    ),
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        VoiceCapsuleDivider()
        Spacer(Modifier.width(CapsuleButtonGap))
        VoiceCapsuleButton(
            icon = Icons.Rounded.ChatBubbleOutline,
            description = "消息",
            tint = CapsuleIconTint,
            onClick = {
                TouchHaptics.click(view)
                onMessage()
            },
        )
        Spacer(Modifier.width(CapsuleButtonGap))
        VoiceCapsuleDivider()
        Spacer(Modifier.width(CapsuleButtonGap))
        VoiceCapsuleButton(
            icon = Icons.Rounded.Keyboard,
            description = "键盘输入",
            tint = CapsuleIconTint,
            onClick = {
                TouchHaptics.click(view)
                onKeyboard()
            },
        )
        Spacer(Modifier.width(CapsuleButtonGap))
        VoiceCapsuleDivider()
        Spacer(Modifier.width(CapsuleButtonGap))
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

/** 胶囊内部的细分隔线：白色半透明，压在玻璃上更接近参考图。 */
@Composable
private fun VoiceCapsuleDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(26.dp)
            .background(Color.White.copy(alpha = 0.45f)),
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
            .size(CapsuleButtonVisualSize)
            .clip(CircleShape)
            // 液态玻璃圆钮：上亮下暗的半透明白渐变 + 白色细边，透过它能看到背后壁纸色。
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.66f),
                        Color.White.copy(alpha = 0.30f),
                    ),
                ),
            )
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.55f), shape = CircleShape)
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
