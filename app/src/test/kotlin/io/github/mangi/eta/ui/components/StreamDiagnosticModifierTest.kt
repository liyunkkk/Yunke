package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Behavior check for the default disabled path; enabled wiring is also source-guarded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
class StreamDiagnosticModifierTest {
    @get:Rule val compose = createComposeRule()

    @Test fun disabledConstructionReturnsTheOriginalModifierChain() {
        assertFalse(StreamPerformanceDiagnostics.enabled)
        val original = Modifier
            .testTag("identity")
            .layout { measurable, constraints ->
                val child = measurable.measure(constraints)
                layout(child.width, child.height) { child.placeRelative(0, 0) }
            }
            .drawWithContent { drawContent() }
        assertSame(original, original.streamDiagnosticMeasure("settings.root.measure"))
        assertSame(original, original.streamDiagnosticDraw("settings.root.draw"))
        assertSame(original, original
            .streamDiagnosticMeasure("settings.root.measure")
            .streamDiagnosticDraw("settings.root.draw"))
    }

    @Test fun disabledObserverPreservesRtlPlacementAndAllIntrinsicBoundaries() {
        assertFalse(StreamPerformanceDiagnostics.enabled)
        var parentX = 0f
        var childX = 0f
        var measured: Constraints? = null
        var intrinsicResults: List<Int> = emptyList()
        val intrinsicCalls = mutableListOf<Pair<String, Int>>()
        val policy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                measured = constraints
                return layout(constraints.constrainWidth(20), constraints.constrainHeight(10)) {}
            }
            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
                intrinsicCalls += "minWidth" to height
                return 17
            }
            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
                intrinsicCalls += "maxWidth" to height
                return 29
            }
            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
                intrinsicCalls += "minHeight" to width
                return 13
            }
            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int {
                intrinsicCalls += "maxHeight" to width
                return 23
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Layout(
                    content = {
                        Layout(content = {}, measurePolicy = policy, modifier = Modifier
                            .onGloballyPositioned { childX = it.positionInRoot().x }
                            .streamDiagnosticMeasure("settings.root.measure")
                            .streamDiagnosticDraw("settings.root.draw"))
                    },
                    modifier = Modifier.width(100.dp).height(40.dp)
                        .onGloballyPositioned { parentX = it.positionInRoot().x },
                ) { measurables, constraints ->
                    val child = measurables.single()
                    intrinsicResults = listOf(child.minIntrinsicWidth(37), child.maxIntrinsicWidth(37),
                        child.minIntrinsicHeight(43), child.maxIntrinsicHeight(43))
                    val placeable = child.measure(Constraints.fixed(20, 10))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(11, 0) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Constraints.fixed(20, 10), measured)
            assertEquals(listOf(17, 29, 13, 23), intrinsicResults)
            assertTrue(intrinsicCalls.isNotEmpty())
            assertTrue(intrinsicCalls.all { (kind, argument) ->
                argument == if (kind.endsWith("Width")) 37 else 43
            })
            // RTL relative x=11 is measured from the right, not the left.
            val expected = with(compose.density) { 100.dp.roundToPx() } - 11 - 20
            assertEquals(expected.toFloat(), childX - parentX, 0.01f)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun disabledObserverPreservesConstraintsSizeAndSingleChildPass() {
        assertFalse(StreamPerformanceDiagnostics.enabled)
        var parentMeasures = 0
        var childMeasures = 0
        var parentDraws = 0
        var childDraws = 0
        var passedConstraints: Constraints? = null
        var receivedConstraints: Constraints? = null
        compose.setContent {
            Layout(
                content = {},
                modifier = Modifier
                    .testTag("observed")
                    .layout { measurable, constraints ->
                        parentMeasures++
                        passedConstraints = constraints
                        val child = measurable.measure(constraints)
                        layout(child.width, child.height) { child.placeRelative(0, 0) }
                    }
                    .drawWithContent { parentDraws++; drawContent() }
                    .streamDiagnosticMeasure("settings.root.measure")
                    .streamDiagnosticDraw("settings.root.draw")
                    .drawWithContent { childDraws++; drawContent() },
            ) { _, constraints ->
                childMeasures++
                receivedConstraints = constraints
                layout(73, 41) {}
            }
        }
        compose.waitForIdle()
        val observed = compose.onNodeWithTag("observed")
        observed.assertWidthIsEqualTo(73.dp).assertHeightIsEqualTo(41.dp)
        // Idle synchronization alone does not guarantee a draw in Robolectric's legacy graphics.
        // Capture with native graphics so these counters observe an actual rendered frame.
        observed.captureToImage()
        compose.runOnIdle {
            assertTrue(parentMeasures > 0)
            assertEquals(parentMeasures, childMeasures)
            assertEquals(passedConstraints, receivedConstraints)
            // Compare actual draw passes rather than assuming a fixed Robolectric frame count.
            assertTrue(parentDraws > 0)
            assertTrue(childDraws > 0)
            assertEquals(parentDraws, childDraws)
        }
    }
}
