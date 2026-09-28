package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ChildGroupContextStatsTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
        model = "child", systemPrompt = "", contextWindow = 1000)
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("fixture", name, args.toString())
    @Suppress("UNCHECKED_CAST")
    private fun groups() = AgentChildTaskGroups.javaClass.getDeclaredField("groups").apply { isAccessible = true }
        .get(AgentChildTaskGroups) as MutableMap<String, Any>
    private fun install(owner: String, run: String, generation: String, coordinator: SubAgentCoordinator): Any {
        val type = AgentChildTaskGroups.javaClass.declaredClasses.single { it.simpleName == "Group" }
        val ctor = type.declaredConstructors.single { it.parameterCount == 8 }.apply { isAccessible = true }
        val group = ctor.newInstance(owner, run, generation, "fixture-lease", coordinator, null, emptyList<Any>(), null)
        type.getDeclaredField("leaseHeld").apply { isAccessible = true }.set(group, false)
        synchronized(AgentChildTaskGroups) { groups()[generation] = group }
        return group
    }
    private fun awaitCondition(check: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(check())
    }

    @Test fun ownerTelemetryIsDirectPerTaskAndDistinguishesPendingFromConfirmedPause() {
        val owner = UUID.randomUUID().toString()
        val generation = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val boundary = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val coordinator = SubAgentCoordinator(listOf(model), modelParallelLimits = listOf(1)) { _, _, controller ->
            entered.countDown(); boundary.await(); controller.throwIfCancelled(); finish.await(); "done"
        }
        val group = install(owner, "run", generation, coordinator)
        try {
            fun start() = JSONObject(coordinator.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
            val running = start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = start()
            val initial = AgentChildTaskGroups.contextStats(owner).associateBy { it.taskId }
            assertEquals(setOf(running, queued), initial.keys)
            assertEquals("running", initial.getValue(running).status)
            assertEquals("queued", initial.getValue(queued).status)
            assertNull(initial.getValue(queued).contextTokens)

            coordinator.pauseGroup()
            val pending = AgentChildTaskGroups.contextStats(owner).associateBy { it.taskId }
            assertEquals("pausing", pending.getValue(running).status)
            assertEquals("awaiting_decision", pending.getValue(queued).status)
            assertTrue(pending.getValue(running).statusVersion > initial.getValue(running).statusVersion)
            assertTrue(pending.getValue(running).statusChangedAtMs!! >= initial.getValue(running).statusChangedAtMs!!)
            boundary.countDown()
            awaitCondition { AgentChildTaskGroups.contextStats(owner).first { it.taskId == running }.status == "awaiting_decision" }
            val confirmed = AgentChildTaskGroups.contextStats(owner).first { it.taskId == running }
            assertTrue(confirmed.statusVersion > pending.getValue(running).statusVersion)

            val handoffs = group.javaClass.getDeclaredField("handoffs").apply { isAccessible = true }.get(group) as ChildTaskHandoff
            assertNull(handoffs.readVersion(running))
            repeat(3) {
                assertEquals(confirmed, AgentChildTaskGroups.contextStats(owner).first { it.taskId == running })
                assertEquals(confirmed, SubAgentContextStats.fromJson(confirmed.toJson()))
            }
            assertNull(handoffs.readVersion(running))

            coordinator.resumeGroup()
            val resumed = AgentChildTaskGroups.contextStats(owner).first { it.taskId == running }
            assertEquals("running", resumed.status)
            assertTrue(resumed.statusVersion > confirmed.statusVersion)
            finish.countDown()
            awaitCondition { !coordinator.hasActiveTasks() }
            val terminal = AgentChildTaskGroups.contextStats(owner).associateBy { it.taskId }
            AgentChildTaskGroups.detach(generation)
            awaitCondition {
                synchronized(AgentChildTaskGroups) {
                    val snapshots = group.javaClass.getDeclaredField("snapshots").apply { isAccessible = true }.get(group) as Map<*, *>
                    snapshots.isNotEmpty()
                }
            }
            val retained = AgentChildTaskGroups.contextStats(owner).associateBy { it.taskId }
            assertEquals("completed", retained.getValue(running).status)
            assertEquals("completed", retained.getValue(queued).status)
            assertEquals(terminal, retained)
        } finally {
            boundary.countDown(); finish.countDown()
            coordinator.close()
            synchronized(AgentChildTaskGroups) { groups().remove(generation) }
        }
    }

    @Test fun queuedPauseResumePauseAdvancesVersionWithoutIntermediateReads() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val coordinator = SubAgentCoordinator(listOf(model), modelParallelLimits = listOf(1)) { _, _, controller ->
            entered.countDown(); release.await(); controller.throwIfCancelled(); "done"
        }
        try {
            fun start() = JSONObject(coordinator.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id")
            start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = start()
            coordinator.pauseGroup()
            val first = coordinator.contextStats().first { it.taskId == queued }
            assertEquals("awaiting_decision", first.status)
            // Simulate a conflating UI collector: no read between resume and the next pause.
            coordinator.resumeGroup()
            coordinator.pauseGroup()
            val second = coordinator.contextStats().first { it.taskId == queued }
            assertEquals(first.status, second.status)
            assertEquals(first.statusVersion + 2, second.statusVersion)
            assertTrue(second.statusChangedAtMs!! >= first.statusChangedAtMs!!)
            // Old JSON and unrelated copies must not mutate a live task's token.
            assertEquals(first, SubAgentContextStats.fromJson(first.toJson()))
            first.copy(status = "running", contextTokens = 123)
            repeat(3) { assertEquals(second, coordinator.contextStats().first { it.taskId == queued }) }
        } finally {
            release.countDown(); coordinator.close()
        }
    }

    @Test fun detachedUsageAndManualCompactionPublishRegistryRevisionWithoutParentSink() {
        val owner = UUID.randomUUID().toString()
        val generation = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observer = AtomicReference<((AgentEvent) -> Unit)?>(null)
        val parentSink = AtomicReference<((SubAgentContextStats) -> Unit)?>(null)
        val registryCallbacks = AtomicInteger()
        val coordinator = SubAgentCoordinator(listOf(model),
            onContext = { parentSink.get()?.invoke(it) },
            onTaskChanged = { AgentChildTaskGroups.onTaskChanged(generation); registryCallbacks.incrementAndGet() },
            executeObservedChild = { _, _, controller, _, _, _, event ->
                observer.set(event); entered.countDown(); release.await(); controller.throwIfCancelled(); "done"
            }, executeChild = { _, _, _ -> error("observed path expected") })
        install(owner, "run", generation, coordinator)
        try {
            val id = JSONObject(coordinator.execute(call("delegate_task", JSONObject().put("task", "measure"))).content).getString("task_id")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            AgentChildTaskGroups.detach(generation)
            val initial = AgentChildTaskGroups.contextStats(owner).single()
            val notify = requireNotNull(observer.get())
            val before = AgentChildTaskGroups.revision.value
            notify(AgentEvent.UsageReceived(1, AgentTokenUsage(contextTokens = 100, inputTokens = 100, outputTokens = 5)))
            awaitCondition { AgentChildTaskGroups.revision.value > before && AgentChildTaskGroups.contextStats(owner).single().contextTokens == 100 }
            val measured = AgentChildTaskGroups.contextStats(owner).single()
            assertEquals(initial.statusVersion, measured.statusVersion)
            assertEquals(initial.statusChangedAtMs, measured.statusChangedAtMs)

            // This rejected request has no explicit changed() at the requestCompact call site.
            // publishContext itself must invalidate the registry even without a parent sink.
            coordinator.pauseGroup()
            awaitCondition { AgentChildTaskGroups.contextStats(owner).single().status == "pausing" }
            val paused = AgentChildTaskGroups.contextStats(owner).single()
            val revision = AgentChildTaskGroups.revision.value
            val callbackCount = registryCallbacks.get()
            assertFalse(coordinator.requestCompact(id, null, null))
            awaitCondition {
                AgentChildTaskGroups.revision.value > revision && registryCallbacks.get() > callbackCount &&
                    AgentChildTaskGroups.contextStats(owner).single().manualCompactionState == "ended"
            }
            val manual = AgentChildTaskGroups.contextStats(owner).single()
            assertEquals(paused.statusVersion, manual.statusVersion)
            assertEquals(paused.statusChangedAtMs, manual.statusChangedAtMs)
            assertEquals(manual, SubAgentContextStats.fromJson(manual.toJson()))
        } finally {
            release.countDown(); coordinator.close()
            synchronized(AgentChildTaskGroups) { groups().remove(generation) }
        }
    }

    @Test fun telemetryIsOwnerScopedAcrossGenerations() {
        val owner = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val coordinators = List(3) { SubAgentCoordinator(listOf(model)) { _, _, _ -> "done" } }
        val generations = List(3) { UUID.randomUUID().toString() }
        coordinators.forEachIndexed { i, coordinator -> install(if (i < 2) owner else other, "run-$i", generations[i], coordinator) }
        try {
            val ids = coordinators.map { JSONObject(it.execute(call("delegate_task", JSONObject().put("task", "inspect"))).content).getString("task_id") }
            awaitCondition { coordinators.none { it.hasActiveTasks() } }
            assertEquals(ids.take(2).toSet(), AgentChildTaskGroups.contextStats(owner).map { it.taskId }.toSet())
            assertEquals(listOf(ids.last()), AgentChildTaskGroups.contextStats(other).map { it.taskId })
        } finally {
            coordinators.forEach { it.close() }
            synchronized(AgentChildTaskGroups) { generations.forEach { groups().remove(it) } }
        }
    }
}
