package io.github.mangi.eta.agent.browser

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChildBrowserFullAccessTest {
    private fun call(args: JSONObject) = AgentModelClient.ToolCall("fixture", "browser_use", args.toString())
    @Test fun dispatchDefaultsFullButRejectsPresentInvalidModes() {
        assertEquals(ChildBrowserAccess.FULL, ChildBrowserAccess.fromArgs(JSONObject()))
        for (mode in ChildBrowserAccess.values())
            assertEquals(mode, ChildBrowserAccess.fromArgs(JSONObject().put("browser_access", mode.wire)))
        for (value in listOf<Any>(JSONObject.NULL, true, 42, "FULL", "unknown", "", JSONArray()))
            assertNull(ChildBrowserAccess.fromArgs(JSONObject().put("browser_access", value)))
    }
    @Test fun controllerGrantCannotBeUpgradedDuringContinuation() {
        val controller = AgentRunController()
        assertTrue(controller.freezeChildBrowserAccess(ChildBrowserAccess.READ_ONLY))
        assertFalse(controller.freezeChildBrowserAccess(ChildBrowserAccess.FULL))
        controller.pauseAtCheckpoint(); controller.resume()
        assertEquals(ChildBrowserAccess.READ_ONLY, controller.childBrowserAccess)
    }
    @Test fun defaultFullAdvertisesAndDispatchesEachNewCapability() {
        val additions = setOf("click", "type", "hover", "execute_js", "get_cookies", "set_cookies", "fetch")
        assertTrue(ChildBrowserPolicy.actionsFor(ChildBrowserPolicy.FULL).containsAll(additions))
        val schema = ChildBrowserPolicy.schema().getJSONObject("function").getJSONObject("parameters")
        val properties = schema.getJSONObject("properties")
        for (name in listOf("script", "text", "cookies", "fuzzy", "coordinate_x")) assertTrue(properties.has(name))
        var count = 0
        val executor = ChildBrowserPolicy.guarded({ true }) { count++; AgentModelClient.ToolResult("ok") }
        for (action in additions) {
            val args = JSONObject().put("action", action)
            if (action == "fetch") args.put("url", "https://example.org/file")
            assertEquals("ok", executor.execute(call(args)).content)
        }
        assertEquals(additions.size, count)
    }
    @Test fun fullStillRefusesLocalFilesUnknownInputAndWrongTypesWithoutEcho() {
        var count = 0
        val executor = ChildBrowserPolicy.guarded({ true }) { count++; AgentModelClient.ToolResult("ok") }
        for (url in listOf("file:///etc/secret", "content://private/secret", "minis://workspace/secret", "/workspace/secret", "example.org", "https://user:secret@example.org")) {
            val result = executor.execute(call(JSONObject().put("action", "fetch").put("url", url))).content
            assertTrue(result.contains("SUB_AGENT_BROWSER_RESTRICTED"))
            assertFalse(result.contains("secret"))
        }
        val result = executor.execute(call(JSONObject().put("action", "private_action_secret"))).content
        assertFalse(result.contains("private_action_secret"))
        assertTrue(executor.execute(call(JSONObject().put("action", "execute_js").put("script", true))).content.contains("MALFORMED_ARGUMENTS"))
        assertEquals(0, count)
    }
    @Test fun explicitReadOnlyDisabledAndGlobalRevocationRemainEnforced() {
        var count = 0
        var enabled = true
        val readOnly = ChildBrowserPolicy.guarded({ enabled }, ChildBrowserPolicy.READ_ONLY) { count++; AgentModelClient.ToolResult("ok") }
        assertTrue(readOnly.execute(call(JSONObject().put("action", "click"))).content.contains("ACTION_NOT_ALLOWED"))
        val full = ChildBrowserPolicy.guarded({ enabled }) { count++; AgentModelClient.ToolResult("ok") }
        assertEquals("ok", full.execute(call(JSONObject().put("action", "click"))).content)
        enabled = false
        val denied = JSONObject(full.execute(call(JSONObject().put("action", "click"))).content)
        assertEquals(0, denied.getJSONArray("allowed_actions").length())
        assertFalse(ChildBrowserPolicy.sessionAllowed(true, "disabled"))
        assertFalse(ChildBrowserPolicy.sessionAllowed(true, "unknown"))
        assertEquals(1, count)
    }
}
