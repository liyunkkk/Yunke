package io.github.mangi.eta.ui.components

import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/** One scroll mutation and one velocity profile, including across supplement/answer boundaries. */
internal suspend fun LazyListState.animateToConversationTurn(targetIndex: Int) {
    require(targetIndex >= 0)
    scroll {
        val lazyScope = LazyLayoutScrollScope(this@animateToConversationTurn, this)
        if (lazyScope.itemCount == 0) return@scroll
        val target = targetIndex.coerceAtMost(lazyScope.itemCount - 1)
        val forward = target > firstVisibleItemIndex
        val sign = if (forward) 1f else -1f
        val motion = ConversationTurnMotion(layoutInfo.viewportSize.height.toFloat())
        var previousFrame = withFrameNanos { it }
        while (true) {
            val visible = layoutInfo.visibleItemsInfo.any { it.index == target }
            // Only an actually measured target may trigger deceleration. Do not settle at
            // estimated distances based on the heights of intermediate supplement bubbles.
            val remaining = if (visible) lazyScope.calculateDistanceTo(target).toFloat() else null
            if (remaining != null && (abs(remaining) <= 0.5f || remaining * sign < 0f)) {
                lazyScope.snapToItem(target)
                break
            }
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previousFrame = frame
            val distance = motion.advance(elapsed, remaining?.let(::abs))
            if (distance <= 0f) continue
            val delta = sign * distance
            val consumed = scrollBy(delta)
            // A small target may become visible and be crossed in this very measurement.
            // Correct before drawing this frame, never start a second reverse animation.
            val crossed = if (forward) firstVisibleItemIndex >= target else firstVisibleItemIndex < target
            if (crossed || (remaining != null && distance >= abs(remaining))) {
                lazyScope.snapToItem(target)
                break
            }
            if (abs(consumed) < abs(delta) - 0.5f) break // Actual list edge, not an estimated waypoint.
        }
    }
}
