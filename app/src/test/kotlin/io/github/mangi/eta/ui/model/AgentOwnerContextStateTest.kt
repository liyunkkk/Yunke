package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.ui.model.AgentOwnerContextState.TaskSnapshot
import org.junit.Assert.*
import org.junit.Test

class AgentOwnerContextStateTest {
    private var now = 0L
    private val owner = "conversation-one"
    private fun state() = AgentOwnerContextState(owner) { now }
    private fun stats(id: String, status: String = "running", tokens: Int? = 123) = SubAgentContextStats(
        taskId = id, worker = 1, role = "implementation", model = "same-model",
        modelName = "Model", providerName = "Provider", contextWindow = 100_000,
        contextTokens = tokens, status = status, agentId = "same-agent",
    )
    private fun AgentOwnerContextState.ids() = projection().children.map { it.taskId }

    @Test fun mergesCrossRoundActiveTasksWithoutDeduplicatingWorkerOrModel() {
        val state = state()
        val a = "5cc942de-b9ef-4084-b124-9673e7320745"
        val b = "5c8398-other-task"
        state.refresh(owner, 1, listOf(stats(a), stats(b)))
        state.select(owner, a)
        // A later parent run can deliver only its newly created tasks.
        state.refresh(owner, 2, listOf(stats("F"), stats("G", "queued", null), stats("H")))
        assertEquals(listOf(a, b, "F", "G", "H"), state.ids())
        assertEquals(a, state.projection().selectedTaskId)
        assertNull(state.latest("G")!!.contextTokens)
        assertEquals(1, state.projection().children.map { it.worker }.distinct().size)
        assertEquals(1, state.projection().children.map { it.model }.distinct().size)
    }

    @Test fun deduplicatesOnlyTaskIdAndKeepsOrderAndLatestUsage() {
        val state = state()
        state.refresh(owner, 1, listOf(stats("A"), stats("B")))
        state.refresh(owner, 2, listOf(stats("A", tokens = 321), stats("A", tokens = 456)))
        assertEquals(listOf("A", "B"), state.ids())
        assertEquals(456, state.latest("A")!!.contextTokens)
        assertFalse(state.refresh(owner, 1, listOf(stats("A", "completed", 1))))
        assertEquals("running", state.latest("A")!!.status)
        assertEquals(456, state.latest("A")!!.contextTokens)
    }

    @Test fun ownerIsolationIncludesDelayedReadsSelectionsAndTimers() {
        val first = state()
        val second = AgentOwnerContextState("conversation-two") { now }
        first.refresh(owner, 1, listOf(stats("shared-id", "awaiting_decision")))
        val oldTimer = first.pendingHides().single()
        second.refresh("conversation-two", 1, listOf(stats("shared-id", "awaiting_decision")))
        assertFalse(second.refresh(owner, 999, listOf(stats("foreign"))))
        assertFalse(second.select(owner, "shared-id"))
        now = 30_000
        assertFalse(second.expire(oldTimer))
        assertEquals("awaiting_decision", second.latest("shared-id")!!.status)
        // A foreign high revision must not poison this owner's next read.
        second.refresh("conversation-two", 2, listOf(stats("shared-id", tokens = 900)))
        assertEquals(listOf("shared-id"), second.ids())
        assertNull(second.latest("foreign"))
        assertNull(second.projection().selectedTaskId)
    }

    @Test fun queuedRunningAndPausingNeverStartHideTimer() {
        for (status in listOf("queued", "running", "pausing")) {
            val state = state()
            state.refresh(owner, 1, listOf(stats(status, status, null)))
            now += 120_000
            state.refresh(owner, 2, listOf(stats(status, status, null)))
            assertEquals(listOf(status), state.ids())
            assertTrue(state.pendingHides().isEmpty())
        }
    }

