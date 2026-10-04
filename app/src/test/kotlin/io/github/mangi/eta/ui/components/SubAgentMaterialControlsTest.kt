package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

    @Test fun selectedMenuRowUsesGrayBackgroundInsteadOfCheckIcon() {
        val selectedColor = Color(0xFFE7E5E6)
        var clicked = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surfaceVariant = selectedColor)) {
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

    @Test fun externalSettingsNeverShowsTierControls() {
        val role = mutableStateOf("implementation")
        compose.setContent {
            MaterialTheme {
                SubAgentProfileRow(SubAgentProfile("test", "测试代理", role = role.value), emptyList(), settings = true)
            }
        }
        compose.onNodeWithContentDescription("设置测试代理任务分工").assertDoesNotExist()
        for (next in listOf("implementation", "review", "image_generation", "video_generation")) {
            compose.runOnIdle { role.value = next }
            compose.onNodeWithText("任务分工").assertDoesNotExist()
            compose.onNodeWithText("未设置分工").assertDoesNotExist()
            compose.onNodeWithContentDescription("选择测试代理职责").assertExists()
            compose.onNodeWithContentDescription("设置测试代理并行上限").assertExists().assertIsNotEnabled()
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
        var selectedModelId: String? = null
        var cleared = false
        val model = io.github.mangi.eta.ui.model.AgentModelOptionUi("m", "p", "测试提供商", "openai", "m", "测试模型", 10000)
        val nextModel = io.github.mangi.eta.ui.model.AgentModelOptionUi("m-next", "p-next", "下一提供商", "openai", "m-next", "下一模型", 10000)
        val picker = io.github.mangi.eta.ui.model.AgentModelPickerUiState(
            providerGroups = listOf(
                io.github.mangi.eta.ui.model.AgentModelProviderGroupUi("p", "测试提供商", "openai", listOf(model)),
                io.github.mangi.eta.ui.model.AgentModelProviderGroupUi("p-next", "下一提供商", "openai", listOf(nextModel)),
            ),
            selectedModel = model)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surfaceVariant = selectedColor)) {
                io.github.mangi.eta.ui.TtsModelPickerDialog(picker, true, {}, { _, id -> selectedModelId = id }, "选择模型",
                    onClearSelection = { cleared = true }, highlightSelection = true)
            }
        }
        val selected = compose.onNode(isSelectable() and hasText("测试模型")).assertIsSelected()
        val pixels = selected.captureToImage().toPixelMap()
        assertEquals(selectedColor, pixels[pixels.width - 12, pixels.height / 2])
        selected.assertHeightIsEqualTo(48.dp)
        assertTrue("top selection margin missing", pixels[pixels.width / 2, 0] != selectedColor)
        assertTrue("bottom selection margin missing", pixels[pixels.width / 2, pixels.height - 1] != selectedColor)
        selected.performTouchInput { click(Offset(center.x, 1f)) }
        compose.runOnIdle { assertEquals("m", selectedModelId); selectedModelId = null }
        selected.performTouchInput { click(Offset(center.x, height - 1f)) }
        compose.runOnIdle { assertEquals("m", selectedModelId) }
        val none = compose.onNode(isSelectable() and hasText("无"))
        none.assertHeightIsEqualTo(48.dp).performTouchInput { click(Offset(center.x, height - 1f)) }
        compose.runOnIdle { assertTrue(cleared) }
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
        val profile = SubAgentProfile("test", "测试代理", tier = SubAgentTaskTier.COMPLEX)
        val fixture = SubAgentUiFixture(profiles = listOf(profile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme {
                SubAgentProfileRow(profile, emptyList(), settings = true)
            }
        }
        val label = compose.onNodeWithText("职责", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("执行", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(value.left > label.right)
        val model = compose.onNodeWithContentDescription("测试代理模型").fetchSemanticsNode().boundsInRoot
        val role = compose.onNodeWithContentDescription("选择测试代理职责").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("设置测试代理任务分工").assertDoesNotExist()
        assertTrue(role.top >= model.bottom)
        val thinking = compose.onNodeWithContentDescription("调整测试代理思考深度").fetchSemanticsNode().boundsInRoot
        val parallel = compose.onNodeWithContentDescription("设置测试代理并行上限").assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        assertTrue(parallel.top >= thinking.bottom)
        compose.onNodeWithText("设置各提供商模型并行上限").assertDoesNotExist()
        compose.onNodeWithText("任务分工").assertDoesNotExist()
    }

    @Test fun iconsAndLabelsUseOnSurfaceLikeTheApprovedSettingsShot() {
        val ink = Color(0xFF202124)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(onSurface = ink, onSurfaceVariant = Color.Red, primary = Color.Blue)) {
                SubAgentSettingRow("模型", "grok-4.6", Icons.Rounded.AccountTree,
                    "测试代理模型", badge = "与", onClick = {})
            }
        }
        val pixels = compose.onNodeWithContentDescription("测试代理模型").captureToImage().toPixelMap()
        var inkCount = 0
        var faded = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            when (pixels[x, y]) {
                ink -> inkCount++
                Color.Red, Color.Blue -> faded++
            }
        }
        assertTrue(inkCount > 40)
        assertEquals(0, faded)
    }

    @Test fun providerBadgeSitsToTheLeftOfTheModelName() {
        compose.setContent {
            MaterialTheme {
                SubAgentSettingRow("模型", "grok-4.6", Icons.Rounded.AccountTree,
                    "测试代理模型", badge = "与", onClick = {})
            }
        }
        val badge = compose.onNodeWithText("与", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithText("grok-4.6", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("模型", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(badge.left > label.right)
        assertTrue(model.left > badge.right)
        assertTrue(kotlin.math.abs((badge.top + badge.bottom) / 2 - (model.top + model.bottom) / 2) < 8f)
    }

    @Test fun compactMenuWrapsToLongestLabelInsteadOfFixedWidth() {
        compose.setContent {
            MaterialTheme {
                SubAgentTaskTierButton("执行", SubAgentTaskTier.COMPLEX, true, {}, compact = true)
            }
        }
        compose.onNodeWithContentDescription("设置执行任务分工").performClick()
        val selected = compose.onNode(isSelectable() and hasText("复杂任务")).fetchSemanticsNode().boundsInRoot
        val other = compose.onNode(isSelectable() and hasText("简单任务")).fetchSemanticsNode().boundsInRoot
        assertTrue("menu stayed oversized: ${selected.width}", selected.width < 200f)
        assertTrue(kotlin.math.abs(selected.width - other.width) < 2f)
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun settingsAddActionStaysVisibleWhileAgentListScrolls() {
        val fixture = SubAgentUiFixture()
        val group = fixture.createPreset()
        compose.setSubAgentContent(fixture) {
            MaterialTheme { io.github.mangi.eta.ui.SubAgentSettingsScreen({}, { fixture.repository }) }
        }
        compose.onNodeWithText("添加子代理组").assertIsDisplayed()
        compose.onNodeWithContentDescription("编辑子代理组${group.name}").performClick()
        compose.onNodeWithText("设置各提供商模型并行上限").assertDoesNotExist()
        val add = compose.onNodeWithText("添加子代理").assertIsDisplayed()
        val parent = add.fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs((parent.left + parent.right) / 2 - (screen.left + screen.right) / 2) < 24f)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
        compose.onNodeWithText("添加子代理").assertIsDisplayed()
    }

    @Test fun boundParallelRowsObserveTheSharedModelLimit() {
        val provider = "parallel-ui-${java.util.UUID.randomUUID()}"
        val a = SubAgentProfile("parallel-a", "并发甲", providerId = provider, modelId = "record-a")
        val b = SubAgentProfile("parallel-b", "并发乙", providerId = provider, modelId = "record-b")
        val fixture = SubAgentUiFixture(profiles = listOf(a, b))
        val config = io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
            providerId = provider, providerName = "已用提供商", baseUrl = "https://example.invalid", apiKey = "test",
            model = "bound-api", systemPrompt = "")
        compose.setSubAgentContent(fixture) { MaterialTheme { Column {
            SubAgentParallelLimitRow(a, config)
            SubAgentParallelLimitRow(b, config)
        } } }
        compose.onAllNodesWithText("不限", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue(fixture.editor.saveParallelLimit(a.id, provider, a.modelId, "bound-api", 0)
                is io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences.WriteResult.Saved)
        }
        compose.onAllNodesWithText("不限", useUnmergedTree = true).assertCountEquals(2)
        compose.runOnIdle {
            val current = fixture.snapshot()
            assertEquals(0, current.parallelLimit(io.github.mangi.eta.agent.delegation.SubAgentParallelModel(provider, "bound-api")))
            assertEquals(1, current.parallelLimit(io.github.mangi.eta.agent.delegation.SubAgentParallelModel(provider, "record-a")))
            assertEquals(1, current.parallelLimit(io.github.mangi.eta.agent.delegation.SubAgentParallelModel(provider, "unused-api")))
        }
    }

    @Test fun parallelRowDisablesWithoutOpeningADialog() {
        val profile = SubAgentProfile("dialog-test", "测试代理", providerId = "provider", modelId = "record")
        val config = mutableStateOf(io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
            providerId = "provider", baseUrl = "https://example.invalid", apiKey = "test", model = "first-api", systemPrompt = ""))
        val enabled = mutableStateOf(true)
        val fixture = SubAgentUiFixture(profiles = listOf(profile))
        compose.setSubAgentContent(fixture) { MaterialTheme { SubAgentParallelLimitRow(profile, config.value, enabled.value) } }
        compose.onNodeWithContentDescription("设置测试代理并行上限").assertIsEnabled()
        compose.runOnIdle { config.value = config.value.copy(model = "second-api") }
        compose.onNodeWithContentDescription("设置测试代理并行上限").assertIsEnabled()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription("设置测试代理并行上限").assertIsNotEnabled()
    }

}
