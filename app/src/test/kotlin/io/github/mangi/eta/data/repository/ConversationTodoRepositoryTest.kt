package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ConversationTodo
import io.github.mangi.eta.data.db.ConversationTodoPriority
import io.github.mangi.eta.data.db.ConversationTodoStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationTodoRepositoryTest {
    private fun todo(
        content: String,
        status: ConversationTodoStatus = ConversationTodoStatus.PENDING,
        priority: ConversationTodoPriority = ConversationTodoPriority.MEDIUM,
    ) = ConversationTodo(content = content, status = status, priority = priority)

    @Test
    fun snapshotWithBlankContentIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateConversationTodoSnapshot(listOf(todo("有效项"), todo("   ")))
        }
        assertEquals("Todo content cannot be blank", error.message)
    }

    @Test
    fun snapshotWithTwoInProgressItemsIsRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            validateConversationTodoSnapshot(
                listOf(
                    todo("第一项", ConversationTodoStatus.IN_PROGRESS),
                    todo("第二项", ConversationTodoStatus.IN_PROGRESS),
                ),
            )
        }
        assertEquals("Only one todo may be in progress", error.message)
    }

    @Test
    fun snapshotWithSingleInProgressAndTerminalItemsIsAccepted() {
        validateConversationTodoSnapshot(
            listOf(
                todo("已完成", ConversationTodoStatus.COMPLETED),
                todo("进行中", ConversationTodoStatus.IN_PROGRESS),
                todo("待办", ConversationTodoStatus.PENDING, ConversationTodoPriority.HIGH),
                todo("已取消", ConversationTodoStatus.CANCELLED, ConversationTodoPriority.LOW),
            ),
        )
    }

    @Test
    fun normalizeKeepsTheFirstInProgressAndDemotesTheRest() {
        val normalized = normalizeConversationTodoSnapshot(
            listOf(
                todo("已完成", ConversationTodoStatus.COMPLETED),
                todo("进行中一", ConversationTodoStatus.IN_PROGRESS, ConversationTodoPriority.HIGH),
                todo("进行中二", ConversationTodoStatus.IN_PROGRESS),
                todo("待办"),
            ),
        )
        assertEquals(
            listOf(
                ConversationTodoStatus.COMPLETED,
                ConversationTodoStatus.IN_PROGRESS,
                ConversationTodoStatus.PENDING,
                ConversationTodoStatus.PENDING,
            ),
            normalized.map { it.status },
        )
        // 归一化后必须仍然满足快照合同。
        validateConversationTodoSnapshot(normalized)
    }

    @Test
    fun normalizeLeavesASingleInProgressUntouched() {
        val todos = listOf(
            todo("进行中", ConversationTodoStatus.IN_PROGRESS),
            todo("待办"),
        )
        assertEquals(todos, normalizeConversationTodoSnapshot(todos))
    }

    @Test
    fun waitingForUserMayKeepEveryUnfinishedItemPending() {
        validateConversationTodoSnapshot(
            listOf(
                todo("等待确认 A"),
                todo("等待确认 B", priority = ConversationTodoPriority.HIGH),
            ),
        )
    }
}
