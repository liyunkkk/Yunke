package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.haptics.TouchHaptics
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.providerBodiesEditor(
    bodies: List<ProviderBodyDraft>,
    onBodiesChange: (List<ProviderBodyDraft>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    item(key = "custom_bodies") {
        ProviderSection(title = "自定义请求体") {
            val view = LocalView.current
            val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f)
            BasicComponent(
                title = if (bodies.isEmpty()) "未设置" else "已设置 ${bodies.size} 项",
                summary = "值使用 JSON 格式，字符串请加双引号；不是请求头。",
                endActions = {
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                onClick = {
                    TouchHaptics.click(view)
                    onExpandedChange(!expanded)
                },
            )
            if (expanded) {
                bodies.forEach { row ->
                    key(row.id) {
                        HorizontalDivider()
                        ProviderBodyRow(
                            row = row,
                            onKeyChange = { value ->
                                onBodiesChange(bodies.map { if (it.id == row.id) it.copy(key = value) else it })
                            },
                            onValueChange = { value ->
                                onBodiesChange(bodies.map { if (it.id == row.id) it.copy(valueJson = value) else it })
                            },
                            onRemove = { onBodiesChange(bodies.filterNot { it.id == row.id }) },
                        )
                    }
                }
                val error = parseProviderBodies(bodies).exceptionOrNull()?.message
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = error ?: "示例：字段 eta_prompt_cache，值 \"1h\"；也支持 true、123、null、{}、[]。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                HorizontalDivider()
                BasicComponent(
                    title = "添加请求体字段",
                    titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary),
                    startAction = {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary)
                    },
                    onClick = {
                        TouchHaptics.click(view)
                        onBodiesChange(bodies + ProviderBodyDraft())
                    },
                )
            }
        }
    }
}

@Composable
private fun ProviderBodyRow(
    row: ProviderBodyDraft,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = row.key,
                onValueChange = onKeyChange,
                label = "字段名",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = row.valueJson,
                onValueChange = onValueChange,
                label = "值（JSON）",
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = {
            TouchHaptics.click(view)
            onRemove()
        }) {
            Icon(imageVector = Icons.Rounded.Delete, contentDescription = "删除请求体字段",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
        }
    }
}
