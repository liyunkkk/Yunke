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

    @Test fun recoveryToolContractsPreserveSnapshotsAndRequireStoppedHandoffWithoutMediaReplay() {
        val tools = JSONArray().also(ExistingChildTaskTools::appendTo)
        val descriptions = (0 until tools.length()).associate { index ->
            val function = tools.getJSONObject(index).getJSONObject("function")
            function.getString("name") to function.getString("description")
        }
        val continuation = descriptions.getValue("continue_task")
        assertTrue(continuation.contains("configuration snapshot"))
        assertTrue(continuation.contains("replace_task_id must not bypass"))
        assertTrue(continuation.contains("Cancelled tasks cannot be revived"))
        val result = descriptions.getValue("get_task_result")
        assertTrue(result.contains("Parent network failure is not evidence of child model failure"))
        assertTrue(result.contains("Never replay uncertain paid media"))
        val cancel = descriptions.getValue("cancel_task")
        assertTrue(cancel.contains("not proof that execution has stopped"))
        assertTrue(cancel.contains("read its handoff result"))
        assertTrue(descriptions.getValue("manage_agent_workspace").contains("ownership checks and isolated handoff"))
    }
}
