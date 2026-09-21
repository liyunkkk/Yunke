package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentDelegationArgumentRepairTest {
    @Test fun repairsAreBoundedByRoundNotNumberOfBadCallsAndNeverFabricateTask() {
        val repair = AgentDelegationArgumentRepair()
        repeat(5) {
            val result = JSONObject(repair.reject("missing task", 1).content)
            assertEquals(1, result.getInt("repair_attempt"))
            assertFalse(result.getBoolean("executed"))
            assertFalse(result.getBoolean("task_created"))
            assertFalse(result.has("task"))
        }
        assertFalse(repair.disabled)
        repair.reject("missing task", 2)
        assertFalse(repair.disabled)
        val final = JSONObject(repair.reject("missing task", 3).content)
        assertTrue(repair.disabled)
        assertEquals("DELEGATION_ARGUMENT_REPAIR_EXHAUSTED", final.getString("code"))
        val catalog = JSONArray().put(JSONObject().put("function", JSONObject().put("name", "delegate_task")))
            .put(JSONObject().put("function", JSONObject().put("name", "get_task_result")))
        assertEquals(1, repair.availableTools(catalog).length())
        assertEquals("get_task_result", repair.availableTools(catalog).getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals(2, catalog.length())
    }
    @Test fun preflightRejectsMissingNullAndBlankTasks() {
        val catalog = JSONArray().also { io.github.mangi.eta.agent.delegation.SubAgentTools.appendTo(it, listOf("worker")) }
        val validator = AgentToolCallValidator(catalog)
        listOf("{}", "{\"context\":\"context only\"}", "{\"task\":null}", "{\"task\":\"   \"}").forEach { args ->
            assertNotNull(validator.validate(AgentModelClient.ToolCall("id", "delegate_task", args)))
        }
        assertNull(validator.validate(AgentModelClient.ToolCall("id", "delegate_task", "{\"task\":\"检查证据\"}")))
    }

}
