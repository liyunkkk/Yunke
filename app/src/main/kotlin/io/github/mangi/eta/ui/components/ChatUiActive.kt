package io.github.mangi.eta.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether this Home/Chat route is the current destination.
 * NavDisplay keeps covered entries composed for swipe-back; those entries must
 * not apply streaming message updates, or they share the frame with Settings.
 * Default true so standalone previews and the voice panel keep live content.
 */
val LocalChatUiActive = staticCompositionLocalOf { true }