    @Test fun pauseConfirmationStartsTimerNotPauseRequestOrFrozenQueue() {
        val state = state()
        state.refresh(owner, 1, listOf(stats("Q", "queued", null)))
        now = 5_000
        state.refresh(owner, 2, listOf(stats("Q", "pausing", null)))
        now = 40_000
        assertEquals(listOf("Q"), state.ids())
        assertTrue(state.pendingHides().isEmpty())
        state.refresh(owner, 3, listOf(stats("Q", "awaiting_decision", null)))
        val token = state.pendingHides().single()
        assertEquals(70_000L, token.deadlineMs)
        now = 69_999
        assertEquals(listOf("Q"), state.ids())
        assertFalse(state.expire(token))
        now = 70_000
        assertTrue(state.expire(token))
        assertTrue(state.ids().isEmpty())
        assertNull(state.latest("Q")!!.contextTokens)
        assertEquals("awaiting_decision", state.latest("Q")!!.status)
    }

    @Test fun terminalAndConfirmedPauseHideAtExactlyThirtySeconds() {
        for (status in listOf("completed", "cancelled", "timed_out", "failed", "awaiting_decision", "paused")) {
            now = 0
            val state = state()
            state.refresh(owner, 1, listOf(stats("task", status)))
            val token = state.pendingHides().single()
            now = 29_999
            assertEquals(listOf("task"), state.ids())
            assertEquals(1L, state.remainingMs(token))
            now = 30_000
            assertTrue(state.expire(token))
            assertFalse(state.expire(token))
            assertTrue(state.ids().isEmpty())
            // UI-only hiding keeps the real measured record and is not cancellation.
            assertEquals(status, state.latest("task")!!.status)
            assertEquals(123, state.latest("task")!!.contextTokens)
        }
    }

    @Test fun newRunPollingAndUsageChangesDoNotRestartOldPauseTimer() {
        val state = state()
        state.refresh(owner, 1, listOf(stats("A", "awaiting_decision")))
        val original = state.pendingHides().single()
        now = 10_000
        state.refresh(owner, 2, listOf(stats("F"), stats("G"), stats("H")))
        now = 20_000
        state.refresh(owner, 3, listOf(stats("A", "awaiting_decision", 456)))
        assertEquals(original, state.pendingHides().single())
        now = 30_000
        assertTrue(state.expire(original))
        state.refresh(owner, 4, listOf(stats("A", "awaiting_decision", 789)))
        assertEquals(listOf("F", "G", "H"), state.ids())
        assertTrue(state.pendingHides().isEmpty())
        assertEquals(789, state.latest("A")!!.contextTokens)
    }

    @Test fun pauseResumePauseRejectsOldTimersAndLateSnapshots() {
        val state = state()
        state.refresh(owner, 1, listOf(stats("A", "awaiting_decision")))
        val firstPause = state.pendingHides().single()
        now = 5_000
        state.refresh(owner, 2, listOf(stats("A", tokens = 500)))
        state.select(owner, "A")
        now = 10_000
        state.refresh(owner, 3, listOf(stats("A", "awaiting_decision", 600)))
        val secondPause = state.pendingHides().single()
        assertNotEquals(firstPause.statusVersion, secondPause.statusVersion)
        assertEquals(40_000L, secondPause.deadlineMs)
        assertFalse(state.refresh(owner, 1, listOf(stats("A", "awaiting_decision", 1))))
        now = 30_000
        assertFalse(state.expire(firstPause))
        assertEquals(listOf("A"), state.ids())
        assertEquals("A", state.projection().selectedTaskId)
        assertEquals(600, state.latest("A")!!.contextTokens)
        now = 40_000
        assertTrue(state.expire(secondPause))
        assertTrue(state.ids().isEmpty())
        assertNull(state.projection().selectedTaskId)
    }

