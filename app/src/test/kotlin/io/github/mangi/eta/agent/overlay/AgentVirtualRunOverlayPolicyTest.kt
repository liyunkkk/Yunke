package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVirtualRunOverlayPolicyTest {
    /**
     * 移植 Eta 胶囊后的口径：浮层跟随「是否拥有手机操作权」，与任务落在主屏还是后台虚拟屏无关。
     * 后台虚拟屏恰恰最需要胶囊（用户在主屏正常使用，胶囊提供微缩镜与接管入口）。
     * 因此三种 surface 的显现/记录/结果卡判定必须与 FOREGROUND 完全一致。
     */
    @Test
    fun `every surface mode shares the same overlay presentation rules`() {
        for (mode in AgentTaskSurfaceMode.entries) {
            assertTrue("$mode", AgentOverlayVisibilityPolicy.allowsOverlay(mode))
            for (event in toolEvents()) {
                assertEquals(
                    "$mode: $event",
                    AgentOverlayVisibilityPolicy.shouldRevealFor(event),
                    AgentOverlayVisibilityPolicy.shouldRevealFor(event, mode),
                )
                assertEquals(
                    "$mode: $event",
                    AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event),
                    AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event, mode),
                )
                for (ready in listOf(false, true)) {
                    assertEquals(
                        "$mode: $event, ready=$ready",
                        AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, ready),
                        AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, ready, mode),
                    )
                }
            }
            assertFalse(AgentOverlayVisibilityPolicy.shouldShowResultCard(mode, false))
            assertTrue(AgentOverlayVisibilityPolicy.shouldShowResultCard(mode, true))
        }
    }

    /** 副屏会话的生命周期工具也要触发显现，否则后台虚拟屏任务没有接管入口。 */
    @Test
    fun `virtual display tools reveal the overlay in background mode`() {
        val mode = AgentTaskSurfaceMode.BACKGROUND
        for (name in listOf("start_virtual_session", "keep_virtual_result", "finish_virtual_session", "present_to_user")) {
            assertTrue(name, AgentOverlayVisibilityPolicy.shouldRevealFor(AgentEvent.ToolStarted(1, "call", name, "{}"), mode))
            assertTrue(name, AgentOverlayVisibilityPolicy.shouldRevealFor(AgentEvent.ToolFinished(1, "call", name, "ok", 0, 0), mode))
        }
    }

    @Test
    fun `foreground mode preserves existing tool and terminal presentation rules`() {
        val mode = AgentTaskSurfaceMode.FOREGROUND
        assertTrue(AgentOverlayVisibilityPolicy.allowsOverlay(mode))
        for (event in toolEvents()) {
            assertEquals(
                AgentOverlayVisibilityPolicy.shouldRevealFor(event),
                AgentOverlayVisibilityPolicy.shouldRevealFor(event, mode),
            )
            assertEquals(
                AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event),
                AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event, mode),
            )
            for (ready in listOf(false, true)) {
                assertEquals(
                    AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, ready),
                    AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(event, ready, mode),
                )
            }
        }
        assertFalse(AgentOverlayVisibilityPolicy.shouldShowResultCard(mode, false))
        assertTrue(AgentOverlayVisibilityPolicy.shouldShowResultCard(mode, true))
    }

    private fun toolEvents(): List<AgentEvent> = listOf(
        "launch_app", "open_uri", "observe_screen", "tap", "tap_area", "tap_element",
        "long_press", "long_press_element", "swipe", "scroll", "scroll_element",
        "input_text", "replace_text", "clear_text", "paste_text", "press_key",
        "open_system_panel", "set_alarm", "set_timer", "run_command", "search_apps",
    ).flatMap { name ->
        listOf(
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = name),
            AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = name, contentChars = 2),
            AgentEvent.AssistantReceived(1, 0, "", listOf(name)),
            AgentEvent.ToolStarted(1, "call", name, "{}"),
            AgentEvent.ToolFinished(1, "call", name, "ok", 0, 0),
            AgentEvent.ToolImagesAttached(1, name, 1, 1024),
        )
    }
}
