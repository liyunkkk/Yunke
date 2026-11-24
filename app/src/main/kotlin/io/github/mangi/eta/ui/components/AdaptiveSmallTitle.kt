package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import top.yukonga.miuix.kmp.basic.SmallTitle as MiuixSmallTitle

/** 分组小标题：COUI 风格下用 COUI 分组标题，Miuix 下保持原来的 SmallTitle。 */
@Composable
internal fun SmallTitle(
    text: String,
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(28.dp, 8.dp),
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSectionTitle(text = text, modifier = modifier)
    } else {
        MiuixSmallTitle(text = text, modifier = modifier, insideMargin = insideMargin)
    }
}