    @Test fun hiddenPauseReappearsWhenContinuationReusesTheStatusToken() {
        val state = state()
        state.refresh(owner, listOf(TaskSnapshot(stats("A", "awaiting_decision"), 1, 4)))
        now = 30_000
        assertTrue(state.expire(state.pendingHides().single()))
        assertTrue(state.ids().isEmpty())
        val resumed = stats("A", "running", 2_345).copy(statusVersion = 4)
        assertTrue(state.refresh(owner, listOf(TaskSnapshot(resumed, 2, 4))))
        assertEquals(listOf("A"), state.ids())
        assertEquals("running", state.latest("A")!!.status)
        assertEquals(2_345, state.latest("A")!!.contextTokens)
        assertTrue(state.pendingHides().isEmpty())
    }

    @Test fun hiddenPauseStaysHiddenUntilExecuting() {
        val state = state()
        state.refresh(owner, listOf(TaskSnapshot(stats("A", "awaiting_decision"), 1, 4)))
        now = 30_000
        assertTrue(state.expire(state.pendingHides().single()))
        assertTrue(state.refresh(owner, listOf(TaskSnapshot(stats("A", "queued"), 2, 5))))
        assertTrue(state.ids().isEmpty())
        assertTrue(state.refresh(owner, listOf(TaskSnapshot(stats("A", "pausing"), 3, 6))))
        assertTrue(state.ids().isEmpty())
        assertTrue(state.refresh(owner, listOf(TaskSnapshot(stats("A", "running"), 4, 7))))
        assertEquals(listOf("A"), state.ids())
    }

    @Test fun hiddenPauseResumesWithRealUsageWithoutStealingSelection() {
        val state = state()
        state.refresh(owner, 1, listOf(stats("A", "awaiting_decision"), stats("B")))
        state.select(owner, "A")
        now = 30_000
        assertTrue(state.expire(state.pendingHides().single()))
        assertNull(state.projection().selectedTaskId)
        assertTrue(state.select(owner, "B"))
        state.refresh(owner, 2, listOf(stats("A", tokens = 2_345)))
        assertEquals(listOf("A", "B"), state.ids())
        assertEquals("running", state.latest("A")!!.status)
        assertEquals(2_345, state.latest("A")!!.contextTokens)
        assertEquals("B", state.projection().selectedTaskId)
        assertTrue(state.pendingHides().isEmpty())
    }

    @Test fun otherTaskExpiryDoesNotResetManualSelection() {
        val state = state()
        state.refresh(owner, 1, listOf(stats("A", "completed"), stats("B")))
        state.select(owner, "B")
        val token = state.pendingHides().single()
        now = 30_000
        state.expire(token)
        assertEquals("B", state.projection().selectedTaskId)
        state.refresh(owner, 2, emptyList())
        state.refresh(owner, 3, listOf(stats("C")))
        assertEquals("B", state.projection().selectedTaskId)
        state.select(owner, null)
        state.refresh(owner, 4, listOf(stats("D")))
        assertNull(state.projection().selectedTaskId)
    }

    @Test fun delayedTimerStillHidesOnProjectionAndResumptionDoesNotAutoSelect() {
        val state = state()
        state.refresh(owner, 1, listOf(stats("A", "awaiting_decision")))
        state.select(owner, "A")
        now = 30_001
        assertTrue(state.ids().isEmpty())
        assertNull(state.projection().selectedTaskId)
        state.refresh(owner, 2, listOf(stats("A")))
        assertEquals(listOf("A"), state.ids())
        assertNull(state.projection().selectedTaskId)
    }

    @Test fun unknownProjectedAndInvalidatedUsageNeverInventsAnEstimate() {
        val state = state()
        state.refresh(owner, 1, listOf(stats("A", tokens = null).copy(inputTokens = 10_000, outputTokens = 20_000)))
        assertNull(state.latest("A")!!.contextTokens)
        state.refresh(owner, 2, listOf(stats("A", tokens = 1_111)))
        assertEquals(1_111, state.latest("A")!!.contextTokens)
        state.refresh(owner, 3, listOf(stats("A", tokens = 50).copy(
            projected = true, beforeCompactionTokens = 1_111, afterCompactionTokens = 50,
        )))
        assertNull(state.latest("A")!!.contextTokens)
        assertNull(state.latest("A")!!.beforeCompactionTokens)
        assertNull(state.latest("A")!!.afterCompactionTokens)
        state.refresh(owner, 4, listOf(stats("A", tokens = 777)))
        state.refresh(owner, 5, listOf(stats("A", tokens = null)))
        assertNull(state.latest("A")!!.contextTokens)
        state.refresh(owner, 6, listOf(stats("A", tokens = -1).copy(contextWindow = 0)))
        assertNull(state.latest("A")!!.contextTokens)
        assertNull(state.latest("A")!!.contextWindow)
    }

