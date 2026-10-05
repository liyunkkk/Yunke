package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.config.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TopBarOverflowMenuInteractiveModeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var previousMode: AgentTaskSurfaceMode
    private var autoCompressChanges = 0
    private var terminalOpens = 0

    @Before fun seedBackgroundSetting() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        previousMode = AgentTaskSurface.stored()
        AgentTaskSurface.save(AgentTaskSurfaceMode.BACKGROUND)
    }

    @After fun restoreSetting() {
        AgentTaskSurface.save(previousMode)
    }

    @Test fun backgroundSettingIsNotOverwrittenByCompositionOrOpeningTheMenu() {
        render()
        assertStored(AgentTaskSurfaceMode.BACKGROUND)
        openMenu()
        row().assertIsDisplayed().assertHasClickAction()
        switch().assertIsDisplayed().assertHasClickAction().assertIsOff()
        compose.onNodeWithText(label(R.string.action_interactive_mode)).assertIsDisplayed()
        assertStored(AgentTaskSurfaceMode.BACKGROUND)
    }

    @Test fun rowEnablesAskAndSwitchDisablesToForegroundWithoutDismissing() {
        render()
        openMenu()
        // Hit the row away from its independently clickable trailing switch.
        row().performTouchInput { click(Offset(width * 0.35f, center.y)) }
        assertStored(AgentTaskSurfaceMode.ASK)
        switch().assertIsOn()
        assertMenuStillOpen()

        switch().performTouchInput { click() }
        assertStored(AgentTaskSurfaceMode.FOREGROUND)
        switch().assertIsOff()
        assertMenuStillOpen()

        // Another actual touch proves the popup is still accepting input, not just
        // retaining semantics during EtaDropdownMenu's exit animation.
        row().performTouchInput { click(Offset(width * 0.35f, center.y)) }
        assertStored(AgentTaskSurfaceMode.ASK)
        switch().performTouchInput { click() }
        assertStored(AgentTaskSurfaceMode.FOREGROUND)
        assertMenuStillOpen()
        compose.runOnIdle {
            assertEquals(0, autoCompressChanges)
            assertEquals(0, terminalOpens)
        }
    }

    @Test fun externalExecutionPositionChangesRefreshTheSameSwitch() {
        render()
        // Observe the setting even before the popup is mounted.
        compose.runOnIdle { AgentTaskSurface.save(AgentTaskSurfaceMode.ASK) }
        openMenu()
        switch().assertIsOn()
        assertStored(AgentTaskSurfaceMode.ASK)

        compose.runOnIdle { AgentTaskSurface.save(AgentTaskSurfaceMode.BACKGROUND) }
        switch().assertIsOff()
        assertStored(AgentTaskSurfaceMode.BACKGROUND)
        assertMenuStillOpen()

        compose.runOnIdle { AgentTaskSurface.save(AgentTaskSurfaceMode.ASK) }
        switch().assertIsOn()
        compose.runOnIdle { AgentTaskSurface.save(AgentTaskSurfaceMode.FOREGROUND) }
        switch().assertIsOff()
        assertStored(AgentTaskSurfaceMode.FOREGROUND)
        assertMenuStillOpen()
    }

    private fun render() {
        compose.setContent {
            MiuixTheme(colors = lightColorScheme()) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.TopEnd)) {
                            TopBarOverflowMenu(
                                onNewConversation = {},
                                onOpenTerminal = { terminalOpens++ },
                                onLaunchKimiWeb = {},
                                kimiWebLabel = "Kimi Web",
                                canStopKimiWeb = false,
                                onStopKimiWeb = {},
                                onRefreshKimiWeb = {},
                                onOpenBrowser = {},
                                onOpenWorkspace = {},
                                autoCompressEnabled = false,
                                onToggleAutoCompress = { autoCompressChanges++ },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithContentDescription(label(R.string.action_more))
            .performTouchInput { click() }
        row().assertIsDisplayed()
    }

    private fun assertMenuStillOpen() {
        // Longer than the menu's 160 ms retained exit lifetime.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        row().assertIsDisplayed()
        switch().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_search_history)).assertIsDisplayed()
    }

    private fun assertStored(mode: AgentTaskSurfaceMode) {
        compose.runOnIdle { assertEquals(mode, AgentTaskSurface.stored()) }
    }

    private fun row() = compose.onNodeWithTag("top-bar-interactive-mode-row")
    private fun switch() = compose.onNodeWithTag("top-bar-interactive-mode-switch", useUnmergedTree = true)
    private fun label(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
