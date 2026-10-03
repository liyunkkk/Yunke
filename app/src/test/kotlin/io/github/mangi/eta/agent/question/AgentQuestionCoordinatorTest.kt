package io.github.mangi.eta.agent.question

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentQuestionCoordinatorTest {
    @Test fun cancelWakesWaiterWithoutAnAnswer() {
        val controller = AgentRunController()
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val coordinator = AgentQuestionCoordinator(controller) { events += it }
        val request = request()
        val answer = AtomicReference<AgentQuestionAnswer?>()
        val waiter = thread(isDaemon = true) { answer.set(coordinator.awaitAnswer(request)) }
        assertEquals("QUESTION_FOREIGN", coordinator.submitAnswer("conv", "run", "q1", "someone-else", option()).code)
        controller.cancel()
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertNull(answer.get())
        val requestedAt = events.indexOfFirst { it is AgentEvent.QuestionRequested }
        val resolvedAt = events.indexOfFirst { it is AgentEvent.QuestionResolved }
        assertTrue(requestedAt >= 0 && requestedAt < resolvedAt)
        val resolved = events.filterIsInstance<AgentEvent.QuestionResolved>().single()
        assertEquals(AgentQuestionStatus.Cancelled, resolved.status)
        assertNull(resolved.answer)
        assertEquals("QUESTION_LATE", coordinator.submitAnswer("conv", "run", "q1", "tool-1", option()).code)
    }

    @Test fun foreignIdDoesNotConsumeThePendingQuestion() {
        val controller = AgentRunController()
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val coordinator = AgentQuestionCoordinator(controller) { events += it }
        val answer = AtomicReference<AgentQuestionAnswer?>()
        val waiter = thread(isDaemon = true) { answer.set(coordinator.awaitAnswer(request())) }
        val foreign = awaitCode(coordinator, "QUESTION_FOREIGN") {
            coordinator.submitAnswer("other-conv", "run", "q1", "tool-1", option())
        }
        assertFalse(foreign.accepted)
        assertTrue(events.none { it is AgentEvent.QuestionResolved })
        val accepted = coordinator.submitAnswer("conv", "run", "q1", "tool-1", option())
        assertTrue(accepted.accepted)
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertEquals("yes", answer.get()?.optionId)
        assertEquals(AgentQuestionStatus.Answered, events.filterIsInstance<AgentEvent.QuestionResolved>().single().status)
    }

    @Test fun duplicateAndConcurrentSubmitsAcceptExactlyOnce() {
        val controller = AgentRunController()
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val coordinator = AgentQuestionCoordinator(controller) { events += it }
        val answer = AtomicReference<AgentQuestionAnswer?>()
        val waiter = thread(isDaemon = true) { answer.set(coordinator.awaitAnswer(request())) }
        awaitCode(coordinator, "QUESTION_FOREIGN") {
            coordinator.submitAnswer("conv", "run", "q1", "not-this", option())
        }
        val gate = CountDownLatch(1)
        val receipts = Collections.synchronizedList(mutableListOf<AgentQuestionReceipt>())
        val submitters = List(2) {
            thread(isDaemon = true) {
                gate.await()
                receipts += coordinator.submitAnswer("conv", "run", "q1", "tool-1", option())
            }
        }
        gate.countDown()
        submitters.forEach { it.join(2_000) }
        assertEquals(1, receipts.count { it.accepted })
        assertEquals(1, receipts.count { it.code == "QUESTION_DUPLICATE" })
        waiter.join(2_000)
        assertEquals("option", answer.get()?.kind)
        assertEquals(1, events.filterIsInstance<AgentEvent.QuestionResolved>().count())
        val third = coordinator.submitAnswer("conv", "run", "q1", "tool-1", option())
        assertEquals("QUESTION_DUPLICATE", third.code)
        assertFalse(third.accepted)
    }

    @Test fun resolvedIsPublishedBeforeTheWaiterContinues() {
        val controller = AgentRunController()
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val returned = java.util.concurrent.atomic.AtomicBoolean(false)
        val resolvedWhileWaiting = java.util.concurrent.atomic.AtomicBoolean(false)
        val coordinator = AgentQuestionCoordinator(controller) { event ->
            events += event
            if (event is AgentEvent.QuestionResolved && event.status == AgentQuestionStatus.Answered) {
                resolvedWhileWaiting.set(!returned.get())
            }
        }
        val answer = AtomicReference<AgentQuestionAnswer?>()
        val waiter = thread(isDaemon = true) {
            answer.set(coordinator.awaitAnswer(request()))
            returned.set(true)
        }
        awaitRequested(events)
        assertTrue(coordinator.submitAnswer("conv", "run", "q1", "tool-1", option()).accepted)
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertTrue(resolvedWhileWaiting.get())
        assertEquals("yes", answer.get()?.optionId)
    }

    @Test fun cancelledFlagRejectsSubmitBeforeTheCancelResourceRuns() {
        val controller = AgentRunController()
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val coordinator = AgentQuestionCoordinator(controller) { events += it }
        val earlyReceipt = AtomicReference<AgentQuestionReceipt?>()
        controller.register {
            earlyReceipt.set(coordinator.submitAnswer("conv", "run", "q1", "tool-1", option()))
        }
        val answer = AtomicReference<AgentQuestionAnswer?>()
        val waiter = thread(isDaemon = true) { answer.set(coordinator.awaitAnswer(request())) }
        awaitRequested(events)
        controller.cancel()
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertNull(answer.get())
        assertEquals("QUESTION_LATE", earlyReceipt.get()?.code)
        assertFalse(earlyReceipt.get()?.accepted == true)
        assertEquals(AgentQuestionStatus.Cancelled, events.filterIsInstance<AgentEvent.QuestionResolved>().single().status)
    }

    @Test fun invalidAnswerStaysWaitingUntilAValidOne() {
        val controller = AgentRunController()
        val events = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val coordinator = AgentQuestionCoordinator(controller) { events += it }
        val answer = AtomicReference<AgentQuestionAnswer?>()
        val waiter = thread(isDaemon = true) { answer.set(coordinator.awaitAnswer(request())) }
        val rejected = awaitNotPending(coordinator) {
            coordinator.submitAnswer("conv", "run", "q1", "tool-1", AgentQuestionAnswer(kind = "other", otherText = ""))
        }
        assertFalse(rejected.accepted)
        assertTrue(waiter.isAlive)
        assertTrue(events.none { it is AgentEvent.QuestionResolved })
        assertTrue(coordinator.submitAnswer("conv", "run", "q1", "tool-1", option()).accepted)
        waiter.join(2_000)
        assertFalse(waiter.isAlive)
        assertEquals("yes", answer.get()?.optionId)
    }

    private fun request() = AgentQuestionRequest(
        questionId = "q1",
        conversationId = "conv",
        runId = "run",
        toolCallId = "tool-1",
        title = "选择",
        question = "继续吗？",
        options = listOf(AgentQuestionOption("yes", "是"), AgentQuestionOption("no", "否")),
    )

    private fun option() = AgentQuestionAnswer(kind = "option", optionId = "yes")

    private fun awaitRequested(events: MutableList<AgentEvent>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (events.any { it is AgentEvent.QuestionRequested }) return
            Thread.yield()
        }
        assertTrue(events.any { it is AgentEvent.QuestionRequested })
    }

    private fun awaitCode(
        coordinator: AgentQuestionCoordinator,
        code: String,
        submit: () -> AgentQuestionReceipt,
    ): AgentQuestionReceipt {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var latest = AgentQuestionReceipt(false, "QUESTION_NOT_PENDING", "")
        while (System.nanoTime() < deadline) {
            latest = submit()
            if (latest.code != "QUESTION_NOT_PENDING") return latest
            Thread.yield()
        }
        return latest.also { assertEquals(code, it.code) }
    }

    private fun awaitNotPending(
        coordinator: AgentQuestionCoordinator,
        submit: () -> AgentQuestionReceipt,
    ): AgentQuestionReceipt = awaitCode(coordinator, "QUESTION_NOT_PENDING", submit).also {
        assertTrue(it.code != "QUESTION_NOT_PENDING")
    }
}
