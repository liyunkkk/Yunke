package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCallValidator
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ExistingChildTaskToolsTest {
    @Test fun disabledDelegationStillExposesOwnerTaskRecoveryWithoutNewDispatch() {
        val tools = JSONArray().also(ExistingChildTaskTools::appendTo)
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(setOf("get_task_result", "manage_agent_workspace", "continue_task", "cancel_task", "supervise_task"), names.toSet())
        assertFalse("delegate_task" in names)
        val validator = AgentToolCallValidator(tools)
        assertNull(validator.validate(AgentModelClient.ToolCall("id", "get_task_result", "{}")))
        assertNull(validator.validate(AgentModelClient.ToolCall("id", "continue_task", "{\"task_id\":\"old-task\"}")))
        assertNotNull(validator.validate(AgentModelClient.ToolCall("id", "cancel_task", "{}")))
    }
}
