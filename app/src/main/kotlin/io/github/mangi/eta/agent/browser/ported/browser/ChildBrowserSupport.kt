// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see third_party/openminis-browser/NOTICE.md.
package io.github.mangi.eta.agent.browser.ported.browser

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.security.SecureRandom

/**
 * [T-child-browser-interactive] Low-level gate for a read-only child browser.
 *
 * The child tool policy is a schema/argument whitelist, but a stale or
 * malicious tool call could still arrive with a mutating action. The pool and
 * the per-tab manager therefore refuse these actions themselves whenever a
 * research child runs WITHOUT interaction (`researchMode && !childInteractive`),
 * so the restriction does not depend on any prompt or model behaviour.
 *
 * Denied actions are those that mutate the page, its origin or on-disk state:
 * page mutation (click/type/execute_js/hover), network writes (fetch), and the
 * cookie tools. `get_cookies` is included deliberately: it exports the matched
 * cookies into an `env` file under `/var/minis/offloads`, so it is a write, not
 * a pure read. Navigation and pure reads stay allowed so read-only research
 * keeps working exactly as before.
 */
internal object ChildBrowserGate {
    private val readOnlyDenied: Set<BrowserAction> = setOf(
        BrowserAction.CLICK,
        BrowserAction.TYPE,
        BrowserAction.HOVER,
        BrowserAction.EXECUTE_JS,
        BrowserAction.FETCH,
        BrowserAction.GET_COOKIES,
        BrowserAction.SET_COOKIES,
        BrowserAction.SET_USER_AGENT,
        BrowserAction.SET_VIEWPORT,
        BrowserAction.NEW_TAB,
        BrowserAction.CLOSE_TAB,
    )

    fun deniesReadOnly(action: BrowserAction): Boolean = action in readOnlyDenied

    /** Only a child's first explicit navigate to 0 may create that exact tab.
     * Missing ids otherwise never fall back to another page (including parents).
     */
    fun allowsInitialTab(researchMode: Boolean, hasTabs: Boolean, tabId: Int?, action: BrowserAction?): Boolean =
        researchMode && !hasTabs && tabId == 0 && action == BrowserAction.NAVIGATE
}

/** [T-child-browser-interactive] Output helpers for browser results. */
internal object BrowserOutput {
    /**
     * Strip ONLY the `user:password@` userinfo component from an absolute URI
     * before it is echoed into a tool result, so a URL the caller already typed
     * does not repeat its credentials. This is deliberately narrow — it is NOT a
     * general credential scrubber and makes no claim about query strings, tokens
     * or any other secret. Anything that does not parse is returned unchanged.
     */
    fun redactUrlCredentials(raw: String): String {
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return raw
        if (uri.userInfo == null) return raw
        return runCatching {
            java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }.getOrDefault(raw)
    }
}

/**
 * [T-child-browser-interactive] Minimal, reply-only JS bridge used by a
 * research child-interactive tab.
 *
 * Android installs every `@JavascriptInterface` method of the object passed to
 * `WebView.addJavascriptInterface` into ALL frames of the page, and native code
 * cannot learn which frame or origin is calling. So a child tab must NOT get the
 * full parent bridge. This class holds the transport only: the exposed object
 * (`__eta_child__`, see BrowserUseManager) has exactly two write-only callbacks,
 * `resolve` and `reject`, and nothing that reads a file, returns credentials or
 * runs a command.
 *
 * Requests are tracked in a bounded token→deferred registry rather than a single
 * slot: a tool call (`execute_js`/`fetch`) may overlap with up to three page
 * downloads, and cancelling one must never cancel another. Every token is a
 * fresh 128-bit random value; `resolve`/`reject` remove and complete only the
 * request registered under that exact token, preventing accidental cross-request
 * delivery. Page content remains untrusted; a token is not origin authentication.
 */
internal class ChildAsyncReplyBridge(private val capacity: Int = DEFAULT_CAPACITY) {
    private val random = SecureRandom()
    private val gate = Any()
    private val pending = LinkedHashMap<String, CompletableDeferred<String>>()

    /**
     * Register [target] under a fresh token and return it, or null when the
     * registry is already at [capacity] (the caller must refuse the new request
     * rather than evict an in-flight one).
     */
    fun begin(target: CompletableDeferred<String>): String? = synchronized(gate) {
        if (pending.size >= capacity) return null
        var fresh = newToken()
        while (pending.containsKey(fresh)) fresh = newToken()
        pending[fresh] = target
        fresh
    }

    /** Success callback exposed to the page; only the live [token] delivers. */
    fun resolve(token: String, result: String) = complete(token, result, null)

    /** Failure callback exposed to the page; mirrors the legacy `{"error":…}` envelope. */
    fun reject(token: String, error: String) = complete(token, null, error)

    /** Drop only [token]; a late callback for it is ignored. */
    fun end(token: String) {
        val target = synchronized(gate) { pending.remove(token) }
        target?.cancel()
    }

    /** Drop every outstanding request (manager destroy). */
    fun endAll() {
        val targets = synchronized(gate) {
            val snapshot = pending.values.toList()
            pending.clear()
            snapshot
        }
        targets.forEach { it.cancel() }
    }

    /** Number of outstanding requests — used by tests and capacity checks. */
    fun pendingCount(): Int = synchronized(gate) { pending.size }

    private fun complete(token: String, result: String?, error: String?) {
        val target = synchronized(gate) { pending.remove(token) }
            ?: return
        if (!target.isActive) return
        if ((result?.length ?: 0) > 44 * 1024 * 1024) {
            target.complete("{\"error\":\"CHILD_BROWSER_REPLY_TOO_LARGE\"}")
            return
        }
        if (error != null) target.complete(JSONObject().put("error", error).toString())
        else target.complete(result.orEmpty())
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val hex = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val value = bytes[i].toInt() and 0xFF
            hex[i * 2] = HEX[value ushr 4]
            hex[i * 2 + 1] = HEX[value and 0x0F]
        }
        return String(hex)
    }

    private companion object {
        const val DEFAULT_CAPACITY = 8
        val HEX = "0123456789abcdef".toCharArray()
    }
}
