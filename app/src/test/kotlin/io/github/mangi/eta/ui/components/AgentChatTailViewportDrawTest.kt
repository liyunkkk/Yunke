package io.github.mangi.eta.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Pixel regression for the chat tail clip: while the reader is not interacting, nothing of the
 * transcript (including a bottom stroke) may be drawn inside the bottom `inset + 14dp` band that
 * sits under the composer. Uses the production [chatTailViewport] and [shouldClipChatTail].
 *
 * Scene (dp): white frame 200x400, a 50dp spacer, then a 300dp viewport. The viewport hosts
 * bottom-anchored red content of variable height with a 3dp blue stroke on its bottom edge, so
 * growth overflows upward like a bottom-following transcript. Only solid rects are drawn; no text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatTailViewportDrawTest {
    @get:Rule val compose = createComposeRule()

    private val scale = mutableStateOf(1f)
    private val inset = mutableStateOf(0.dp)
    private val contentHeight = mutableStateOf(ViewportHeight)
    private val scrolling = mutableStateOf(false)
    private val dragging = mutableStateOf(false)
    private val navigating = mutableStateOf(false)
    private var lastClip: Boolean? = null

    @Test
    fun idleTailStaysClippedAndEveryInteractionReleasesIt() {
        showScene()
        for (density in listOf(1f, 1.3f)) {
            for (bottomInset in listOf(0.dp, 40.dp)) {
                update { scale.value = density; inset.value = bottomInset; setFlags(false, false, false) }
                // Not following the bottom and no gesture: must still clip (the reported bug).
                assertClipped("idle density=$density inset=$bottomInset")
                for (mask in 1 until 8) {
                    val s = mask and 1 != 0
                    val d = mask and 2 != 0
                    val n = mask and 4 != 0
                    val label = "density=$density inset=$bottomInset scroll=$s drag=$d nav=$n"
                    update { setFlags(s, d, n) }
                    assertReleased(label)
                    update { setFlags(false, false, false) }
                    assertClipped("restored after $label")
                }
            }
        }
    }

    @Test
    fun fastGrowthCompletionAndInsetChangesNeverDrawUnderComposer() {
        showScene()
        for (density in listOf(1f, 1.3f)) {
            update {
                scale.value = density
                inset.value = 24.dp
                contentHeight.value = 120.dp
                setFlags(false, false, false)
            }
            assertClipped("start density=$density")
            for (step in listOf(160.dp, 220.dp, 300.dp, 420.dp, 640.dp, 900.dp)) {
                update { contentHeight.value = step }
                assertClipped("growth density=$density height=$step")
            }
            // Several growth steps inside one frame, as a fast burst of streamed output.
            update {
                contentHeight.value = 960.dp
                contentHeight.value = 1_040.dp
                contentHeight.value = 1_200.dp
            }
            assertClipped("burst density=$density")
            // Completion: content settles while the composer inset changes.
            for (bottomInset in listOf(0.dp, 40.dp, 24.dp)) {
                update { inset.value = bottomInset }
                assertClipped("completed density=$density inset=$bottomInset")
            }
            update { dragging.value = true }
            assertReleased("drag after growth density=$density")
            update { dragging.value = false; scrolling.value = true }
            assertReleased("fling after growth density=$density")
            update { scrolling.value = false }
            assertClipped("stopped after growth density=$density")
            // Inset taller than the viewport clamps the rest line to 0: whole viewport hidden.
            update { inset.value = 400.dp }
            assertClipped("oversized inset density=$density")
        }
    }

    private fun showScene() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(scale.value)) {
                val clip = shouldClipChatTail(
                    isUserScrolling = scrolling.value,
                    isUserDragging = dragging.value,
                    navigationActive = navigating.value,
                )
                SideEffect { lastClip = clip }
                Column(Modifier.size(FrameWidth, FrameHeight).background(Color.White).testTag(Tag)) {
                    Spacer(Modifier.height(TopGap))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(ViewportHeight)
                            .chatTailViewport(shouldClipTail = clip, bottomInset = inset.value),
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .wrapContentHeight(Alignment.Bottom, unbounded = true)
                                .height(contentHeight.value)
                                .background(Color.Red),
                        ) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(StrokeHeight)
                                    .background(Color.Blue),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setFlags(scroll: Boolean, drag: Boolean, nav: Boolean) {
        scrolling.value = scroll
        dragging.value = drag
        navigating.value = nav
    }

    private fun update(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    private fun assertClipped(label: String) {
        val g = geometry()
        val f = capture(g, label)
        assertEquals("policy should clip: $label", true, lastClip)
        val restAbs = g.top + g.restLine
        val firstHidden = ceil(restAbs).toInt()
        val lastVisible = floor(restAbs).toInt() - 1
        for (y in 0 until f.height) {
            when {
                y < g.top || y >= g.bottom -> f.assertRow(y, label, "outside viewport", ::isWhite)
                y >= firstHidden -> f.assertRow(y, label, "under composer band", ::isWhite)
                y > lastVisible -> Unit // anti-aliased rest line row
                y < g.contentTop -> f.assertRow(y, label, "above content", ::isWhite)
                else -> f.assertRow(y, label, "visible content", ::isRed)
            }
        }
        assertTrue("bottom stroke leaked under composer: $label", f.pixels.none(::isBlue))
    }

    private fun assertReleased(label: String) {
        val g = geometry()
        val f = capture(g, label)
        assertEquals("policy should release clip: $label", false, lastClip)
        val strokeTop = g.bottom - g.stroke
        for (y in 0 until f.height) {
            when {
                y < g.top || y >= g.bottom -> f.assertRow(y, label, "outside viewport", ::isWhite)
                y < g.contentTop -> f.assertRow(y, label, "above content", ::isWhite)
                y < strokeTop -> f.assertRow(y, label, "released content", ::isRed)
                else -> f.assertRow(y, label, "released bottom stroke", ::isBlue)
            }
        }
    }

    private fun geometry(): Geometry = with(Density(scale.value)) {
        val top = TopGap.roundToPx()
        val height = ViewportHeight.roundToPx()
        // Mirrors the documented contract (rest line = height - (inset + 14dp)); the clip itself is
        // performed only by the production modifier under test.
        val restLine = (height - (inset.value + ComposerGap).toPx()).coerceAtLeast(0f)
        Geometry(
            top = top,
            bottom = top + height,
            restLine = restLine,
            contentTop = top + height - contentHeight.value.roundToPx(),
            stroke = StrokeHeight.roundToPx(),
            frameWidth = FrameWidth.roundToPx(),
            frameHeight = FrameHeight.roundToPx(),
        )
    }

    private fun capture(g: Geometry, label: String): Frame {
        val bitmap = compose.onNodeWithTag(Tag).captureToImage().asAndroidBitmap()
        assertEquals("frame width: $label", g.frameWidth, bitmap.width)
        assertEquals("frame height: $label", g.frameHeight, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Frame(bitmap.width, bitmap.height, pixels)
    }

    private class Geometry(
        val top: Int,
        val bottom: Int,
        val restLine: Float,
        val contentTop: Int,
        val stroke: Int,
        val frameWidth: Int,
        val frameHeight: Int,
    )

    private class Frame(val width: Int, val height: Int, val pixels: IntArray) {
        fun assertRow(y: Int, label: String, region: String, expected: (Int) -> Boolean) {
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                assertTrue(
                    "$region mismatch at x=$x y=$y pixel=${Integer.toHexString(pixel)}: $label",
                    expected(pixel),
                )
            }
        }
    }

    private fun isWhite(p: Int) = AndroidColor.red(p) >= 250 && AndroidColor.green(p) >= 250 &&
        AndroidColor.blue(p) >= 250
    private fun isRed(p: Int) = AndroidColor.red(p) >= 250 && AndroidColor.green(p) <= 5 &&
        AndroidColor.blue(p) <= 5
    private fun isBlue(p: Int) = AndroidColor.red(p) <= 5 && AndroidColor.green(p) <= 5 &&
        AndroidColor.blue(p) >= 250

    private companion object {
        const val Tag = "chat-tail-frame"
        val FrameWidth: Dp = 200.dp
        val FrameHeight: Dp = 400.dp
        val TopGap: Dp = 50.dp
        val ViewportHeight: Dp = 300.dp
        val StrokeHeight: Dp = 3.dp
        /** Production ConversationComposerGap; duplicated because it is not visible to tests. */
        val ComposerGap: Dp = 14.dp
    }
}
