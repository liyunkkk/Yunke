package io.github.mangi.eta.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentStopTaskDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun materialActionsAndCancelAreSeparate() {
        val visible = mutableStateOf(true)
        var main = 0
        var all = 0
        compose.setContent {
            if (visible.value) AgentStopTaskDialog(
                mainRunning = true,
                childrenRunning = true,
                onStopMain = { main++ },
                onStopAll = { all++ },
                onDismiss = { visible.value = false },
            )
        }
        compose.onNodeWithText("仅停止主回复").assertExists()
        compose.onNodeWithText("停止整个任务").assertExists()
        compose.onNodeWithText("可能继续产生费用", substring = true).assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertEquals(0, main)
            assertEquals(0, all)
            assertFalse(visible.value)
        }
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithText("仅停止主回复").performClick()
        compose.runOnIdle { assertEquals(1, main); assertEquals(0, all) }
        compose.onNodeWithText("停止整个任务").performClick()
        compose.runOnIdle { assertEquals(1, main); assertEquals(1, all) }
    }

    @Test fun childOnlyDialogDoesNotOfferMainStop() {
        compose.setContent {
            AgentStopTaskDialog(false, true, {}, {}, {})
        }
        compose.onNodeWithText("仅停止主回复").assertDoesNotExist()
        compose.onNodeWithText("停止整个任务").assertExists()
    }

    @Test fun capturedRunOwnerAndGenerationMustAllRemainCurrent() {
        val captured = AgentStopSelection("conversation-1", "run-1", 4L)
        assertTrue(captured.stillCurrent("conversation-1", "run-1", 4L))
        assertFalse(captured.stillCurrent("conversation-2", "run-1", 4L))
        assertFalse(captured.stillCurrent("conversation-1", "run-2", 4L))
        assertFalse(captured.stillCurrent("conversation-1", "run-1", 5L))
        assertFalse(captured.stillCurrent(null, "run-1", 4L))
        val childrenAfterParent = AgentStopSelection("conversation-1", null, 4L)
        assertTrue(childrenAfterParent.stillCurrent("conversation-1", null, 4L))
        assertFalse(childrenAfterParent.stillCurrent("conversation-1", "run-2", 4L))
    }
}
