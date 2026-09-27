package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeRunLeaseTest {
    @Test fun replacementWorkerCannotReleaseNewRunLeaseByReusingRunId() {
        val original = AgentRuntimeRunLease.create("same-run")
        val replacement = AgentRuntimeRunLease.create("same-run")
        assertNotEquals(original.id, replacement.id)
        assertTrue(original.id.startsWith("run:same-run:"))
    }
}
