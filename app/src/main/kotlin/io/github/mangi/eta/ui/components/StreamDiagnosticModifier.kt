package io.github.mangi.eta.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout

/**
 * Observes the existing child once without changing constraints, size or placement.
 * Labels must be fixed stage names, never content or item identity. No snapshot state,
 * semantics, layers or scheduled work are introduced by this modifier.
 */
internal fun Modifier.streamDiagnosticMeasure(stage: String): Modifier {
    if (!StreamPerformanceDiagnostics.enabled) return this
    return layout { measurable, constraints ->
        StreamPerformanceDiagnostics.measureDetail(stage) {
            val child = measurable.measure(constraints)
            layout(child.width, child.height) { child.placeRelative(0, 0) }
        }
    }
}

/** Disabled construction preserves modifier identity; enabled draws content exactly once. */
internal fun Modifier.streamDiagnosticDraw(stage: String): Modifier {
    if (!StreamPerformanceDiagnostics.enabled) return this
    return drawWithContent {
        StreamPerformanceDiagnostics.measureDetail(stage) { drawContent() }
    }
}
