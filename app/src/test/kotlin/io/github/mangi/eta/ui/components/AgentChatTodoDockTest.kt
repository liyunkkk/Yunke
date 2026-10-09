package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.db.ConversationTodo
import io.github.mangi.eta.data.db.ConversationTodoPriority
import io.github.mangi.eta.data.db.ConversationTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatTodoDockTest {
    private fun todo(content: String, status: ConversationTodoStatus) =
        ConversationTodo(content, status, ConversationTodoPriority.MEDIUM)

    @Test
    fun inProgressItemWinsOverPendingOnes() {
        val todos = listOf(
            todo("一", ConversationTodoStatus.COMPLETED),
            todo("二", ConversationTodoStatus.PENDING),
            todo("三", ConversationTodoStatus.IN_PROGRESS),
        )
        assertEquals(3, currentTodoStep(todos))
    }

    @Test
    fun firstPendingIsCurrentWhenNothingIsInProgress() {
        val todos = listOf(
            todo("一", ConversationTodoStatus.COMPLETED),
            todo("二", ConversationTodoStatus.PENDING),
            todo("三", ConversationTodoStatus.PENDING),
        )
        assertEquals(2, currentTodoStep(todos))
    }

    @Test
    fun allTerminalReportsTotalCount() {
        val todos = listOf(
            todo("一", ConversationTodoStatus.COMPLETED),
            todo("二", ConversationTodoStatus.CANCELLED),
        )
        assertEquals(2, currentTodoStep(todos))
    }

    @Test
    fun unfinishedPlanExpiresAfterTheIdleWindow() {
        val todos = listOf(todo("一", ConversationTodoStatus.IN_PROGRESS))
        val lastActivityAt = 1_000_000L
        assertFalse(todoDockExpired(todos, lastActivityAt, lastActivityAt + TODO_DOCK_STALE_MS - 1, false))
        assertTrue(todoDockExpired(todos, lastActivityAt, lastActivityAt + TODO_DOCK_STALE_MS, false))
    }

    @Test
    fun runningPlanNeverExpires() {
        val todos = listOf(
            todo("一", ConversationTodoStatus.COMPLETED),
            todo("二", ConversationTodoStatus.IN_PROGRESS),
        )
        val lastActivityAt = 5_000L
        assertFalse(todoDockExpired(todos, lastActivityAt, lastActivityAt + TODO_DOCK_STALE_MS * 10, true))
    }

    @Test
    fun recentActivityKeepsThePlanVisible() {
        val todos = listOf(
            todo("一", ConversationTodoStatus.COMPLETED),
            todo("二", ConversationTodoStatus.IN_PROGRESS),
        )
        assertFalse(todoDockExpired(todos, 5_000L, 5_000L + TODO_DOCK_STALE_MS - 60_000L, false))
    }

    @Test
    fun pendingOnlyPlanAlsoExpires() {
        // 模型收尾前漏写终态时，清单常常只剩 pending；不判过期会让胶囊永久驻留。
        val todos = listOf(
            todo("一", ConversationTodoStatus.COMPLETED),
            todo("二", ConversationTodoStatus.PENDING),
        )
        assertTrue(todoDockExpired(todos, 1_000_000L, 1_000_000L + TODO_DOCK_STALE_MS, false))
        assertFalse(todoDockExpired(todos, 1_000_000L, 1_000_000L + TODO_DOCK_STALE_MS - 1, false))
    }

    @Test
    fun terminalOrUnknownPlansNeverExpire() {
        val farFuture = Long.MAX_VALUE / 2
        val done = listOf(todo("一", ConversationTodoStatus.COMPLETED))
        assertFalse(todoDockExpired(done, 1_000_000L, farFuture, false))
        assertFalse(todoDockExpired(emptyList(), 1_000_000L, farFuture, false))
        val running = listOf(todo("一", ConversationTodoStatus.IN_PROGRESS))
        assertFalse(todoDockExpired(running, null, farFuture, false))
        assertFalse(todoDockExpired(running, 0L, farFuture, false))
    }
}
