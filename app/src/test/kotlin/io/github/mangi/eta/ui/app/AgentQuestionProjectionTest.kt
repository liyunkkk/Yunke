package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.question.*
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentQuestionProjectionTest {
    private fun request(conversation: String = "chat", run: String = "run", q: String = "q", call: String = "call") =
        AgentQuestionRequest(q, conversation, run, call, "Decide", "Choose one", listOf(
            AgentQuestionOption("a", "Original A", "description"), AgentQuestionOption("b", "B")), recommendedOptionId = "a")
    private fun card(messages: List<AgentChatMessageUi>) = messages.filterIsInstance<AgentQuestionMessageUi>().single()
    private fun selected() = AgentQuestionAnswer("option", "b", note = "note")

    @Test fun recommendedOptionIsNotAutomaticallySelected() {
        val m = card(AgentQuestionProjection.requested("chat", "run", request(), emptyList()))
        assertNull(m.selectedOptionId)
        assertFalse(AgentQuestionCodec.validateAnswer(m.request, AgentQuestionProjection.draftAnswer(m)).accepted)
    }
    @Test fun liveReplayIsIdempotentAndKeepsDraft() {
        val initial = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", note = "draft", otherText = "retained")
        val once = AgentQuestionProjection.requested("chat", "run", request(), listOf(initial), replaying = true)
        val twice = AgentQuestionProjection.requested("chat", "run", request(), once, replaying = true)
        assertEquals(once, twice)
        assertEquals("b", card(twice).selectedOptionId)
        assertEquals("draft", card(twice).note)
        assertEquals("retained", card(twice).otherText)
    }
    @Test fun requestRejectsOtherConversationAndRun() {
        val original = listOf<AgentChatMessageUi>(AgentMessageUi("text", "evidence"))
        assertEquals(original, AgentQuestionProjection.requested("different", "run", request(), original))
        assertEquals(original, AgentQuestionProjection.requested("chat", "different", request(), original))
    }
    @Test fun sealedRunDoesNotCreateNewQuestion() {
        assertTrue(AgentQuestionProjection.requested("chat", "run", request(), emptyList(), acceptNew = false).isEmpty())
    }
    @Test fun foreignAndAmbiguousResolutionsCannotChangeCard() {
        val first = AgentQuestionMessageUi("id", request())
        val event = AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Answered, selected())
        assertEquals(listOf(first), AgentQuestionProjection.resolved("foreign", "run", event, listOf(first)))
        assertEquals(listOf(first), AgentQuestionProjection.resolved("chat", "run", event.copy(runId = "other"), listOf(first)))
        val ambiguous = listOf(first, first.copy(id = "second", request = request(call = "different")))
        assertEquals(ambiguous, AgentQuestionProjection.resolved("chat", "run", event, ambiguous))
    }
    @Test fun resolutionValidatesAnswerAndIsIdempotent() {
        val initial = listOf<AgentChatMessageUi>(AgentQuestionMessageUi("id", request()))
        val invalid = AgentEvent.QuestionResolved("q", "run", AgentQuestionStatus.Answered, AgentQuestionAnswer("option", "fake"))
        assertEquals(initial, AgentQuestionProjection.resolved("chat", "run", invalid, initial))
        val event = invalid.copy(answer = selected())
        val resolved = AgentQuestionProjection.resolved("chat", "run", event, initial)
        assertEquals(AgentQuestionStatus.Answered, card(resolved).status)
        assertEquals(selected(), card(resolved).answer)
        assertEquals(resolved, AgentQuestionProjection.resolved("chat", "run", event, resolved))
    }
    @Test fun reservationAckDoesNotInventAnswer() {
        val m = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", submitting = true)
        val result = AgentQuestionProjection.acknowledged(m, selected(), AgentQuestionReceipt(true, "QUESTION_RESERVED"))
        assertEquals(AgentQuestionStatus.Waiting, result.status)
        assertTrue(result.submitting)
        assertNull(result.answer)
    }
    @Test fun ackTimeoutKeepsDraftAndAllowsRetry() {
        val m = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", note = "draft", submitting = true)
        val result = AgentQuestionProjection.acknowledged(m, selected(), AgentQuestionReceipt(false, "QUESTION_ACK_TIMEOUT"))
        assertEquals(AgentQuestionStatus.Waiting, result.status)
        assertFalse(result.submitting)
        assertEquals("b", result.selectedOptionId)
        assertEquals("draft", result.note)
    }
    @Test fun lateAckCannotResurrectInterruptedCard() {
        val m = AgentQuestionMessageUi("id", request(), status = AgentQuestionStatus.Interrupted)
        assertEquals(m, AgentQuestionProjection.acknowledged(m, selected(), AgentQuestionReceipt(true, "QUESTION_RESERVED")))
    }
    @Test fun resolvedBeforeAckRemainsActualAnswer() {
        val actual = selected().copy(note = "runtime")
        val m = AgentQuestionMessageUi("id", request(), status = AgentQuestionStatus.Answered, answer = actual)
        assertEquals(actual, AgentQuestionProjection.acknowledged(m, selected(), AgentQuestionReceipt(true, "QUESTION_RESERVED")).answer)
    }
    @Test fun definitiveRuntimeLossInterruptsOnlyOwningRunAndPreservesDraft() {
        val m = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", submitting = true, note = "draft")
        val other = m.copy(id = "other", request = request(run = "other"))
        val result = AgentQuestionProjection.interruptWaiting("run", listOf(m, other))
        val interrupted = result.first() as AgentQuestionMessageUi
        assertEquals(AgentQuestionStatus.Interrupted, interrupted.status)
        assertFalse(interrupted.submitting)
        assertEquals("draft", interrupted.note)
        assertEquals(other, result.last())
    }
    @Test fun persistenceRoundTripsRequestDraftStatusButNotTransientSubmission() {
        val original = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", answerKind = "other",
            otherText = "custom", note = "persisted", submitting = true, error = "transient")
        val decoded = AgentQuestionPersistence.decode("id", "chat", AgentQuestionPersistence.encode(original))!!
        assertEquals(original.copy(submitting = false, error = null), decoded)
    }
    @Test fun answeredPersistenceUsesValidatedOriginalOption() {
        val m = AgentQuestionMessageUi("id", request(), AgentQuestionStatus.Answered, selected())
        val decoded = AgentQuestionPersistence.decode("id", "chat", AgentQuestionPersistence.encode(m))!!
        assertEquals(m, decoded)
        assertEquals("B", AgentQuestionCodec.resultJson(decoded.request, decoded.answer!!).getJSONObject("selected_option").getString("label"))
    }
    @Test fun storageRejectsForeignOwnershipAndFutureVersions() {
        val raw = AgentQuestionPersistence.encode(AgentQuestionMessageUi("id", request()))
        assertNull(AgentQuestionPersistence.decode("id", "foreign", raw))
        assertNull(AgentQuestionPersistence.decode("id", "chat", JSONObject(raw).put("version", 2).toString()))
    }
    @Test fun corruptAnsweredHistoryBecomesInterruptedNotFakeSuccess() {
        val raw = JSONObject(AgentQuestionPersistence.encode(AgentQuestionMessageUi("id", request())))
            .put("status", "Answered")
        assertEquals(AgentQuestionStatus.Interrupted, AgentQuestionPersistence.decode("id", "chat", raw.toString())!!.status)
    }
    @Test fun draftAnswerFiltersInactiveFieldsAndForbiddenNote() {
        val m = AgentQuestionMessageUi("id", request().copy(allowNote = false), selectedOptionId = "a", otherText = "retained", note = "forbidden")
        assertEquals(AgentQuestionAnswer("option", "a"), AgentQuestionProjection.draftAnswer(m))
        assertEquals(AgentQuestionAnswer("delegate"), AgentQuestionProjection.draftAnswer(m.copy(answerKind = "delegate")))
    }
    @Test fun historyReplayDoesNotReviveOrMoveAnInterruptedCard() {
        val m = AgentQuestionMessageUi("id", request(), status = AgentQuestionStatus.Interrupted, note = "draft")
        val messages = listOf<AgentChatMessageUi>(m, AgentMessageUi("after", "later evidence"))
        assertEquals(messages, AgentQuestionProjection.requested("chat", "run", request(), messages, replaying = true))
        assertEquals(messages, AgentQuestionProjection.requested("chat", "run", request(), messages, acceptNew = false, replaying = true))
    }
    @Test fun authoritativeSnapshotResolvesButForeignSnapshotIsIgnored() {
        val m = AgentQuestionMessageUi("id", request(), submitting = true)
        val snapshot = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Answered, selected())
        assertEquals(m, AgentQuestionProjection.reconcile(m, snapshot.copy(toolCallId = "foreign")))
        val resolved = AgentQuestionProjection.reconcile(m, snapshot)
        assertEquals(AgentQuestionStatus.Answered, resolved.status)
        assertEquals(selected(), resolved.answer)
        assertFalse(resolved.submitting)
    }
    @Test fun pendingSnapshotOrUnknownTransportUnlocksOnlySubmissionNotUserWait() {
        val m = AgentQuestionMessageUi("id", request(), selectedOptionId = "b", note = "draft", submitting = true)
        val pending = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Waiting)
        assertEquals(m.copy(submitting = false), AgentQuestionProjection.reconcile(m, pending))
        val unknown = AgentQuestionProjection.reconcile(m, null)
        assertEquals(AgentQuestionStatus.Waiting, unknown.status)
        assertEquals("draft", unknown.note)
        assertFalse(unknown.submitting)
    }
    @Test fun authoritativeDefinitiveLossDisablesCardWithoutInventingAnswer() {
        val m = AgentQuestionMessageUi("id", request(), submitting = true)
        val ended = AgentQuestionSnapshot("chat", "run", "q", "call", AgentQuestionStatus.Interrupted)
        val result = AgentQuestionProjection.reconcile(m, ended)
        assertEquals(AgentQuestionStatus.Interrupted, result.status)
        assertNull(result.answer)
        assertFalse(result.submitting)
    }

}
