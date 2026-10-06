package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import io.github.mangi.eta.core.AppFileLogger
import java.util.concurrent.atomic.AtomicBoolean
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

/** Disabled identity plus enabled transparency and unrelated-recomposition regression checks. */
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
        assertSame(original, original.streamDiagnosticPlacement("list.place"))
        assertSame(original, original
            .streamDiagnosticMeasure("settings.root.measure")
            .streamDiagnosticDraw("settings.root.draw"))
    }

    @Test fun disabledObserverPreservesRtlPlacementAndAllIntrinsicBoundaries() {
        assertFalse(StreamPerformanceDiagnostics.enabled)
        assertRtlPlacementAndAllIntrinsicBoundaries()
    }

    @Test fun enabledObserverPreservesRtlPlacementAndAllIntrinsicBoundaries() = withDiagnosticSession { session ->
        assertRtlPlacementAndAllIntrinsicBoundaries()
        compose.runOnIdle {
            assertTrue((session.snapshot(final = false).stats["list.place"]?.count ?: 0L) > 0)
        }
    }

    @Test fun enabledMeasureCountsOnlyOutermostScopesAndPreservesCapturedSourceSpan() = withDiagnosticSession { session ->
        val mainField = StreamPerformanceDiagnostics::class.java.getDeclaredField("mainLog").apply { isAccessible = true }
        val previous = mainField.get(null)
        val log = MainThreadMessageLog(capacity = 2)
        mainField.set(null, log)
        try {
            compose.setContent { }
            compose.runOnIdle {
                val begin = System.nanoTime()
                log.onLine(">>>>> Dispatching to Handler (android.os.Handler) {1} test@2: 0", begin)
                StreamPerformanceDiagnostics.withAttribution(StreamDiagnosticAttribution(session.serial, sourceSpan = 999)) {
                    StreamPerformanceDiagnostics.measure("ui.flush") {
                        StreamPerformanceDiagnostics.measure("reveal.step") {
                            StreamPerformanceDiagnostics.measure("reveal.measure") { Unit }
                        }
                    }
                }
                log.onLine("<<<<< Finished", maxOf(System.nanoTime(), begin + 100_000_000))
                val sample = log.timingsBetween(begin, Long.MAX_VALUE).single()
                val stats = session.snapshot(final = false).stats
                assertEquals(stats.getValue("ui.flush").totalNs, sample.coveredNs)
                assertEquals(stats.getValue("reveal.step").totalNs, sample.revealNs)
                assertTrue(sample.revealNs <= sample.coveredNs)
                assertEquals(sample.endNs - sample.beginNs - sample.coveredNs, sample.uninstrumentedNs)
            }
        } finally {
            mainField.set(null, previous)
        }
    }

    private fun assertRtlPlacementAndAllIntrinsicBoundaries() {
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
                            .streamDiagnosticDraw("settings.root.draw")
                            .streamDiagnosticPlacement("list.place"))
                    },
                    modifier = Modifier.width(100.dp).height(40.dp)
                        .onGloballyPositioned { parentX = it.positionInRoot().x },
                ) { measurables, constraints ->
                    val child = measurables.single()
                    intrinsicResults = listOf(0, 37, Constraints.Infinity).flatMap { argument ->
                        listOf(child.minIntrinsicWidth(argument), child.maxIntrinsicWidth(argument),
                            child.minIntrinsicHeight(argument), child.maxIntrinsicHeight(argument))
                    }
                    val placeable = child.measure(Constraints.fixed(20, 10))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(11, 0) }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Constraints.fixed(20, 10), measured)
            assertEquals(List(3) { listOf(17, 29, 13, 23) }.flatten(), intrinsicResults)
            val expectedCalls = listOf(0, 37, Constraints.Infinity).flatMap { argument ->
                listOf("minWidth", "maxWidth", "minHeight", "maxHeight").map { it to argument }
            }.toSet()
            assertEquals(expectedCalls, intrinsicCalls.toSet())
            // RTL relative x=11 is measured from the right, not the left.
            val expected = with(compose.density) { 100.dp.roundToPx() } - 11 - 20
            assertEquals(expected.toFloat(), childX - parentX, 0.01f)
        }
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun disabledObserverPreservesConstraintsSizeAndSingleChildPass() {
        assertFalse(StreamPerformanceDiagnostics.enabled)
        assertConstraintsSizeAndSingleChildPass()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun enabledObserverPreservesConstraintsSizeAndSingleChildPass() = withDiagnosticSession {
        assertConstraintsSizeAndSingleChildPass()
    }

    private fun assertConstraintsSizeAndSingleChildPass() {
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
                    .streamDiagnosticPlacement("list.place")
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

    @Test fun equalStageAvoidsUnrelatedRemeasureButOldCapturedLambdaDoesNot() = withDiagnosticSession { session ->
        val revision = mutableStateOf(0)
        val stage = mutableStateOf("settings.root.measure")
        val childWidth = mutableStateOf(30)
        val childConstraints = mutableStateOf(Constraints(maxWidth = 100, maxHeight = 40))
        var committedRevision = -1
        var nodeChildMeasures = 0
        var nodeReceivedConstraints: Constraints? = null
        val nodePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                nodeChildMeasures++
                nodeReceivedConstraints = constraints
                return layout(constraints.constrainWidth(childWidth.value), constraints.constrainHeight(10)) {}
            }
        }
        val legacyPolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult =
                layout(constraints.constrainWidth(childWidth.value), constraints.constrainHeight(10)) {}
        }
        val parentPolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val children = measurables.map { it.measure(childConstraints.value) }
                return layout(constraints.constrainWidth(100), constraints.constrainHeight(40)) {
                    children[0].placeRelative(0, 0)
                    children[1].placeRelative(0, 20)
                }
            }
        }
        compose.setContent {
            Layout(content = {
                // Read inside the content scope so even memoized parent content cannot skip this recomposition.
                val currentRevision = revision.value
                val currentStage = stage.value
                SideEffect { committedRevision = currentRevision }
                Layout(content = {}, measurePolicy = nodePolicy,
                    modifier = Modifier.testTag("node")
                        // Fresh but equal strings ensure equality is by label, not by String identity.
                        .streamDiagnosticMeasure(String(currentStage.toCharArray())))
                Layout(content = {}, measurePolicy = legacyPolicy,
                    modifier = Modifier.testTag("legacy").legacyDiagnosticMeasure("markdown.tail.measure"))
            }, measurePolicy = parentPolicy)
        }
        compose.waitForIdle()
        var initialChildMeasures = 0
        compose.runOnIdle {
            val initial = session.snapshot(final = false).stats
            assertTrue(initial.getValue("settings.root.measure").count > 0)
            assertTrue(initial.getValue("markdown.tail.measure").count > 0)
            initialChildMeasures = nodeChildMeasures
        }
        repeat(5) { index ->
            compose.runOnIdle { revision.value = index + 1 }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(index + 1, committedRevision)
                val unrelated = session.snapshot(final = false).stats
                assertEquals(0L, unrelated["settings.root.measure"]?.count ?: 0L)
                assertTrue("the old captured lambda must exercise the negative control",
                    (unrelated["markdown.tail.measure"]?.count ?: 0L) > 0L)
                assertEquals(initialChildMeasures, nodeChildMeasures)
            }
        }

        // A changed stage must still update the node and automatically invalidate its measurement.
        compose.runOnIdle { stage.value = "markdown.stable.measure" }
        compose.waitForIdle()
        compose.runOnIdle {
            val changed = session.snapshot(final = false).stats
            assertTrue((changed["markdown.stable.measure"]?.count ?: 0L) > 0L)
            assertEquals(0L, changed["settings.root.measure"]?.count ?: 0L)
        }

        // Child measure-state changes still propagate through the equal-stage observer.
        compose.runOnIdle { childWidth.value = 45 }
        compose.waitForIdle()
        compose.onNodeWithTag("node").assertWidthIsEqualTo(45.dp).assertHeightIsEqualTo(10.dp)
        var beforeConstraintChange = 0
        compose.runOnIdle {
            assertTrue(nodeChildMeasures > initialChildMeasures)
            assertEquals(childConstraints.value, nodeReceivedConstraints)
            assertTrue((session.snapshot(final = false).stats["markdown.stable.measure"]?.count ?: 0L) > 0L)
            beforeConstraintChange = nodeChildMeasures
        }

        // Parent constraints continue to reach the child unchanged, even with the same stage.
        compose.runOnIdle { childConstraints.value = Constraints.fixed(60, 15) }
        compose.waitForIdle()
        compose.onNodeWithTag("node").assertWidthIsEqualTo(60.dp).assertHeightIsEqualTo(15.dp)
        compose.runOnIdle {
            assertTrue(nodeChildMeasures > beforeConstraintChange)
            assertEquals(Constraints.fixed(60, 15), nodeReceivedConstraints)
            assertTrue((session.snapshot(final = false).stats["markdown.stable.measure"]?.count ?: 0L) > 0L)
        }
    }

    /** Exactly the previous helper, intentionally outside composition's lambda memoization. */
    private fun Modifier.legacyDiagnosticMeasure(stage: String): Modifier = layout { measurable, constraints ->
        StreamPerformanceDiagnostics.measureDetail(stage) {
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { child.placeRelative(0, 0) }
        }
    }

    /** Open a real recording session without a Window listener, logger I/O or a test-only production switch. */
    private fun withDiagnosticSession(block: (StreamPerformanceDiagnostics.Session) -> Unit) {
        val activeField = StreamPerformanceDiagnostics::class.java.getDeclaredField("active").apply { isAccessible = true }
        val loggerField = AppFileLogger::class.java.getDeclaredField("enabled").apply { isAccessible = true }
        val loggerEnabled = loggerField.get(null) as AtomicBoolean
        val previousActive = activeField.get(null)
        val previousEnabled = loggerEnabled.get()
        val session = StreamPerformanceDiagnostics.Session()
        loggerEnabled.set(true)
        activeField.set(null, session)
        try {
            assertTrue(StreamPerformanceDiagnostics.enabled)
            block(session)
        } finally {
            activeField.set(null, previousActive)
            loggerEnabled.set(previousEnabled)
        }
    }
}
