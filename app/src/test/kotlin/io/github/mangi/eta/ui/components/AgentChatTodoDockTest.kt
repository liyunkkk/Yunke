package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.db.ConversationTodo
import io.github.mangi.eta.data.db.ConversationTodoPriority
import io.github.mangi.eta.data.db.ConversationTodoStatus
import org.junit.Assert.assertEquals
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
}
