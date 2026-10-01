package io.github.mangi.eta.agent.model

/** The provider kept stopping at its output limit before any body or tool call; retries are exhausted. */
internal class AgentOutputLimitException(message: String) : IllegalStateException(message)
