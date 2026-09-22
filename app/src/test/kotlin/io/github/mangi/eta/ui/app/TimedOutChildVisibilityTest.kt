package io.github.mangi.eta.ui.app

import org.junit.Assert.*
import org.junit.Test

class TimedOutChildVisibilityTest {
    @Test fun hideFinishedTasksAfter30SecondsWithoutRevivingOnDuplicateEvents() {
        var now = 0L
        val visibility = TimedOutChildVisibility { now }
        for (status in listOf("completed", "cancelled", "timed_out", "failed")) {
            assertTrue(visibility.observe("run", status, status))
            assertEquals(30_000L, visibility.remaining("run", status))
            now = 29_999
            assertTrue(visibility.observe("run", status, status))
            now = 30_000
            assertFalse(visibility.observe("run", status, status))
            now = 0
        }
    }

    @Test fun runningQueuedAndAwaitingDecisionStayVisible() {
        var now = 0L
        val visibility = TimedOutChildVisibility { now }
        for (status in listOf("queued", "running", "awaiting_decision")) {
            assertTrue(visibility.observe("run", "task", status))
            now = 100_000
            assertTrue(visibility.observe("run", "task", status))
            assertFalse(visibility.schedulesHide(status))
            now = 0
        }
    }

    @Test fun returningToRunningClearsTheHideDeadline() {
        var now = 0L
        val visibility = TimedOutChildVisibility { now }
        assertTrue(visibility.observe("run", "task", "completed"))
        now = 10_000
        assertTrue(visibility.observe("run", "task", "running"))
        now = 50_000
        assertTrue(visibility.observe("run", "task", "running"))
        assertTrue(visibility.observe("run", "task", "completed"))
        assertEquals(30_000L, visibility.remaining("run", "task"))
    }
}
