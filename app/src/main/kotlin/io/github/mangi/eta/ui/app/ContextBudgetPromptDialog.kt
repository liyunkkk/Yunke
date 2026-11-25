package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.components.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import io.github.mangi.eta.ui.components.WindowDialog

@Composable
internal fun ContextBudgetPromptDialog(
    reason: String,
    onDismiss: () -> Unit,
    onCompactThisRun: () -> Unit,
    onStop: () -> Unit,
) {
    val view = LocalView.current
    val colors = MiuixTheme.colorScheme
    WindowDialog(
        show = true,
        title = stringResource(R.string.context_budget_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.context_budget_body),
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
            )
            if (reason.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    colors = CardDefaults.defaultColors(
                        color = colors.surfaceContainerHigh,
                        contentColor = colors.onSurfaceVariantSummary,
                    ),
                ) {
                    Text(
                        text = reason.trim(),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
            Text(
                text = stringResource(R.string.context_budget_hint),
                style = MiuixTheme.textStyles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextBudgetChoice(
                    title = stringResource(R.string.context_budget_compact),
                    summary = stringResource(R.string.context_budget_compact_summary),
                    container = colors.primary,
                    content = colors.onPrimary,
                    onClick = {
                        TouchHaptics.click(view)
                        onCompactThisRun()
                    },
                )
                ContextBudgetChoice(
                    title = stringResource(R.string.context_budget_pause),
                    summary = stringResource(R.string.context_budget_pause_summary),
                    container = colors.surfaceContainerHigh,
                    content = colors.onSurface,
                    onClick = {
                        TouchHaptics.click(view)
                        onDismiss()
                    },
                )
                ContextBudgetChoice(
                    title = stringResource(R.string.context_budget_stop),
                    summary = stringResource(R.string.context_budget_stop_summary),
                    container = colors.error,
                    content = colors.onError,
                    onClick = {
                        TouchHaptics.click(view)
                        onStop()
                    },
                )
            }
        }
    }
}

@Composable
private fun ContextBudgetChoice(
    title: String,
    summary: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.defaultColors(
            color = container,
            contentColor = content,
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        onClick = onClick,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = content,
            )
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote2,
                color = content.copy(alpha = 0.78f),
            )
        }
    }
}
