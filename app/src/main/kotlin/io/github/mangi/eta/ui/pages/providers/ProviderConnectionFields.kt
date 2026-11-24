package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.TextField
import io.github.mangi.eta.ui.components.SwitchPreference
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Native Miuix connection form; independent of OAuth provider identities. */
@Composable
internal fun GeminiConnectionFields(
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
) {
    val vertex = GeminiEndpointMode.normalize(draft.endpointMode) == GeminiEndpointMode.VERTEX_AI
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiGeminiConnectionFields(draft = draft, vertex = vertex, onDraftChange = onDraftChange)
        return
    }
    WindowSpinnerPreference(
        title = "Gemini 接入平台",
        items = listOf(DropdownItem(text = "Google AI Studio"), DropdownItem(text = "Google Cloud Vertex AI")),
        selectedIndex = if (vertex) 1 else 0,
        summary = if (vertex) "Gemini 4 · streamGenerateContent" else "Gemini Interactions API",
        onSelectedIndexChange = {
            onDraftChange(draft.withGeminiEndpointMode(if (it == 1) GeminiEndpointMode.VERTEX_AI else GeminiEndpointMode.AI_STUDIO))
        },
    )
    if (vertex) {
        Spacer(Modifier.height(12.dp))
        TextField(
            value = draft.vertexProject,
            onValueChange = { onDraftChange(draft.copy(vertexProject = it)) },
            label = "Google Cloud Project ID / 项目编号",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        TextField(
            value = draft.vertexLocation,
            onValueChange = { onDraftChange(draft.copy(vertexLocation = it)) },
            label = "Location（默认 global）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "官方 Gemini 4 接口需要 Project；按 Location 直连 global、us、eu 或区域端点。连接测试验证当前模型的 countTokens。模型使用内置预设或手动添加，网页搜索使用本地工具。",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    } else {
        SwitchPreference(
            title = "服务端网页搜索",
            summary = "允许支持的 Gemini 模型调用 Google Search",
            checked = draft.hostedWebSearchEnabled,
            onCheckedChange = { onDraftChange(draft.copy(hostedWebSearchEnabled = it)) },
        )
    }
}

@Composable
private fun CouiGeminiConnectionFields(
    draft: ProviderConfigDraft,
    vertex: Boolean,
    onDraftChange: (ProviderConfigDraft) -> Unit,
) {
    CouiDropdownPreference(
        title = "Gemini 接入平台",
        items = listOf("Google AI Studio", "Google Cloud Vertex AI"),
        selectedIndex = if (vertex) 1 else 0,
        summary = if (vertex) "Gemini 4 · streamGenerateContent" else "Gemini Interactions API",
        onSelectedIndexChange = {
            onDraftChange(draft.withGeminiEndpointMode(if (it == 1) GeminiEndpointMode.VERTEX_AI else GeminiEndpointMode.AI_STUDIO))
        },
    )
    if (vertex) {
        Spacer(Modifier.height(12.dp))
        CouiTextField(
            value = draft.vertexProject,
            onValueChange = { onDraftChange(draft.copy(vertexProject = it)) },
            label = "Google Cloud Project ID / 项目编号",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        CouiTextField(
            value = draft.vertexLocation,
            onValueChange = { onDraftChange(draft.copy(vertexLocation = it)) },
            label = "Location（默认 global）",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "官方 Gemini 4 接口需要 Project；按 Location 直连 global、us、eu 或区域端点。连接测试验证当前模型的 countTokens。模型使用内置预设或手动添加，网页搜索使用本地工具。",
            style = MiuixTheme.textStyles.footnote2,
            color = CouiPageDefaults.themeColors().summary,
        )
    } else {
        CouiSwitchPreference(
            title = "服务端网页搜索",
            summary = "允许支持的 Gemini 模型调用 Google Search",
            checked = draft.hostedWebSearchEnabled,
            onCheckedChange = { onDraftChange(draft.copy(hostedWebSearchEnabled = it)) },
        )
    }
}
