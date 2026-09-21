package io.github.mangi.eta.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationCollaborationDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsRolesAndToggleWithoutObsoleteReadOnlyParagraph() {
        val enabled = mutableStateOf(false)
        val visible = mutableStateOf(true)
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(visible.value, enabled.value,
                    { enabled.value = it }, { visible.value = false })
            }
        }
        compose.onNodeWithText("本会话协作").assertExists()
        listOf("执行代理 1", "执行代理 2", "执行代理 3", "审查／总结代理").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("点按模型切换 · 长按调整思考", substring = true).assertExists()
        compose.onNodeWithText("最多两个只读子代理", substring = true).assertDoesNotExist()
        compose.onNodeWithText("自动委派").performClick()
        compose.runOnIdle { assertTrue(enabled.value) }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertFalse(visible.value) }
    }
    @Test fun emptySlotClickOpensModelPickerAndLongPressDoesNotTriggerClick() {
        val slot = 0
        val saved = io.github.mangi.eta.agent.delegation.SubAgentPreferences.selection(slot)
        val savedEffort = io.github.mangi.eta.agent.delegation.SubAgentPreferences.reasoning(slot)
        try {
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.save(slot,
                io.github.mangi.eta.agent.model.ModelFeatureSelection(true, "", ""))
            compose.setContent {
                MiuixTheme(colors = lightColorScheme()) {
                    ConversationCollaborationDialog(true, true, {}, {})
                }
            }
            compose.onNodeWithContentDescription("执行代理 1模型").performTouchInput { longClick() }
            compose.onNodeWithText("选择执行代理 1模型").assertDoesNotExist()
            compose.onNodeWithContentDescription("执行代理 1模型").performTouchInput { click() }
            compose.onNodeWithText("选择执行代理 1模型").assertExists()
            compose.onNode(hasText("无") and SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)).performClick()
            compose.onNodeWithText("本会话协作").assertExists()
            compose.onNodeWithContentDescription("执行代理 1模型").performTouchInput { longClick() }
            compose.onNodeWithText("调整思考深度").assertDoesNotExist()
            compose.onNodeWithText("选择执行代理 1模型").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(io.github.mangi.eta.agent.delegation.SubAgentPreferences.selection(slot).modelId.isBlank())
            }
        } finally {
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.save(slot, saved)
            io.github.mangi.eta.agent.delegation.SubAgentPreferences.saveReasoning(slot, savedEffort)
        }
    }

    @Test fun runningTaskDisablesAllControlsAndClosesOpenPicker() {
        val running = mutableStateOf(false)
        var changes = 0
        var dismissals = 0
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(true, true, { changes++ }, { dismissals++ }, taskRunning = running.value)
            }
        }
        compose.onNodeWithContentDescription("执行代理 1模型").performClick()
        compose.onNodeWithText("选择执行代理 1模型").assertExists()
        compose.runOnIdle { running.value = true }
        compose.onNodeWithText("选择执行代理 1模型").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("执行代理 1").assertIsNotEnabled()
        compose.onNodeWithContentDescription("执行代理 1模型").performTouchInput { click(); longClick() }
        compose.runOnIdle { org.junit.Assert.assertEquals("disabled model row must not dismiss", 0, dismissals) }
        compose.onNodeWithText("自动委派").performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals("disabled toggle must not dismiss", 0, dismissals) }
        compose.onNodeWithText("完成").performTouchInput { click() }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(0, changes)
            org.junit.Assert.assertEquals(0, dismissals)
            running.value = false
        }
        compose.onNodeWithText("执行代理 1").assertIsEnabled()
        compose.onNodeWithText("完成").assertIsEnabled().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, dismissals) }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun lockedDialogKeepsDisabledDoneButtonInsideSmallViewport() {
        var dismissals = 0
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(true, true, {}, { dismissals++ }, taskRunning = true)
            }
        }
        compose.onNodeWithText("完成").assertIsDisplayed().assertIsNotEnabled()
            .performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals(0, dismissals) }
    }

    @Test fun taskTierMenuHasThreeChoicesAndClosesWhenTaskStarts() {
        val running = mutableStateOf(false)
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                ConversationCollaborationDialog(true, true, {}, {}, taskRunning = running.value)
            }
        }
        compose.onNodeWithContentDescription("设置执行代理 1任务分工").performClick()
        listOf("简单任务", "常规任务", "复杂任务").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("选择执行代理 1模型").assertDoesNotExist()
        compose.runOnIdle { running.value = true }
        compose.onNodeWithText("复杂任务").assertDoesNotExist()
        compose.onNodeWithText("执行代理 1").assertIsNotEnabled()
    }

    @Test fun agentModelAndNameShareLeftColumnAndTierLivesOnRight() {
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                ConversationCollaborationDialog(true, true, {}, {})
            }
        }
        val info = compose.onNodeWithContentDescription("执行代理 1模型").fetchSemanticsNode().boundsInRoot
        val tier = compose.onNodeWithContentDescription("设置执行代理 1任务分工").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("tier must be to the right of the model column", tier.left >= info.right)
        org.junit.Assert.assertTrue("name and model belong to the same column", compose
            .onNodeWithContentDescription("执行代理 1模型").fetchSemanticsNode().config
            .contains(androidx.compose.ui.semantics.SemanticsProperties.Text))
        compose.onNodeWithContentDescription("设置审查／总结代理任务分工").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun compactTierKeepsAccessibleTouchTargetAndDoneStaysVisible() {
        compose.setContent {
            androidx.compose.material3.MaterialTheme {
                ConversationCollaborationDialog(true, true, {}, {})
            }
        }
        compose.onNodeWithContentDescription("设置执行代理 1任务分工")
            .assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
            .assertWidthIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose.onNodeWithText("完成").assertIsDisplayed()
        compose.onNodeWithContentDescription("审查／总结代理模型").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsDisplayed()
    }

}
