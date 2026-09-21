package io.github.mangi.eta.agent.delegation

/** A child has no interactive pause UI. Return control to the parent instead of waiting for timeout. */
internal class SubAgentContextLimitException : IllegalStateException("SUB_AGENT_CONTEXT_LIMIT")
