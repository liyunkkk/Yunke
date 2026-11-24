package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.shape.CouiShape
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPreferenceRow
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SwitchColors
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.TextButtonColors
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent as MiuixBasicComponent
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog as MiuixWindowDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.state.ToggleableState
import com.itos.coui.checkbox.Checkbox as CouiCheckbox
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.Divider as CouiDivider
import com.itos.coui.slider.Slider as CouiSlider
import top.yukonga.miuix.kmp.basic.CheckboxColors
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import top.yukonga.miuix.kmp.basic.DividerDefaults
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.basic.SliderColors
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.dialog.Dialog as CouiDialog
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogAction

/*
 * 按外观风格自动切换的通用控件。参数与 Miuix 0.9.5-beta03 原组件一致，页面只需换导入：
 * COUI 风格下渲染 COUI 外观，Miuix 风格下原样调用 Miuix 组件，行为不变。
 */

@Composable
private fun isCouiStyle(): Boolean = LocalUiStyle.current == AppearanceUiStyle.COUI

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!isCouiStyle()) {
        MiuixCard(
            modifier = modifier,
            cornerRadius = cornerRadius,
            insideMargin = insideMargin,
            colors = colors,
            pressFeedbackType = pressFeedbackType,
            showIndication = showIndication,
            holdDownState = holdDownState,
            onClick = onClick,
            onLongPress = onLongPress,
            content = content,
        )
        return
    }
    val pageColors = CouiPageDefaults.themeColors(CouiTheme.isDark)
    // 页面自定义了卡片底色时保留它，默认卡片才换成 COUI 卡片底色。
    val bg = if (colors == CardDefaults.defaultColors()) pageColors.card else colors.color
    val shape = remember(cornerRadius) { CouiShape(maxOf(cornerRadius, 20.dp), 2.2f) }
    val clickModifier = if (onClick != null || onLongPress != null) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongPress)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .then(clickModifier)
            .padding(insideMargin),
        content = content,
    )
}

@Composable
internal fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    textStyle: TextStyle? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
) {
    if (!isCouiStyle()) {
        MiuixTextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            cornerRadius = cornerRadius,
            minWidth = minWidth,
            minHeight = minHeight,
            colors = colors,
            insideMargin = insideMargin,
            textStyle = textStyle,
            interactionSource = interactionSource,
            indication = indication,
        )
        return
    }
    // 主色按钮 → COUI 主按钮；错误色（删除类）→ COUI 危险按钮；其余 → COUI 次按钮。
    val role = when (colors.color) {
        MiuixTheme.colorScheme.primary -> CouiButtonRole.Primary
        MiuixTheme.colorScheme.error -> CouiButtonRole.Destructive
        else -> CouiButtonRole.Secondary
    }
    CouiButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        role = role,
        minHeight = minHeight,
    )
}

@Composable
internal fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    colors: SwitchColors = SwitchDefaults.switchColors(),
    enabled: Boolean = true,
) {
    if (!isCouiStyle()) {
        MiuixSwitch(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, colors = colors, enabled = enabled)
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

@Composable
internal fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    insideMargin: DpSize = TextFieldDefaults.InsideMargin,
    colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,
    label: String = "",
    useLabelAsPlaceholder: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.main,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(colors.borderColor),
) {
    // 只读或带前置图标的输入框没有 COUI 对应写法，仍走 Miuix。
    if (!isCouiStyle() || readOnly || leadingIcon != null) {
        MiuixTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            insideMargin = insideMargin,
            colors = colors,
            cornerRadius = cornerRadius,
            label = label,
            useLabelAsPlaceholder = useLabelAsPlaceholder,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            maxLines = maxLines,
            minLines = minLines,
            visualTransformation = visualTransformation,
            onTextLayout = onTextLayout,
            interactionSource = interactionSource,
            cursorBrush = cursorBrush,
        )
        return
    }
    CouiTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = if (useLabelAsPlaceholder) null else label.ifEmpty { null },
        placeholder = label.ifEmpty { null },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        showClearButton = singleLine && trailingIcon == null,
        trailingIcon = trailingIcon,
    )
}

@Composable
internal fun BasicComponent(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    role: Role? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    // 带底部操作区或无标题的行没有 COUI 对应样式，仍走 Miuix。
    if (!isCouiStyle() || title == null || bottomAction != null) {
        MiuixBasicComponent(
            modifier = modifier,
            title = title,
            titleColor = titleColor,
            summary = summary,
            summaryColor = summaryColor,
            startAction = startAction,
            endActions = endActions,
            bottomAction = bottomAction,
            insideMargin = insideMargin,
            onClick = onClick,
            onClickLabel = onClickLabel,
            role = role,
            holdDownState = holdDownState,
            enabled = enabled,
            interactionSource = interactionSource,
        )
        return
    }
    CouiPreferenceRow(
        modifier = modifier,
        title = title,
        summary = summary,
        enabled = enabled,
        icon = startAction,
        onClick = onClick,
        trailing = {
            if (endActions != null) {
                Row(verticalAlignment = Alignment.CenterVertically) { endActions() }
            }
        },
    )
}

