package io.github.mangi.eta.ui.components

import android.app.Application
import android.graphics.Rect
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowView
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h891dp",
    shadows = [EtaDropdownMenuResizeTest.FrameShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtaDropdownMenuResizeTest {
    @get:Rule val compose = createComposeRule()

    private class VisibleFrameView : View(RuntimeEnvironment.getApplication()) {
        var frameHeight = 320
        var reads = 0
        override fun getWindowVisibleDisplayFrame(outRect: Rect) {
            reads++
            outRect.set(0, 0, 1000, frameHeight)
        }
        fun resize(height: Int) {
            frameHeight = height
            viewTreeObserver.dispatchOnGlobalLayout()
        }
    }

    @Implements(View::class)
    class FrameShadow : ShadowView() {
        @Implementation
        public override fun getWindowVisibleDisplayFrame(outRect: Rect) {
            super.getWindowVisibleDisplayFrame(outRect)
            visibleHeight?.let { outRect.bottom = outRect.top + it }
        }
        companion object { var visibleHeight: Int? = null }
    }

    @After fun resetFrame() { FrameShadow.visibleHeight = null }

    @Test fun stationaryHostFrameGrowthExpandsOpenMenuWithoutRemountingContent() {
        FrameShadow.visibleHeight = 320
        lateinit var host: View
        var mounts = 0
        compose.setContent {
            host = LocalView.current
            MiuixTheme {
                Box {
                    EtaDropdownMenu(expanded = true, onDismissRequest = {}) {
                        DisposableEffect(Unit) { mounts++; onDispose {} }
                        repeat(11) { Text("row-$it", Modifier.height(40.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("row-10").assertIsNotDisplayed()
        // Change the frame read by both the host and Popup without moving the anchor.
        compose.runOnIdle {
            FrameShadow.visibleHeight = null
            host.viewTreeObserver.dispatchOnGlobalLayout()
        }
        compose.waitForIdle()
        compose.onNodeWithText("row-10").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, mounts) }
        compose.runOnIdle {
            FrameShadow.visibleHeight = 320
            host.viewTreeObserver.dispatchOnGlobalLayout()
        }
        compose.waitForIdle()
        compose.onNodeWithText("row-10").assertIsNotDisplayed()
        compose.onNodeWithText("row-10").performScrollTo().assertIsDisplayed()
    }

    @Test fun visibleFrameListenerIsRemovedWhenMenuContentLeavesComposition() {
        val host = VisibleFrameView()
        var mounted by mutableStateOf(true)
        var observed = -1
        compose.setContent {
            if (mounted) observed = rememberMenuVisibleHeight(host)
        }
        compose.runOnIdle { assertEquals(320, observed); host.resize(600) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(600, observed) }
        mounted = false
        compose.waitForIdle()
        compose.runOnIdle {
            val reads = host.reads
            host.resize(900)
            assertEquals(reads, host.reads)
        }
    }
}
