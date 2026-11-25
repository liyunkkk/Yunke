package io.github.mangi.eta.ui.components.coui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.sheet.BottomSheet
import com.itos.coui.theme.CouiTheme

/**
 * COUI 风格下替代 Material ModalBottomSheet 的底部面板：COUI 毛玻璃材质、拖动条、COUI 进出场。
 * 调用方和 ModalBottomSheet 一样「显示时组合、关闭时移除」；关闭请求直接交给 onDismiss。
 * 正文多数自带滚动或 LazyColumn，所以默认不让面板再包一层滚动。
 */
@Composable
internal fun CouiModalSheet(
    onDismiss: () -> Unit,
    scrollableContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible.value = true }
    BottomSheet(
        visible = visible,
        onDismissRequest = {
            visible.value = false
            onDismiss()
        },
        dark = CouiTheme.isDark,
        backdrop = rememberCouiWindowBackdrop(),
        showDragHandle = true,
        scrollableContent = scrollableContent,
    ) {
        CompositionLocalProvider(LocalInsideCouiDialog provides true) {
            content()
        }
    }
}
