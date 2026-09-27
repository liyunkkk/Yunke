package io.github.mangi.eta.agent.runtime

import java.util.UUID

/** Each replacement run gets a unique FGS key. The old worker must release exactly its own key. */
internal class AgentRuntimeRunLease private constructor(val id: String) {
    companion object {
        fun create(runId: String): AgentRuntimeRunLease {
            require(runId.isNotBlank())
            return AgentRuntimeRunLease("run:$runId:${UUID.randomUUID()}")
        }
    }
}
