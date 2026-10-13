package io.github.mangi.eta.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 三条左对齐、从上到下变短的横线。最上一条接近图标宽度，中条约为其三分之二，下条约为其三分之一；垂直位置仍与右上角三点对齐。线条用黑色占位，实际颜色由调用处的 tint 决定。 */
internal val MenuLines: ImageVector by lazy {
    ImageVector.Builder("MenuLines", 24.dp, 24.dp, 24f, 24f).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4.5f, 6.5f)
            lineTo(19.5f, 6.5f)
            moveTo(4.5f, 12f)
            lineTo(14.5f, 12f)
            moveTo(4.5f, 17.5f)
            lineTo(9.5f, 17.5f)
        }
    }.build()
}
