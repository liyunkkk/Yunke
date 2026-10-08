package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.ConversationTodo
import io.github.mangi.eta.data.db.ConversationTodoStatus
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toConversationTodo
import io.github.mangi.eta.data.db.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * 校验清单快照：内容非空，且最多一项 in_progress。
 * 与模型侧工具合同一致：等待用户或外部条件时，未完成项可以全部为 pending。
 */
internal fun validateConversationTodoSnapshot(todos: List<ConversationTodo>) {
    require(todos.all { it.content.isNotBlank() }) { "Todo content cannot be blank" }
    require(todos.count { it.status == ConversationTodoStatus.IN_PROGRESS } <= 1) {
        "Only one todo may be in progress"
    }
}

/** 按当前会话 id 观察清单；没有会话时给空列表，避免 UI 拿到上一会话的残留。 */
internal fun Flow<String?>.observeConversationTodos(
    observe: (String) -> Flow<List<ConversationTodo>>,
): Flow<List<ConversationTodo>> =
    flatMapLatest { conversationId ->
        if (conversationId.isNullOrBlank()) {
            flowOf(emptyList<ConversationTodo>())
        } else {
            observe(conversationId).onStart { emit(emptyList()) }
        }
    }

internal class ConversationTodoRepository private constructor(context: Context) {
    private val dao = EtaDatabase.get(context.applicationContext).conversationTodoDao()

    fun observe(conversationId: String): Flow<List<ConversationTodo>> =
        dao.observeByConversation(conversationId).map { rows -> rows.map { it.toConversationTodo() } }

    suspend fun replace(conversationId: String, todos: List<ConversationTodo>) {
        require(conversationId.isNotBlank()) { "Conversation ID is required" }
        validateConversationTodoSnapshot(todos)
        dao.replaceForConversation(
            conversationId,
            todos.mapIndexed { index, todo -> todo.toEntity(conversationId, index) },
        )
    }

    companion object {
        @Volatile
        private var instance: ConversationTodoRepository? = null

        fun getInstance(context: Context): ConversationTodoRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationTodoRepository(context).also { instance = it }
            }
    }
}
