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

    @Test fun inlineRecoveryHasThreeWrappingMaterialActionsAndManualClosePreview() {
        val source = File(ui, "VirtualDisplayRecoveryScreen.kt").readText()
        val actions = source.substring(source.indexOf("FlowRow("))
        assertEquals(3, Regex("""TouchHaptics\.click\(view\)""").findAll(actions).count())
        assertEquals(3, Regex("""maxLines = 1""").findAll(actions).count())
        assertTrue(actions.contains("Button(") && actions.contains("OutlinedButton(") && actions.contains("TextButton("))
        assertTrue(source.contains("!present -> R.string.vd_recovery_empty"))
        assertTrue(source.contains("VirtualDisplayWebPreview.openWithManualClose(context)"))
        assertFalse(source.contains("VirtualDisplayWebPreview.open(context)"))
        assertTrue(source.contains("vd_preview_open"))
        assertFalse(source.contains("vd_preview_control_open"))
        for (explanation in listOf("vd_recovery_explanation", "vd_recovery_scope", "vd_preview_note")) {
            assertFalse(source.contains(explanation))
        }
        assertTrue(source.contains("recover(context.applicationContext)"))
        assertTrue(source.contains("VirtualDisplayWebPreview.stop()"))
    }
}
