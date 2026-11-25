// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see third_party/openminis-browser/NOTICE.md.
package io.github.mangi.eta.agent.browser.ported.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle

// Material3 ButtonDefaults.MinHeight = 40dp; tuned to 48dp for touch
// ergonomics on phones. IconButton family is unaffected (icon-sized).
val MinisButtonHeight = 48.dp

// Compact button height for actions embedded inside section cards
// (e.g. "Sign out" inside a credentials card, "Set Bearer Token" inside
// a token section). Visually subordinate to MinisButtonHeight (48dp)
// which remains the size for primary screen actions ("Add Custom Model",
// TopAppBar Save, AlertDialog confirm).
val MinisSmallButtonHeight = 32.dp

private val SmallButtonContentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)

@Composable
private fun couiPrimaryButtonColors(defaultColors: ButtonColors, provided: ButtonColors): ButtonColors {
    if (provided != defaultColors) return provided
    val dark = CouiTheme.isDark
    val accent = CouiPageDefaults.themeColors(dark).accent
    return ButtonDefaults.buttonColors(
        containerColor = accent,
        contentColor = Color.White,
        disabledContainerColor = accent.copy(alpha = 0.3f),
        disabledContentColor = Color(0x4DFFFFFF),
    )
}

@Composable
private fun couiSecondaryButtonColors(defaultColors: ButtonColors, provided: ButtonColors): ButtonColors {
    if (provided != defaultColors) return provided
    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorSchemeFor(dark)
    return ButtonDefaults.buttonColors(
        containerColor = if (dark) Color(0xFF353535) else Color(0xFFEBEBEB),
        contentColor = scheme.onSurface,
        disabledContainerColor = if (dark) Color(0x1AFFFFFF) else Color(0x14000000),
        disabledContentColor = scheme.onSurface.copy(alpha = 0.35f),
    )
}

@Composable
private fun couiTextButtonColors(defaultColors: ButtonColors, provided: ButtonColors): ButtonColors {
    if (provided != defaultColors) return provided
    val dark = CouiTheme.isDark
    val accent = CouiPageDefaults.themeColors(dark).accent
    return ButtonDefaults.textButtonColors(
        contentColor = accent,
        disabledContentColor = accent.copy(alpha = 0.35f),
    )
}

@Composable
fun MinisButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val coui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val resolvedColors = if (coui) couiPrimaryButtonColors(ButtonDefaults.buttonColors(), colors) else colors
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = if (coui) CouiCapsuleShape else shape,
        colors = resolvedColors,
        elevation = if (coui) null else elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val resolvedColors = couiSecondaryButtonColors(ButtonDefaults.outlinedButtonColors(), colors)
        Button(
            onClick = onClick,
            modifier = modifier.heightIn(min = MinisButtonHeight),
            enabled = enabled,
            shape = CouiCapsuleShape,
            colors = resolvedColors,
            elevation = null,
            border = null,
            contentPadding = contentPadding,
            interactionSource = interactionSource ?: remember { MutableInteractionSource() },
            content = content,
        )
        return
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val coui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val resolvedColors = if (coui) couiTextButtonColors(ButtonDefaults.textButtonColors(), colors) else colors
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinisButtonHeight),
        enabled = enabled,
        shape = if (coui) CouiCapsuleShape else shape,
        colors = resolvedColors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

// defaultMinSize is also pinned at MinisSmallButtonHeight so Material3's
// internal 40dp floor (ButtonDefaults.MinHeight) doesn't override the
// heightIn modifier and keep the button at 40dp.
@Composable
fun MinisSmallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val coui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val resolvedColors = if (coui) couiPrimaryButtonColors(ButtonDefaults.buttonColors(), colors) else colors
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = if (coui) CouiCapsuleShape else shape,
        colors = resolvedColors,
        elevation = if (coui) null else elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisSmallOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled = enabled),
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val resolvedColors = couiSecondaryButtonColors(ButtonDefaults.outlinedButtonColors(), colors)
        Button(
            onClick = onClick,
            modifier = modifier
                .heightIn(min = MinisSmallButtonHeight)
                .defaultMinSize(minHeight = MinisSmallButtonHeight),
            enabled = enabled,
            shape = CouiCapsuleShape,
            colors = resolvedColors,
            elevation = null,
            border = null,
            contentPadding = contentPadding,
            interactionSource = interactionSource ?: remember { MutableInteractionSource() },
            content = content,
        )
        return
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

@Composable
fun MinisSmallTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = SmallButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val coui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val resolvedColors = if (coui) couiTextButtonColors(ButtonDefaults.textButtonColors(), colors) else colors
    TextButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = MinisSmallButtonHeight)
            .defaultMinSize(minHeight = MinisSmallButtonHeight),
        enabled = enabled,
        shape = if (coui) CouiCapsuleShape else shape,
        colors = resolvedColors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}
