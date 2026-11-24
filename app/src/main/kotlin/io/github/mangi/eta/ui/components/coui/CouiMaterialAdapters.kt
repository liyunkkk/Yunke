package io.github.mangi.eta.ui.components.coui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox as MaterialCheckbox
import androidx.compose.material3.CircularProgressIndicator as MaterialCircularProgressIndicator
import androidx.compose.material3.FloatingActionButton as MaterialFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator as MaterialLinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.RadioButton as MaterialRadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch as MaterialSwitch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.checkbox.Checkbox as CouiCheckbox
import com.itos.coui.floatingactionbutton.CouiFabDefaults
import com.itos.coui.floatingactionbutton.Fab as CouiFab
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.motion.couiPressMotion
import com.itos.coui.motion.couiTouchMotion
import com.itos.coui.motion.rememberCouiPressMotion
import com.itos.coui.motion.rememberCouiTouchMotion
import com.itos.coui.radiobutton.CouiRadioButtonDefaults
import com.itos.coui.radiobutton.RadioButton as CouiRadioButton
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle

/**
 * COUI 风格下替代 Material3 RadioButton 的自适应单选框；非 COUI 风格保持 Material3 原样。
 * 当 onClick 非空时维持 48dp 最小触控区，确保与现有行内排版对齐。
 */
@Composable
internal fun EtaRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(),
) {
    if (LocalUiStyle.current != AppearanceUiStyle.COUI) {
        MaterialRadioButton(
            selected = selected,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
        )
        return
    }
    val dark = CouiTheme.isDark
    val pageColors = CouiPageDefaults.themeColors(dark)
    val couiColors = CouiRadioButtonDefaults.colors(pageColors.accent, dark)
    if (onClick != null) {
        Box(
            modifier = modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .clip(CouiCapsuleShape)
                .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            CouiRadioButton(
                selected = selected,
                enabled = enabled,
                colors = couiColors,
            )
        }
    } else {
        CouiRadioButton(
            modifier = modifier,
            selected = selected,
            enabled = enabled,
            colors = couiColors,
        )
    }
}

/**
 * COUI 风格下替代 Material3 Switch 的自适应开关；非 COUI 风格保持 Material3 原样。
 */
@Composable
internal fun EtaMaterialSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalUiStyle.current != AppearanceUiStyle.COUI) {
        MaterialSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
        return
    }
    val dark = CouiTheme.isDark
    val pageColors = CouiPageDefaults.themeColors(dark)
    CouiSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        dark = dark,
        colors = CouiSwitchDefaults.colors(pageColors.accent, dark),
        enabled = enabled,
    )
}

/**
 * COUI 风格下替代 Material3 Checkbox 的自适应复选框；非 COUI 风格保持 Material3 原样。
 */
@Composable
internal fun EtaMaterialCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalUiStyle.current != AppearanceUiStyle.COUI) {
        MaterialCheckbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
        return
    }
    if (onCheckedChange != null) {
        Box(
            modifier = modifier
                .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
                .clip(CouiCapsuleShape)
                .clickable(enabled = enabled, role = Role.Checkbox, onClick = { onCheckedChange(!checked) }),
            contentAlignment = Alignment.Center,
        ) {
            CouiCheckbox(
                checked = checked,
                enabled = enabled,
            )
        }
    } else {
        CouiCheckbox(
            modifier = modifier,
            checked = checked,
            enabled = enabled,
        )
    }
}

/**
 * COUI 风格下替代 Material3 FloatingActionButton 的悬浮按钮；
 * Android 14+ 且具备窗口 backdrop 时使用 COUI 原生 Fab，低版本回退为 COUI 圆形强调色按钮；
 * 非 COUI 风格保持 Material3 原样。
 */
@Composable
internal fun EtaFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalUiStyle.current != AppearanceUiStyle.COUI) {
        MaterialFloatingActionButton(
            onClick = onClick,
            modifier = modifier,
            content = content,
        )
        return
    }
    val dark = CouiTheme.isDark
    val backdrop = rememberCouiWindowBackdrop()
    if (Build.VERSION.SDK_INT >= 34 && backdrop != null) {
        CouiFab(
            modifier = modifier.size(56.dp),
            backdrop = backdrop,
            onClick = onClick,
            dark = dark,
            contentColor = CouiFabDefaults.contentColor,
            content = content,
        )
        return
    }
    val pageColors = CouiPageDefaults.themeColors(dark)
    val motion = rememberCouiTouchMotion(true)
    val press = rememberCouiPressMotion()
    Box(
        modifier = modifier
            .size(56.dp)
            .graphicsLayer {
                scaleX = motion.scaleX.value + press.scale.value - 1f
                scaleY = motion.scaleY.value + press.scale.value - 1f
                translationX = motion.translationX.value
                translationY = motion.translationY.value
            }
            .couiTouchMotion(motion, true)
            .couiPressMotion(press, true, cancelOnConsumedMove = true)
            .shadow(elevation = 8.dp, shape = CouiCapsuleShape, clip = false)
            .clip(CouiCapsuleShape)
            .background(pageColors.accent)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides CouiFabDefaults.contentColor) {
            content()
        }
    }
}

/**
 * COUI 风格下统一配色与圆角帽的线性进度条；非 COUI 风格保持 Material3 默认样式。
 */
@Composable
internal fun EtaLinearProgressIndicator(
    modifier: Modifier = Modifier,
    progress: (() -> Float)? = null,
) {
    if (LocalUiStyle.current != AppearanceUiStyle.COUI) {
        if (progress != null) {
            MaterialLinearProgressIndicator(progress = progress, modifier = modifier)
        } else {
            MaterialLinearProgressIndicator(modifier = modifier)
        }
        return
    }
    val dark = CouiTheme.isDark
    val pageColors = CouiPageDefaults.themeColors(dark)
    val track = if (dark) Color(0x26FFFFFF) else Color(0x14000000)
    if (progress != null) {
        MaterialLinearProgressIndicator(
            progress = progress,
            modifier = modifier,
            color = pageColors.accent,
            trackColor = track,
            strokeCap = StrokeCap.Round,
        )
    } else {
        MaterialLinearProgressIndicator(
            modifier = modifier,
            color = pageColors.accent,
            trackColor = track,
            strokeCap = StrokeCap.Round,
        )
    }
}

/**
 * COUI 风格下统一强调色的圆形加载指示器；非 COUI 风格保持 Material3 默认样式。
 */
@Composable
internal fun EtaCircularProgressIndicator(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    if (LocalUiStyle.current != AppearanceUiStyle.COUI) {
        MaterialCircularProgressIndicator(
            modifier = modifier,
            strokeWidth = strokeWidth,
        )
        return
    }
    val dark = CouiTheme.isDark
    val pageColors = CouiPageDefaults.themeColors(dark)
    MaterialCircularProgressIndicator(
        modifier = modifier,
        color = pageColors.accent,
        strokeWidth = strokeWidth,
        strokeCap = StrokeCap.Round,
    )
}
