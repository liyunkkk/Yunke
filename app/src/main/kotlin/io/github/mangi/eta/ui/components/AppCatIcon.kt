package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 自己画的简笔猫，不使用别人的图标文件。 */
@Composable
internal fun AppCatIcon(
    background: Color,
    cat: Color,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    Canvas(modifier.size(size)) {
        drawRect(background)
        val w = this.size.width
        fun ear(left: Boolean) {
            val sign = if (left) 1f else -1f
            val cx = w * 0.5f
            val path = Path().apply {
                moveTo(cx - sign * w * 0.22f, w * 0.40f)
                lineTo(cx - sign * w * 0.16f, w * 0.18f)
                lineTo(cx - sign * w * 0.05f, w * 0.38f)
                close()
            }
            drawPath(path, cat)
        }
        ear(true)
        ear(false)
        drawCircle(cat, radius = w * 0.035f, center = Offset(w * 0.40f, w * 0.50f))
        drawCircle(cat, radius = w * 0.035f, center = Offset(w * 0.60f, w * 0.50f))
        val nose = Path().apply {
            moveTo(w * 0.50f, w * 0.56f)
            lineTo(w * 0.47f, w * 0.60f)
            lineTo(w * 0.53f, w * 0.60f)
            close()
        }
        drawPath(nose, cat)
        val stroke = Stroke(width = w * 0.018f, cap = StrokeCap.Round)
        drawLine(cat, Offset(w * 0.47f, w * 0.64f), Offset(w * 0.42f, w * 0.68f), strokeWidth = stroke.width)
        drawLine(cat, Offset(w * 0.53f, w * 0.64f), Offset(w * 0.58f, w * 0.68f), strokeWidth = stroke.width)
        listOf(-0.04f, 0f, 0.04f).forEach { dy ->
            drawLine(
                cat,
                Offset(w * 0.18f, w * (0.50f + dy)),
                Offset(w * 0.34f, w * (0.50f + dy * 0.4f)),
                strokeWidth = stroke.width,
            )
            drawLine(
                cat,
                Offset(w * 0.82f, w * (0.50f + dy)),
                Offset(w * 0.66f, w * (0.50f + dy * 0.4f)),
                strokeWidth = stroke.width,
            )
        }
    }
}
