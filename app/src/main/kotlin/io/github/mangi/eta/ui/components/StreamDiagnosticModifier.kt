package io.github.mangi.eta.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

/**
 * Observes the existing child once without changing constraints, size or placement.
 * Labels must be fixed stage names, never content or item identity. No snapshot state,
 * semantics, layers or scheduled work are introduced by this modifier.
 */
internal fun Modifier.streamDiagnosticMeasure(stage: String): Modifier {
    if (!StreamPerformanceDiagnostics.enabled) return this
    return this.then(StreamDiagnosticMeasureElement(stage))
}

// Equal fixed labels reuse the node on unrelated recomposition; changed labels retain automatic invalidation.
private data class StreamDiagnosticMeasureElement(val stage: String) : ModifierNodeElement<StreamDiagnosticMeasureNode>() {
    override fun create() = StreamDiagnosticMeasureNode(stage)
    override fun update(node: StreamDiagnosticMeasureNode) { node.stage = stage }
    override fun InspectorInfo.inspectableProperties() {
        name = "streamDiagnosticMeasure"
        properties["stage"] = stage
    }
}

private class StreamDiagnosticMeasureNode(var stage: String) : Modifier.Node(), LayoutModifierNode {
    // Default LayoutModifierNode intrinsic dispatch, like Modifier.layout, runs this same measure policy.
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult =
        StreamPerformanceDiagnostics.measureDetail(stage) {
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { child.placeRelative(0, 0) }
        }
}

/** Disabled construction preserves modifier identity; enabled draws content exactly once. */
internal fun Modifier.streamDiagnosticDraw(stage: String): Modifier {
    if (!StreamPerformanceDiagnostics.enabled) return this
    return drawWithContent {
        StreamPerformanceDiagnostics.measureDetail(stage) { drawContent() }
    }
}

/** Placement-only observer. Existing measure/draw observers keep their original semantics. */
internal fun Modifier.streamDiagnosticPlacement(stage: String): Modifier {
    if (!StreamPerformanceDiagnostics.enabled) return this
    return this.then(StreamDiagnosticPlacementElement(stage))
}

private data class StreamDiagnosticPlacementElement(val stage: String) : ModifierNodeElement<StreamDiagnosticPlacementNode>() {
    override fun create() = StreamDiagnosticPlacementNode(stage)
    override fun update(node: StreamDiagnosticPlacementNode) { node.stage = stage }
    override fun InspectorInfo.inspectableProperties() {
        name = "streamDiagnosticPlacement"
        properties["stage"] = stage
    }
}

private class StreamDiagnosticPlacementNode(var stage: String) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val child = measurable.measure(constraints)
        return layout(child.width, child.height) {
            StreamPerformanceDiagnostics.measureDetail(stage) { child.placeRelative(0, 0) }
        }
    }
}
