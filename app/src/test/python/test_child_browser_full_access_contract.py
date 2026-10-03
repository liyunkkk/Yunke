"""Source-wiring regressions only; these do not replace Kotlin/WebView tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
AGENT = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent"


def source(relative):
    return (AGENT / relative).read_text()


class ChildBrowserFullAccessContractTest(unittest.TestCase):
    def test_grant_defaults_to_full_but_malformed_values_do_not(self):
        access = source("browser/ChildBrowserAccess.kt")
        self.assertIn('if (!args.has("browser_access")) return FULL', access)
        self.assertIn('if (raw is String) byWire[raw] else null', access)
        self.assertIn('byWire[', access)
        tools = source("delegation/SubAgentTools.kt")
        self.assertIn('.put("default", "full")', tools)
        self.assertIn('JSONArray(listOf("full", "read_only", "disabled"))', tools)

    def test_dispatch_validates_before_task_creation_and_freezes_controller(self):
        dispatch = source("delegation/SubAgentCoordinator.kt")
        self.assertIn('ChildBrowserAccess.fromArgs(args)', dispatch)
        self.assertIn('?: return invalidArguments("browser_access', dispatch)
        self.assertIn('check(it.freezeChildBrowserAccess(browserAccess))', dispatch)
        self.assertIn('.put("browser_access", task.browserAccess.wire)', dispatch)
        controller = source("runtime/AgentRunController.kt")
        self.assertIn('if (current != null) return current == mode', controller)
        self.assertIn('frozenChildBrowserAccess ?: ChildBrowserAccess.FULL', controller)

    def test_full_advertises_every_new_browser_action(self):
        policy = source("browser/ChildBrowserPolicy.kt")
        block = policy.split('private val fullOnlyActions =', 1)[1].splitlines()[0]
        for action in ('click', 'type', 'hover', 'execute_js', 'get_cookies', 'set_cookies', 'fetch'):
            self.assertIn('"' + action + '"', block)
        self.assertIn('schema(mode)', policy)
        runner = source("delegation/SubAgentRunner.kt")
        self.assertIn('controller.childBrowserAccess.wire', runner)
        self.assertIn('ChildBrowserPolicy.note(', runner)
        self.assertIn('ChildBrowserPolicy.schema(', runner)

    def test_read_only_actions_do_not_silently_gain_interaction(self):
        policy = source("browser/ChildBrowserPolicy.kt")
        block = policy.split('private val readOnlyActions =', 1)[1].split('val actions:', 1)[0]
        for action in ('click', 'type', 'hover', 'execute_js', 'get_cookies', 'set_cookies', 'fetch'):
            self.assertNotIn('"' + action + '"', block)
        self.assertIn('readOnlyChild && ChildBrowserGate.deniesReadOnly(input.action)', source("browser/ported/browser/BrowserTabPool.kt"))
        self.assertIn('readOnlyChild && ChildBrowserGate.deniesReadOnly(input.action)', source("browser/ported/browser/BrowserUseManager.kt"))

    def test_global_authorization_and_task_grant_reach_execution(self):
        runtime = source("runtime/AgentRuntimeRunExecutor.kt")
        self.assertIn('allowBrowser && currentPermissions().browserTools, controller.childBrowserAccess.wire', runtime)
        session = source("browser/ChildBrowserSession.kt")
        self.assertIn('ChildBrowserPolicy.guarded(enabled, access,', session)
        self.assertIn('childInteractive = ChildBrowserPolicy.interactive(access)', session)
        groups = source("runtime/AgentChildTaskGroups.kt")
        self.assertIn('"browser_access"', groups)

    def test_owner_propagation_and_bounded_download_receipts(self):
        pool = source("browser/ported/browser/BrowserTabPool.kt")
        self.assertEqual(2, pool.count('childOwnerId = if (researchMode) sessionId else null'))
        self.assertIn('child-browser-[a-f0-9-]{36}', pool)
        self.assertIn('ConcurrentLinkedDeque', pool)
        self.assertRegex(pool, r'childReceipts\.size\s*>\s*16')
        session = source("browser/ChildBrowserSession.kt")
        self.assertIn('childDownloadReceipts()', session)
        self.assertIn('"downloads"', session)

    def test_child_bridge_is_separate_from_parent_number_sequence(self):
        manager = source("browser/ported/browser/BrowserUseManager.kt")
        self.assertIn('asyncJsRequestId += 1', manager)
        self.assertNotIn('nextAsyncJsToken', manager)
        self.assertIn('"__eta_child__"', manager)
        self.assertIn('else webView.addJavascriptInterface(jsBridge, "__minis__")', manager)
        helper = source("browser/ported/browser/ChildBrowserSupport.kt")
        self.assertIn('ByteArray(16)', helper)
        self.assertIn('DEFAULT_CAPACITY = 8', helper)
        self.assertIn('CHILD_BROWSER_REPLY_TOO_LARGE', helper)

    def test_cancellation_destroys_pages_and_cancels_owned_downloads(self):
        session = source("browser/ChildBrowserSession.kt")
        cancellation = session.split('catch (error: CancellationException)', 1)[1].split('throw error', 1)[0]
        self.assertIn('browser.cancelChildDownloads()', cancellation)
        self.assertIn('browser.destroy()', cancellation)
        manager = source("browser/ported/browser/BrowserUseManager.kt")
        self.assertIn('childAbortKeys', manager)
        self.assertIn('childReplyBridge.endAll()', manager)
        self.assertIn('cancelChildren()', manager)
        downloads = source("browser/ported/browser/ChildBrowserDownloads.kt")
        self.assertIn('generation.incrementAndGet()', downloads)
        self.assertIn('disconnect()', downloads)

    def test_download_urls_credentials_and_downgrades_are_refused(self):
        downloads = source("browser/ported/browser/ChildBrowserDownloads.kt")
        for expected in ('rawUserInfo', '8192', 'CHILD_DOWNLOAD_URL_NOT_ALLOWED', 'CHILD_DOWNLOAD_DOWNGRADE_REFUSED'):
            self.assertIn(expected, downloads)
        self.assertIn('"http"', downloads)
        self.assertIn('"https"', downloads)
        self.assertIn('cookies(', downloads)
        self.assertIn('setRequestProperty("Cookie"', downloads)

    def test_download_storage_is_bounded_and_partial_files_are_removed(self):
        downloads = source("browser/ported/browser/ChildBrowserDownloads.kt")
        self.assertIn('Semaphore(3)', downloads)
        self.assertRegex(downloads, r'MAX_BYTES\s*=\s*32L\s*\*\s*1024\s*\*\s*1024')
        self.assertIn('CHILD_DOWNLOAD_TOO_LARGE', downloads)
        self.assertIn('ensureActive()', downloads)
        self.assertIn('.part', downloads)
        self.assertIn('.delete()', downloads)
        self.assertIn('UUID.randomUUID()', downloads)
        self.assertIn('renameTo(', downloads)

    def test_blob_downloads_stream_and_abort_instead_of_unbounded_fetch(self):
        manager = source("browser/ported/browser/BrowserUseManager.kt")
        block = manager.split('fun fetchChildBlobDownload(', 1)[1].split('\n    private fun ', 1)[0]
        for expected in ('AbortController', 'getReader()', 'TOO_LARGE', '32 * 1024 * 1024', 'JS_EVALUATION_TIMEOUT_MS * 4', 'ensureActive()'):
            self.assertIn(expected, block)

    def test_first_explicit_tab_zero_only_bootstraps_child_navigate(self):
        helper = source("browser/ported/browser/ChildBrowserSupport.kt")
        self.assertIn('researchMode && !hasTabs && tabId == 0 && action == BrowserAction.NAVIGATE', helper)
        pool = source("browser/ported/browser/BrowserTabPool.kt")
        self.assertEqual(2, pool.count('ChildBrowserGate.allowsInitialTab('))
        self.assertIn('acquireTab(requestedTabId, input.action)', pool)
        acquire = pool.split('private suspend fun acquireTab(', 1)[1].split('private fun createTab(', 1)[0]
        explicit = acquire.split('val tab = if (requestedTabId != null)', 1)[1].split('} else {', 1)[0]
        self.assertIn('currentTabs.firstOrNull { it.id == requestedTabId }', explicit)
        self.assertIn('createTab(currentTabs) else null', explicit)
        self.assertNotIn('_selectedTabId', explicit)

    def test_owned_fetch_reuses_site_visible_ua_without_creating_a_page(self):
        pool = source("browser/ported/browser/BrowserTabPool.kt")
        fetch = pool.split('private suspend fun fetchChildResource(', 1)[1].split('private fun startChildPageDownload(', 1)[0]
        self.assertIn('withContext(Dispatchers.Main)', fetch)
        self.assertIn('webView?.settings?.userAgentString', fetch)
        self.assertIn('userAgentProfile.userAgentString', fetch)
        self.assertIn('WebSettings.getDefaultUserAgent(context)', fetch)
        self.assertIn('downloadScope.async { childDownloads.fetch(url, userAgent) }', fetch)
        self.assertNotIn('createTab(', fetch)
        self.assertIn('fetchChildResource(input.url, input.tabId)', pool)

    def test_new_kotlin_runtime_regressions_are_present_not_claimed_executed(self):
        tests = ROOT / 'app/src/test/kotlin/io/github/mangi/eta/agent/browser'
        for name in ('ChildBrowserFullAccessTest.kt', 'ported/browser/ChildBrowserBackendTest.kt', 'ported/browser/ChildBrowserDownloadsTest.kt'):
            self.assertTrue((tests / name).is_file(), name)


if __name__ == '__main__':
    unittest.main()
