package io.github.mangi.eta.agent.browser

import android.content.Context
import android.os.Looper
import io.github.mangi.eta.agent.browser.ported.browser.BrowserActionInput
import io.github.mangi.eta.agent.browser.ported.browser.BrowserTabPool
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

/** Execution-owned ephemeral pool. Never inserted into AgentBrowserSession's UI/session registry. */
internal class ChildBrowserSession(
    context: Context,
    private val controller: AgentRunController,
    private val enabled: () -> Boolean,
) : AutoCloseable {
    private val app = context.applicationContext
    internal val ownerId = "child-browser-${UUID.randomUUID()}"
    private val gate = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Deferred<AgentModelClient.ToolResult>? = null
    private var cleanup: Job? = null
    private var closed = false
    // All pool and quota access is confined to Dispatchers.Main.
    private var pool: BrowserTabPool? = null
    private var hasSlot = false
    private val cancellation = controller.register { close() }
    val executor = ChildBrowserPolicy.guarded(enabled, AgentModelClient.ToolExecutor(::execute))

    private fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        controller.throwIfCancelled()
        if (Looper.myLooper() == Looper.getMainLooper()) return ChildBrowserPolicy.error("MAIN_THREAD_CALL")
        val job = synchronized(gate) {
            if (closed) return ChildBrowserPolicy.error("SUB_AGENT_BROWSER_CLOSED")
            if (pending?.isCompleted == false) return ChildBrowserPolicy.error("BROWSER_BUSY")
            scope.async(start = CoroutineStart.LAZY) {
                controller.throwIfCancelled()
                if (!enabled()) return@async ChildBrowserPolicy.error("BROWSER_TOOLS_DISABLED")
                if (pool == null) {
                    if (activePools >= MAX_POOLS) return@async ChildBrowserPolicy.error("SUB_AGENT_BROWSER_CAPACITY")
                    pool = BrowserTabPool(app, researchMode = true).also { it.setSession(ownerId) }
                    activePools++
                    hasSlot = true
                }
                val browser = requireNotNull(pool)
                val args = JSONObject(call.argumentsJson)
                val input = BrowserActionInput.parse(args.toString())
                    ?: return@async ChildBrowserPolicy.error("INVALID_ARGUMENT")
                val result = try {
                    withTimeout(90000) { browser.execute(input, singleTab = true) }
                } finally {
                    if (!isActive) {
                        browser.tabs.value.forEach { it.manager.stopLoading() }
                        browser.releaseAllTabs()
                    }
                }
                val output = AgentBrowserSession.toToolResult(args.getString("action"), args, result, browser)
                AgentModelClient.ToolResult(output.content, images = output.images.map { image ->
                    AgentModelClient.ModelImage(reference = image.dataUrl, mimeType = image.mimeType,
                        bytes = image.bytes, width = image.width, height = image.height, source = "agent_browser")
                })
            }.also { pending = it }
        }
        return try {
            runBlocking { job.await() }
        } catch (_: TimeoutCancellationException) {
            ChildBrowserPolicy.error("BROWSER_TIMEOUT")
        } catch (_: CancellationException) {
            controller.throwIfCancelled()
            ChildBrowserPolicy.error("CANCELLED")
        } catch (_: Exception) {
            controller.throwIfCancelled()
            ChildBrowserPolicy.error("BROWSER_ERROR")
        } finally {
            synchronized(gate) { if (pending === job) pending = null }
        }
    }

    override fun close() {
        val job = synchronized(gate) {
            if (closed) return@synchronized cleanup
            closed = true
            val action = pending
            action?.cancel()
            scope.launch {
                try {
                    action?.join()
                    pool?.destroy()
                } finally {
                    pool = null
                    if (hasSlot) { activePools--; hasSlot = false }
                    scope.cancel()
                }
            }.also { cleanup = it }
        }
        // No join on Main: cancellation may be requested by the UI.
        if (Looper.myLooper() != Looper.getMainLooper()) runBlocking { job?.join() }
    }

    fun release() {
        cancellation.close()
        close()
    }

    private companion object {
        const val MAX_POOLS = 6
        var activePools = 0
    }
}
