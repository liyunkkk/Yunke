package io.github.mangi.eta.agent.runtime

/**
 * Keeps ordinary dispatch and explicit replacement on separate configuration paths.
 * A retained task's candidate is authoritative even when current settings changed;
 * current candidates are exposed only to a caller that is handling replace_task_id.
 * These are construction candidates for separate run-local dispatch groups, NOT permission
 * to dispatch through, resume or transfer any task in the snapshot's historical coordinator.
 */
internal object ChildTaskOrdinaryDispatchSelection {
    data class Plan<C : Any>(
        val ordinary: List<ChildTaskConfigPolicy.Candidate<C>>,
        val replacement: List<ChildTaskConfigPolicy.Candidate<C>>,
        val retained: Boolean,
    )

    fun <C : Any> plan(
        current: List<ChildTaskConfigPolicy.Candidate<C>>,
        frozen: List<ChildTaskConfigPolicy.Candidate<C>>?,
        retainedTasks: Boolean,
    ): Plan<C> {
        if (!retainedTasks) return Plan(current, current, retained = false)
        // Missing or empty frozen state is a hard stop, never a current-config fallback.
        return Plan(frozen.orEmpty(), current, retained = true)
    }
}
