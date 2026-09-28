package io.github.mangi.eta.agent.runtime

/** A parent-only stop must never escalate into cancellation of retained children. */
internal object AgentRuntimeStopDispatch {
    fun message(mainReason: AgentChildControlPolicy.Reason?): Int =
        if (mainReason == null) AgentRuntimeWire.MSG_CANCEL else AgentRuntimeWire.MSG_STOP_MAIN_RUN
}
