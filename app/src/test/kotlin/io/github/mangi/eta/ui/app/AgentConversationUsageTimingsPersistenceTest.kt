package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 单轮耗时真的落盘：保存 → 重进会话（重新读库）→ 胶囊数据还在。
 * 同时覆盖旧行（列 NULL）与损坏 JSON 两种情况：不炸、读回 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationUsageTimingsPersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        java.io.File(context.filesDir, "conversation-history").deleteRecursively()
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
    }

    @Test
    fun timingsSurviveReloadingTheConversation() = runBlocking {
        val usage = TokenUsageUi(
            contextTokens = 32_000,
            inputTokens = 1_500,
            outputTokens = 120,
            reasoningTokens = 40,
            cachedTokens = 1_000,
            stepCount = 3,
            llmMs = 8_400L,
            toolMs = 2_100L,
            ttftMs = 1_200L,
            ttftSteps = 3,
            decodeMs = 6_000L,
            decodeTokens = 120,
        )
        saveConversation("c-timings", listOf(AgentMessageUi(id = "assistant-1", content = "回答", usage = usage)))

        // 模拟退出会话：关库、重新从磁盘读。
        EtaDatabase.closeForTests()
        val restored = reload("c-timings")
        val message = restored.single() as AgentMessageUi
        assertEquals(usage, message.usage)
        assertTrue(requireNotNull(message.usage).hasPerformanceStats)
        // ttftMs 1200 / ttftSteps 3
        assertEquals(400L, message.usage?.averageTtftMs)
        assertEquals(20.0, requireNotNull(message.usage?.tokensPerSecond), 0.0001)
    }

    @Test
    fun timingColumnStoresCompactJsonNotDuplicateTokens() = runBlocking {
        val usage = TokenUsageUi(
            inputTokens = 1_500,
            outputTokens = 120,
            cachedTokens = 1_000,
            llmMs = 8_400L,
            toolMs = 2_100L,
        )
        saveConversation("c-json", listOf(AgentMessageUi(id = "assistant-1", content = "回答", usage = usage)))
        val raw = rawTimingColumn("assistant-1")
        assertNotNull(raw)
        assertTrue(requireNotNull(raw).startsWith("{"))
        assertTrue(requireNotNull(raw).contains("\"l\":8400"))
        assertFalse(requireNotNull(raw).contains("input"))
    }

    @Test
    fun legacyRowWithoutTimingsStillLoadsTokens() = runBlocking {
        saveConversation(
            "c-legacy",
            listOf(
                AgentMessageUi(
                    id = "assistant-1",
                    content = "旧回答",
                    usage = TokenUsageUi(contextTokens = 9_000, inputTokens = 8_000, outputTokens = 90),
                ),
            ),
        )
        // 迁移前的老行：列是 NULL。
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "UPDATE conversation_messages SET usage_timings_json = NULL WHERE id = 'assistant-1'",
        )
        EtaDatabase.closeForTests()
        val message = reload("c-legacy").single() as AgentMessageUi
        assertEquals(8_000, message.usage?.inputTokens)
        assertEquals(90, message.usage?.outputTokens)
        assertNull(message.usage?.llmMs)
        assertFalse(requireNotNull(message.usage).hasPerformanceStats)
    }

    @Test
    fun corruptTimingJsonFallsBackToTokensOnly() = runBlocking {
        saveConversation(
            "c-corrupt",
            listOf(
                AgentMessageUi(
                    id = "assistant-1",
                    content = "回答",
                    usage = TokenUsageUi(inputTokens = 700, outputTokens = 30, llmMs = 5_000L),
                ),
            ),
        )
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "UPDATE conversation_messages SET usage_timings_json = 'garbage{' WHERE id = 'assistant-1'",
        )
        EtaDatabase.closeForTests()
        val message = reload("c-corrupt").single() as AgentMessageUi
        // 坏列被忽略：token 还在，耗时读回 null，不抛异常。
        assertEquals(700, message.usage?.inputTokens)
        assertEquals(30, message.usage?.outputTokens)
        assertNull(message.usage?.llmMs)
    }

    @Test
    fun messageWithoutTimingsWritesNullOrEmptyColumn() = runBlocking {
        saveConversation(
            "c-plain",
            listOf(AgentMessageUi(id = "assistant-1", content = "纯回答", usage = TokenUsageUi(inputTokens = 10))),
        )
        assertNull(rawTimingColumn("assistant-1"))
    }

    private suspend fun saveConversation(id: String, messages: List<AgentMessageUi>) {
        AgentConversationStore.saveAssistantConversation(
            context = context,
            conversationId = id,
            title = "t",
            messages = messages,
            history = listOf(AgentModelClient.ConversationMessage("user", "提问")),
        )
    }

    private suspend fun reload(id: String): List<AgentMessageUi> {
        val loaded = requireNotNull(AgentConversationStore.loadAssistantConversation(context, id))
        return loaded.messages.map { it as AgentMessageUi }
    }

    private fun rawTimingColumn(messageId: String): String? =
        EtaDatabase.get(context).openHelper.readableDatabase.query(
            "SELECT usage_timings_json FROM conversation_messages WHERE id = ?",
            arrayOf(messageId),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getString(0)
        }
}
