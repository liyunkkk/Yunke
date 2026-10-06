package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual measurement negative control, not just ModifierNodeElement equality.
 * The legacy modifier below is the pre-fix implementation. Both subjects use
 * the same remembered child/parent policies and text-layout callback so only
 * the visibility modifier can cause the unrelated-recomposition invalidation.
 * These counts establish redundant work removal, not device frame-time gains.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
class StreamingListItemLayoutInvalidationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unrelatedRecompositionDoesNotRemeasureUnchangedVisibleOrHiddenRows() {
        val controls = Controls()
        val legacy = Probe()
        val candidate = Probe()
        setFixture(controls, legacy, candidate)
        compose.waitForIdle()

        for (visible in listOf(false, true)) {
            compose.runOnIdle { controls.visible.value = visible }
            compose.waitForIdle()
            assertSameMeasurement(legacy, candidate)
            val legacySize = legacy.reportedSize
            val candidateSize = candidate.reportedSize
            repeat(3) {
                val oldMeasures = legacy.measures
                val newMeasures = candidate.measures
                val oldCompositions = legacy.compositions
                val newCompositions = candidate.compositions
                compose.runOnIdle { controls.unrelated.value++ }
                compose.waitForIdle()
                compose.runOnIdle {
                    assertTrue("legacy subject must actually recompose", legacy.compositions > oldCompositions)
                    assertTrue("candidate subject must actually recompose", candidate.compositions > newCompositions)
                    assertEquals(controls.unrelated.value, candidate.lastGeneration)
                    assertTrue("negative control must remeasure with visible=$visible", legacy.measures > oldMeasures)
                    assertEquals("unchanged visible=$visible must not remeasure child", newMeasures, candidate.measures)
                    assertEquals(legacySize, legacy.reportedSize)
                    assertEquals(candidateSize, candidate.reportedSize)
                    assertEquals(controls.text.value, candidate.layouts.last().layoutInput.text.text)
                }
            }
        }
    }

    @Test
    fun realChangesStillMeasureHiddenChildrenAndUpdateTheirLayouts() {
        val controls = Controls()
        val legacy = Probe()
        val candidate = Probe()
        setFixture(controls, legacy, candidate)
        compose.waitForIdle()
        assertSameMeasurement(legacy, candidate)
        assertEquals(0, candidate.reportedSize.height)
        assertEquals(controls.text.value, candidate.layouts.last().layoutInput.text.text)

        assertBothRemeasure(legacy, candidate, "hidden text append") {
            controls.text.value += "\nNew hidden text that must wrap and register a fresh target."
        }
        assertEquals(controls.text.value, candidate.layouts.last().layoutInput.text.text)
        assertTrue("hidden child must retain a real text height", candidate.layouts.last().size.height > 0)
        assertEquals(0, candidate.reportedSize.height)

        assertBothRemeasure(legacy, candidate, "hidden width constraint") {
            controls.constraints.value = Constraints(minWidth = 96, maxWidth = 96, maxHeight = 500)
        }
        assertEquals(96, candidate.reportedSize.width)
        assertEquals(82, candidate.layouts.last().layoutInput.constraints.maxWidth)
        assertEquals(0, candidate.reportedSize.height)

        assertBothRemeasure(legacy, candidate, "hidden minimum height") {
            controls.constraints.value = controls.constraints.value.copy(minHeight = 9)
        }
        assertEquals("hidden rows must respect the parent's minimum", 9, candidate.reportedSize.height)

        assertBothRemeasure(legacy, candidate, "becomes visible") { controls.visible.value = true }
        assertTrue(candidate.reportedSize.height > 9)
        assertBothRemeasure(legacy, candidate, "visible text replacement") {
            controls.text.value = "Changed\nvisible text"
        }
        assertEquals(controls.text.value, candidate.layouts.last().layoutInput.text.text)
        assertBothRemeasure(legacy, candidate, "visible width constraint") {
            controls.constraints.value = controls.constraints.value.copy(minWidth = 128, maxWidth = 128)
        }
        assertEquals(128, candidate.reportedSize.width)
        assertEquals(114, candidate.layouts.last().layoutInput.constraints.maxWidth)
        assertBothRemeasure(legacy, candidate, "becomes hidden") { controls.visible.value = false }
        assertEquals(9, candidate.reportedSize.height)
        assertEquals(controls.text.value, candidate.layouts.last().layoutInput.text.text)
    }

    @Test
    fun intrinsicSizingMinimumConstraintsAndRtlPlacementMatchLegacy() {
        val controls = Controls().apply {
            constraints.value = Constraints(minWidth = 160, maxWidth = 160, minHeight = 9, maxHeight = 500)
        }
        val legacy = Probe()
        val candidate = Probe()
        setFixture(controls, legacy, candidate, collectIntrinsics = true)
        compose.waitForIdle()

        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            compose.runOnIdle { controls.direction.value = direction }
            compose.waitForIdle()
            for (visible in listOf(false, true)) {
                compose.runOnIdle { controls.visible.value = visible }
                compose.waitForIdle()
                assertSameMeasurement(legacy, candidate)
                assertEquals("all four intrinsic queries", legacy.intrinsics, candidate.intrinsics)
                val intrinsic = checkNotNull(candidate.intrinsics)
                assertTrue("hidden width must remain real", intrinsic.minWidth > 0)
                assertTrue(intrinsic.maxWidth >= intrinsic.minWidth)
                if (!visible) {
                    assertEquals(9, candidate.reportedSize.height)
                    assertEquals(0, intrinsic.minHeight)
                    assertEquals(0, intrinsic.maxHeight)
                } else {
                    assertTrue(intrinsic.minHeight > 0)
                    assertTrue(intrinsic.maxHeight > 0)
                    assertEquals(direction, candidate.layouts.last().layoutInput.layoutDirection)
                    val oldRoot = compose.onNodeWithTag("legacy").getUnclippedBoundsInRoot()
                    val newRoot = compose.onNodeWithTag("candidate").getUnclippedBoundsInRoot()
                    val oldText = compose.onNodeWithTag("legacy-text").getUnclippedBoundsInRoot()
                    val newText = compose.onNodeWithTag("candidate-text").getUnclippedBoundsInRoot()
                    assertEquals(oldText.left.value - oldRoot.left.value, newText.left.value - newRoot.left.value, 0.01f)
                    assertEquals(oldText.top.value - oldRoot.top.value, newText.top.value - newRoot.top.value, 0.01f)
                    val textWidth = newText.right.value - newText.left.value
                    val expectedLeft = if (direction == LayoutDirection.Ltr) 7f else 160f - 7f - textWidth
                    assertEquals("relative text placement in $direction", expectedLeft, newText.left.value - newRoot.left.value, 0.01f)
                    assertEquals(3f, newText.top.value - newRoot.top.value, 0.01f)
                }
            }
        }
    }

    private fun assertBothRemeasure(legacy: Probe, candidate: Probe, change: String, update: () -> Unit) {
        val oldMeasures = legacy.measures
        val newMeasures = candidate.measures
        compose.runOnIdle(update)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("legacy must remeasure: $change", legacy.measures > oldMeasures)
            assertTrue("candidate must remeasure: $change", candidate.measures > newMeasures)
            assertSameMeasurement(legacy, candidate)
        }
    }

    private fun assertSameMeasurement(legacy: Probe, candidate: Probe) {
        assertTrue("legacy child must have been measured", legacy.measures > 0)
        assertTrue("candidate child must have been measured", candidate.measures > 0)
        assertEquals("reported width and height", legacy.reportedSize, candidate.reportedSize)
        val a = legacy.layouts.last()
        val b = candidate.layouts.last()
        assertEquals(a.layoutInput.text, b.layoutInput.text)
        assertEquals(a.layoutInput.constraints, b.layoutInput.constraints)
        assertEquals(a.layoutInput.layoutDirection, b.layoutInput.layoutDirection)
        assertEquals(a.size, b.size)
        assertEquals(a.lineCount, b.lineCount)
    }

    private fun setFixture(controls: Controls, legacy: Probe, candidate: Probe, collectIntrinsics: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides controls.direction.value) {
                Column {
                    Host(controls, legacy, "legacy", candidate = false, collectIntrinsics = collectIntrinsics)
                    Host(controls, candidate, "candidate", candidate = true, collectIntrinsics = collectIntrinsics)
                }
            }
        }
    }

    @Composable
    private fun Host(controls: Controls, probe: Probe, tag: String, candidate: Boolean, collectIntrinsics: Boolean) {
        val requested = controls.constraints.value
        val policy = remember(probe, requested, collectIntrinsics) {
            MeasurePolicy { measurables, _ ->
                val child = measurables.single()
                if (collectIntrinsics) {
                    probe.intrinsics = Intrinsics(
                        child.minIntrinsicWidth(200), child.maxIntrinsicWidth(200),
                        child.minIntrinsicHeight(160), child.maxIntrinsicHeight(160),
                    )
                }
                val placeable = child.measure(requested)
                probe.reportedSize = IntSize(placeable.width, placeable.height)
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            }
        }
        Layout(
            content = { Subject(controls, probe, tag, candidate) },
            modifier = Modifier.testTag(tag),
            measurePolicy = policy,
        )
    }

    @Composable
    private fun Subject(controls: Controls, probe: Probe, tag: String, candidate: Boolean) {
        // Force this exact call site to recompose while keeping text, constraints,
        // child measure policy and callback unchanged for the negative control.
        val generation = controls.unrelated.value
        val visible = controls.visible.value
        val text = controls.text.value
        SideEffect {
            probe.compositions++
            probe.lastGeneration = generation
        }
        val policy = remember(probe) { ChildPolicy(probe) }
        val onTextLayout = remember(probe) { { result: TextLayoutResult -> probe.layouts.add(result); Unit } }
        val modifier = if (candidate) Modifier.streamingListItemLayout(visible) else Modifier.legacyStreamingListItemLayout(visible)
        Layout(
            content = {
                BasicText(
                    text = text,
                    style = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.testTag("$tag-text"),
                    onTextLayout = onTextLayout,
                )
            },
            modifier = modifier,
            measurePolicy = policy,
        )
    }

    private class ChildPolicy(private val probe: Probe) : MeasurePolicy {
        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
            probe.measures++
            val placeable = measurables.single().measure(
                constraints.offset(horizontal = -14, vertical = -6).copy(minWidth = 0, minHeight = 0),
            )
            val width = (placeable.width + 14).coerceIn(constraints.minWidth, constraints.maxWidth)
            val height = (placeable.height + 6).coerceIn(constraints.minHeight, constraints.maxHeight)
            return layout(width, height) { placeable.placeRelative(7, 3) }
        }

        override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
            measurables.single().minIntrinsicWidth((height - 6).coerceAtLeast(0)) + 14

        override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
            measurables.single().maxIntrinsicWidth((height - 6).coerceAtLeast(0)) + 14

        override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
            measurables.single().minIntrinsicHeight((width - 14).coerceAtLeast(0)) + 6

        override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
            measurables.single().maxIntrinsicHeight((width - 14).coerceAtLeast(0)) + 6
    }

    private class Controls {
        val visible = mutableStateOf(false)
        val text = mutableStateOf("short\nwide wide")
        val unrelated = mutableStateOf(0)
        val constraints = mutableStateOf(Constraints(minWidth = 160, maxWidth = 160, maxHeight = 500))
        val direction = mutableStateOf(LayoutDirection.Ltr)
    }

    private class Probe {
        var compositions = 0
        var lastGeneration = -1
        var measures = 0
        var reportedSize = IntSize.Zero
        var intrinsics: Intrinsics? = null
        val layouts = mutableListOf<TextLayoutResult>()
    }

    private data class Intrinsics(val minWidth: Int, val maxWidth: Int, val minHeight: Int, val maxHeight: Int)
}

// Test-only copy of the original production implementation, including its
// unconditional child measurement and diagnostic branch.
private fun Modifier.legacyStreamingListItemLayout(visible: Boolean): Modifier =
    this.layout { measurable, constraints ->
        val diagnoseHidden = !visible && StreamPerformanceDiagnostics.enabled
        val measureItem = {
            val placeable = measurable.measure(constraints)
            if (visible) {
                layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
            } else {
                if (diagnoseHidden) {
                    StreamPerformanceDiagnostics.record("markdown.hidden.childHeight", value = placeable.height.toLong())
                    StreamPerformanceDiagnostics.record("markdown.hidden.reportHeight", value = constraints.minHeight.toLong())
                }
                layout(placeable.width, constraints.minHeight) {}
            }
        }
        if (diagnoseHidden) {
            StreamPerformanceDiagnostics.measureDetail("markdown.hidden.measure", block = measureItem)
        } else {
            measureItem()
        }
    }
