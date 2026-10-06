package io.github.mangi.eta.agent.runtime

import android.os.Bundle
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeHistorySnapshotTest {
    @Test fun tokenSurvivesMessengerAndReplayAndLegacyHasNoToken() {
        val event = AgentEvent.RoundStarted(3, 9, "snapshot-id")
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        assertEquals(event, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event)))
        val legacy = AgentRuntimeWire.eventToBundle(event).apply { remove("history_snapshot_id") }
        assertEquals(AgentEvent.RoundStarted(3, 9), AgentRuntimeWire.eventFromBundle(legacy))
    }

    @Test fun snapshotCodecDoesNotTruncateTextAndRejectsInvalidBodies() {
        val history = listOf(ConversationMessage("user", "x".repeat(100000), turnId = "run"))
        assertEquals(history, AgentConversationCodec.decodeHistorySnapshot(AgentConversationCodec.encodeHistorySnapshot(history)))
        assertThrows(Exception::class.java) { AgentConversationCodec.decodeHistorySnapshot("[]") }
        assertThrows(Exception::class.java) { AgentConversationCodec.decodeHistorySnapshot("broken") }
        assertThrows(Exception::class.java) { AgentConversationCodec.decodeHistorySnapshot("[{\"role\":\"unknown\",\"content\":\"x\"}]") }
    }

    @Test fun sameProcessDescriptorRemainsReadableUntilExplicitRelease() {
        val context = RuntimeEnvironment.getApplication()
        val history = listOf(ConversationMessage("user", "task", turnId = "run"),
            ConversationMessage("assistant", "x".repeat(100000), turnId = "run"))
        val prepared = AgentRuntimeHistoryTransfer.prepareSnapshot(context, history)
        val bundle = Bundle().apply { putParcelable(AgentRuntimeWire.KEY_HISTORY_FD, prepared.descriptor) }
        // Same-process Messenger shares the descriptor object: sender must not close it yet.
        try {
            assertEquals(history, AgentRuntimeHistoryTransfer.readSnapshotFromBundle(bundle))
        } finally { prepared.close() }
    }
}
