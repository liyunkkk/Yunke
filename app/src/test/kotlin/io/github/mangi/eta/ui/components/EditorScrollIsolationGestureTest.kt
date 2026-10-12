package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScrollIsolationGestureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var list: LazyListState

    private fun render(text: String) {
        compose.setContent {
            val isolation = remember { EditorScrollIsolation() }
            val draft = remember { mutableStateOf(text) }
            list = rememberLazyListState()
            MiuixTheme(colors = lightColorScheme()) {
                MiuixScaffoldPage(title = "人格", onBack = {}, listState = list, editorScrollIsolation = isolation) {
                    item { Box(Modifier.fillMaxWidth().height(100.dp)) }
                    item {
                        TextField(value = draft.value, onValueChange = { draft.value = it }, singleLine = false,
                            modifier = Modifier.fillMaxWidth().height(180.dp).testTag("editor").isolateEditorScroll(isolation))
                    }
                    items(15) { Box(Modifier.fillMaxWidth().height(100.dp).testTag("outside-$it")) }
                }
            }
        }
        compose.runOnIdle { runBlocking { list.scrollToItem(1) } }
    }

    private fun assertEditorDoesNotMovePage(expectInternalScroll: Boolean) {
        val before = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
        val bounds = compose.onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot
        val pixelsBefore = compose.onNodeWithTag("editor").captureToImage().toPixelMap()
        repeat(3) {
            compose.onNodeWithTag("editor").performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertEquals(before, compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset })
            assertEquals(bounds, compose.onNodeWithTag("editor").fetchSemanticsNode().boundsInRoot)
        }
        if (expectInternalScroll) {
            val pixelsAfter = compose.onNodeWithTag("editor").captureToImage().toPixelMap()
            assertTrue((0 until pixelsBefore.height).any { y ->
                (0 until pixelsBefore.width).any { x -> pixelsBefore[x, y] != pixelsAfter[x, y] }
            })
        }
        compose.onNodeWithTag("editor").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(before, compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset })
        compose.onNodeWithTag("editor").performTouchInput {
            down(center)
            moveTo(center + Offset(1f, 1f))
            up()
        }
        compose.onNodeWithTag("editor").performTextInput("编辑保留")
        compose.onNodeWithTag("editor").assertTextContains("编辑保留", substring = true)
        compose.onNodeWithTag("outside-1").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(before != compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset })
    }

    @Test fun longEditorScrollAndFlingStayLocalThenOutsideScrollWorks() {
        render((1..100).joinToString("\n") { "人格记忆第 $it 行" })
        assertEditorDoesNotMovePage(expectInternalScroll = true)
    }

    @Test fun shortEditorAlsoDoesNotHandDragToPage() {
        render("短文本")
        assertEditorDoesNotMovePage(expectInternalScroll = false)
    }
}
