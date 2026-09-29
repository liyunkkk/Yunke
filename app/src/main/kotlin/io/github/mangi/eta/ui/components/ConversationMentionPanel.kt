package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.model.ConversationMention
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConversationMentionPanel(
    state: ConversationMentionInputUi,
    query: String?,
    onSelect: (String) -> Unit,
) {
    if (state.pending.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.pending.forEach { mention ->
                Row(
                    modifier = Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text("@${mention.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.body2)
                        Text(
                            text = when {
                                mention.transcript.contains("[已截取：") -> "已截取中间记录 · ${mention.transcript.length} 字符"
                                mention.transcript.startsWith("[选择时快照：") -> "运行中会话快照 · ${mention.transcript.length} 字符"
                                else -> "选择时快照 · ${mention.transcript.length} 字符"
                            },
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { state.onRemove(mention.id) }, minWidth = 40.dp, minHeight = 48.dp) {
                        Icon(Icons.Rounded.Close, contentDescription = "移除会话引用 ${mention.title}", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (query == null) return
    val candidates = ConversationMention.candidates(state.conversations, query, state.currentConversationId,
        state.pending.map { it.conversationId }.toSet())
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        .clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh)) {
        Text("引用其他会话 · 只放引用，需要时再读", modifier = Modifier.padding(12.dp), style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (state.pending.size >= ConversationMention.MAX_ATTACHED) {
            Text("最多引用 3 个会话，请先移除一个。", modifier = Modifier.padding(12.dp))
        } else if (candidates.isEmpty()) {
            Text("没有匹配的其他会话", modifier = Modifier.padding(12.dp))
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(candidates, key = { it.id }) { candidate ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onSelect(candidate.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text("@${candidate.title}", style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(candidate.preview, candidate.timeLabel, if (candidate.isActiveRun) "运行中" else "")
                            .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
