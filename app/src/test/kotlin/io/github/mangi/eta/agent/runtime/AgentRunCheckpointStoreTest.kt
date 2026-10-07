package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunCheckpointStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun recorderCoalescesTextAndPreservesVisibleToolTrace() {
        var nowNanos = 0L
        val request = request("run-1")
        val recorder = AgentRunCheckpointRecorder.create(
            context = context,
            request = request,
            nanoTime = { nowNanos },
        )!!

        recorder.accept(textDelta("你"))
        nowNanos = 300_000_000L
        recorder.accept(textDelta("好"))
        recorder.accept(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
                index = 1,
                deltaChars = 18,
                delta = "{\"secret\":\"value\"}",
            )
        )
        recorder.accept(
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "uptime",
            )
        )
        recorder.accept(
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                resultSummary = "完成",
                imageCount = 0,
                imageBytes = 0,
                success = true,
            )
        )

        val restored = AgentRunCheckpointStore.list(context).single()
        val delta = restored.events.filterIsInstance<AgentEvent.AssistantBlockDelta>().single()
        assertEquals("你好", delta.delta)
        assertEquals(2, delta.deltaChars)
        val toolStarted = restored.events.filterIsInstance<AgentEvent.ToolStarted>().single()
        assertEquals("执行命令 · Android · root", toolStarted.argsPreview)
        assertEquals("uptime", toolStarted.command)
        val toolFinished = restored.events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("完成", toolFinished.resultSummary)
        assertFalse(restored.events.any { event ->
            event is AgentEvent.AssistantBlockDelta &&
                event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL
        })

        recorder.seal()
        assertEquals(
            "run-1",
            AgentRunCheckpointStore.list(context).single().runId,
        )
        AgentRunCheckpointStore.remove(context, "run-1")
        assertTrue(
            AgentRunCheckpointStore.list(context).isEmpty()
        )
    }

    @Test
    fun checkpointsRemainVisibleToAReplacementUiInTheSameProcess() {
        val request = request("run-2")
        assertTrue(
            AgentRunCheckpointStore.start(
                context = context,
                request = request,
                ownerInstanceId = "old-process",
            )
        )
        AgentRunCheckpointStore.append(
            context = context,
            runId = request.runId,
            sortIndex = 0,
            event = AgentEvent.RunStarted(0, 0, 1, false),
        )

        assertEquals(
            "run-2",
            AgentRunCheckpointStore.list(context).single().runId,
        )
    }

    @Test
    fun discardRemovesCheckpointWithoutFlushingBufferedText() {
        val recorder = AgentRunCheckpointRecorder.create(
            context = context,
            request = request("run-discard"),
            nanoTime = { 0L },
        )!!
        recorder.accept(textDelta("不应保留"))

        recorder.discard()

        assertTrue(
            AgentRunCheckpointStore.list(context).isEmpty()
        )
    }

    @Test
    fun oversizedEventUsesValidSafeProjectionAndMarksRecoveryIncomplete() {
        val runId = "run-large-event"
        assertTrue(
            AgentRunCheckpointStore.start(
                context = context,
                request = request(runId),
            )
        )

        AgentRunCheckpointStore.append(
            context = context,
            runId = runId,
            sortIndex = 0,
            event = AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
                deltaChars = 80_000,
                delta = "界".repeat(80_000),
            ),
        )

        val storedJson = runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context).runtimeRunDao()
                .inFlightEvents(runId = runId, limit = 8, offset = 0)
                .single()
                .eventJson
        }
        val checkpoint = AgentRunCheckpointStore.list(context).single()
        val restoredDelta = checkpoint.events.single() as AgentEvent.AssistantBlockDelta

        assertTrue(checkpoint.recoveryIncomplete)
        assertEquals(0, checkpoint.skippedEventCount)
        assertEquals("", restoredDelta.delta)
        assertTrue(AgentEventJsonCodec.isCheckpointDegraded(storedJson))
        assertTrue(storedJson.toByteArray(Charsets.UTF_8).size <= AgentEventJsonCodec.MAX_CHECKPOINT_EVENT_BYTES)
        assertEquals(restoredDelta, AgentEventJsonCodec.decode(storedJson))
    }

    @Test
    fun checkpointListReadsEventsAcrossSmallPagesInSortOrder() {
        val runId = "run-paged"
        assertTrue(
            AgentRunCheckpointStore.start(
                context = context,
                request = request(runId),
            )
        )

        repeat(17) { index ->
            AgentRunCheckpointStore.append(
                context = context,
                runId = runId,
                sortIndex = index,
                event = AgentEvent.RunStarted(index, index + 1, index + 2, false),
            )
        }

        val checkpoint = AgentRunCheckpointStore.list(context).single()
        assertEquals(17, checkpoint.events.size)
        assertEquals(
            (0 until 17).toList(),
            checkpoint.events.map { (it as AgentEvent.RunStarted).initialImages },
        )
    }

    private fun request(runId: String): AgentRuntimeWire.RunRequest =
        AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "测试",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = "conversation-1",
            ),
        )

    private fun textDelta(text: String): AgentEvent.AssistantBlockDelta =
        AgentEvent.AssistantBlockDelta(
            round = 1,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = 0,
            deltaChars = text.length,
            delta = text,
        )
}
