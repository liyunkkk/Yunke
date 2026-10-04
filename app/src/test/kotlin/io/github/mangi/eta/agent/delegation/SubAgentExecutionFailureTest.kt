package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentInvalidToolArgumentsGuard
import io.github.mangi.eta.agent.model.AgentModelFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentExecutionFailureTest {
    @Test fun classifiesOnlyTypedRepairExhaustionWithSafeNextStep() {
        val failure = SubAgentExecutionFailure.find(
            AgentModelFailure(
                AgentInvalidToolArgumentsGuard.STOP_CODE,
                retryable = false,
                message = "secret raw arguments / provider text",
            )
        )

        requireNotNull(failure)
        assertEquals(AgentInvalidToolArgumentsGuard.STOP_CODE, failure.code)
        assertTrue(failure.nextStep.contains("schema"))
        assertTrue(failure.nextStep.contains("inspect"))
        assertTrue(failure.nextStep.contains("不要重放"))
        assertFalse(failure.toJson().toString().contains("secret raw arguments"))
        assertFalse(failure.toJson().toString().contains("provider text"))
    }

    @Test fun doesNotInferFromObfuscatedClassNamesMessagesOrWrappedFailures() {
        assertNull(SubAgentExecutionFailure.find(IllegalStateException("INVALID_TOOL_ARGUMENTS_REPAIR_EXHAUSTED")))
        assertNull(SubAgentExecutionFailure.find(IllegalStateException("to")))
        assertNull(SubAgentExecutionFailure.find(
            RuntimeException(
                "wrapper",
                AgentModelFailure(AgentInvalidToolArgumentsGuard.STOP_CODE, retryable = false, message = "raw"),
            )
        ))
    }

    @Test fun otherModelAndControlFlowFailuresAreNotReclassified() {
        for (code in listOf("HTTP_503", "CONTEXT_WINDOW_EXCEEDED", "INVALID_TOOL_ARGUMENTS", "UNKNOWN")) {
            assertNull(SubAgentExecutionFailure.find(AgentModelFailure(code, false, "raw text")))
        }
        assertNull(SubAgentExecutionFailure.find(io.github.mangi.eta.agent.runtime.AgentRunCancelledException()))
        assertNull(SubAgentExecutionFailure.find(java.util.concurrent.CancellationException()))
        assertNull(SubAgentExecutionFailure.find(InterruptedException()))
        assertNull(SubAgentExecutionFailure.find(SubAgentContextLimitException()))
        assertNull(SubAgentExecutionFailure.find(org.json.JSONException("invalid action")))
        assertNull(SubAgentExecutionFailure.find(AssertionError("not an Exception")))
    }

    @Test fun outputIsFixedAndContainsOnlyAllowlistedFieldsRegardlessOfExceptionText() {
        fun output(message: String) = requireNotNull(SubAgentExecutionFailure.find(
            AgentModelFailure(AgentInvalidToolArgumentsGuard.STOP_CODE, false, message,
                IllegalArgumentException("private cause text"))
        )).toJson()
        val result = output("private path, arguments, URL and schema rejection detail")
        assertEquals(output("different sensitive detail").toString(), result.toString())
        assertEquals(setOf("ok", "code", "message", "next_step"), result.keys().asSequence().toSet())
        assertFalse(result.getBoolean("ok"))
        assertTrue(result.getString("message").contains("任务未完成"))
        assertTrue(result.getString("next_step").contains("不要据此替换"))
        assertTrue(result.getString("next_step").contains("自动重试"))
        assertFalse(result.toString().contains("private"))
        assertFalse(result.has("can_replace"))
    }
}
