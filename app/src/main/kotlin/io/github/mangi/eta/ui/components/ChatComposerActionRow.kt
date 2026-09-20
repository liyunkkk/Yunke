package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The microphone is centered on the entire composer, not the gap between asymmetric controls.
 * Keep its touch target in the centered layer too; no hidden reasoning button is retained. */
@Composable
internal fun ChatComposerActionRow(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
    indicator: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = actions)
        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
            indicator()
        }
    }
}
