package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：节点动作校验必须按快照所在 display 取根。
 *
 * 旧实现固定用 rootInActiveWindow（默认屏），副屏快照必然不匹配其 windowId，
 * 导致副屏 replace_text / clear_text 恒返回 STALE_WINDOW。
 */
class NodeValidationDisplayTest {
    @Test
    fun defaultDisplayKeepsActiveWindowRoot() {
        assertFalse(NodeValidationDisplay.usesDisplayScopedRoot(android.view.Display.DEFAULT_DISPLAY))
        assertEquals(0, android.view.Display.DEFAULT_DISPLAY)
    }

    @Test
    fun virtualDisplaysRequireTheirOwnRoot() {
        listOf(1, 2, 4, 5, 6, 42).forEach { displayId ->
            assertTrue("display $displayId 必须用该 display 自己的根", NodeValidationDisplay.usesDisplayScopedRoot(displayId))
        }
    }
}
