package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTurnNavigationTest {
    private val up = ConversationNavigationDirection.Up
    private val down = ConversationNavigationDirection.Down

    @Test fun directionFollowsConversationRatherThanFinger() {
        val tracker = ConversationNavigationDirectionTracker(12f)
        assertEquals(down, tracker.direction)
        assertEquals(up, tracker.onScroll(20f, true))
        assertEquals(down, tracker.onScroll(-20f, true))
    }
    @Test fun tinyReversalsDoNotFlickerArrow() {
        val tracker = ConversationNavigationDirectionTracker(12f)
        assertEquals(up, tracker.onScroll(20f, true))
        assertEquals(up, tracker.onScroll(-3f, true))
        assertEquals(up, tracker.onScroll(-4f, true))
        assertEquals(down, tracker.onScroll(-5f, true))
    }
    @Test fun animationsAndFlingsDoNotChangeDirection() {
        val tracker = ConversationNavigationDirectionTracker(12f)
        tracker.onScroll(20f, true)
        assertEquals(up, tracker.onScroll(-900f, false))
        tracker.endGesture()
        assertEquals(up, tracker.direction)
    }
    @Test fun separateTinyGesturesDoNotAccumulate() {
        val tracker = ConversationNavigationDirectionTracker(12f)
        tracker.onScroll(8f, true)
        tracker.endGesture()
        assertEquals(down, tracker.onScroll(8f, true))
        assertEquals(up, tracker.onScroll(4f, true))
    }
    @Test fun zeroAndNonFiniteDeltasAreIgnored() {
        val tracker = ConversationNavigationDirectionTracker(12f)
        assertEquals(down, tracker.onScroll(Float.NaN, true))
        assertEquals(down, tracker.onScroll(Float.POSITIVE_INFINITY, true))
        assertEquals(down, tracker.onScroll(0f, true))
        assertEquals(up, tracker.onScroll(12f, true))
    }
    @Test fun clickMovesToAdjacentUserMessageRatherThanPreviousTurn() {
        val starts = listOf(0, 4, 9, 13)
        assertEquals(9, conversationUserMessageTarget(starts, 4, 18, down, false))
        assertEquals(9, conversationUserMessageTarget(starts, 7, 18, down, false))
        assertEquals(0, conversationUserMessageTarget(starts, 4, 18, up, false))
        assertEquals(4, conversationUserMessageTarget(starts, 7, 18, up, false))
        assertEquals(4, conversationUserMessageTarget(starts, 9, 18, up, false))
    }
    @Test fun longPressAlwaysGoesToDirectionalEdge() {
        assertEquals(18, conversationUserMessageTarget(listOf(0, 4, 9), 4, 18, down, true))
        assertEquals(0, conversationUserMessageTarget(listOf(0, 4, 9), 4, 18, up, true))
    }
    @Test fun boundaryClicksFallBackToTopOrBottom() {
        assertEquals(18, conversationUserMessageTarget(listOf(0, 4, 9), 15, 18, down, false))
        assertEquals(0, conversationUserMessageTarget(listOf(0, 4, 9), 0, 18, up, false))
    }
    @Test fun emptyAndSingleTurnConversationsAreSafe() {
        assertEquals(0, conversationUserMessageTarget(emptyList(), 0, 0, down, false))
        assertEquals(0, conversationUserMessageTarget(emptyList(), 0, 0, up, false))
        assertEquals(5, conversationUserMessageTarget(listOf(0), 2, 5, down, false))
        assertEquals(0, conversationUserMessageTarget(listOf(0), 2, 5, up, false))
    }
    @Test fun contentBeforeFirstQuestionNavigatesToFirstQuestion() {
        assertEquals(2, conversationUserMessageTarget(listOf(2, 6), 0, 9, down, false))
        assertEquals(0, conversationUserMessageTarget(listOf(2, 6), 0, 9, up, false))
    }
    @Test fun upwardClickFirstRevealsPartiallyScrolledUserMessage() {
        val users = listOf(0, 4, 9)
        assertEquals(4, conversationUserMessageTarget(users, 4, 14, up, false, firstVisibleScrollOffset = 80))
        assertEquals(0, conversationUserMessageTarget(users, 4, 14, up, false, firstVisibleScrollOffset = 0))
        assertEquals(9, conversationUserMessageTarget(users, 4, 14, down, false, firstVisibleScrollOffset = 80))
    }

    @Test fun consecutiveSupplementMessagesAreEachReachableInBothDirections() {
        val users = listOf(0, 2, 3, 5)
        assertEquals(2, conversationUserMessageTarget(users, 0, 7, down, false))
        assertEquals(3, conversationUserMessageTarget(users, 2, 7, down, false))
        assertEquals(2, conversationUserMessageTarget(users, 3, 7, up, false))
        assertEquals(3, conversationUserMessageTarget(users, 4, 7, up, false))
    }

}
