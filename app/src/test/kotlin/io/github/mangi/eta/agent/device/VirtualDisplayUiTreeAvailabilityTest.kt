package io.github.mangi.eta.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayUiTreeAvailabilityTest {
    private var now = 0L
    private val policy = VirtualDisplayUiTreeAvailability { now }

    private fun observe(runId: String = "run", displayId: Int = 2, packageName: String = "pkg", hasNodes: Boolean) =
        policy.let {
            it.updateScope(runId, displayId, packageName)
            it.record(hasNodes)
        }

    @Test
    fun singleEmptyObservationDoesNotDisableNodes() {
        assertFalse(observe(hasNodes = false))
        assertFalse(policy.unavailable())
    }

    @Test
    fun threeSpacedEmptyObservationsWithinGraceStillAvailable() {
        observe(hasNodes = false)
        now += 600
        assertFalse(observe(hasNodes = false))
        now += 600
        // 3 次但跨度只有 1.2s，未达 1.5s 宽限
        assertFalse(observe(hasNodes = false))
        assertFalse(policy.unavailable())
    }

    @Test
    fun threeSpacedEmptyObservationsBeyondGraceDisableNodes() {
        observe(hasNodes = false)
        now += 600
        observe(hasNodes = false)
        now += 1200
        assertTrue(observe(hasNodes = false))
        assertTrue(policy.unavailable())
    }

    @Test
    fun nodesReturningImmediatelyRestoreAvailability() {
        observe(hasNodes = false)
        now += 600
        observe(hasNodes = false)
        now += 1200
        assertTrue(observe(hasNodes = false))
        assertFalse(observe(hasNodes = true))
        assertFalse(policy.unavailable())
    }

    @Test
    fun scopeChangeClearsEvidence() {
        observe(hasNodes = false)
        now += 600
        observe(hasNodes = false)
        now += 1200
        assertTrue(observe(hasNodes = false))
        // 换应用后重新计数
        assertFalse(observe(packageName = "other", hasNodes = false))
        assertFalse(policy.unavailable())
    }

    @Test
    fun rapidEmptyObservationsDoNotAccumulateAttempts() {
        observe(hasNodes = false)
        now += 100
        observe(hasNodes = false)
        now += 100
        observe(hasNodes = false)
        now += 2000
        // 只有第一次和这次计入次数，仍未到 3 次
        assertFalse(observe(hasNodes = false))
        assertFalse(policy.unavailable())
    }
}
