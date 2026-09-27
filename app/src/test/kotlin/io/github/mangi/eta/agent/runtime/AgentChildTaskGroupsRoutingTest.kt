package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AgentChildTaskGroupsRoutingTest {
    @Test fun disabledCurrentGenerationDoesNotAdmitDelegateButListsOldOwnerTasks() {
        val owner = UUID.randomUUID().toString()
        fun execute(name: String, args: JSONObject = JSONObject()): JSONObject = JSONObject(
            AgentChildTaskGroups.execute(owner, null, AgentModelClient.ToolCall("test", name, args.toString())).content)
        assertEquals("RUN_CLOSED", execute("delegate_task", JSONObject().put("task", "new" )).getString("code"))
        assertEquals(0, execute("get_task_result").getJSONArray("tasks").length())
        assertEquals("TASK_NOT_FOUND", execute("cancel_task", JSONObject().put("task_id", "other-owner-task")).getString("code"))
    }
}
