package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.ConversationTodo
import io.github.mangi.eta.data.db.ConversationTodoStatus
import io.github.mangi.eta.data.db.ConversationTodoPriority
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

/**
 * 归一化清单：最多保留一项 in_progress，多余的按顺序降级为 pending。
 *
 * 模型偶尔会把两项同时标成 in_progress。整份拒绝会让清单停在上一版（界面看起来卡住），
 * 所以这里保序降级而不是丢弃；内容为空的项仍由 [validateConversationTodoSnapshot] 拒绝。
 */
internal fun normalizeConversationTodoSnapshot(todos: List<ConversationTodo>): List<ConversationTodo> {
    var seenInProgress = false
    return todos.map { todo ->
        if (todo.status != ConversationTodoStatus.IN_PROGRESS) return@map todo
        if (!seenInProgress) {
            seenInProgress = true
            todo
        } else {
            todo.copy(status = ConversationTodoStatus.PENDING)
        }
    }
}

/** 增量更新的一项：index 是 0 基下标，status/content/priority 至少给一个（不给即保持原值）。 */
internal data class ConversationTodoUpdate(
    val index: Int,
    val status: ConversationTodoStatus? = null,
    val content: String? = null,
    val priority: ConversationTodoPriority? = null,
)

/**
 * 把增量更新应用到当前清单，保持顺序不变、只改指定项。
 *
 * 返回 null 表示下标越界或没有任何更新：调用方据此回报并让模型重新取下标，
 * 绝不猜、也不整份丢弃。
 */
internal fun applyConversationTodoUpdates(
    current: List<ConversationTodo>,
    updates: List<ConversationTodoUpdate>,
): List<ConversationTodo>? {
    if (updates.isEmpty()) return null
    if (updates.any { it.index !in current.indices }) return null
    val next = current.toMutableList()
    for (update in updates) {
        val old = next[update.index]
        next[update.index] = old.copy(
            status = update.status ?: old.status,
            // 空白内容不覆盖原值：宁可只改状态，也不把一项写成空。
            content = update.content?.trim()?.takeIf { it.isNotEmpty() } ?: old.content,
            priority = update.priority ?: old.priority,
        )
    }
    return next
}

internal class ConversationTodoRepository private constructor(private val context: Context) {
    private val dao = EtaDatabase.get(context.applicationContext).conversationTodoDao()

    fun observe(conversationId: String): Flow<List<ConversationTodo>> =
        dao.observeByConversation(conversationId).map { rows -> rows.map { it.toConversationTodo() } }

    /** 会话最后活动时间（毫秒）；读不到时给 null，调用方按「未过期」处理。 */
    fun observeConversationUpdatedAt(conversationId: String): Flow<Long?> =
        EtaDatabase.get(context.applicationContext).conversationDao().observeUpdatedAt(conversationId)

    /** 读取某会话当前的清单（按 position 顺序）；用于增量更新。 */
    suspend fun current(conversationId: String): List<ConversationTodo> {
        if (conversationId.isBlank()) return emptyList()
        return dao.todosByConversation(conversationId).map { it.toConversationTodo() }
    }

    /**
     * 销毁某会话的清单。
     *
     * 清单是「当前任务的进度指示」，不是历史记录：一份已经收尾（全终态）或已经过期的清单
     * 在隐藏之后没有保留价值，留着只会让用户重开会话时又被弹一次。
     */
    suspend fun clear(conversationId: String) {
        if (conversationId.isBlank()) return
        dao.deleteByConversation(conversationId)
    }

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
