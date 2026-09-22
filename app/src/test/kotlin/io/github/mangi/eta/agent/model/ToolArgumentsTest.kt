package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolArgumentsTest {
    @Test fun terminalMissingOrNullFieldsAreNotRevived() {
        val streamed = """{"task":"edit","role":"implementation","project":"/workspace/Eta","agent_id":"worker-1"}"""
        val terminal = """{"task":"edit"}"""
        assertEquals(terminal, ToolArguments.merge(streamed, terminal))
        val parsedTerminal = JSONObject(ToolArguments.merge(streamed, JSONObject().put("task", "edit")))
        assertEquals("edit", parsedTerminal.getString("task"))
        assertFalse(parsedTerminal.has("role"))
        assertFalse(parsedTerminal.has("project"))
        assertFalse(parsedTerminal.has("agent_id"))

        val explicitNull = """{"task":"edit","role":null,"project":null}"""
        assertEquals(explicitNull, ToolArguments.merge(streamed, explicitNull))
        val parsedNull = JSONObject(ToolArguments.merge(streamed, explicitNull))
        assertTrue(parsedNull.has("role") && parsedNull.isNull("role"))
        assertTrue(parsedNull.has("project") && parsedNull.isNull("project"))
        assertFalse(parsedNull.has("agent_id"))
    }

    @Test fun invalidTerminalIsNotCoveredByExistingJsonAndBlankFallsBack() {
        val streamed = """{"task":"edit","role":"implementation"}"""
        assertEquals("{not-json", ToolArguments.merge(streamed, "{not-json"))
        assertEquals("not-json", ToolArguments.merge(streamed, "not-json"))
        assertEquals(streamed, ToolArguments.merge(streamed, ""))
        assertEquals(streamed, ToolArguments.merge(streamed, "   "))
        assertEquals("null", ToolArguments.merge(streamed, JSONObject.NULL))
        assertEquals(streamed, ToolArguments.merge(streamed, null))
    }

    @Test fun objectArgumentsAreAcceptedWhenNothingWasStreamed() {
        val incoming = JSONObject().put("task", "edit").put("role", "implementation")
        assertEquals(incoming.toString(), ToolArguments.merge("", incoming))
        assertEquals("implementation", JSONObject(ToolArguments.merge("", incoming)).getString("role"))
    }
}