    @Test fun explicitVersionsHandleAnUnobservedResumeBetweenTwoPausedSnapshots() {
        val state = state()
        state.refresh(owner, listOf(TaskSnapshot(stats("A", "awaiting_decision"), 1, 1)))
        val firstPause = state.pendingHides().single()
        now = 10_000
        // The registry observed running(v2) then awaiting_decision(v3), UI saw only v3.
        state.refresh(owner, listOf(TaskSnapshot(stats("A", "awaiting_decision", 999), 3, 3)))
        val secondPause = state.pendingHides().single()
        assertNotEquals(firstPause, secondPause)
        now = 30_000
        assertFalse(state.expire(firstPause))
        assertEquals(listOf("A"), state.ids())
        now = 40_000
        assertTrue(state.expire(secondPause))
    }

    @Test fun explicitVersionRejectsLateStatusWithNewDeliveryRevision() {
        val state = state()
        state.refresh(owner, listOf(TaskSnapshot(stats("A", "awaiting_decision"), 1, 1)))
        state.refresh(owner, listOf(TaskSnapshot(stats("A", tokens = 222), 2, 2)))
        assertFalse(state.refresh(owner, listOf(TaskSnapshot(stats("A", "awaiting_decision"), 10, 1))))
        assertFalse(state.refresh(owner, listOf(TaskSnapshot(stats("A", "completed"), 11, 2))))
        assertEquals("running", state.latest("A")!!.status)
        assertEquals(222, state.latest("A")!!.contextTokens)
        assertTrue(state.pendingHides().isEmpty())
    }

    @Test fun sourceTransitionTimestampSurvivesMenuOpeningLateAndUsageRefreshes() {
        now = 20_000
        val state = state()
        state.refresh(owner, listOf(TaskSnapshot(stats("A", "awaiting_decision"), 1, 7, 1_000)))
        val token = state.pendingHides().single()
        assertEquals(31_000L, token.deadlineMs)
        now = 25_000
        state.refresh(owner, listOf(TaskSnapshot(stats("A", "awaiting_decision", 500), 2, 7, 25_000)))
        assertEquals(token, state.pendingHides().single())
        assertEquals(6_000L, state.remainingMs(token))
    }

    @Test fun richSnapshotsDeduplicateByHighestTaskRevisionRegardlessOfListOrder() {
        for (reverse in listOf(false, true)) {
            val state = state()
            val snapshots = listOf(
                TaskSnapshot(stats("A", tokens = 100), 1, 1),
                TaskSnapshot(stats("A", tokens = 300), 3, 1),
                TaskSnapshot(stats("A", tokens = 200), 2, 1),
                TaskSnapshot(stats("B", tokens = 400), 4, 1),
            )
            state.refresh(owner, if (reverse) snapshots.reversed() else snapshots)
            assertEquals(setOf("A", "B"), state.ids().toSet())
            assertEquals(300, state.latest("A")!!.contextTokens)
        }
    }

    @Test fun invalidSelectionsAndBlankTaskIdsCannotLeakIntoMenu() {
        val state = state()
        state.refresh(owner, 1, listOf(stats(""), stats(" "), stats("A")))
        assertEquals(listOf("A"), state.ids())
        assertTrue(state.select(owner, "A"))
        assertFalse(state.select(owner, "missing"))
        assertEquals("A", state.projection().selectedTaskId)
        assertFalse(state.select("other-owner", null))
        assertEquals("A", state.projection().selectedTaskId)
    }
}
