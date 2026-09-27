package io.github.mangi.eta.agent.runtime

/** Protocol extension kept separate from the large, stable serialization wire. */
internal val AgentRuntimeWire.MSG_STOP_MAIN_RUN: Int get() = 17
