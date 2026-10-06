package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Message
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class RuntimeOrderedDecodeDeliveryTest {
    @Test fun decodingIsSerialOffMainAndApplicationIsFifoOnMain() {
        val decoded = mutableListOf<Int>()
        val applied = mutableListOf<Int>()
        repeat(20) { index -> dispatchRuntimeDecoded {
            assertFalse(Looper.myLooper() == Looper.getMainLooper())
            decoded += index
            val apply: () -> Unit = {
                assertTrue(Looper.myLooper() == Looper.getMainLooper())
                applied += index
            }
            apply
        } }
        shadowOf(RuntimeStreamDispatch.decoder.looper).idle()
        assertEquals((0 until 20).toList(), decoded)
        assertTrue(applied.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(decoded, applied)
    }

    @Test fun binderDeathBoundaryCannotOvertakeEarlierMainReceives() {
        val applied = mutableListOf<String>()
        RuntimeStreamDispatch.main.post { dispatchRuntimeDecoded { { applied += "event" } } }
        Thread { dispatchRuntimeTerminalBarrier { applied += "death" } }.apply { start(); join() }
        RuntimeStreamDispatch.main.post { dispatchRuntimeDecoded { { applied += "later" } } }
        shadowOf(Looper.getMainLooper()).idle() // Receive/barrier enqueue order.
        drain()
        assertEquals(listOf("event", "death", "later"), applied)
    }

    @Test fun interleavedRunsKeepTheirOwnerAndTheirChildIdsUntilResultsAreApplied() {
        val received = mutableListOf<String>()
        fun client(owner: String) = newHandler("ClientHandler",
            arrayOf(Function1::class.java, Function1::class.java, Function0::class.java),
            { event: AgentEvent ->
                assertTrue(Looper.myLooper() == Looper.getMainLooper())
                val child = (event as? AgentEvent.QuestionResolved)?.runId ?: "main"
                received += "$owner/event/$child"
            }, { result: AgentRuntimeWire.RunResult -> received += "$owner/result/${result.runId}" },
            { received += "$owner/ingested" })
        val a = client("a")
        val b = client("b")
        a.handleMessage(event(AgentEvent.RoundStarted(1, 0)))
        b.handleMessage(event(AgentEvent.QuestionResolved("q", "child-b", AgentQuestionStatus.Cancelled)))
        a.handleMessage(Message.obtain().apply { what = AgentRuntimeWire.MSG_REQUEST_INGESTED })
        b.handleMessage(result("b"))
        a.handleMessage(result("a"))
        assertTrue(received.isEmpty())
        drain()
        assertEquals(listOf("a/event/main", "b/event/child-b", "a/ingested", "b/result/b", "a/result/a"), received)
    }

    @Test fun ackReceivedBeforeDecodingCompletesStillSeparatesReplayFromLiveAndResult() {
        val received = mutableListOf<String>()
        val handler = attach(received)
        handler.handleMessage(event(AgentEvent.RoundStarted(1, 0)))
        handler.handleMessage(event(AgentEvent.RoundStarted(2, 0)))
        handler.handleMessage(ack(true))
        handler.handleMessage(event(AgentEvent.RoundStarted(3, 0)))
        handler.handleMessage(result("r"))
        assertTrue(received.isEmpty())
        drain()
        assertEquals(listOf("replay/1,2", "ack/true", "live/3", "result/r"), received)
    }

    @Test fun legacyResultBeforeAckFlushesReplayOnceAndDoesNotReopenTheRun() {
        val received = mutableListOf<String>()
        val handler = attach(received)
        handler.handleMessage(event(AgentEvent.RoundStarted(1, 0)))
        handler.handleMessage(result("r"))
        handler.handleMessage(ack(true))
        handler.handleMessage(event(AgentEvent.RoundStarted(2, 0)))
        drain()
        assertEquals(listOf("replay/1", "result/r"), received)
    }

    @Test fun rejectedAttachDiscardsQueuedReplayAndTerminalCallbacks() {
        val received = mutableListOf<String>()
        val handler = attach(received)
        handler.handleMessage(event(AgentEvent.RoundStarted(1, 0)))
        handler.handleMessage(ack(false))
        handler.handleMessage(event(AgentEvent.RoundStarted(2, 0)))
        handler.handleMessage(result("r"))
        drain()
        assertEquals(listOf("ack/false"), received)
    }

    private fun attach(received: MutableList<String>) = newHandler("AttachHandler",
        arrayOf(Function1::class.java, Function1::class.java, Function1::class.java, Function1::class.java),
        { events: List<AgentEvent> -> received += "replay/" + events.joinToString(",") { (it as AgentEvent.RoundStarted).round.toString() } },
        { event: AgentEvent -> received += "live/${(event as AgentEvent.RoundStarted).round}" },
        { attached: Boolean -> received += "ack/$attached" },
        { result: AgentRuntimeWire.RunResult -> received += "result/${result.runId}" })

    private fun newHandler(name: String, types: Array<Class<*>>, vararg args: Any?): Handler =
        Class.forName("io.github.mangi.eta.agent.runtime.AgentRuntimeClient\$$name")
            .getDeclaredConstructor(*types).apply { isAccessible = true }.newInstance(*args) as Handler

    private fun drain() {
        shadowOf(RuntimeStreamDispatch.decoder.looper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun event(value: AgentEvent) = Message.obtain().apply {
        what = AgentRuntimeWire.MSG_EVENT
        data = AgentRuntimeWire.eventToBundle(value)
    }
    private fun result(runId: String) = Message.obtain().apply {
        what = AgentRuntimeWire.MSG_RESULT
        data = AgentRuntimeWire.toBundle(AgentRuntimeWire.RunResult(runId, true, "done"))
    }
    private fun ack(attached: Boolean) = Message.obtain().apply {
        what = AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE
        data = AgentRuntimeWire.attachRunResponseBundle("r", attached)
    }
}
