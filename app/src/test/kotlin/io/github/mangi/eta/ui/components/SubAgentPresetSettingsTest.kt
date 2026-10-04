package io.github.mangi.eta.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.ui.SubAgentSettingsScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubAgentPresetSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsStartsAtGroupsAndBothBackEntrypointsLeaveDetailFirst() {
        val fixture = SubAgentUiFixture(canEdit = { false }) // A running conversation must not lock presets.
        val group = fixture.repository.presets().first()
        val before = fixture.snapshot()
        var exits = 0
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentSettingsScreen({ exits++ }, { fixture.repository }) }
        }
        compose.onNodeWithText("添加子代理组").assertIsDisplayed()
        compose.onNodeWithText("添加子代理", substring = false).assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑子代理组${group.name}").performClick()
        compose.onNodeWithText("添加子代理", substring = false).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(group.config.profiles.size + 1, fixture.repository.snapshot(SubAgentConfigKey.Preset(group.id)).profiles.size)
            assertEquals(before, fixture.snapshot())
            assertEquals(0, exits)
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("添加子代理组").assertExists()
        compose.onNodeWithContentDescription("编辑子代理组${group.name}").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("添加子代理组").assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun addNamedEmptyGroupRenameAndDeleteDoNotChangeConversation() {
        val fixture = SubAgentUiFixture()
        val before = fixture.snapshot()
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentSettingsScreen({}, { fixture.repository }) }
        }
        compose.onNodeWithText("添加子代理组").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("新组")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithContentDescription("编辑子代理组新组").performScrollTo().performClick()
        compose.onNodeWithText("添加子代理", substring = false).assertExists()
        compose.runOnIdle {
            assertTrue(fixture.repository.presets().single { it.name == "新组" }.config.profiles.isEmpty())
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("新组组更多操作").performScrollTo().performClick()
        compose.onNodeWithText("重命名组").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("改名组")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithContentDescription("改名组组更多操作").performScrollTo().performClick()
        compose.onNodeWithText("删除组").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.onNodeWithContentDescription("编辑子代理组改名组").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(fixture.repository.presets().any { it.name == "改名组" })
            assertEquals(before, fixture.snapshot())
        }
    }
}
