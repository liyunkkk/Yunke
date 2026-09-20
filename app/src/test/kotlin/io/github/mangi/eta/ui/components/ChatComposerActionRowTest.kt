package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatComposerActionRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reasoningVisibilityChangesDoNotMoveIndicatorOrItsTouchTarget() {
        val reasoning = mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            Box(Modifier.width(300.dp)) {
                ChatComposerActionRow(Modifier.testTag("row"), actions = {
                    Box(Modifier.size(40.dp))
                    if (reasoning.value) Box(Modifier.size(40.dp))
                    Box(Modifier.size(40.dp))
                    Spacer(Modifier.weight(1f))
                    repeat(3) { Box(Modifier.size(40.dp)) }
                }, indicator = {
                    Box(Modifier.size(48.dp).testTag("voice").clickable { clicks++ })
                })
            }
        }
        assertCentered()
        val before = bounds("voice")
        compose.runOnIdle { reasoning.value = false }
        assertCentered()
        assertEquals(before, bounds("voice"))
        compose.onNodeWithTag("voice").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun editingAndRtlRemainCentered() {
        val editing = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(300.dp)) {
                    ChatComposerActionRow(Modifier.testTag("row"), actions = {
                        Box(Modifier.size(if (editing.value) 40.dp else 80.dp, 40.dp))
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(120.dp, 40.dp))
                    }, indicator = { Box(Modifier.size(48.dp).testTag("voice")) })
                }
            }
        }
        assertCentered()
        compose.runOnIdle { editing.value = true }
        assertCentered()
    }

    @Test fun absentThinkingButtonKeepsNeighbouringControlsClickable() {
        var leftClicks = 0
        var rightClicks = 0
        compose.setContent {
            Box(Modifier.width(280.dp)) {
                ChatComposerActionRow(Modifier.testTag("row"), actions = {
                    Box(Modifier.size(80.dp, 40.dp).testTag("left").clickable { leftClicks++ })
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(110.dp, 40.dp).testTag("right").clickable { rightClicks++ })
                }, indicator = { Box(Modifier.size(48.dp).testTag("voice")) })
            }
        }
        assertCentered()
        assertTrue(bounds("left").right <= bounds("voice").left)
        assertTrue(bounds("voice").right <= bounds("right").left)
        compose.onNodeWithTag("left").performClick()
        compose.onNodeWithTag("right").performClick()
        compose.runOnIdle { assertEquals(1, leftClicks); assertEquals(1, rightClicks) }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun assertCentered() {
        assertEquals(bounds("row").center.x, bounds("voice").center.x, 1f)
        assertEquals(bounds("row").center.y, bounds("voice").center.y, 1f)
    }
}
