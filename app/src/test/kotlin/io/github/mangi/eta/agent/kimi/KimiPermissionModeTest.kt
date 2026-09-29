package io.github.mangi.eta.agent.kimi

import org.junit.Assert.assertEquals
import org.junit.Test

class KimiPermissionModeTest {

    @Test
    fun defaultsToYoloWhenUnsetOrBlank() {
        // 默认自动批准：否则 Kimi 侧等待人工确认，委派看起来一直不动。
        assertEquals(KimiPermissionMode.YOLO, KimiPermissionMode.resolve(null))
        assertEquals(KimiPermissionMode.YOLO, KimiPermissionMode.resolve(""))
        assertEquals(KimiPermissionMode.YOLO, KimiPermissionMode.resolve("   "))
        assertEquals(KimiPermissionMode.YOLO, KimiPermissionMode.current())
    }

    @Test
    fun invalidValuesFallBackToYolo() {
        assertEquals(KimiPermissionMode.YOLO, KimiPermissionMode.resolve("bogus"))
        assertEquals(KimiPermissionMode.YOLO, KimiPermissionMode.resolve("YOLO!"))
        assertEquals(KimiPermissionMode.YOLO, KimiPermissionMode.resolve("yolo2"))
    }

    @Test
    fun acceptsKnownModesAndNormalizesCaseAndWhitespace() {
        assertEquals(KimiPermissionMode.YOLO, KimiPermissionMode.resolve("yolo"))
        assertEquals(KimiPermissionMode.AUTO, KimiPermissionMode.resolve("AUTO"))
        assertEquals(KimiPermissionMode.MANUAL, KimiPermissionMode.resolve(" manual "))
        assertEquals(listOf("yolo", "auto", "manual"), KimiPermissionMode.all)
    }
}
