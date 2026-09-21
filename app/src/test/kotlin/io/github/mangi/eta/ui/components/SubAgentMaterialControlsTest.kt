package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubAgentMaterialControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedMenuRowUsesTonalBackgroundInsteadOfCheckIcon() {
        val selectedColor = Color(0xFFB5D8F3)
        var clicked = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(secondaryContainer = selectedColor)) {
                Column(Modifier.width(220.dp)) {
                    SubAgentSelectionItem("复杂任务", true) { clicked = true }
                    SubAgentSelectionItem("简单任务", false) { }
                }
            }
        }
        val selected = compose.onNodeWithText("复杂任务").assertIsSelected()
        compose.onNodeWithText("简单任务").assertIsNotSelected()
        compose.onNodeWithContentDescription("当前分工").assertDoesNotExist()
        val pixels = selected.captureToImage().toPixelMap()
        assertEquals(selectedColor, pixels[pixels.width - 12, pixels.height / 2])
        selected.performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test fun onlyImplementationShowsTierControlsInSettings() {
        val role = mutableStateOf("implementation")
        compose.setContent {
            MaterialTheme {
                SubAgentProfileRow(SubAgentProfile("test", "测试代理", role = role.value), emptyList(), settings = true)
            }
        }
        compose.onNodeWithContentDescription("设置测试代理任务分工").assertExists()
        for (next in listOf("review", "image_generation", "video_generation")) {
            compose.runOnIdle { role.value = next }
            compose.onNodeWithText("任务分工").assertDoesNotExist()
            compose.onNodeWithText("未设置分工").assertDoesNotExist()
            compose.onNodeWithContentDescription("选择测试代理职责").assertExists()
        }
    }

    @Test fun nonImplementationConversationLabelHasNoTierMenu() {
        val role = mutableStateOf("review")
        compose.setContent {
            MaterialTheme { SubAgentProfileRow(SubAgentProfile("test", "测试代理", role = role.value), emptyList()) }
        }
        for (next in listOf("review", "image_generation", "video_generation")) {
            compose.runOnIdle { role.value = next }
            compose.onNodeWithContentDescription("测试代理模型").assertHasClickAction()
            compose.onNodeWithContentDescription("设置测试代理任务分工").assertDoesNotExist()
            compose.onNodeWithText("未设置分工").assertDoesNotExist()
        }
    }

    @Test fun selectedTierIsMarkedAndDisabledControlClosesMenu() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SubAgentTaskTierButton("执行", SubAgentTaskTier.COMPLEX, enabled.value, {}, Modifier.width(200.dp))
            }
        }
        compose.onNodeWithContentDescription("设置执行任务分工").performClick()
        compose.onNode(isSelectable() and hasText("复杂任务")).assertIsSelected()
        compose.onNodeWithContentDescription("当前分工").assertDoesNotExist()
        compose.runOnIdle { enabled.value = false }
        compose.onNode(isSelectable() and hasText("复杂任务")).assertDoesNotExist()
        compose.onNodeWithContentDescription("设置执行任务分工").assertIsNotEnabled()
    }
    @Test fun modelPickerHighlightsSelectionAndProviderRowsHaveNoPressRipple() {
        val selectedColor = Color(0xFFB5D8F3)
        val model = io.github.mangi.eta.ui.model.AgentModelOptionUi("m", "p", "测试提供商", "openai", "m", "测试模型", 10000)
        val picker = io.github.mangi.eta.ui.model.AgentModelPickerUiState(
            providerGroups = listOf(io.github.mangi.eta.ui.model.AgentModelProviderGroupUi("p", "测试提供商", "openai", listOf(model))),
            selectedModel = model)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(secondaryContainer = selectedColor)) {
                io.github.mangi.eta.ui.TtsModelPickerDialog(picker, true, {}, { _, _ -> }, "选择模型",
                    onClearSelection = {}, highlightSelection = true)
            }
        }
        val selected = compose.onNode(isSelectable() and hasText("测试模型")).assertIsSelected()
        val pixels = selected.captureToImage().toPixelMap()
        assertEquals(selectedColor, pixels[pixels.width - 12, pixels.height / 2])
        compose.onAllNodes(isSelectable()).assertCountEquals(2) // rows only; no separate radio widgets
        val header = compose.onNodeWithText("测试提供商")
        val before = header.captureToImage().toPixelMap()
        header.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(240)
        val pressed = header.captureToImage().toPixelMap()
        // Compare the entire provider row while held, not merely a corner outside the ripple.
        assertEquals(before.width, pressed.width)
        assertEquals(before.height, pressed.height)
        for (y in 0 until before.height) for (x in 0 until before.width) {
            assertEquals(before[x, y], pressed[x, y])
        }
        header.performTouchInput { cancel() }
    }

    @Test fun flatSettingsRowsPlaceValuesAfterAlignedLabels() {
        compose.setContent {
            MaterialTheme {
                SubAgentProfileRow(SubAgentProfile("test", "测试代理", tier = SubAgentTaskTier.COMPLEX), emptyList(), settings = true)
            }
        }
        val label = compose.onNodeWithText("职责", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("执行", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(value.left > label.right)
        val model = compose.onNodeWithContentDescription("测试代理模型").fetchSemanticsNode().boundsInRoot
        val role = compose.onNodeWithContentDescription("选择测试代理职责").fetchSemanticsNode().boundsInRoot
        val tier = compose.onNodeWithContentDescription("设置测试代理任务分工").fetchSemanticsNode().boundsInRoot
        assertTrue(role.top >= model.bottom)
        assertTrue(tier.top >= role.bottom)
        compose.onNodeWithContentDescription("设置测试代理任务分工").performClick()
        compose.onNode(isSelectable() and hasText("复杂任务")).assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun settingsAddActionStaysVisibleWhileAgentListScrolls() {
        compose.setContent { MaterialTheme { io.github.mangi.eta.ui.SubAgentSettingsScreen({}) } }
        compose.onNodeWithText("添加子代理").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        compose.onNodeWithText("添加子代理").assertIsDisplayed()
    }

}
