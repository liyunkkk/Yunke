package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：只凭「实例存在」不算可用。
 *
 * 重装 APK 后实例存在但窗口缓存为空的场景必须上报不可用，否则后端不会重绑，
 * 副屏节点能力会一直静默失效（真机实测过）。
 */
class AccessibilityConnectionHealthTest {
    @Test
    fun connectedInstanceWithUsableWindowsIsHealthy() {
        assertTrue(accessibilityConnectionHealthy(instanceAvailable = true, defaultDisplayWindowsUsable = true))
    }

    @Test
    fun connectedInstanceWithEmptyWindowCacheIsNotHealthy() {
        assertFalse(accessibilityConnectionHealthy(instanceAvailable = true, defaultDisplayWindowsUsable = false))
    }

    @Test
    fun missingInstanceIsNeverHealthy() {
        assertFalse(accessibilityConnectionHealthy(instanceAvailable = false, defaultDisplayWindowsUsable = false))
        assertFalse(accessibilityConnectionHealthy(instanceAvailable = false, defaultDisplayWindowsUsable = true))
    }
}
