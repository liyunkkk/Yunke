package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ConversationTodoDao {
    @Query(
        "SELECT * FROM conversation_todos WHERE conversation_id = :conversationId ORDER BY position ASC"
    )
    fun observeByConversation(conversationId: String): Flow<List<ConversationTodoEntity>>

    @Query(
        "SELECT * FROM conversation_todos WHERE conversation_id = :conversationId ORDER BY position ASC"
    )
    suspend fun todosByConversation(conversationId: String): List<ConversationTodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(todos: List<ConversationTodoEntity>)

    @Query("DELETE FROM conversation_todos WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    /** 快照替换：先清空该会话的清单再按顺序写入，避免残留上一轮的项。 */
    @Transaction
    suspend fun replaceForConversation(
        conversationId: String,
        todos: List<ConversationTodoEntity>,
    ) {
        deleteByConversation(conversationId)
        if (todos.isNotEmpty()) insertAll(todos)
    }
}
