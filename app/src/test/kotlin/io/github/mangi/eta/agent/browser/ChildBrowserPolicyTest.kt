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

    @Test fun structuredRefusalIsSafeAndNeverDispatches() {
        var calls = 0
        val executor = ChildBrowserPolicy.guarded({ true }) { calls++; AgentModelClient.ToolResult("ok") }
        val blocked = body(executor.execute(call(JSONObject().put("action", "click")
            .put("url", "https://secret.example/private?token=abc"))))
        assertEquals("SUB_AGENT_BROWSER_RESTRICTED", blocked.getString("code"))
        assertEquals("ACTION_NOT_ALLOWED", blocked.getString("reason"))
        assertEquals("click", blocked.getString("blocked_action"))
        assertTrue(blocked.getJSONArray("allowed_actions").length() > 0)
        for (leak in listOf("secret.example", "token", "private")) assertFalse(blocked.toString().contains(leak))
        val unknown = body(executor.execute(call(JSONObject().put("action", "future_action").put("password", "hunter2"))))
        assertEquals("ACTION_NOT_ALLOWED", unknown.getString("reason"))
        assertFalse(unknown.has("blocked_action"))
        assertFalse(unknown.toString().contains("future_action"))
        assertFalse(unknown.toString().contains("hunter2"))
        val extra = body(executor.execute(call(JSONObject().put("action", "get_text").put("script", "steal()"))))
        assertEquals("ARGUMENT_NOT_ALLOWED", extra.getString("reason"))
        assertEquals("get_text", extra.getString("blocked_action"))
        assertFalse(extra.toString().contains("steal()"))
        assertEquals(0, calls)
    }

    @Test fun urlOnNonNavigateActionExplainsNavigateFirstRecovery() {
        var calls = 0
        val executor = ChildBrowserPolicy.guarded({ true }) { calls++; AgentModelClient.ToolResult("ok") }
        val refusal = body(executor.execute(call(JSONObject().put("action", "get_readable")
            .put("url", "https://secret.example/doc"))))
        assertEquals("SUB_AGENT_BROWSER_RESTRICTED", refusal.getString("code"))
        assertEquals("URL_NOT_ALLOWED", refusal.getString("reason"))
        assertEquals("get_readable", refusal.getString("blocked_action"))
        assertEquals("navigate", refusal.getString("suggested_action"))
        assertTrue(refusal.getString("recovery_hint").contains("navigate"))
        assertFalse(refusal.toString().contains("secret.example"))
        assertEquals(0, calls)
    }

    @Test fun colonSearchTermsBecomeQueriesWhileSchemesAndHttpTargetsStayBlocked() {
        assertEquals("https://www.bing.com/search?q=site%3Aexample.org+kotlin",
            ChildBrowserPolicy.normalizeUrl("site:example.org kotlin"))
        val chinese = ChildBrowserPolicy.normalizeUrl("Rust: 所有权")
        assertNotNull(chinese)
        assertTrue(chinese.orEmpty().startsWith("https://www.bing.com/search?q=Rust%3A+"))
        for (value in listOf("javascript:alert(1)", "file:///etc/passwd", "minis://workspace/a", "about:blank",
                "data:text/html,a", "http://", "http://example.org bad", "https://user:pass@example.org",
                "/workspace/a.html")) {
            assertNull(value, ChildBrowserPolicy.normalizeUrl(value))
        }
    }

    @Test fun disabledExecutorRefusesStructuredAndNeverRetries() {
        var calls = 0
        val executor = ChildBrowserPolicy.guarded({ false }) { calls++; AgentModelClient.ToolResult("ok") }
        val first = body(executor.execute(call(JSONObject().put("action", "get_readable"))))
        assertEquals("BROWSER_TOOLS_DISABLED", first.getString("code"))
        assertEquals("BROWSER_DISABLED", first.getString("reason"))
        assertEquals(0, first.getJSONArray("allowed_actions").length())
        assertFalse(first.has("suggested_action"))
        assertTrue(first.getString("recovery_hint").contains("不要重试"))
        val second = body(executor.execute(call(JSONObject().put("action", "navigate").put("url", "https://example.org"))))
        assertEquals("BROWSER_DISABLED", second.getString("reason"))
        assertEquals(0, calls)
    }

    @Test fun schemaDocumentsNavigateOnlyUrlAndKeepsWhitelist() {
        val schema = ChildBrowserPolicy.schema()
        val function = schema.getJSONObject("function")
        val parameters = function.getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")
        val enum = properties.getJSONObject("action").getJSONArray("enum")
        assertEquals(ChildBrowserPolicy.actions, (0 until enum.length()).map { enum.getString(it) }.toSet())
        assertFalse(parameters.getBoolean("additionalProperties"))
        val url = properties.getJSONObject("url").getString("description")
        assertTrue(url.contains("只有 navigate 接受 url"))
        assertTrue(url.contains("先 navigate 再不带 url"))
        val note = function.getString("description")
        assertTrue(note.contains("不代表浏览器工具不存在"))
        assertTrue(note.contains("不承诺公网可达性"))
        assertTrue(note.contains("登录状态可能共享"))
        assertTrue(note.contains("标签页"))
    }

    private fun call(args: JSONObject) = AgentModelClient.ToolCall("id", "browser_use", args.toString())
    private fun body(result: AgentModelClient.ToolResult) = JSONObject(result.content)
}
