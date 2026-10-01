"""Integration wiring checks; Kotlin tests exercise the tool policy and runner dispatch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
AGENT = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent"


class ChildBrowserContractTest(unittest.TestCase):
    def test_all_runtime_entrypoints_share_owned_browser_and_finally_cleanup(self):
        source = (AGENT / "runtime/AgentRuntimeRunExecutor.kt").read_text()
        self.assertIn("if (allowBrowser && currentPermissions().browserTools)", source)
        self.assertEqual(3, source.count("runTextChild(config, prompt, controller,"))
        self.assertEqual(2, source.count("browserExecutor = browser?.executor"))
        self.assertIn("finally {\n                            browser?.release()", source)

    def test_child_pool_cannot_be_selected_or_evicted_by_parent(self):
        source = (AGENT / "browser/ChildBrowserSession.kt").read_text()
        self.assertIn('"child-browser-${UUID.randomUUID()}"', source)
        self.assertIn("BrowserTabPool(app, researchMode = true)", source)
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
        self.assertIn("if (researchMode) return@setDownloadListener", manager)
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


if __name__ == "__main__":
    unittest.main()
