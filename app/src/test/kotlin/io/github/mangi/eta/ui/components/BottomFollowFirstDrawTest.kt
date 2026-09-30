package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.max

/** No follow coroutine and no post-growth test scroll: inspect the FIRST real draw. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomFollowFirstDrawTest {
    @get:Rule val compose = createComposeRule()
    private val height = mutableIntStateOf(100)
    private val following = mutableStateOf(false)
    private val extraRows = mutableIntStateOf(0)
    private var revision = 0
    private var measuredHeight = 0
    private var expectedCount = 22
    private lateinit var state: LazyListState
    private lateinit var scope: CoroutineScope
    private lateinit var baseline: Bitmap
    private var consumed = 0f
    private var actualLift = 0f
    private var callbacks = 0
    private var clicks = 0
    private val probes = mutableMapOf<Int, Probe>()
    private val images = mutableMapOf<Int, Bitmap>()
    private data class Probe(
        val overflow: Int?, val bottom: Int?, val lift: Float,
        val consumed: Float, val callbacks: Int, val index: Int, val offset: Int,
    )

    @Test fun firstDrawAndContinuousMeasureOnlyGrowthKeepWholeTail() {
        setup()
        var oldOverflow = 0
        var oldConsumed = 0f
        var oldCallbacks = callbacks
        for (growth in listOf(24, 18, 3 * PAD, 24, PAD + 37, 1, 2 * PAD)) {
            val p = grow(growth)
            assertTrue("fixed viewport still needs the post-layout callback", p.callbacks > oldCallbacks)
            val overflow = checkNotNull(p.overflow)
            assertTrue("FIRST draw overflow=$overflow", overflow in 0..PAD)
            assertEquals("actual layer must use recovered layout", overflow.toFloat(), p.lift, 0f)
            val delta = p.consumed - oldConsumed
            val expected = max(0, oldOverflow + growth - PAD)
            assertTrue("actual delta=$delta expected=$expected", abs(delta - expected) <= 1f)
            if (oldOverflow + growth <= PAD) assertEquals(0f, delta, 0f)
            assertWholeTail(p, checkNotNull(images[revision]))
            oldOverflow = overflow
            oldConsumed = p.consumed
            oldCallbacks = p.callbacks
        }
        compose.onNodeWithTag(TAP).performTouchInput { click() }
        compose.runOnIdle { assertEquals("drawn and hit-test coordinates agree", 1, clicks) }
    }

    @Test fun oldPathIsNegativeControlOnFirstDraw() {
        setup(recoveryEnabled = false)
        val p = grow(3 * PAD)
        assertTrue(checkNotNull(p.overflow) > PAD)
        assertEquals(0f, p.consumed, 0f)
        assertTrue("old lift remains bounded", p.lift in 0f..PAD.toFloat())
        assertTrue(checkNotNull(p.bottom) - p.lift > REST)
        val image = checkNotNull(images[revision])
        assertEquals("the tail marker really is clipped", 0, count(image, MARKER))
        assertCleanInput(image)
    }

    @Test fun unknownTailNeverAuthorizesAnEstimatedRecovery() {
        setup()
        val p = grow(3 * PAD, appendedRows = 10)
        assertNull("final row and sentinel must both be outside measured items", p.overflow)
        assertEquals(0f, p.consumed, 0f)
        assertCleanInput(checkNotNull(images[revision]))
    }

    @Test fun leavingFollowPreservesHistoricalAnchor() {
        setup()
        val index = state.firstVisibleItemIndex
        val offset = state.firstVisibleItemScrollOffset
        setFollowing(false)
        val p = grow(3 * PAD)
        assertEquals(0f, p.consumed, 0f)
        assertEquals(0f, p.lift, 0f)
        assertEquals(index, p.index)
        assertEquals(offset, p.offset)
    }

    private fun setup(recoveryEnabled: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                state = rememberLazyListState()
                scope = rememberCoroutineScope()
                val recovery = remember(state) { BottomFollowViewportRecovery(state, SENTINEL) }
                val held = remember { intArrayOf(0) }
                val snapshots = List(12) { rememberGraphicsLayer() }
                val cardShape = RoundedCornerShape(12.dp)
                Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag(ROOT).drawWithContent {
                    val rev = revision
                    if (rev > 0 && probes[rev] == null && measuredHeight == height.intValue &&
                        state.layoutInfo.totalItemsCount == expectedCount
                    ) {
                        val layer = snapshots[rev]
                        layer.record {
                            drawRect(BG, size = Size(size.width, REST.toFloat()))
                            drawRect(INPUT, Offset(0f, REST.toFloat()), Size(size.width, size.height - REST))
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(layer)
                        val info = state.layoutInfo
                        probes[rev] = Probe(
                            info.measuredBottomFollowTailOverflow(SENTINEL),
                            info.visibleItemsInfo.firstOrNull { it.key == TAIL }?.let { it.offset + it.size },
                            actualLift, consumed, callbacks,
                            state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset,
                        )
                        // Keep a separate display list for each FIRST draw; never overwrite it
                        // with later frames or use captureToImage after recovery to pass this test.
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            images[rev] = layer.toImageBitmap().asAndroidBitmap()
                                .copy(Bitmap.Config.ARGB_8888, false)
                        }
                    } else {
                        drawRect(BG, size = Size(size.width, REST.toFloat()))
                        drawRect(INPUT, Offset(0f, REST.toFloat()), Size(size.width, size.height - REST))
                        drawContent()
                    }
                }) {
                    Box(Modifier.fillMaxSize()
                        .then(if (following.value) Modifier.graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                            clip = true
                            shape = ComposerRestClip(PAD.dp)
                        } else Modifier)
                        .drawWithContent {
                            if (following.value) clipRect(bottom = REST.toFloat()) {
                                this@drawWithContent.drawContent()
                            } else drawContent()
                        }
                    ) {
                        LazyColumn(
                            state = state,
                            contentPadding = PaddingValues(bottom = PAD.dp),
                            overscrollEffect = null,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                val overflow = state.layoutInfo.measuredBottomFollowTailOverflow(SENTINEL)
                                    ?.coerceAtMost(state.layoutInfo.afterContentPadding)
                                held[0] = nextHeldTailLift(following.value, overflow, held[0])
                                actualLift = held[0].toFloat()
                                translationY = -actualLift
                            }.onGloballyPositioned {
                                callbacks++
                                if (recoveryEnabled) consumed += recovery.recover { following.value }
                            },
                        ) {
                            items(20, key = { "filler-$it" }) {
                                Box(Modifier.fillMaxWidth().height(40.dp).background(Color.Gray))
                            }
                            item(key = TAIL) {
                                Column(
                                    Modifier.fillMaxWidth().layout { measurable, constraints ->
                                        // This State is only read during measurement, not composition.
                                        val h = height.intValue
                                        val child = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                                        measuredHeight = h
                                        layout(child.width, child.height) { child.place(0, 0) }
                                    }.border(2.dp, Color.Black, cardShape).clip(cardShape).background(CARD),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Spacer(Modifier.weight(1f))
                                    BasicText("LAST LINE", style = TextStyle(
                                        color = Color.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    ))
                                    Box(Modifier.fillMaxWidth().height(14.dp).background(MARKER)
                                        .testTag(TAP).clickable { clicks++ })
                                }
                            }
                            items(extraRows.intValue, key = { "new-$it" }) {
                                Box(Modifier.fillMaxWidth().height(80.dp).background(Color.Gray))
                            }
                            item(key = SENTINEL) { Spacer(Modifier.height(1.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        var snapped = false
        compose.runOnIdle { scope.launch { state.scrollBy(100_000f); snapped = true } }
        compose.waitUntil(5_000) { snapped }
        setFollowing(true)
        compose.runOnIdle { assertEquals(0, state.layoutInfo.measuredBottomFollowTailOverflow(SENTINEL)) }
        baseline = compose.onNodeWithTag(ROOT).captureToImage().asAndroidBitmap()
        assertEquals(WIDTH, baseline.width)
        assertEquals(HEIGHT, baseline.height)
        assertTrue("baseline must contain actual tail marker", count(baseline, MARKER) > 100)
        assertCleanInput(baseline)
        compose.mainClock.autoAdvance = false
    }

    private fun setFollowing(value: Boolean) {
        val old = compose.mainClock.autoAdvance
        try {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { following.value = value }
            compose.waitForIdle()
        } finally { compose.mainClock.autoAdvance = old }
    }

    private fun grow(px: Int, appendedRows: Int = 0): Probe {
        compose.runOnIdle {
            revision++
            expectedCount = 22 + appendedRows
            extraRows.intValue = appendedRows
            height.intValue += px
        }
        compose.mainClock.advanceTimeByFrame()
        // Host waiting may finish the draw; it cannot advance the frozen Compose clock.
        compose.waitUntil(5_000) { probes.containsKey(revision) && images.containsKey(revision) }
        assertFalse(compose.mainClock.autoAdvance)
        return checkNotNull(probes[revision])
    }

    private fun assertWholeTail(p: Probe, image: Bitmap) {
        val bottom = checkNotNull(p.bottom) - p.lift.toInt()
        assertTrue("actual drawn bottom=$bottom rest=$REST", bottom in (REST - 1)..REST)
        for (dy in 1..32) for (x in 0 until WIDTH) {
            assertTrue("first-draw tail mismatch x=$x dy=$dy bottom=$bottom",
                near(image.getPixel(x, bottom - dy), baseline.getPixel(x, REST - 1 - dy)))
        }
        assertCleanInput(image)
    }

    private fun assertCleanInput(image: Bitmap) {
        for (y in REST until HEIGHT) for (x in 0 until WIDTH) {
            assertTrue("input leak at $x,$y", near(image.getPixel(x, y), INPUT.toArgb()))
        }
    }
    private fun count(image: Bitmap, color: Color): Int =
        (0 until image.height).sumOf { y ->
            (0 until image.width).count { x -> near(image.getPixel(x, y), color.toArgb()) }
        }
    private fun near(a: Int, b: Int): Boolean = listOf(0, 8, 16, 24).all {
        abs(((a ushr it) and 255) - ((b ushr it) and 255)) <= 3
    }
    private companion object {
        const val ROOT = "first-draw-root"
        const val TAP = "tail-tap"
        const val TAIL = "tail"
        const val SENTINEL = "sentinel"
        const val WIDTH = 240
        const val HEIGHT = 600
        const val PAD = 142
        const val REST = HEIGHT - PAD
        val BG = Color.White
        val INPUT = Color(0xff30a0d0)
        val CARD = Color(0xffffd080)
        val MARKER = Color(0xffe00050)
    }
}
