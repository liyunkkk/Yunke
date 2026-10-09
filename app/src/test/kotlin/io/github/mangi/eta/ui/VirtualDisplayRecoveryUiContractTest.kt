package io.github.mangi.eta.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contract: recovery is a single inline control group, not two competing preview flows. */
class VirtualDisplayRecoveryUiContractTest {
    private val ui = File("src/main/kotlin/io/github/mangi/eta/ui")

    @Test fun modePreferencesHaveNoExplanationsOrRecoveryNavigation() {
        val source = File(ui, "AgentTaskPreferenceScreen.kt").readText()
        assertFalse(source.contains("summary ="))
        assertFalse(source.contains("onOpenRecovery"))
        assertFalse(source.contains("agent_task_surface_not_ready_hint"))
        assertTrue(source.contains("VirtualDisplayRecoveryControls("))
    }

    @Test fun inlineRecoveryHasWrappingActionsWithoutWebPreviewEntry() {
        val source = File(ui, "VirtualDisplayRecoveryScreen.kt").readText()
        val actions = source.substring(source.indexOf("FlowRow("))
        assertEquals(2, Regex("""TouchHaptics\.click\(view\)""").findAll(actions).count())
        assertEquals(2, Regex("""maxLines = 1""").findAll(actions).count())
        assertTrue(actions.contains("Button(") && actions.contains("TextButton("))
        assertTrue(source.contains("!present -> R.string.vd_recovery_empty"))
        // 跨设备网页预览入口已移除：不再有打开/撤销按钮，只保留后端被移除时的自动撤销。
        assertFalse(source.contains("VirtualDisplayWebPreview.openWithManualClose(context)"))
        assertFalse(source.contains("VirtualDisplayWebPreview.open(context)"))
        assertFalse(source.contains("vd_preview_open"))
        assertFalse(source.contains("vd_preview_revoke"))
        assertFalse(source.contains("vd_preview_control_open"))
        for (explanation in listOf("vd_recovery_explanation", "vd_recovery_scope", "vd_preview_note")) {
            assertFalse(source.contains(explanation))
        }
        assertTrue(source.contains("recover(context.applicationContext)"))
        assertFalse(source.contains("VirtualDisplayWebPreview.stop()"))
        assertTrue(source.contains("VirtualDisplayWebPreview.revoke(context)"))
        assertTrue(source.contains("val showWeb = installed == true || webPaired"))
        val leaving = source.substringAfter("DisposableEffect(Unit)").substringBefore("val snapshot")
        assertFalse(leaving.contains("VirtualDisplayWebPreview."))
    }
}
