package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Real LazyColumn geometry, production idle reanchoring and clip policy. No touch gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatComposerViewportTest {
    @get:Rule val compose = createComposeRule()
    private val inset = mutableIntStateOf(BASE_INSET)
    private val viewportHeight = mutableIntStateOf(HEIGHT)
    private val tailHeight = mutableIntStateOf(100)
    private val streaming = mutableStateOf(false)
    private val settling = mutableStateOf(false)
    private val anchored = mutableStateOf(true)
    private val blocked = mutableStateOf(false)
    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope

    @Test fun attachmentAddRemoveAndImeChangesKeepIdleTailAtMeasuredRestLine() {
        setup()
        assertAtRest()
        // A single measured inset already includes attachment height. Never add it twice.
        for (pad in listOf(BASE_INSET + 60, BASE_INSET, 320, BASE_INSET)) {
            compose.runOnIdle { inset.intValue = pad }
            compose.waitForIdle()
            assertAtRest()
        }
        compose.runOnIdle { viewportHeight.intValue = HEIGHT - 80 }
        compose.waitForIdle()
        assertAtRest()
        compose.runOnIdle { viewportHeight.intValue = HEIGHT }
        compose.waitForIdle()
        assertAtRest()
    }

    @Test fun historyAndInterruptedOwnerDoNotGetPulledBackOnInsetChange() {
        setup()
        // Model a previously positioned historical anchor. This is test preparation,
        // not a gesture used to reveal the composer surround after the update.
        scroll(-200f)
        compose.runOnIdle { anchored.value = false }
        val before = position()
        compose.runOnIdle { inset.intValue += 60 }
        compose.waitForIdle()
        assertEquals(before, position())
        compose.runOnIdle { anchored.value = true; blocked.value = true; inset.intValue += 40 }
        compose.waitForIdle()
        assertEquals(before, position())
        // Releasing an owner alone must not initiate a viewport snap.
        compose.runOnIdle { blocked.value = false }
        compose.waitForIdle()
        assertEquals(before, position())
    }

    @Test fun staticContentAndAttachmentSurroundAreVisibleWithoutASwipe() {
        setup()
        val before = position()
        compose.runOnIdle { tailHeight.intValue += 48 }
        compose.waitForIdle()
        assertEquals("completed content growth is not a viewport change", before, position())
        assertTrue("idle anchored content is visible behind the composer", markerInSurround(capture()) > 100)
        // A busy owner (e.g. navigation/gesture) keeps its own position on attachment
        // insertion. The strip's empty surround still has no full-width draw cutoff.
        compose.runOnIdle { blocked.value = true; inset.intValue += 60 }
        compose.waitForIdle()
        assertEquals(before, position())
        assertTrue("attachment surround must remain transparent without scrolling", markerInSurround(capture()) > 100)
    }

    @Test fun streamingAndPostNetworkSettlingStillClipThenIdleReleasesWithoutASwipe() {
        streaming.value = true
        setup()
        compose.runOnIdle { tailHeight.intValue += 48 }
        compose.waitForIdle()
        assertEquals("streaming safety clip remains", 0, markerInSurround(capture()))
        compose.runOnIdle { streaming.value = false; settling.value = true }
        compose.waitForIdle()
        assertEquals("network completion is not layout completion", 0, markerInSurround(capture()))
        compose.runOnIdle { settling.value = false }
        compose.waitForIdle()
        assertTrue("settled content releases the clip without a gesture", markerInSurround(capture()) > 100)
    }

    private fun setup() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                AnchorChatTailOnViewportChange(state, 21) {
                    anchored.value && !blocked.value && !streaming.value && !settling.value
                }
                val pad = inset.intValue.dp
                val shouldClip = shouldClipChatTail(
                    isStreaming = streaming.value,
                    isBottomSettling = settling.value,
                    keepBottomAnchored = anchored.value,
                    isUserScrolling = false,
                    isUserDragging = false,
                    navigationActive = false,
                )
                val restClip = remember(pad) { ComposerRestClip(pad) }
                Box(Modifier.size(WIDTH.dp, viewportHeight.intValue.dp).background(BG).testTag(ROOT)) {
                    // Mirror the two production safety clips; Python contracts guard
                    // that the app uses the same production policy and measured inset.
                    Box(Modifier.fillMaxSize().clipToBounds()
                        .then(if (shouldClip) Modifier.graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            clip = true
                            shape = restClip
                        } else Modifier)
                        .drawWithContent {
                            if (shouldClip) clipRect(bottom = size.height - pad.toPx()) {
                                this@drawWithContent.drawContent()
                            } else drawContent()
                        }
                    ) {
                        LazyColumn(
                            state = state,
                            contentPadding = PaddingValues(bottom = pad),
                            modifier = Modifier.fillMaxSize(),
                            overscrollEffect = null,
                        ) {
                            items(20, key = { "filler-$it" }) {
                                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Gray))
                            }
                            item(key = "tail") {
                                Column(
                                    Modifier.fillMaxWidth().height(tailHeight.intValue.dp).background(CARD),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Spacer(Modifier.weight(1f))
                                    BasicText("LAST LINE", style = TextStyle(color = Color.Black, fontSize = 12.sp))
                                    Box(Modifier.fillMaxWidth().height(14.dp).background(MARKER))
                                }
                            }
                            item(key = SENTINEL) { Spacer(Modifier.height(1.dp)) }
                        }
                    }
                    // Local composer/thumbnail fills only: never paint a full-width strip.
                    Box(Modifier.fillMaxSize().drawWithContent {
                        drawRect(INPUT, Offset(40f, size.height - 70f), Size(160f, 56f))
                        if (inset.intValue > BASE_INSET) {
                            drawRect(INPUT, Offset(40f, size.height - pad.toPx() + 14f), Size(60f, 60f))
                        }
                    })
                }
            }
        }
        compose.waitForIdle()
        scroll(100_000f) // Initial positioning only; assertions never require a swipe.
    }

    private fun scroll(px: Float) {
        var done = false
        compose.runOnIdle { scope.launch { state.scrollBy(px); done = true } }
        compose.waitUntil(5_000) { done }
        compose.waitForIdle()
    }

    private fun position(): Pair<Int, Int> {
        var result = 0 to 0
        compose.runOnIdle { result = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        return result
    }

    private fun assertAtRest() {
        compose.runOnIdle {
            val info = state.layoutInfo
            assertEquals(inset.intValue, info.afterContentPadding)
            val bottom = checkNotNull(info.visibleItemsInfo.firstOrNull { it.key == SENTINEL })
                .let { it.offset + it.size }
            assertTrue("tail=$bottom rest=${info.viewportEndOffset - info.afterContentPadding}",
                abs(bottom - (info.viewportEndOffset - info.afterContentPadding)) <= 1)
            assertFalse(state.canScrollForward)
        }
    }

    private fun capture() = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()

    private fun markerInSurround(image: Bitmap): Int {
        val rest = image.height - inset.intValue
        // These leftmost pixels are outside both the card and thumbnail.
        return (rest until image.height).sumOf { y ->
            (0 until 20).count { x -> near(image.getPixel(x, y), MARKER.toArgb()) }
        }
    }

    private fun near(a: Int, b: Int) = listOf(0, 8, 16, 24).all {
        abs(((a ushr it) and 255) - ((b ushr it) and 255)) <= 3
    }

    private companion object {
        const val ROOT = "composer-viewport"
        const val SENTINEL = "bottom"
        const val WIDTH = 240
        const val HEIGHT = 600
        const val BASE_INSET = 142
        val BG = Color.White
        val INPUT = Color(0xff30a0d0)
        val CARD = Color(0xffffd080)
        val MARKER = Color(0xffe00050)
    }
}
