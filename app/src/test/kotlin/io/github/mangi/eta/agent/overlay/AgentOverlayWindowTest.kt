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
    @Test fun orbAndControlsStaySmallNonModalAndIdentifiable() {
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = controller.get()
            for ((method, title) in listOf("orbLayoutParams" to "Eta Agent Orb", "bubbleLayoutParams" to "Eta Agent Controls")) {
                val lp = AgentRuntimeService::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(service) as WindowManager.LayoutParams
                assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, lp.width)
                assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, lp.height)
                assertEquals(title, lp.title.toString())
                assertTrue(lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertEquals(0, lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                assertEquals(0, lp.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
            assertFalse(AgentRuntimeService::class.java.declaredMethods.any { it.name == "glowLayoutParams" })
            assertFalse(AgentRuntimeService::class.java.declaredFields.any { it.name == "glowView" })
        } finally { controller.destroy() }
    }
}
