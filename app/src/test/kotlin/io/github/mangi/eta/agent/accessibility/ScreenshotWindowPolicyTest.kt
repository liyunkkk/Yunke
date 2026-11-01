package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotWindowPolicyTest {
    @Test
    fun `only confirmed own accessibility overlay is excluded`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.EXCLUDE,
            decide(overlay = true, resolvedPackage = "io.github.mangi.eta"),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.CAPTURE,
            decide(overlay = true, resolvedPackage = "third.party.accessibility"),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.CAPTURE,
            decide(overlay = true, resolvedPackage = null),
        )
    }

    @Test
    fun `unknown active or focused window blocks package exclusion capture`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(active = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(focused = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.BLOCK_UNKNOWN,
            decide(application = true, resolvedPackage = null, excluded = setOf("entry.app")),
        )
    }

    @Test
    fun `confirmed excluded package is omitted regardless of window type`() {
        assertEquals(
            ScreenshotWindowPolicy.Decision.EXCLUDE,
            decide(resolvedPackage = "entry.app", excluded = setOf("entry.app")),
        )
    }

    @Test
    fun `own overlay title excludes application overlay windows`() {
        // 胶囊等浮窗改为 TYPE_APPLICATION_OVERLAY 后按标题排除，避免进入 Agent 截图。
        assertEquals(
            ScreenshotWindowPolicy.Decision.EXCLUDE,
            decide(resolvedPackage = "io.github.mangi.eta", windowTitle = "Eta Agent Capsule"),
        )
        assertEquals(
            ScreenshotWindowPolicy.Decision.EXCLUDE,
            decide(resolvedPackage = "io.github.mangi.eta", windowTitle = "Eta Agent Glow"),
        )
        // 同包名的普通窗口（比如我们自己的页面）不能被标题误伤。
        assertEquals(
            ScreenshotWindowPolicy.Decision.CAPTURE,
            decide(resolvedPackage = "io.github.mangi.eta", windowTitle = "设置"),
        )
        // 第三方窗口即使标题撞上前缀也不排除（包名不是自己）。
        assertEquals(
            ScreenshotWindowPolicy.Decision.CAPTURE,
            decide(resolvedPackage = "third.party", windowTitle = "Eta Agent Fake"),
        )
    }

    private fun decide(
        overlay: Boolean = false,
        application: Boolean = false,
        active: Boolean = false,
        focused: Boolean = false,
        resolvedPackage: String? = "normal.app",
        excluded: Set<String> = emptySet(),
        windowTitle: String? = null,
    ): ScreenshotWindowPolicy.Decision = ScreenshotWindowPolicy.decide(
        isAccessibilityOverlay = overlay,
        isApplicationWindow = application,
        active = active,
        focused = focused,
        resolvedPackage = resolvedPackage,
        ownPackage = "io.github.mangi.eta",
        excludedPackages = excluded,
        windowTitle = windowTitle,
    )
}
