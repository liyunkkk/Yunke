package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 会话 Todo 状态；wire 值与模型侧合同一致（小写）。 */
internal enum class ConversationTodoStatus(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWire(value: String): ConversationTodoStatus? =
            entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/** 会话 Todo 优先级；wire 值与模型侧合同一致（小写）。 */
internal enum class ConversationTodoPriority(val wire: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low"),
    ;

    companion object {
        fun fromWire(value: String): ConversationTodoPriority? =
            entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/**
 * 会话 Todo 清单的一行。
 *
 * 主键 (conversation_id, position) 让"快照替换"天然去重；position 决定展示顺序。
 * 会话删除时随 CASCADE 一起清理，不留孤儿行。
 */
@Entity(
    tableName = "conversation_todos",
    primaryKeys = ["conversation_id", "position"],
    indices = [Index(value = ["conversation_id"])],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationTodoEntity(
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val position: Int,
    val content: String,
    val status: String,
    val priority: String,
)

/** 领域模型：只保留模型与 UI 都需要的最小字段。 */
internal data class ConversationTodo(
    val content: String,
    val status: ConversationTodoStatus,
    val priority: ConversationTodoPriority,
)

internal fun ConversationTodoEntity.toConversationTodo(): ConversationTodo =
    ConversationTodo(
        content = content,
        status = ConversationTodoStatus.fromWire(status) ?: ConversationTodoStatus.PENDING,
        priority = ConversationTodoPriority.fromWire(priority) ?: ConversationTodoPriority.MEDIUM,
    )

internal fun ConversationTodo.toEntity(conversationId: String, position: Int): ConversationTodoEntity =
    ConversationTodoEntity(
        conversationId = conversationId,
        position = position,
        content = content,
        status = status.wire,
        priority = priority.wire,
    )
