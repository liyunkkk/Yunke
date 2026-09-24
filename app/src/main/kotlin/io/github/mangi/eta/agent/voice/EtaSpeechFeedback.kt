package io.github.mangi.eta.agent.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 浮窗语音态的提示条。
 *
 * 与主仓库不同，我方识别链路是豆包 ASR / 离线包，错误是 [VoiceModeState.error] 里的
 * 现成文案而非 SpeechRecognizer 错误码，因此这里直接展示字符串，不做资源映射。
 */
@Composable
internal fun EtaSpeechFeedback(error: String?, modifier: Modifier = Modifier) {
    val message = error?.takeIf { it.isNotBlank() } ?: return
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = if (dark) Color(0xFFFF9E99) else Color(0xFFB42318),
            modifier = Modifier
                .weight(1f, fill = false)
                .background(
                    if (dark) Color(0xFF34363B) else Color(0xFFF2F3F5),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
