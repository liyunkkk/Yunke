package io.github.mangi.eta.ui.components

/** Direction in the conversation, not the physical direction of the finger. */
internal enum class ConversationNavigationDirection { Up, Down }

/** Ignore programmatic scrolls and small direction reversals; flings retain the drag direction. */
internal class ConversationNavigationDirectionTracker(private val thresholdPx: Float) {
    init { require(thresholdPx > 0f && thresholdPx.isFinite()) }
    var direction = ConversationNavigationDirection.Down
        private set
    private var pendingDelta = 0f

    fun onScroll(deltaY: Float, userInput: Boolean): ConversationNavigationDirection {
        if (!userInput || !deltaY.isFinite() || deltaY == 0f) return direction
        if (pendingDelta * deltaY < 0f) pendingDelta = 0f
        pendingDelta += deltaY
        if (kotlin.math.abs(pendingDelta) >= thresholdPx) {
            // LazyColumn's negative nested-scroll delta moves toward later messages.
            direction = if (pendingDelta < 0f) ConversationNavigationDirection.Down else ConversationNavigationDirection.Up
            pendingDelta = 0f
        }
        return direction
    }

    fun endGesture() { pendingDelta = 0f }
}

/** Navigate actual user bubbles, including steering supplements, not logical run/turn boundaries. */
internal fun conversationUserMessageTarget(
    userMessageIndices: List<Int>,
    firstVisibleIndex: Int,
    bottomItemIndex: Int,
    direction: ConversationNavigationDirection,
    toEdge: Boolean,
    firstVisibleScrollOffset: Int = 0,
): Int {
    if (toEdge) return if (direction == ConversationNavigationDirection.Down) bottomItemIndex else 0
    return when (direction) {
        ConversationNavigationDirection.Down -> userMessageIndices.firstOrNull { it > firstVisibleIndex } ?: bottomItemIndex
        ConversationNavigationDirection.Up -> {
            // In an answer, first return to its preceding user bubble. If a long user
            // bubble is clipped, reveal its beginning before moving to the previous one.
            userMessageIndices.lastOrNull {
                it < firstVisibleIndex || (it == firstVisibleIndex && firstVisibleScrollOffset > 0)
            } ?: 0
        }
    }
}
