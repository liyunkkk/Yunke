package io.github.mangi.eta.agent.overlay

import android.app.Application
import android.view.WindowManager
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AgentOverlayWindowTest {
    /**
     * 胶囊浮层：顶部状态胶囊有确定尺寸档位、非模态、可识别；
     * 氛围光窗口全屏、触摸穿透、同样非模态。旧 orb/bubble 方案已下线。
     */
    @Test fun capsuleAndGlowStayNonModalAndIdentifiable() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val capsuleLp = AgentRuntimeService::class.java
                .getDeclaredMethod("capsuleLayoutParams").apply { isAccessible = true }
                .invoke(service) as WindowManager.LayoutParams
            // 收起态是固定尺寸（不是 WRAP_CONTENT），高度为收起档。
            assertTrue(capsuleLp.width > 0)
            assertTrue(capsuleLp.height > 0)
            assertEquals("Eta Agent Capsule", capsuleLp.title.toString())
            assertTrue(capsuleLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(capsuleLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertEquals(0, capsuleLp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            val glowLp = AgentRuntimeService::class.java
                .getDeclaredMethod("glowLayoutParams").apply { isAccessible = true }
                .invoke(service) as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, glowLp.width)
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, glowLp.height)
            assertEquals("Eta Agent Glow", glowLp.title.toString())
            // 氛围光是装饰层：必须触摸穿透，否则会挡住整屏操作。
            assertTrue(glowLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
            assertTrue(glowLp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertEquals(0, glowLp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            // 旧方案必须彻底下线。
            assertFalse(AgentRuntimeService::class.java.declaredMethods.any { it.name == "orbLayoutParams" })
            assertFalse(AgentRuntimeService::class.java.declaredMethods.any { it.name == "bubbleLayoutParams" })
            assertFalse(AgentRuntimeService::class.java.declaredFields.any { it.name == "orbView" })
            assertFalse(AgentRuntimeService::class.java.declaredFields.any { it.name == "bubbleView" })
            assertTrue(AgentRuntimeService::class.java.declaredFields.any { it.name == "capsuleView" })
        } finally { controller.destroy() }
    }
}