@Composable
internal fun BasicComponent(
    modifier: Modifier = Modifier,
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    role: Role? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 自定义内容的行保持 Miuix 结构（内容里的文字/按钮已各自适配）。
    MiuixBasicComponent(
        modifier = modifier,
        startAction = startAction,
        endActions = endActions,
        bottomAction = bottomAction,
        insideMargin = insideMargin,
        onClick = onClick,
        onClickLabel = onClickLabel,
        role = role,
        holdDownState = holdDownState,
        enabled = enabled,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
internal fun WindowDialog(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = Color.Unspecified,
    summary: String? = null,
    summaryColor: Color = Color.Unspecified,
    backgroundColor: Color = Color.Unspecified,
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true,
    maxWidth: Dp = DialogDefaults.MaxWidth,
    largeScreen: Boolean? = null,
    cornerRadius: Dp? = null,
    couiCloseText: String? = null,
    content: @Composable () -> Unit,
) {
    // COUI 下换成 COUI-Kit 的弹窗（毛玻璃材质、COUI 动画、底部操作区）。COUI 弹窗必须至少有一个底部操作，
    // 这里统一放一个"关闭"，点它等同于点弹窗外关闭；正文照原样放在 COUI 弹窗的正文区。
    if (isCouiStyle()) {
        val dark = CouiTheme.isDark
        val view = LocalView.current
        val dismiss: () -> Unit = onDismissRequest ?: {}
        CouiDialog(
            modifier = modifier,
            visible = show,
            onDismissRequest = dismiss,
            dark = dark,
            title = title,
            message = summary,
            actionRows = listOf(
                listOf(
                    CouiDialogAction(
                        text = couiCloseText ?: stringResource(R.string.action_close),
                        onClick = {
                            TouchHaptics.click(view)
                            dismiss()
                        },
                    ),
                ),
            ),
            material = rememberCouiDialogMaterial(dark),
            backdrop = rememberCouiWindowBackdrop(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiDialogDefaults.contentPadding)
                    .padding(top = 4.dp, bottom = 12.dp),
            ) {
                content()
            }
        }
        return
    }
    val coui = false
    val pageColors = if (coui) CouiPageDefaults.themeColors(CouiTheme.isDark) else null
    MiuixWindowDialog(
        show = show,
        modifier = modifier,
        title = title,
        titleColor = when {
            titleColor.isSpecified -> titleColor
            pageColors != null -> pageColors.title
            else -> DialogDefaults.titleColor()
        },
        summary = summary,
        summaryColor = when {
            summaryColor.isSpecified -> summaryColor
            pageColors != null -> pageColors.summary
            else -> DialogDefaults.summaryColor()
        },
        backgroundColor = when {
            backgroundColor.isSpecified -> backgroundColor
            pageColors != null -> pageColors.card
            else -> DialogDefaults.backgroundColor()
        },
        enableWindowDim = enableWindowDim,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        outsideMargin = outsideMargin,
        insideMargin = insideMargin,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        maxWidth = maxWidth,
        largeScreen = largeScreen,
        cornerRadius = cornerRadius ?: if (coui) 28.dp else null,
        content = content,
    )
}

@Composable
internal fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    cornerRadius: Dp = IconButtonDefaults.CornerRadius,
    minHeight: Dp = IconButtonDefaults.MinHeight,
    minWidth: Dp = IconButtonDefaults.MinWidth,
    content: @Composable () -> Unit,
) {
    if (!isCouiStyle()) {
        MiuixIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            holdDownState = holdDownState,
            backgroundColor = backgroundColor,
            cornerRadius = cornerRadius,
            minHeight = minHeight,
            minWidth = minWidth,
            content = content,
        )
        return
    }
    // COUI：同尺寸、同圆角，换成 COUI 按压反馈（缩放 + 遮罩），去掉 Miuix 的按压底色。
    val dark = CouiTheme.isDark
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .sizeIn(minWidth = minWidth, minHeight = minHeight)
            .clip(shape)
            .then(if (backgroundColor.isSpecified) Modifier.background(backgroundColor, shape) else Modifier)
            .couiButtonFeedback(dark, source, enabled = enabled)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = DividerDefaults.Thickness,
    color: Color = DividerDefaults.DividerColor,
) {
    if (!isCouiStyle()) {
        MiuixHorizontalDivider(modifier = modifier, thickness = thickness, color = color)
        return
    }
    // COUI 分割线：缩进交给调用方的 modifier，颜色用 COUI outlineVariant。
    CouiDivider(modifier = modifier, startIndent = 0.dp, endIndent = 0.dp)
}

@Composable
internal fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    reverseDirection: Boolean = false,
    height: Dp = SliderDefaults.MinHeight,
    colors: SliderColors = SliderDefaults.sliderColors(),
    hapticEffect: SliderDefaults.SliderHapticEffect = SliderDefaults.DefaultHapticEffect,
    showKeyPoints: Boolean = false,
    keyPoints: List<Float>? = null,
    magnetThreshold: Float = 0.02f,
) {
    if (!isCouiStyle() || reverseDirection) {
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            reverseDirection = reverseDirection,
            height = height,
            colors = colors,
            hapticEffect = hapticEffect,
            showKeyPoints = showKeyPoints,
            keyPoints = keyPoints,
            magnetThreshold = magnetThreshold,
        )
        return
    }
    CouiSlider(
        modifier = modifier,
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        showTickMarks = showKeyPoints && steps > 0,
        enabled = enabled,
        onValueChangeFinished = { onValueChangeFinished?.invoke() },
    )
}

@Composable
internal fun Checkbox(
    state: ToggleableState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    colors: CheckboxColors = CheckboxDefaults.checkboxColors(),
    enabled: Boolean = true,
) {
    if (!isCouiStyle()) {
        MiuixCheckbox(state = state, onClick = onClick, modifier = modifier, colors = colors, enabled = enabled)
        return
    }
    // COUI 复选框只负责绘制，点击由这里挂上。
    CouiCheckbox(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
            } else {
                Modifier
            },
        ),
        checked = state == ToggleableState.On,
        enabled = enabled,
    )
}
