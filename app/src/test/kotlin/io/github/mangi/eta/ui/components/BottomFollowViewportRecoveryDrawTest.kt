package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Real LazyColumn + LazyListState draw test for bottom follow recovery.
 *
 * Geometry mirrors the measured app ratio (raw gap 1260 / afterContentPadding 426) at 1/3 scale:
 * the list draws inside an outer clip ending at the rest line, the inner graphicsLayer lift is
 * capped at afterContentPadding, and the input rect below the rest line is painted by the parent
 * background so any leaked list content would show up in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomFollowViewportRecoveryDrawTest {
    @get:Rule val compose = createComposeRule()

    private val tailHeight = mutableIntStateOf(TAIL_START_HEIGHT)
    private val lift = mutableFloatStateOf(0f)
    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope

    @Test fun smoothStepAloneLeavesTailMarkerClippedBelowRestLine() {
        setUpFollowedList()
        grow(LARGE_GROWTH)
        val raw = checkNotNull(overflow())
        assertTrue(raw in (LARGE_GROWTH - SENTINEL_HEIGHT)..LARGE_GROWTH)
        assertTrue("raw overflow must clearly exceed the lift budget", raw > 2 * PAD)

        val motion = BottomFollowMotion()
        motion.step(raw.toFloat(), 0L, 1f)
        val smooth = motion.step(raw.toFloat(), FRAME_NANOS, 1f)
        assertTrue(smooth >= 0f && smooth < raw - PAD)

        // Old behaviour: only the smooth step reaches the real scroll, lift stays capped.
        val residual = applyFrame(smooth)
        assertTrue("residual=$residual", residual >= PAD + MARKER_HEIGHT)
        assertEquals(PAD.toFloat(), lift.floatValue, 0f)

        val image = capture()
        assertEquals("tail marker must be clipped in the negative control", 0, countPixels(image, MARKER))
        // The card body still reaches the rest line: the tail is cut off, not scrolled away.
        assertTrue(near(image.getPixel(image.width / 2, REST_LINE - 1), CARD))
        assertInputRegionClean(image)
    }

    @Test fun largeGrowthReturnsFullTailMarkerToRestLineInOneRealScroll() {
        setUpFollowedList()
        grow(LARGE_GROWTH)
        val raw = checkNotNull(overflow())
        assertTrue(raw in (LARGE_GROWTH - SENTINEL_HEIGHT)..LARGE_GROWTH)

        val motion = BottomFollowMotion()
        motion.step(raw.toFloat(), 0L, 1f)
        val smooth = motion.step(raw.toFloat(), FRAME_NANOS, 1f)
        val step = resolveBottomFollowViewportStep(smooth, raw, PAD)
        assertTrue("step=$step smooth=$smooth", step > smooth)
        assertTrue("step=$step raw=$raw", step <= raw)

        val residual = applyFrame(step)
        assertTrue("residual=$residual", residual in 0..(PAD + SENTINEL_HEIGHT))
        assertTrue(lift.floatValue <= PAD)
        assertEquals(residual.coerceAtMost(PAD).toFloat(), lift.floatValue, 0f)

        val image = capture()
        assertTailRestsOnLine(image)
        assertInputRegionClean(image)
    }

    @Test fun inBudgetFramesAfterRecoveryAndSmallGrowthKeepPlainSmoothStep() {
        setUpFollowedList()
        grow(LARGE_GROWTH)
        val motion = BottomFollowMotion()
        var time = 0L
        var distance = checkNotNull(overflow())
        motion.step(distance.toFloat(), time, 1f)
        time += FRAME_NANOS
        distance = applyFrame(resolveBottomFollowViewportStep(motion.step(distance.toFloat(), time, 1f), distance, PAD))
        assertTrue("distance=$distance", distance in 0..(PAD + SENTINEL_HEIGHT))

        fun settle(label: String) {
            var frames = 0
            while (distance > 0) {
                assertTrue("$label did not settle", frames++ < 600)
                time += FRAME_NANOS
                val smooth = motion.step(distance.toFloat(), time, 1f)
                val step = resolveBottomFollowViewportStep(smooth, distance, PAD)
                // Within budget the viewport step is exactly the smooth step: no extra jump.
                if (distance <= PAD) {
                    assertEquals("$label frame=$frames distance=$distance", smooth, step, 0f)
                } else {
                    // The newly visible sentinel may add its own one-pixel height.
                    assertTrue(step - smooth <= SENTINEL_HEIGHT)
                }
                val next = applyFrame(step)
                assertTrue("$label went backwards $distance -> $next", next <= distance)
                assertTrue("$label left budget: $next", next in 0..(PAD + SENTINEL_HEIGHT))
                distance = next
                if (frames % 8 == 0) assertTailRestsOnLine(capture())
            }
            assertEquals(0f, lift.floatValue, 0f)
            val image = capture()
            assertTailRestsOnLine(image)
            assertInputRegionClean(image)
        }

        settle("after large recovery")

        grow(SMALL_GROWTH)
        distance = checkNotNull(overflow())
        assertEquals(SMALL_GROWTH, distance)
        applyFrame(0f) // presentation lift only, no scroll yet
        val image = capture()
        assertTailRestsOnLine(image)
        assertInputRegionClean(image)

        settle("after small growth")
    }

    private fun setUpFollowedList() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                Box(
                    Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(VIEWPORT).drawBehind {
                        drawRect(LIST_BG, size = Size(size.width, REST_LINE.toFloat()))
                        drawRect(
                            INPUT_BG,
                            topLeft = Offset(0f, REST_LINE.toFloat()),
                            size = Size(size.width, size.height - REST_LINE),
                        )
                    },
                ) {
                    // Outer static clip at the rest line.
                    Box(
                        Modifier.fillMaxSize().drawWithContent {
                            clipRect(bottom = REST_LINE.toFloat()) { this@drawWithContent.drawContent() }
                        },
                    ) {
                        LazyColumn(
                            state = state,
                            // Inner lift, capped by the caller at afterContentPadding.
                            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = -lift.floatValue },
                            contentPadding = PaddingValues(bottom = PAD.dp),
                        ) {
                            items(FILLER_COUNT, key = { "filler-$it" }) { index ->
                                Box(
                                    Modifier.fillMaxWidth().height(FILLER_HEIGHT.dp)
                                        .background(if (index % 2 == 0) FILLER_A else FILLER_B),
                                )
                            }
                            item(key = TAIL_KEY) {
                                Column(Modifier.fillMaxWidth().height(tailHeight.intValue.dp).background(CARD)) {
                                    Spacer(Modifier.weight(1f))
                                    Box(Modifier.fillMaxWidth().height(MARKER_HEIGHT.dp).background(MARKER))
                                }
                            }
                            item(key = SENTINEL_KEY) {
                                Box(Modifier.fillMaxWidth().height(SENTINEL_HEIGHT.dp).background(CARD))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        scroll(100_000f)
        compose.runOnIdle {
            val info = state.layoutInfo
            assertEquals(PAD, info.afterContentPadding)
            assertEquals(REST_LINE, info.viewportEndOffset - info.afterContentPadding)
            assertFalse(state.canScrollForward)
        }
        assertEquals(0, overflow())
        val image = capture()
        assertEquals(WIDTH, image.width)
        assertEquals(HEIGHT, image.height)
        assertTailRestsOnLine(image)
        assertInputRegionClean(image)
    }

    private fun grow(px: Int) {
        compose.runOnIdle { tailHeight.intValue += px }
        compose.waitForIdle()
    }

    private fun scroll(px: Float): Float {
        if (px == 0f) return 0f
        var consumed: Float? = null
        compose.runOnIdle { scope.launch { consumed = state.scrollBy(px) } }
        compose.waitForIdle()
        return checkNotNull(consumed)
    }

    /** Real scroll, then the presentation lift for the remaining overflow, capped at the padding. */
    private fun applyFrame(step: Float): Int {
        val consumed = scroll(step)
        assertEquals(step, consumed, 0.51f)
        val residual = checkNotNull(overflow())
        compose.runOnIdle { lift.floatValue = residual.coerceIn(0, PAD).toFloat() }
        compose.waitForIdle()
        return residual
    }

    /** Same sentinel/last-content fallback used by the production controller. */
    private fun overflow(): Int? = compose.runOnIdle {
        val info = state.layoutInfo
        val sentinel = info.visibleItemsInfo.firstOrNull { it.key == SENTINEL_KEY }
        val last = info.visibleItemsInfo.lastOrNull()
        val bottom = resolveTailBottomPx(
            sentinelBottom = sentinel?.let { it.offset + it.size },
            lastVisibleIndex = last?.index,
            lastVisibleBottom = last?.let { it.offset + it.size },
            totalItems = info.totalItemsCount,
        ) ?: return@runOnIdle null
        bottom - (info.viewportEndOffset - info.afterContentPadding)
    }

    private fun capture(): Bitmap = compose.onNodeWithTag(VIEWPORT).captureToImage().asAndroidBitmap()

    /** Full-width marker rows: left edge, centre and right edge all carry the marker colour. */
    private fun markerRows(image: Bitmap): List<Int> = (0 until image.height).filter { y ->
        listOf(1, image.width / 2, image.width - 2).all { x -> near(image.getPixel(x, y), MARKER) }
    }

    private fun assertTailRestsOnLine(image: Bitmap) {
        val rows = markerRows(image)
        assertEquals(MARKER_HEIGHT, rows.size)
        val bottom = rows.last() + 1
        assertTrue(bottom in (REST_LINE - SENTINEL_HEIGHT)..REST_LINE)
        assertEquals("full tail marker must sit on the rest line",
            (bottom - MARKER_HEIGHT until bottom).toList(), markerRows(image))
        // No blank band between the tail and the input rect.
        for (y in bottom until REST_LINE) {
            assertTrue("gap at y=$y", near(image.getPixel(image.width / 2, y), CARD))
        }
        assertTrue(near(image.getPixel(image.width / 2, bottom - MARKER_HEIGHT - 1), CARD))
    }

    private fun assertInputRegionClean(image: Bitmap) {
        for (y in REST_LINE until image.height) for (x in 0 until image.width) {
            assertTrue("list content leaked into input at x=$x y=$y", near(image.getPixel(x, y), INPUT_BG))
        }
    }

    private fun countPixels(image: Bitmap, color: Color): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (near(image.getPixel(x, y), color)) count++
        }
        return count
    }

    private fun near(pixel: Int, color: Color): Boolean {
        val expected = color.toArgb()
        return listOf(0, 8, 16, 24).all { shift ->
            abs(((pixel ushr shift) and 255) - ((expected ushr shift) and 255)) <= 3
        }
    }

    private companion object {
        const val VIEWPORT = "viewport"
        const val TAIL_KEY = "tail"
        const val SENTINEL_KEY = "sentinel"
        const val WIDTH = 240
        const val HEIGHT = 600
        const val PAD = 142 // 426 / 3
        const val REST_LINE = HEIGHT - PAD
        const val LARGE_GROWTH = 420 // 1260 / 3, raw overflow ≈ 2.96 × padding
        const val SMALL_GROWTH = 30
        const val FILLER_COUNT = 20
        const val FILLER_HEIGHT = 40
        const val TAIL_START_HEIGHT = 100
        const val MARKER_HEIGHT = 12
        const val SENTINEL_HEIGHT = 1
        const val FRAME_NANOS = 16_666_667L
        val LIST_BG = Color.White
        val INPUT_BG = Color(0xFF303030)
        val CARD = Color(0xFF3D7BD9)
        val MARKER = Color(0xFFFF1E1E)
        val FILLER_A = Color(0xFFDDDDDD)
        val FILLER_B = Color(0xFFBBBBBB)
    }
}
