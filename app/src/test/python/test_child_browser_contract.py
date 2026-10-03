"""Integration wiring checks; Kotlin tests exercise the tool policy and runner dispatch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
AGENT = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent"
POLICY = AGENT / "browser/ChildBrowserPolicy.kt"


class ChildBrowserContractTest(unittest.TestCase):
    def test_all_runtime_entrypoints_share_owned_browser_and_finally_cleanup(self):
        source = (AGENT / "runtime/AgentRuntimeRunExecutor.kt").read_text()
        self.assertIn("ChildBrowserPolicy.sessionAllowed(", source)
        self.assertIn("allowBrowser && currentPermissions().browserTools, controller.childBrowserAccess.wire", source)
        self.assertEqual(3, source.count("runTextChild(config, prompt, controller,"))
        self.assertEqual(2, source.count("browserExecutor = browser?.executor"))
        self.assertIn("finally {\n                            browser?.release()", source)

    def test_child_pool_cannot_be_selected_or_evicted_by_parent(self):
        source = (AGENT / "browser/ChildBrowserSession.kt").read_text()
        self.assertIn('"child-browser-${UUID.randomUUID()}"', source)
        self.assertIn("researchMode = true, childInteractive = ChildBrowserPolicy.interactive(access)", source)
        self.assertNotIn("AgentBrowserSession.execute(", source)
        self.assertNotIn("interruptAgentAction", source)
        self.assertIn("controller.register { close() }", source)
        self.assertIn("action?.cancel()", source)
        self.assertIn("action?.join()", source)
        self.assertIn("pool?.destroy()", source)
        self.assertIn("activePools >= MAX_POOLS", source)
        self.assertIn("activePools--", source)

    def test_research_pool_is_ephemeral_and_has_no_background_download_or_popup(self):
        pool = (AGENT / "browser/ported/browser/BrowserTabPool.kt").read_text()
        manager = (AGENT / "browser/ported/browser/BrowserUseManager.kt").read_text()
        self.assertIn("private val tabLimit = if (researchMode) 1 else MAX_TABS", pool)
        self.assertIn("evictionJob = if (researchMode) null else", pool)
        self.assertIn("if (!researchMode) loadSavedState()", pool)
        self.assertIn("private fun saveState() {\n        if (researchMode) return", pool)
        self.assertIn("evictionScope.cancel()", pool)
        self.assertIn("downloadScope.cancel()", pool)
        self.assertIn("if (readOnlyChild) return@setDownloadListener", manager)
        self.assertIn("if (researchMode) return false", manager)
        self.assertIn('else webView.addJavascriptInterface(jsBridge, "__minis__")', manager)
        self.assertIn("allowFileAccess = false", manager)
        self.assertIn("allowContentAccess = false", manager)
        self.assertIn("if (!researchMode && histUrl.isNotEmpty()", manager)
        self.assertIn("ChildBrowserPolicy.isWebUrl(request.url.toString())", manager)
        self.assertIn("ChildBrowserPolicy.isWebUrl(url.toString())", manager)

    def test_prompt_no_longer_claims_no_browser(self):
        source = (AGENT / "model/AgentPromptBuilder.kt").read_text()
        self.assertNotIn("不能执行 shell、GUI 或浏览器", source)
        self.assertIn("文本子代理（包括工作树代理）", source)
        self.assertIn("登录状态可能共享", source)

    def test_refusals_stay_structured_and_never_echo_unknown_input(self):
        source = POLICY.read_text()
        for field in ('"code"', '"reason"', '"allowed_actions"', '"recovery_hint"', '"blocked_action"'):
            self.assertIn(field, source)
        # An action name may only be echoed from the child whitelist or the disabled standard set.
        self.assertIn("action.takeIf { it in parentActions && it !in granted }", source)
        self.assertIn('decision.reason == "BROWSER_DISABLED" || granted.isEmpty()', source)
        self.assertIn("JSONArray(allowed.sorted())", source)
        # prepare still refuses with null; the guard only forwards the re-validated object.
        self.assertIn("fun prepare(args: JSONObject, mode: String = FULL)", source)
        self.assertIn("call.copy(argumentsJson = prepared.toString())", source)

    def test_colon_search_terms_are_queries_but_schemes_and_http_targets_are_refused(self):
        source = POLICY.read_text()
        self.assertIn('lower.startsWith("http://") || lower.startsWith("https://")', source)
        self.assertIn("value.none(Char::isWhitespace)", source)
        self.assertIn("https://www.bing.com/search?q=", source)
        # The original web URL boundary is untouched: http/https, a host, and no userinfo.
        self.assertIn('uri.scheme?.lowercase() in setOf("https", "http")', source)
        self.assertIn("uri.rawUserInfo == null", source)

    def test_note_documents_navigate_only_url_without_network_guarantees(self):
        source = POLICY.read_text()
        note = source.split("const val READ_ONLY_NOTE =", 1)[1].split("const val FULL_NOTE", 1)[0]
        for expected in ("唯一接受 url", "先 navigate 再不带 url", "不代表浏览器工具不存在",
                         "不承诺公网可达性", "登录状态可能共享", "标签页"):
            self.assertIn(expected, note)
        self.assertNotIn("保证公网", note)
        url_block = source.split('properties.getJSONObject("url").put("description"', 1)[1]
        self.assertIn("只有 navigate 接受 url", url_block)


if __name__ == "__main__":
    unittest.main()
