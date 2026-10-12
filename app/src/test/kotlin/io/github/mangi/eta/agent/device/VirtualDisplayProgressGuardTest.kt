package io.github.mangi.eta.agent.device

import android.graphics.Rect
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
    fun sameCoordinateWithNoScreenChangeTripsGuardOnEighthAttempt() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        // 坐标类动作即便回读含 screen_changed=false，也一律使用宽松阈值（8 次），避免误杀恒定界面
        for (i in 1..7) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
            assertFalse(guard.isPaused())
        }
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
        assertTrue(guard.isPaused())
        assertEquals("坐标模式下同一目标重复操作达到 8 次且无进展", guard.pauseReason())
    }

    @Test
    fun nullOrMissingScreenChangedDoesNotTripAtThreeAttempts() {
        val guard = VirtualDisplayProgressGuard()
        val fallbackSummary = JSONObject().put("requires_observation", true).put("note", "回读不可用")

        // 坐标模式下，前 3 次点击不能熔断
        for (i in 1..3) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), fallbackSummary, nodeBased = false)
            assertFalse(guard.isPaused())
        }

        // 持续重复到第 7 次仍不熔断
        for (i in 4..7) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), null, nodeBased = false)
            assertFalse(guard.isPaused())
        }

        // 第 8 次达到盲区上限熔断
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), null, nodeBased = false)
        assertTrue(guard.isPaused())
    }

    @Test
    fun blindActionsTripAtTwelveDifferentActions() {
        val guard = VirtualDisplayProgressGuard()
        for (i in 1..11) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(i * 100, i * 100), null, nodeBased = false)
            assertFalse(guard.isPaused())
        }
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(1200, 1200), null, nodeBased = false)
        assertTrue(guard.isPaused())
    }

    @Test
    fun coordinatesBeyondToleranceDoNotTripSameTarget() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(200, 200), noChangeSummary, nodeBased = false)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(300, 300), noChangeSummary, nodeBased = false)
        // 虽有 3 次动作，但目标不同，累计未达 12 次，不应熔断
        assertFalse(guard.isPaused())
    }

    @Test
    fun sameElementIndexTripsGuardOnThirdAttempt() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(5), noChangeSummary, nodeBased = true)
        assertFalse(guard.isPaused())
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(5), noChangeSummary, nodeBased = true)
        assertFalse(guard.isPaused())
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(5), noChangeSummary, nodeBased = true)
        assertTrue(guard.isPaused())
        assertEquals("同一目标重复操作达到 3 次且无进展", guard.pauseReason())
    }

    @Test
    fun nodeElementWithoutScreenChangedUsesLooseThreshold() {
        val guard = VirtualDisplayProgressGuard()
        val fallbackSummary = JSONObject().put("note", "回读不可用")

        for (i in 1..7) {
            guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(5), fallbackSummary, nodeBased = true)
            assertFalse(guard.isPaused())
        }
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(5), fallbackSummary, nodeBased = true)
        assertTrue(guard.isPaused())
        assertEquals("坐标模式下同一目标重复操作达到 8 次且无进展", guard.pauseReason())
    }

    @Test
    fun sixDifferentNodeActionsWithoutProgressTripsGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        for (i in 1..5) {
            guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(i), noChangeSummary, nodeBased = true)
            assertFalse(guard.isPaused())
        }
        // 第 6 次不同目标无进展动作
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(6), noChangeSummary, nodeBased = true)
        assertTrue(guard.isPaused())
        assertEquals("当前停滞窗口内累计动作达到 6 次且无进展", guard.pauseReason())
    }

    @Test
    fun twelveDifferentCoordinateActionsWithNoChangeTripGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")
        for (i in 1..11) {
            guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(i * 100, i * 100), noChangeSummary, nodeBased = false)
            assertFalse(guard.isPaused())
        }
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(1200, 1200), noChangeSummary, nodeBased = false)
        assertTrue(guard.isPaused())
        assertEquals("坐标模式下累计动作达到 12 次且无进展", guard.pauseReason())
    }

    @Test
    fun switchingBetweenNodeAndCoordinateClearsOppositeCounters() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        // 2 次节点动作（严格计数达到 2）
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(1), noChangeSummary, nodeBased = true)
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(1), noChangeSummary, nodeBased = true)
        assertFalse(guard.isPaused())

        // 切换到 1 次坐标动作：应清空严格计数
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
        assertFalse(guard.isPaused())

        // 再次进行之前的节点动作：因严格计数已被清空，此时累计为 1 次，不应熔断
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(1), noChangeSummary, nodeBased = true)
        assertFalse(guard.isPaused())
    }

    @Test
    fun pausedErrorMessageIncludesReason() {
        val guard = VirtualDisplayProgressGuard()
        assertEquals(VirtualDisplayProgressGuard.ERROR_MESSAGE, guard.pausedErrorMessage())

        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")
        for (i in 1..3) {
            guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(1), noChangeSummary, nodeBased = true)
        }
        assertTrue(guard.isPaused())
        val expected = "${VirtualDisplayProgressGuard.ERROR_MESSAGE}：同一目标重复操作达到 3 次且无进展"
        assertEquals(expected, guard.pausedErrorMessage())
    }

    @Test
    fun screenChangedResetsGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")
        val changedSummary = JSONObject().put("screen_changed", true).put("package", "com.test.app")

        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
        // 界面变化了！
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), changedSummary, nodeBased = false)
        assertFalse(guard.isPaused())

        // 重新计数
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
        assertFalse(guard.isPaused())
    }

    @Test
    fun packageChangedResetsGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")
        val pkgChangedSummary = JSONObject().put("screen_changed", false).put("package_changed", true).put("package", "com.other.app")

        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), noChangeSummary, nodeBased = false)
        guard.recordAction("tap", VirtualDisplayProgressGuard.ActionTarget.Point(100, 100), pkgChangedSummary, nodeBased = false)
        assertFalse(guard.isPaused())
    }

    @Test
    fun observationChangeResetsGuard() {
        val guard = VirtualDisplayProgressGuard()
        val noChangeSummary = JSONObject().put("screen_changed", false).put("package", "com.test.app")

        guard.onObservation("com.test.app", listOf(sampleNode(1, "A")))
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(1), noChangeSummary, nodeBased = true)
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(1), noChangeSummary, nodeBased = true)
        guard.recordAction("tap_element", VirtualDisplayProgressGuard.ActionTarget.Element(1), noChangeSummary, nodeBased = true)
        assertTrue(guard.isPaused())

        // observe_screen 观察到界面节点变化
        guard.onObservation("com.test.app", listOf(sampleNode(1, "B")))
        assertFalse(guard.isPaused())
    }
}
