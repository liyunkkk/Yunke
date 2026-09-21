package io.github.mangi.eta.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Original 24dp mark: one coordinator branches into two robot workers. Uses the caller's tint. */
internal val SubAgents: ImageVector by lazy {
    ImageVector.Builder("SubAgents", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 1.5f); lineTo(12f, 3f)
            moveTo(12f, 9f); lineTo(12f, 12f)
            moveTo(5.5f, 15f); lineTo(5.5f, 12f); lineTo(18.5f, 12f); lineTo(18.5f, 15f)
            for ((x, y) in listOf(8.5f to 3f, 2f to 15f, 15f to 15f)) {
                moveTo(x + 1.5f, y)
                lineTo(x + 5.5f, y)
                curveTo(x + 6.33f, y, x + 7f, y + 0.67f, x + 7f, y + 1.5f)
                lineTo(x + 7f, y + 4.5f)
                curveTo(x + 7f, y + 5.33f, x + 6.33f, y + 6f, x + 5.5f, y + 6f)
                lineTo(x + 1.5f, y + 6f)
                curveTo(x + 0.67f, y + 6f, x, y + 5.33f, x, y + 4.5f)
                lineTo(x, y + 1.5f)
                curveTo(x, y + 0.67f, x + 0.67f, y, x + 1.5f, y)
                close()
            }
        }
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round) {
            for ((x, y) in listOf(8.5f to 3f, 2f to 15f, 15f to 15f)) {
                moveTo(x + 2.2f, y + 2.8f); lineTo(x + 2.2f, y + 3.1f)
                moveTo(x + 4.8f, y + 2.8f); lineTo(x + 4.8f, y + 3.1f)
            }
        }
    }.build()
}
