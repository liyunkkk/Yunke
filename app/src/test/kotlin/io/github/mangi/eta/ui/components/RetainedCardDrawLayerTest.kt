package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercise the shared card modifier inside the real enter/exit transition.
 *
 * The structural assertions deliberately inspect its parameter-form layer element, not
 * frame timings: adding/removing that element at a transition boundary is the regression.
 * Native screenshots additionally check invalidation and the >8192px scrollable fallback.
 * This is not a substitute for a RenderThread/GPU trace on the affected device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetainedCardDrawLayerTest {
    @get:Rule val compose = createComposeRule()

    private val visibility = MutableTransitionState(false)
    private val contentHeight = mutableIntStateOf(80)
    private val bodyColor = mutableStateOf(Color.Red)
    private var observation: LayerObservation? = null
    private lateinit var scrollState: ScrollState
    private lateinit var scope: CoroutineScope

    @Test fun layerElementRemainsPresentAcrossEntryExitAndReversal() {
        setUpCard()
        compose.runOnIdle { visibility.targetState = true }
        advance(32)
        val entering = assertLayer(CompositingStrategy.Auto)
        assertEquals(EnterExitState.PreEnter, entering.current)
        assertEquals(EnterExitState.Visible, entering.target)
        val elementTypes = entering.elements.map { it.javaClass }

        awaitVisibilitySettled(visible = true)
        val idle = assertLayer(CompositingStrategy.Offscreen)
        assertEquals(EnterExitState.Visible, idle.current)
        assertEquals(EnterExitState.Visible, idle.target)
        assertEquals(elementTypes, idle.elements.map { it.javaClass })

        compose.runOnIdle { visibility.targetState = false }
        advance(32)
        val exiting = assertLayer(CompositingStrategy.Auto)
        assertEquals(EnterExitState.Visible, exiting.current)
        assertEquals(EnterExitState.PostExit, exiting.target)
        assertEquals(elementTypes, exiting.elements.map { it.javaClass })

        // Reverse before removal: changing the strategy must not change the node chain.
        compose.runOnIdle { visibility.targetState = true }
        awaitVisibilitySettled(visible = true)
        val reversed = assertLayer(CompositingStrategy.Offscreen)
        assertEquals(elementTypes, reversed.elements.map { it.javaClass })
        assertEquals(EnterExitState.Visible, reversed.current)
        assertEquals(EnterExitState.Visible, reversed.target)

        compose.runOnIdle { visibility.targetState = false }
        awaitVisibilitySettled(visible = false)
        compose.onNodeWithTag(CONTENT_TAG).assertDoesNotExist()

        // A new composition after full collapse still starts with an Auto layer.
        compose.runOnIdle { visibility.targetState = true }
        advance(32)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass })
        awaitVisibilitySettled(visible = true)
        assertLayer(CompositingStrategy.Offscreen)
    }

    @Test fun heightLimitChangesStrategyWithoutRemovingLayerOrClippingTheTail() {
        setUpCard()
        compose.runOnIdle { visibility.targetState = true }
        advance(256)
        val elementTypes = assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass }

        compose.runOnIdle { contentHeight.intValue = 8192 }
        advance(256)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass })

        compose.runOnIdle { contentHeight.intValue = 8193 }
        advance(256)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Auto).elements.map { it.javaClass })
        compose.runOnIdle {
            assertTrue("Tall content must remain fully scrollable", scrollState.maxValue > 8000)
            scope.launch { scrollState.scrollTo(scrollState.maxValue) }
        }
        advance(32)
        val tail = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().asAndroidBitmap()
        assertEquals("The final eight pixels must not be clipped by a retained texture",
            Color.Green.toArgb(), tail.getPixel(24, 156))

        // Returning below the limit must re-enable retention without changing the chain.
        compose.runOnIdle { contentHeight.intValue = 80 }
        advance(256)
        assertEquals(elementTypes, assertLayer(CompositingStrategy.Offscreen).elements.map { it.javaClass })
    }

    @Test fun retainedLayerRepaintsWhenVisibleContentChanges() {
        setUpCard()
        compose.runOnIdle { visibility.targetState = true }
        advance(256)
        assertLayer(CompositingStrategy.Offscreen)
        val before = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().asAndroidBitmap()
        assertEquals(Color.Red.toArgb(), before.getPixel(24, 24))

        // Read in the draw phase, like selection/streaming invalidation, not a new card.
        compose.runOnIdle { bodyColor.value = Color.Blue }
        advance(32)
        assertLayer(CompositingStrategy.Offscreen)
        val after = compose.onNodeWithTag(VIEWPORT_TAG).captureToImage().asAndroidBitmap()
        assertEquals(Color.Blue.toArgb(), after.getPixel(24, 24))
    }

    private fun setUpCard() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                scrollState = rememberScrollState()
                scope = rememberCoroutineScope()
                Column(
                    Modifier.size(80.dp, 160.dp)
                        .background(Color.Black)
                        .testTag(VIEWPORT_TAG)
                        .verticalScroll(scrollState),
                ) {
                    AnimatedVisibility(
                        visibleState = visibility,
                        enter = tailDetailsEnter(fromBottom = false),
                        exit = tailDetailsExit(toBottom = false),
                    ) {
                        val retained = retainDrawLayerWhenIdle()
                        val current = transition.currentState
                        val target = transition.targetState
                        val elements = retained.foldIn(emptyList<Modifier.Element>()) { list, element ->
                            list + element
                        }
                        SideEffect { observation = LayerObservation(current, target, elements) }
                        Box(
                            retained.width(48.dp)
                                .height(contentHeight.intValue.dp)
                                .testTag(CONTENT_TAG)
                                .drawBehind {
                                    drawRect(bodyColor.value)
                                    drawRect(
                                        color = Color.Green,
                                        topLeft = Offset(0f, size.height - 8f),
                                        size = Size(size.width, 8f),
                                    )
                                },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun awaitVisibilitySettled(visible: Boolean) {
        // An interrupted transition can outlive its original tween duration. Wait for
        // the transition itself, not the expected layer strategy, so the assertions
        // below still catch a missing layer or an incorrect caching policy.
        compose.mainClock.advanceTimeUntil(5_000L) {
            visibility.isIdle && visibility.currentState == visible &&
                visibility.targetState == visible
        }
        compose.waitForIdle()
        // Let the final measurement/onSizeChanged update reach the observed modifier.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advance(milliseconds: Long) {
        // Let composition and the Android measure pass establish the animation's size.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
        // onSizeChanged publishes the measured height; give its composition a frame too.
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun assertLayer(strategy: CompositingStrategy): LayerObservation {
        var result: LayerObservation? = null
        compose.runOnIdle {
            val actual = checkNotNull(observation)
            val expected = checkNotNull(
                Modifier.graphicsLayer(compositingStrategy = strategy)
                    .foldIn<Modifier.Element?>(null) { _, element -> element },
            )
            // Use the public factory's own element rather than an internal class name.
            val layers = actual.elements.filter { it.javaClass == expected.javaClass }
            assertEquals("One stable parameter-form graphics layer is required in every phase",
                listOf(expected), layers)
            result = actual
        }
        return checkNotNull(result)
    }

    private data class LayerObservation(
        val current: EnterExitState,
        val target: EnterExitState,
        val elements: List<Modifier.Element>,
    )

    private companion object {
        const val VIEWPORT_TAG = "retained-card-viewport"
        const val CONTENT_TAG = "retained-card-content"
    }
}
