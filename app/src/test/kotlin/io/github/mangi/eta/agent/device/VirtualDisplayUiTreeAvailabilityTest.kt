package io.github.mangi.eta.agent.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayUiTreeAvailabilityTest {
    private var now = 0L
    private val policy = VirtualDisplayUiTreeAvailability { now }

    private fun observe(
        runId: String = "run",
        displayId: Int = 2,
        packageName: String = "pkg",
        windowId: Int? = 1,
        ownerPackage: String = "pkg",
        hasNodes: Boolean,
    ) =
        policy.let {
            it.updateScope(runId, displayId, packageName)
            it.record(windowId, ownerPackage, hasNodes)
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
        assertFalse(observe(packageName = "other", ownerPackage = "other", hasNodes = false))
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

    @Test
    fun windowIdChangeResetsPrimaryEvidence() {
        observe(windowId = 1, hasNodes = false)
        now += 600
        observe(windowId = 1, hasNodes = false)
        now += 600
        // 换到新窗口 windowId=2：清空主证据后重新累计为第 1 次，不应禁用
        assertFalse(observe(windowId = 2, hasNodes = false))
        assertFalse(policy.unavailable())
        now += 600
        observe(windowId = 2, hasNodes = false)
        now += 1000
        // windowId=2 累计达到 3 次且跨度 >= 1.5s，触发禁用
        assertTrue(observe(windowId = 2, hasNodes = false))
        assertTrue(policy.unavailable())
    }

    @Test
    fun unattributedObservationsSafetyValve() {
        // windowId 为 null 且 ownerPackage 为空：无法归因
        for (i in 0 until 7) {
            assertFalse(observe(windowId = null, ownerPackage = "", hasNodes = false))
            assertFalse(policy.unavailable())
            now += 1200
        }
        // 第 8 次，跨度达到 7 * 1200 = 8400ms >= 8000ms，触发安全阀
        assertTrue(observe(windowId = null, ownerPackage = "", hasNodes = false))
        assertTrue(policy.unavailable())
    }

    @Test
    fun unattributedResetWhenValidWindowAppears() {
        for (i in 0 until 5) {
            observe(windowId = null, ownerPackage = "", hasNodes = false)
            now += 1200
        }
        // 出现有效归因，安全阀计数清空
        assertFalse(observe(windowId = 1, ownerPackage = "pkg", hasNodes = false))
        assertFalse(policy.unavailable())
    }

    @Test
    fun ownerPackagePresentWithoutWindowIdAccumulatesPrimary() {
        // windowId 为 null，但 ownerPackage 不为空，按真实证据走主规则
        observe(windowId = null, ownerPackage = "pkg", hasNodes = false)
        now += 600
        observe(windowId = null, ownerPackage = "pkg", hasNodes = false)
        now += 1000
        assertTrue(observe(windowId = null, ownerPackage = "pkg", hasNodes = false))
        assertTrue(policy.unavailable())
    }
}
