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
    @Test fun clickMovesOneLogicalTurnInEitherDirection() {
        val starts = listOf(0, 4, 9, 13)
        assertEquals(9, conversationTurnTarget(starts, 4, 18, down, false))
        assertEquals(9, conversationTurnTarget(starts, 7, 18, down, false))
        assertEquals(0, conversationTurnTarget(starts, 4, 18, up, false))
        assertEquals(0, conversationTurnTarget(starts, 7, 18, up, false))
        assertEquals(4, conversationTurnTarget(starts, 9, 18, up, false))
    }
    @Test fun longPressAlwaysGoesToDirectionalEdge() {
        assertEquals(18, conversationTurnTarget(listOf(0, 4, 9), 4, 18, down, true))
        assertEquals(0, conversationTurnTarget(listOf(0, 4, 9), 4, 18, up, true))
    }
    @Test fun boundaryClicksFallBackToTopOrBottom() {
        assertEquals(18, conversationTurnTarget(listOf(0, 4, 9), 15, 18, down, false))
        assertEquals(0, conversationTurnTarget(listOf(0, 4, 9), 0, 18, up, false))
    }
    @Test fun emptyAndSingleTurnConversationsAreSafe() {
        assertEquals(0, conversationTurnTarget(emptyList(), 0, 0, down, false))
        assertEquals(0, conversationTurnTarget(emptyList(), 0, 0, up, false))
        assertEquals(5, conversationTurnTarget(listOf(0), 2, 5, down, false))
        assertEquals(0, conversationTurnTarget(listOf(0), 2, 5, up, false))
    }
    @Test fun contentBeforeFirstQuestionNavigatesToFirstQuestion() {
        assertEquals(2, conversationTurnTarget(listOf(2, 6), 0, 9, down, false))
        assertEquals(0, conversationTurnTarget(listOf(2, 6), 0, 9, up, false))
    }
}
