package io.github.mangi.eta.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ChildTaskOrdinaryDispatchSelectionTest {
    private fun candidate(id: String, value: String) = ChildTaskConfigPolicy.Candidate(
        worker = ChildTaskConfigPolicy.WorkerKey("owner", id, "research"),
        availability = ChildTaskConfigPolicy.Availability.AVAILABLE,
        configurationRevision = value,
        configuration = value,
    )

    @Test
    fun retainedTaskUsesFrozenCandidateForOrdinaryAndCurrentForReplacement() {
        val frozen = listOf(candidate("worker-1", "old"))
        val current = listOf(candidate("worker-1", "new"))

        val plan = ChildTaskOrdinaryDispatchSelection.plan(current, frozen, retainedTasks = true)

        assertSame(frozen, plan.ordinary)
        assertSame(current, plan.replacement)
        assertEquals(true, plan.retained)
    }

    @Test
    fun missingFrozenStateDoesNotFallBackToCurrent() {
        val current = listOf(candidate("worker-1", "current"))

        val plan = ChildTaskOrdinaryDispatchSelection.plan(current, null, retainedTasks = true)

        assertEquals(emptyList(), plan.ordinary)
        assertSame(current, plan.replacement)
        assertEquals(true, plan.retained)
    }

    @Test
    fun withoutRetainedTaskOrdinaryUsesCurrentCandidate() {
        val current = listOf(candidate("worker-1", "current"))

        val plan = ChildTaskOrdinaryDispatchSelection.plan(current, null, retainedTasks = false)

        assertSame(current, plan.ordinary)
        assertSame(current, plan.replacement)
        assertEquals(false, plan.retained)
    }
}
