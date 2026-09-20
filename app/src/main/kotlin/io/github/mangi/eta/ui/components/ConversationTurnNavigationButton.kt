package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationTurnNavigationButton(
    direction: ConversationNavigationDirection,
    visible: Boolean,
    onStep: () -> Unit,
    onEdge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val down = direction == ConversationNavigationDirection.Down
    val stepLabel = stringResource(if (down) R.string.chat_next_turn else R.string.chat_previous_turn)
    val edgeLabel = stringResource(if (down) R.string.chat_go_to_bottom else R.string.chat_go_to_top)
    val view = LocalView.current
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
        exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
    ) {
        Box(
            modifier = Modifier.size(40.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stepLabel,
                    onLongClickLabel = edgeLabel,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    // Both gestures have one explicit feedback; never add the built-in long-press haptic.
                    hapticFeedbackEnabled = false,
                    onClick = { TouchHaptics.click(view); onStep() },
                    onLongClick = { TouchHaptics.longPress(view); onEdge() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = stepLabel,
                modifier = Modifier.size(17.dp),
                tint = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}
