package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/** 语音输入态无色液态玻璃操控坞圆角半径（与 66.dp 高度匹配为全圆胶囊）。 */
private val CapsuleCornerRadius = 33.dp

/** 左右圆钮的触控与视觉尺寸。 */
private val CapsuleButtonVisualSize = 44.dp

/** 按钮内图标尺寸。 */
private val CapsuleButtonIconSize = 19.dp

/** 中央浅色 Siri 珍珠声波球直径。 */
private val CapsuleCenterOrbSize = 52.dp

/**
 * 「语音输入态」底部三段式极简无色液态玻璃操控坞（Liquid Glass V2）。
 *
 * 布局与功能映射 100% 对齐既有回调：
 * - 左侧双无色液态玻璃圆钮：消息/历史 ([onMessage]) + 切回键盘输入 ([onKeyboard])；
 * - 中央浅色 Siri 珍珠晶球声波按钮：实时跟随 [level] 与 [listening] 呼吸律动，点按触发 [onPrimaryAction]；
 * - 右侧纯净无色玻璃圆钮：暂停/恢复语音 ([onStop])，长按呼出模式菜单 ([onStopLongClick])，仅保留克制核心停止符号。
 */
@OptIn(ExperimentalFoundationApi::class)
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
    /** 语音已暂停时停止键变「恢复语音」（播放图标），再点一次继续聆听。 */
    paused: Boolean = false,
    /** 点击中央声波球的主操作（例如悬浮面板中提前结束收音并发送，或暂停态恢复聆听）。 */
    onPrimaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val dark = isLiquidGlassDark()
    val shape = RoundedCornerShape(CapsuleCornerRadius)
    val neutralIconTint = if (dark) LiquidGlassInkDark else LiquidGlassInkLight
    val stopIconTint = if (paused) {
        neutralIconTint
    } else {
        if (dark) LiquidGlassStopRedDark else LiquidGlassStopRedLight
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(66.dp)
            .voiceGlassShadow(shape, elevation = 14.dp)
            .voiceGlass(backdrop, shape, surfaceAlpha = VoiceGlassSurfaceAlpha)
            .voiceGlassRim(shape, alpha = VoiceGlassRimAlpha, width = 1.1.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VoiceCapsuleGlassButton(
                icon = Icons.Rounded.ChatBubbleOutline,
                description = "消息",
                iconTint = neutralIconTint,
                onClick = {
                    TouchHaptics.click(view)
                    onMessage()
                },
            )
            VoiceCapsuleGlassButton(
                icon = Icons.Rounded.Keyboard,
                description = "键盘输入",
                iconTint = neutralIconTint,
                onClick = {
                    TouchHaptics.click(view)
                    onKeyboard()
                },
            )
        }

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val orbInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(CapsuleCenterOrbSize)
                    .clip(CircleShape)
                    .then(
                        if (onPrimaryAction != null) {
                            Modifier.combinedClickable(
                                indication = null,
                                interactionSource = orbInteraction,
                                onClick = {
                                    TouchHaptics.click(view)
                                    onPrimaryAction()
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .semantics {
                        role = Role.Button
                        contentDescription = statusText
                    },
                contentAlignment = Alignment.Center,
            ) {
                LiquidGlassVoiceOrb(
                    size = CapsuleCenterOrbSize,
                    active = listening && !paused,
                    level = level,
                )
            }
        }

        // 右侧停止/恢复键：100% 纯净无色玻璃圆底，零粉色底晕，仅保留极克制的停止方块核心。
        VoiceCapsuleGlassButton(
            icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Stop,
            description = if (paused) "恢复语音" else "暂停语音",
            iconTint = stopIconTint,
            iconSize = if (paused) 20.dp else 17.dp,
            onClick = {
                TouchHaptics.click(view)
                onStop()
            },
            onLongClick = onStopLongClick,
        )
    }
}

/**
 * 极简无色液态玻璃圆形操作按钮：44.dp 触控区、无水波纹、带 3D 曲面玻璃折射边与按钮语义。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceCapsuleGlassButton(
    icon: ImageVector,
    description: String,
    iconTint: Color,
    iconSize: Dp = CapsuleButtonIconSize,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(CapsuleButtonVisualSize)
            .liquidGlassCircle(rimWidth = 0.95.dp)
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
            modifier = Modifier.size(iconSize),
            tint = iconTint,
        )
    }
}
