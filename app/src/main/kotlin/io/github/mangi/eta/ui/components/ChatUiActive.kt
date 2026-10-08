package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether this Home/Chat route is the current destination.
 * NavDisplay keeps covered entries composed for swipe-back; those entries must
 * not apply streaming message updates, or they share the frame with Settings.
 * Default true so standalone previews and the voice panel keep live content.
 */
val LocalChatUiActive = staticCompositionLocalOf { true }

/** 完成且不再逐帧变化的内容复用一张离屏纹理。层始终挂着，只切换合成策略，避免插入时重挂。 */
@Composable
internal fun completedContentDrawLayer(enabled: Boolean): Modifier {
    var heightPx by remember { mutableIntStateOf(0) }
    val retain = enabled && heightPx in 1..8192
    return Modifier
        .onSizeChanged { heightPx = it.height }
        .graphicsLayer(
            compositingStrategy = if (retain) CompositingStrategy.Offscreen else CompositingStrategy.Auto,
        )
}
