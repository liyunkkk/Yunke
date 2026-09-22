package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Direct media requests do not go through the remote text-agent runtime. */
internal class DirectMediaRunControl {
    private val controllers = ConcurrentHashMap<String, AgentRunController>()

    fun start(runId: String): AgentRunController = AgentRunController().also {
        check(controllers.putIfAbsent(runId, it) == null) { "Duplicate media run" }
    }

    fun cancel(runId: String) { controllers.remove(runId)?.cancel() }

    suspend fun <T> execute(controller: AgentRunController, block: suspend () -> T): T = coroutineScope {
        // The suspended child is cancelled immediately with its parent, even while the
        // generating sibling is in blocking OkHttp.execute()/Future.get().
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { controller.cancel() }
        }
        try {
            controller.throwIfCancelled()
            block()
        } finally {
            controller.cancel()
            cancellation.cancel()
        }
    }
}
