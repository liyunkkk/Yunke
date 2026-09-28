package io.github.mangi.eta.agent.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容量超限必须是可识别的类型，而不是"任何 Exception"。
 * 过去 store 一律 catch，于是 OOM 与序列化故障也会触发"清空 turnId 重编"，
 * 保护静默失效，被保护回合之前的消息被悄悄丢掉。
 */
class AgentCheckpointCapacityTest {

    private fun message(role: String, content: String, turnId: String = "") =
        AgentModelClient.ConversationMessage(role = role, content = content, turnId = turnId)

    @Test
    fun `an oversized protected turn raises the dedicated capacity failure`() {
        val history = listOf(
            message("user", "x".repeat(4_000)),
            message("assistant", "y".repeat(2_000_000), turnId = "turn-1"),
        )
        val failure = runCatching { AgentConversationCodec.encodeConversationCheckpoint(history) }
            .exceptionOrNull()
        assertTrue(
            "期望 ConversationCheckpointTooLargeException，实际 ${failure?.let { it::class.simpleName }}",
            failure is ConversationCheckpointTooLargeException,
        )
        assertEquals(AgentConversationCodec.CHECKPOINT_TOO_LARGE_MESSAGE, failure!!.message)
    }

    @Test
    fun `dropping the protection lets the same history encode`() {
        val history = listOf(
            message("user", "x".repeat(4_000)),
            message("assistant", "y".repeat(2_000_000), turnId = "turn-1"),
        )
        val encoded = AgentConversationCodec.encodeConversationCheckpoint(
            history.map { it.copy(turnId = "") }
        )
        assertTrue(encoded.length <= AgentConversationCodec.MAX_CONVERSATION_CHECKPOINT_CHARS)
        assertTrue(encoded.startsWith("["))
    }

    @Test
    fun `a history within the cap keeps every message and its protection`() {
        val history = listOf(
            message("user", "hello"),
            message("assistant", "hi", turnId = "turn-1"),
        )
        val decoded = AgentConversationCodec.decodeTranscript(
            AgentConversationCodec.encodeConversationCheckpoint(history)
        )
        assertEquals(2, decoded.size)
        assertEquals("turn-1", decoded.last().turnId)
    }

    @Test
    fun `the capacity failure is an IllegalStateException so existing callers still see it`() {
        assertTrue(
            IllegalStateException::class.java.isAssignableFrom(
                ConversationCheckpointTooLargeException::class.java
            )
        )
    }

    @Test
    fun `the store only degrades on the capacity failure`() {
        val candidates = listOf(
            File("src/main/kotlin/io/github/mangi/eta/ui/app/AgentConversationStore.kt"),
            File("app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentConversationStore.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("AgentConversationStore.kt 未找到", file != null)
        val start = file!!.readText().indexOf("private fun encodeCheckpoint(")
        assertTrue("未找到 encodeCheckpoint", start >= 0)
        val body = file.readText().substring(start, start + 1_200)
        assertTrue(
            "只能对容量超限降级",
            body.contains("catch (tooLarge: ConversationCheckpointTooLargeException)"),
        )
        assertFalse("不得再捕获所有异常", body.contains("catch (_: Exception)"))
    }
}
