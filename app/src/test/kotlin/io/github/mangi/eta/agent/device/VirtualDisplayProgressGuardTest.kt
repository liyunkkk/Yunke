package io.github.mangi.eta.agent.device

import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayProgressGuardTest {

    private fun sampleNode(index: Int, text: String): UiNode =
        UiNode(
            index = index,
            text = text,
            desc = "",
            className = "android.widget.TextView",
            packageName = "com.test.app",
            viewId = "id_$index",
            bounds = Rect(0, 0, 100, 100),
            clickable = true,
            longClickable = false,
            scrollable = false,
            focused = false,
            editable = false,
            password = false,
            enabled = true,
        )

    @Test
    fun initiallyNotPaused() {
        val guard = VirtualDisplayProgressGuard()
        assertFalse(guard.isPaused())
    }

    @Test
    fun sameCoordinateWithNoScreenChangeTripsGuardOnThirdAttempt() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        // 第一次点击 (100, 100)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        assertFalse(guard.isPaused())

        // 第二次点击 (130, 120)，距离在 48px 内
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(130, 120), noChangeSummary)
        assertFalse(guard.isPaused())

        // 第三次点击 (90, 110)，距离在 48px 内 -> 达到 3 次，熔断！
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(90, 110), noChangeSummary)
        assertTrue(guard.isPaused())
    }

    @Test
    fun nullOrMissingScreenChangedDoesNotTripAtThreeAttempts() {
        val guard = VirtualDisplayProgressGuard()
        val fallbackSummary = JSONObject().put("requires_observation", true).put("note", "回读不可用")

        // 坐标模式下，前 3 次点击不能熔断
        for (i in 1..3) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), fallbackSummary)
            assertFalse(guard.isPaused())
        }

        // 持续重复到第 7 次仍不熔断
        for (i in 4..7) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), null)
            assertFalse(guard.isPaused())
        }

        // 第 8 次达到盲区上限熔断
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), null)
        assertTrue(guard.isPaused())
    }

    @Test
    fun blindActionsTripAtTwelveDifferentActions() {
        val guard = VirtualDisplayProgressGuard()
        for (i in 1..11) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(i * 100, i * 100), null)
            assertFalse(guard.isPaused())
        }
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(1200, 1200), null)
        assertTrue(guard.isPaused())
    }

    @Test
    fun coordinatesBeyondToleranceDoNotTripSameTarget() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(200, 200), noChangeSummary)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(300, 300), noChangeSummary)
        // 虽有 3 次动作，但目标不同，累计未达 6 次，不应熔断
        assertFalse(guard.isPaused())
    }

    @Test
    fun sameElementIndexTripsGuardOnThirdAttempt() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(5), noChangeSummary)
        assertFalse(guard.isPaused())
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(5), noChangeSummary)
        assertFalse(guard.isPaused())
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(5), noChangeSummary)
        assertTrue(guard.isPaused())
    }

    @Test
    fun sixDifferentActionsWithoutProgressTripsGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        for (i in 1..5) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(i * 100, i * 100), noChangeSummary)
            assertFalse(guard.isPaused())
        }
        // 第 6 次不同目标无进展动作
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(600, 600), noChangeSummary)
        assertTrue(guard.isPaused())
    }

    @Test
    fun screenChangedResetsGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")
        val changedSummary = JSONObject().put("screen_changed", true).put("package", "com.test.app")

        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        // 界面变化了！
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), changedSummary)
        assertFalse(guard.isPaused())

        // 重新计数
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        assertFalse(guard.isPaused())
    }

    @Test
    fun packageChangedResetsGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")
        val pkgChangedSummary = JSONObject().put("screen_changed", false).put("package_changed", true).put("package", "com.other.app")

        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), pkgChangedSummary)
        assertFalse(guard.isPaused())
    }

    @Test
    fun observationChangeResetsGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        guard.onObservation("com.test.app", listOf(sampleNode(1, "A")))
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary)
        assertTrue(guard.isPaused())

        // observe_screen 观察到界面节点变化
        guard.onObservation("com.test.app", listOf(sampleNode(1, "B")))
        assertFalse(guard.isPaused())
    }
}
