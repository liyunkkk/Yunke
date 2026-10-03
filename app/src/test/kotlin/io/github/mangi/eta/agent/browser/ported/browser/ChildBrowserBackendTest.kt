package io.github.mangi.eta.agent.browser.ported.browser

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test

class ChildBrowserBackendTest {
    @Test fun bridgeCapacityRefusesWithoutEvictionAndTokensDoNotCross() {
        val bridge = ChildAsyncReplyBridge(2)
        val first = CompletableDeferred<String>(); val second = CompletableDeferred<String>()
        val one = requireNotNull(bridge.begin(first)); val two = requireNotNull(bridge.begin(second))
        assertTrue(one.matches(Regex("[a-f0-9]{32}")))
        assertNotEquals(one, two)
        assertNull(bridge.begin(CompletableDeferred()))
        bridge.resolve("unknown", "secret")
        assertFalse(first.isCompleted); assertFalse(second.isCompleted)
        bridge.resolve(one, "first")
        assertTrue(first.isCompleted); assertFalse(second.isCompleted)
        bridge.resolve(one, "stale duplicate")
        assertEquals(1, bridge.pendingCount())
        bridge.end(two)
        assertTrue(second.isCancelled)
        assertEquals(0, bridge.pendingCount())
    }
    @Test fun endAllCancelsOnlyThisRegistryAndReadOnlyDeniesEverySideEffect() {
        val bridge = ChildAsyncReplyBridge()
        val pending = CompletableDeferred<String>()
        requireNotNull(bridge.begin(pending)); bridge.endAll()
        assertTrue(pending.isCancelled)
        for (action in listOf(BrowserAction.CLICK, BrowserAction.TYPE, BrowserAction.HOVER,
            BrowserAction.EXECUTE_JS, BrowserAction.FETCH, BrowserAction.GET_COOKIES, BrowserAction.SET_COOKIES))
            assertTrue(ChildBrowserGate.deniesReadOnly(action))
        assertFalse(ChildBrowserGate.deniesReadOnly(BrowserAction.GET_TEXT))
    }
    @Test fun onlyFirstChildNavigateMayBootstrapExplicitTabZero() {
        assertTrue(ChildBrowserGate.allowsInitialTab(true, false, 0, BrowserAction.NAVIGATE))
        assertFalse(ChildBrowserGate.allowsInitialTab(false, false, 0, BrowserAction.NAVIGATE))
        assertFalse(ChildBrowserGate.allowsInitialTab(true, true, 0, BrowserAction.NAVIGATE))
        assertFalse(ChildBrowserGate.allowsInitialTab(true, false, 1, BrowserAction.NAVIGATE))
        assertFalse(ChildBrowserGate.allowsInitialTab(true, false, -1, BrowserAction.NAVIGATE))
        assertFalse(ChildBrowserGate.allowsInitialTab(true, false, null, BrowserAction.NAVIGATE))
        assertFalse(ChildBrowserGate.allowsInitialTab(true, false, 0, BrowserAction.GET_TEXT))
        assertFalse(ChildBrowserGate.allowsInitialTab(true, false, 0, BrowserAction.FETCH))
    }
}
