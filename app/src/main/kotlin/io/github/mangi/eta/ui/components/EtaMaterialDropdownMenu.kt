package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.data.model.AppearanceUiStyle
import com.itos.coui.theme.CouiTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Compact Material menu: content-sized width, outline and no drop shadow. */
@Composable
internal fun EtaMaterialDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // COUI 下换成 COUI 菜单外观：28dp 圆角、COUI 菜单底色、阴影代替描边。
    val coui = LocalUiStyle.current == AppearanceUiStyle.COUI
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = Modifier.widthIn(min = 112.dp, max = 280.dp),
        shape = RoundedCornerShape(if (coui) 28.dp else 16.dp),
        containerColor = if (coui) CouiTheme.colorScheme.menuSurface else MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = if (coui) 12.dp else 0.dp,
        border = if (coui) null else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal, fontStyle = FontStyle.Normal)) {
            content()
        }
    }
}

/** Match the conversation overflow menu's 40dp rows and 12dp horizontal padding. */
@Composable
internal fun EtaMaterialDropdownMenuItem(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}
