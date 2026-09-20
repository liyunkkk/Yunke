package io.github.mangi.eta.agent.translation

import android.graphics.Rect

data class ScreenTranslationBlock(
    val source: String,
    val boundsInScreen: Rect,
    val sampledBgColor: Int? = null,
    val translated: String? = null,
)

internal fun String.sameTranslationInputAs(other: String): Boolean {
    if (this == other) return true
    return this.filter { ch -> !ch.isWhitespace() } == other.filter { ch -> !ch.isWhitespace() }
}
