package io.github.mangi.eta.ui.components

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState

/**
 * Post-layout recovery only; never call from measure, placement or draw.
 * Keeps the presentation layer and consumes only measured overflow beyond its existing
 * padding budget. Unknown tails authorize no recovery scroll. Actual consumption and
 * the fresh measured tail are checked on every iteration; requests are not evidence.
 */
internal class BottomFollowViewportRecovery(
    private val state: LazyListState,
    private val sentinelKey: Any,
) {
    private var recovering = false

    fun recover(canRecover: () -> Boolean): Float {
        if (recovering || state.isScrollInProgress || !canRecover()) return 0f
        recovering = true
        var consumed = 0f
        try {
            // Remeasurement may reveal the one-pixel sentinel. Bound reentrant work.
            repeat(4) {
                if (state.isScrollInProgress || !canRecover()) return consumed
                val info = state.layoutInfo
                val overflow = info.measuredBottomFollowTailOverflow(sentinelKey)
                    ?: return consumed
                val step = resolveBottomFollowViewportStep(0f, overflow, info.afterContentPadding)
                if (step <= 0f || !state.canScrollForward) return consumed
                val actual = state.dispatchRawDelta(step)
                if (!actual.isFinite() || actual <= 0f) return consumed
                consumed += actual
            }
            return consumed
        } finally {
            recovering = false
        }
    }
}

internal fun LazyListLayoutInfo.measuredBottomFollowTailOverflow(sentinelKey: Any): Int? {
    val sentinel = visibleItemsInfo.firstOrNull { it.key == sentinelKey }
    val last = visibleItemsInfo.lastOrNull()
    val bottom = resolveTailBottomPx(
        sentinelBottom = sentinel?.let { it.offset + it.size },
        lastVisibleIndex = last?.index,
        lastVisibleBottom = last?.let { it.offset + it.size },
        totalItems = totalItemsCount,
    ) ?: return null
    return bottom - (viewportEndOffset - afterContentPadding)
}
