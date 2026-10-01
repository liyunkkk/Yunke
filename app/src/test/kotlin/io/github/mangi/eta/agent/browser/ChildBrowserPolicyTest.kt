package io.github.mangi.eta.agent.browser

import io.github.mangi.eta.agent.model.AgentBrowserToolCatalog
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCallValidator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChildBrowserPolicyTest {
    @Test fun schemaAndExecutorAgreeAndDoNotMutateParentCatalog() {
        val schema = ChildBrowserPolicy.schema()
        val params = schema.getJSONObject("function").getJSONObject("parameters")
        val properties = params.getJSONObject("properties")
        val actions = properties.getJSONObject("action").getJSONArray("enum")
        assertEquals(ChildBrowserPolicy.actions, (0 until actions.length()).map { actions.getString(it) }.toSet())
        assertFalse(params.getBoolean("additionalProperties"))
        for (name in listOf("script", "cookies", "text", "submit", "user_agent", "coordinate_x")) assertFalse(properties.has(name))
        val parent = JSONArray().also(AgentBrowserToolCatalog::appendTo).getJSONObject(0)
            .getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertTrue(parent.has("script"))
        assertTrue(parent.has("cookies"))
        val validator = AgentToolCallValidator(JSONArray().put(schema))
        for (action in ChildBrowserPolicy.actions) {
            val args = JSONObject().put("action", action)
            if (action == "navigate") args.put("url", "https://example.org")
            assertNull(validator.validate(call(args)))
            assertNotNull(ChildBrowserPolicy.prepare(args))
        }
    }

    @Test fun forbiddenActionsAndUnexpectedArgumentsFailClosedBeforeDispatch() {
        var calls = 0
        val executor = ChildBrowserPolicy.guarded({ true }) { calls++; AgentModelClient.ToolResult("ok") }
        for (action in listOf("click", "type", "execute_js", "get_cookies", "set_cookies", "fetch", "set_user_agent",
                "set_viewport", "hover", "new_tab", "future_action")) {
            assertTrue(executor.execute(call(JSONObject().put("action", action))).content.contains("SUB_AGENT_BROWSER_RESTRICTED"))
        }
        assertTrue(executor.execute(call(JSONObject().put("action", "get_text").put("script", "steal()")))
            .content.contains("SUB_AGENT_BROWSER_RESTRICTED"))
        assertTrue(executor.execute(AgentModelClient.ToolCall("id", "browser_use", "not JSON")).content.contains("SUB_AGENT_BROWSER_RESTRICTED"))
        assertEquals(0, calls)
    }

    @Test fun disablingPermissionImmediatelyStopsExistingExecutor() {
        var enabled = true
        var calls = 0
        val executor = ChildBrowserPolicy.guarded({ enabled }) { calls++; AgentModelClient.ToolResult("ok") }
        val read = call(JSONObject().put("action", "get_readable"))
        executor.execute(read)
        enabled = false
        assertTrue(executor.execute(read).content.contains("BROWSER_TOOLS_DISABLED"))
        assertEquals(1, calls)
    }

    @Test fun urlsAndSearchAreNormalizedButLocalAndExternalSchemesAreBlocked() {
        assertEquals("https://example.org/docs", ChildBrowserPolicy.normalizeUrl("example.org/docs"))
        assertEquals("https://example.org:8080/docs", ChildBrowserPolicy.normalizeUrl("example.org:8080/docs"))
        assertEquals("https://www.bing.com/search?q=android+webview", ChildBrowserPolicy.normalizeUrl("android webview"))
        assertTrue(ChildBrowserPolicy.normalizeUrl("中文资料")!!.startsWith("https://www.bing.com/search?q="))
        for (url in listOf("/workspace/a.html", "/var/minis/a", "minis://workspace/a", "file:///etc/passwd",
                "content://media/1", "javascript:alert(1)", "data:text/html,a", "intent://x", "about:blank",
                "//example.org", "../local", "\\\\server\\local", "https://user:pass@example.org", "https://example.org\nfile://a")) {
            assertNull(url, ChildBrowserPolicy.normalizeUrl(url))
        }
        assertFalse(ChildBrowserPolicy.isWebUrl("file:///etc/passwd"))
        assertFalse(ChildBrowserPolicy.isWebUrl("minis://workspace/private"))
        assertTrue(ChildBrowserPolicy.isWebUrl("https://example.org/path?q=a"))
    }

    @Test fun normalizedNavigationIsPassedToBackendOnce() {
        var received = ""
        val executor = ChildBrowserPolicy.guarded({ true }) {
            received = JSONObject(it.argumentsJson).getString("url")
            AgentModelClient.ToolResult("page")
        }
        assertEquals("page", executor.execute(call(JSONObject().put("action", "navigate").put("url", "example.org"))).content)
        assertEquals("https://example.org", received)
        assertNull(ChildBrowserPolicy.prepare(JSONObject().put("action", "get_text").put("url", "file:///etc/passwd")))
    }

    private fun call(args: JSONObject) = AgentModelClient.ToolCall("id", "browser_use", args.toString())
}
