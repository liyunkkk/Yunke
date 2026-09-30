package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R

/** 作者提供的猫咪图标。底色和猫咪颜色可以分别着色。 */
@Composable
internal fun AppCatIcon(
    background: Color,
    cat: Color,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(background),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_cat_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(cat),
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
        )
    }
}
