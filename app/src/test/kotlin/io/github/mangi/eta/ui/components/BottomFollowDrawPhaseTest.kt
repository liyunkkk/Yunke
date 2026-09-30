package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlin.math.abs

/**
 * Unlike the recovery fixture, lift is never assigned by the test after waiting for layout.
 * A real lazy item's height changes in measure (not composition), and the production follow layer
 * must consume that completed layout under the chat body's offscreen rest-line clip.
 * No follow scroll is needed here: growth deliberately stays within the existing draw budget.
 * This does not prove recovery from continuous growth exceeding that budget on every frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomFollowDrawPhaseTest {
    @get:Rule val compose = createComposeRule()

    private val bodyHeight = mutableIntStateOf(600)
    private val following = mutableStateOf(false)
    private val measurableTail = mutableStateOf(true)
    private val markerColor = mutableStateOf(Color.Green)
    private val heldLift = intArrayOf(0)
    private var overflowReads = 0
    @Volatile private var committedFollow: Boolean? = null
    private var taps = 0
    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope

    @Test fun measureOnlyGrowthKeepsLastLineAndWholeRoundedBottomAtRestLine() {
        setUpList()
        val baseline = capture()
        assertInputClean(baseline)
        val markerPixels = countColor(baseline, Color.Green.toArgb())
        assertTrue("Fixture must include a visible last-line marker", markerPixels > 100)
        val scrollIndex = state.firstVisibleItemIndex
        val scrollOffset = state.firstVisibleItemScrollOffset

        repeat(4) { frame ->
            compose.runOnIdle { bodyHeight.intValue += 24 }
            advanceFrame()
            val image = capture()
            compose.runOnIdle {
                assertEquals(24 * (frame + 1), rawOverflow())
                assertEquals(rawOverflow(), heldLift[0])
                assertEquals(scrollIndex, state.firstVisibleItemIndex)
                assertEquals(scrollOffset, state.firstVisibleItemScrollOffset)
            }
            // Includes both corners, horizontal border and the complete last-line marker.
            assertBottomMatches(baseline, image)
            assertEquals(markerPixels, countColor(image, Color.Green.toArgb()))
            assertInputClean(image)
        }
        assertFalse("No extra Compose frames may settle the lift", compose.mainClock.autoAdvance)
    }

    @Test fun retainedDescendantDrawsFreshContentWithoutMovingTheBorder() {
        setUpList()
        compose.runOnIdle { bodyHeight.intValue += 48 }
        advanceFrame()
        val before = capture()
        val count = countColor(before, Color.Green.toArgb())
        compose.runOnIdle { markerColor.value = Color.Cyan }
        advanceFrame()
        val after = capture()
        assertEquals(0, countColor(after, Color.Green.toArgb()))
        assertEquals(count, countColor(after, Color.Cyan.toArgb()))
        // The lowest eight rows contain the bottom border/corners, not the marker.
        assertBottomMatches(before, after, rows = 8)
        assertInputClean(after)
    }

    @Test fun unknownMeasurementHoldsLiftAndManualReleaseClearsItWithoutReadingLayout() {
        setUpList()
        compose.runOnIdle { bodyHeight.intValue += 32 }
        advanceFrame()
        val known = capture()
        assertEquals(32, heldLift[0])

        compose.runOnIdle { measurableTail.value = false }
        advanceFrame()
        assertBottomMatches(known, capture())
        assertEquals(32, heldLift[0])
        val anchoredBounds = compose.onNodeWithTag(MARKER).fetchSemanticsNode().boundsInRoot
        val readsBeforeRelease = overflowReads

        // The production caller removes the composer clip when a user starts scrolling.
        compose.runOnIdle { following.value = false }
        awaitFollowCommitted(false)
        capture()
        assertEquals(0, heldLift[0])
        assertEquals(readsBeforeRelease, overflowReads)
        val releasedBounds = compose.onNodeWithTag(MARKER).fetchSemanticsNode().boundsInRoot
        assertEquals(anchoredBounds.top + 32f, releasedBounds.top, 0.5f)
        assertEquals(anchoredBounds.bottom + 32f, releasedBounds.bottom, 0.5f)

        compose.runOnIdle { measurableTail.value = true; following.value = true }
        awaitFollowCommitted(true)
        val resumed = capture()
        assertEquals(32, heldLift[0])
        assertBottomMatches(known, resumed)
        assertInputClean(resumed)
    }

    @Test fun semanticBoundsStayAlignedWithTheLiftedLastLine() {
        setUpList()
        val before = compose.onNodeWithTag(MARKER).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { bodyHeight.intValue += 96 }
        advanceFrame()
        val after = compose.onNodeWithTag(MARKER).fetchSemanticsNode().boundsInRoot
        // Content grows down by 96px and the layer lifts by 96px: the visual target stays put.
        assertEquals(before.top, after.top, 0.5f)
        assertEquals(before.bottom, after.bottom, 0.5f)
    }

    @Test fun realTapHitsTheLiftedLastLineNotItsUntranslatedPosition() {
        setUpList()
        val viewport = compose.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInRoot
        val marker = compose.onNodeWithTag(MARKER).fetchSemanticsNode().boundsInRoot
        val target = marker.center - viewport.topLeft
        compose.onNodeWithTag(VIEWPORT).performTouchInput { click(target) }
        compose.runOnIdle { assertEquals(1, taps); bodyHeight.intValue += 96 }
        advanceFrame()
        // Inject screen coordinates instead of using a semantic click action.
        compose.onNodeWithTag(VIEWPORT).performTouchInput { click(target) }
        compose.runOnIdle { assertEquals(2, taps) }
        compose.onNodeWithTag(VIEWPORT).performTouchInput { click(target + Offset(0f, 96f)) }
        compose.runOnIdle { assertEquals("Untranslated bounds must not remain clickable", 2, taps) }
    }

    private fun setUpList() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f), LocalSquircleEnabled provides false) {
                MiuixTheme(colors = lightColorScheme()) {
                    state = rememberLazyListState()
                    scope = rememberCoroutineScope()
                    val follow = following.value
                    SideEffect { committedFollow = follow }
                    Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(VIEWPORT).drawBehind {
                        drawRect(BACKGROUND)
                        drawRect(INPUT, topLeft = Offset(0f, REST.toFloat()),
                            size = Size(size.width, size.height - REST))
                    }) {
                        Box(Modifier.fillMaxSize().clipToBounds()
                            .then(if (follow) Modifier.graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                                clip = true
                                shape = RestClip
                            } else Modifier)
                            .drawWithContent {
                                if (follow) {
                                    clipRect(bottom = REST.toFloat()) { this@drawWithContent.drawContent() }
                                } else drawContent()
                            },
                        ) {
                            LazyColumn(
                                state = state,
                                contentPadding = PaddingValues(top = TOP.dp, bottom = PAD.dp),
                                modifier = Modifier.fillMaxSize().bottomFollowLayer(follow, heldLift) {
                                    overflowReads++
                                    if (measurableTail.value) rawOverflow()?.coerceAtMost(PAD) else null
                                },
                            ) {
                                item(key = "card") {
                                    WorkProcessCardSlice(WorkProcessCardPart.Whole) {
                                        Column(Modifier.fillMaxWidth().layout { measurable, constraints ->
                                            // Same outer viewport size; only a descendant remeasures.
                                            val height = bodyHeight.intValue
                                            val child = measurable.measure(constraints.copy(
                                                minHeight = height, maxHeight = height,
                                            ))
                                            layout(child.width, child.height) { child.placeRelative(0, 0) }
                                        }.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                                            Spacer(Modifier.weight(1f))
                                            Box(Modifier.fillMaxWidth().height(8.dp).testTag(MARKER)
                                                .pointerInput(Unit) { detectTapGestures { taps++ } }
                                                .drawBehind { drawRect(markerColor.value) })
                                            Spacer(Modifier.height(8.dp))
                                        }
                                    }
                                }
                                item(key = SENTINEL) { Spacer(Modifier.height(1.dp)) }
                            }
                        }
                    }
                }
            }
        }
        compose.runOnIdle { scope.launch { state.scrollBy(100_000f) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(state.canScrollForward)
            assertEquals(PAD, state.layoutInfo.afterContentPadding)
            assertEquals(0, rawOverflow())
            following.value = true
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        capture()
        assertEquals(0, heldLift[0])
    }

    private fun rawOverflow(): Int? {
        val info = state.layoutInfo
        val sentinel = info.visibleItemsInfo.firstOrNull { it.key == SENTINEL }
        val last = info.visibleItemsInfo.lastOrNull()
        val bottom = resolveTailBottomPx(sentinel?.let { it.offset + it.size },
            last?.index, last?.let { it.offset + it.size }, info.totalItemsCount) ?: return null
        return bottom - (info.viewportEndOffset - info.afterContentPadding)
    }

    private fun advanceFrame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /** Only composition-driven follow transitions use this barrier; growth stays single-frame. */
    private fun awaitFollowCommitted(follow: Boolean) {
        // Yield to Android between frames so snapshot-apply notifications can schedule recomposition.
        // advanceTimeUntil runs its whole loop on the UI thread and cannot provide that host yield.
        compose.waitUntil(timeoutMillis = 5_000L) {
            if (committedFollow != follow) compose.mainClock.advanceTimeByFrame()
            committedFollow == follow
        }
        // Flush Android measure/draw after the composition parameter has actually been committed.
        compose.waitForIdle()
    }

    private fun capture(): Bitmap = compose.onNodeWithTag(VIEWPORT).captureToImage().asAndroidBitmap()

    private fun assertBottomMatches(expected: Bitmap, actual: Bitmap, rows: Int = 36) {
        for (y in REST - rows until REST) for (x in 0 until WIDTH) {
            assertTrue("Bottom differs at ($x,$y); lift=${heldLift[0]} overflow=${rawOverflow()}",
                near(expected.getPixel(x, y), actual.getPixel(x, y)))
        }
    }

    private fun assertInputClean(image: Bitmap) {
        for (y in REST until HEIGHT) for (x in 0 until WIDTH) {
            assertTrue("List leaked into composer at ($x,$y)", near(INPUT.toArgb(), image.getPixel(x, y)))
        }
    }

    private fun countColor(image: Bitmap, color: Int): Int {
        var count = 0
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            if (near(color, image.getPixel(x, y))) count++
        }
        return count
    }

    private fun near(a: Int, b: Int): Boolean = (0..3).all {
        abs(((a ushr (it * 8)) and 255) - ((b ushr (it * 8)) and 255)) <= 3
    }

    private object RestClip : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
            Outline.Rectangle(Rect(0f, 0f, size.width, (size.height - PAD).coerceAtLeast(0f)))
    }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 400
        const val TOP = 14
        const val PAD = 128
        const val REST = HEIGHT - PAD
        const val VIEWPORT = "draw-phase-viewport"
        const val SENTINEL = "draw-phase-sentinel"
        const val MARKER = "draw-phase-last-line"
        val BACKGROUND = Color.Magenta
        val INPUT = Color(0xff16324f)
    }
}
