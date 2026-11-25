package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog as CouiDialog
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.LocalInsideCouiDialog
import io.github.mangi.eta.ui.components.coui.couiPrimaryDialogAction
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 标题 + 说明 + 取消/确认 两个按钮的通用确认弹窗。
 * COUI 风格下直接使用 COUI-Kit 的 Dialog（模糊材质、COUI 进出场动画与按钮布局）；
 * 其他风格保持原来的 WindowDialog + MiuixDialogActions，行为不变。
 */
@Composable
internal fun AdaptiveConfirmDialog(
    show: Boolean,
    title: String?,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    summary: String? = null,
    onDismissRequest: (() -> Unit)? = null,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    confirmHaptic: Boolean = true,
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val view = LocalView.current
        val dark = CouiTheme.isDark
        val backdrop = rememberCouiWindowBackdrop()
        val material = rememberCouiDialogMaterial(dark)
        CouiDialog(
            modifier = modifier,
            visible = show,
            onDismissRequest = onDismissRequest ?: {},
            dark = dark,
            title = title,
            message = summary,
            confirmAction = couiPrimaryDialogAction(
                text = confirmText,
                onClick = {
                    if (confirmHaptic) TouchHaptics.click(view)
                    onConfirm()
                },
                enabled = confirmEnabled,
                destructive = destructive,
                dark = dark,
            ),
            dismissAction = CouiDialogAction(
                text = cancelText ?: stringResource(R.string.action_cancel),
                onClick = {
                    TouchHaptics.click(view)
                    onCancel()
                },
                enabled = cancelEnabled,
            ),
            material = material,
            backdrop = backdrop,
        ) {
            if (content != null) {
                CompositionLocalProvider(LocalInsideCouiDialog provides true) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CouiDialogDefaults.contentPadding)
                            .padding(top = 6.dp, bottom = 12.dp),
                        content = content,
                    )
                }
            }
        }
        return
    }
    WindowDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
    ) {
        if (content != null) Column { content() }
        MiuixDialogActions(
            modifier = if (content != null) Modifier.padding(top = 16.dp) else Modifier,
            confirmText = confirmText,
            onCancel = onCancel,
            onConfirm = onConfirm,
            cancelText = cancelText,
            cancelEnabled = cancelEnabled,
            confirmEnabled = confirmEnabled,
            destructive = destructive,
            confirmHaptic = confirmHaptic,
        )
    }
}
