package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog as CouiDialog
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.LocalInsideCouiDialog
import io.github.mangi.eta.ui.components.coui.couiPrimaryDialogAction
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.ButtonDefaults

/**
 * 标题 + 说明 + 单个“知道了”按钮的提示弹窗。
 * COUI 风格下使用 COUI-Kit 的 Dialog（只有一个按钮，不额外加取消）；
 * 其他风格保持原来的 WindowDialog + 整行 TextButton，行为不变。
 */
@Composable
internal fun AdaptiveNoticeDialog(
    show: Boolean,
    title: String?,
    summary: String?,
    buttonText: String,
    onDismiss: () -> Unit,
    primary: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val view = LocalView.current
        val dark = CouiTheme.isDark
        val onClick = {
            TouchHaptics.click(view)
            onDismiss()
        }
        CouiDialog(
            visible = show,
            onDismissRequest = onDismiss,
            dark = dark,
            title = title,
            message = summary,
            confirmAction = if (primary) {
                couiPrimaryDialogAction(text = buttonText, onClick = onClick, dark = dark)
            } else {
                CouiDialogAction(text = buttonText, onClick = onClick)
            },
            material = rememberCouiDialogMaterial(dark),
            backdrop = rememberCouiWindowBackdrop(),
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
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        if (content != null) Column { content() }
        TextButton(
            text = buttonText,
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
        )
    }
}
