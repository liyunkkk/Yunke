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
    @Test fun capsuleStaysSmallNonModalAndIdentifiable() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            val lp = AgentRuntimeService::class.java.getDeclaredMethod("capsuleLayoutParams")
                .apply { isAccessible = true }.invoke(service) as WindowManager.LayoutParams
            assertEquals("Eta Agent Capsule", lp.title.toString())
            assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
            assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
            assertEquals(0, lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            assertEquals(0, lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            // 旧光球/气泡窗口已彻底移除。
            assertFalse(AgentRuntimeService::class.java.declaredMethods.any { it.name == "orbLayoutParams" })
            assertFalse(AgentRuntimeService::class.java.declaredMethods.any { it.name == "bubbleLayoutParams" })
            assertFalse(AgentRuntimeService::class.java.declaredFields.any { it.name == "orbView" })
            assertFalse(AgentRuntimeService::class.java.declaredFields.any { it.name == "bubbleView" })
        } finally { controller.destroy() }
    }
}
