package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeTranscriptTransfer
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentProtectedHistoryPersistenceTest {
    @Test fun protectedLongTextAndArgumentsSurviveStorageSerialization() {
        val text = "evidence".repeat(12_000)
        val arguments = org.json.JSONArray().put(org.json.JSONObject().put("id", "call")
            .put("type", "function").put("function", org.json.JSONObject().put("name", "terminal")
                .put("arguments", org.json.JSONObject().put("command", "x".repeat(40_000)).toString()))).toString()
        val history = listOf(AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = arguments, turnId = "turn"),
            AgentModelClient.ConversationMessage("tool", text, toolCallId = "call", turnId = "turn"))
        assertEquals(history, AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(history)))
    }

    @Test fun protectedOverflowIsExplicitInsteadOfDroppingOldMessages() {
        val history = listOf(AgentModelClient.ConversationMessage("user", "x".repeat(1_100_000), turnId = "turn"))
        assertThrows(ConversationCheckpointTooLargeException::class.java) {
            AgentConversationCodec.encodeConversationCheckpoint(history)
        }
        assertEquals(1_100_000, history.single().content.length)
    }

    @Test fun olderTurnsCanBeDroppedWhileKeepingTheCurrentTurn() {
        val history = listOf(
            AgentModelClient.ConversationMessage("user", "old".repeat(200_000), turnId = "old-turn"),
            AgentModelClient.ConversationMessage("assistant", "reply".repeat(200_000), turnId = "old-turn"),
            AgentModelClient.ConversationMessage("user", "你好", turnId = "new-turn"),
        )
        val encoded = AgentConversationCodec.encodeConversationCheckpoint(history)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)
        assertTrue(encoded.length <= AgentConversationCodec.MAX_CONVERSATION_CHECKPOINT_CHARS)
        assertTrue(decoded.any { it.content == "你好" && it.turnId == "new-turn" })
        assertTrue(decoded.none { it.turnId == "old-turn" && it.content.length > 10_000 })
    }

    @Test fun compactionHistoryTravelsOutOfBandWithItsFullTurnIdentity() {
        val text = "original".repeat(12_000)
        val history = listOf(AgentModelClient.ConversationMessage("user", text, turnId = "run-1"))
        AgentRuntimeTranscriptTransfer.prepare(RuntimeEnvironment.getApplication(), history).use { prepared ->
            val event = AgentEvent.ContextCompacted(2, true, 10, 1, history = history)
            val bundle = AgentRuntimeWire.eventToBundle(event, prepared.descriptor)
            assertFalse(bundle.containsKey("history_json"))
            val decoded = AgentRuntimeWire.eventFromBundle(bundle) as AgentEvent.ContextCompacted
            assertEquals(history, decoded.history)
        }
    }

    @Test fun manualCompressionModelSurvivesWireAndQueueWithoutChangingConversation() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1",
            apiKey = "test", model = "summary", systemPrompt = "",
            openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        val bundle = AgentRuntimeWire.compactBundle("run", 0, compressModelConfig = config)
        bundle.putString("compact_strategy", "preserve_turn")
        val controller = io.github.mangi.eta.agent.runtime.AgentRunController()
        controller.requestCompact(AgentRuntimeWire.compactKeepRecentFromBundle(bundle),
            AgentRuntimeWire.compactModelConfigFromBundle(bundle))
        assertEquals(config, controller.takePendingCompact()!!.compressModelConfig)
        assertNull(AgentRuntimeWire.compactModelConfigFromBundle(AgentRuntimeWire.compactBundle("run")))
    }

    @Test fun blockedReasonRoundTrip() {
        val event = AgentEvent.ContextCompacted(1, false, 4, 4, blocked = true, reason = "original retained")
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
    }
}
