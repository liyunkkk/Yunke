package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.WebHttpMethod
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiTextField

/** Independent page: deliberately has no shared strings or navigation dependencies. */
@Composable
internal fun WebToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context, scope) { WebToolsSettingsStore(context.applicationContext, scope) }
    val settings = store.settings
    val credentials = store.credentials
    MiuixScaffoldPage(title = "网页搜索与读取", onBack = onBack) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!store.loaded) {
                    Text(store.message ?: "正在加载设置")
                    return@Column
                }
                Text("公开搜索与静态正文读取不占用浏览器，不继承浏览器登录状态。后续页读取当前运行的快照。")
                BackendPicker("搜索服务", settings.searchBackend) { store.edit(settings.copy(searchBackend = it)) }
                BackendPicker("读取服务", settings.fetchBackend) { store.edit(settings.copy(fetchBackend = it)) }
                if (settings.searchBackend == WebToolBackend.DEEPSEEK || settings.fetchBackend == WebToolBackend.DEEPSEEK) {
                    Text("DeepSeek Search / Fetch", style = MaterialTheme.typography.titleMedium)
                    WebField("Base URL", settings.deepSeek.baseUrl) {
                        store.edit(settings.copy(deepSeek = settings.deepSeek.copy(baseUrl = it)))
                    }
                    WebField("DeepSeek API Key（留空复用模型提供商）", credentials.deepSeekApiKey, secret = true) {
                        store.editCredentials(credentials.copy(deepSeekApiKey = it))
                    }
                    Text(if (store.hasProviderDeepSeekKey) "已检测到 DeepSeek 提供商凭据" else "尚未检测到 DeepSeek 提供商凭据")
                }
                if (settings.searchBackend == WebToolBackend.CUSTOM) {
                    val c = settings.customSearch
                    Text("自定义搜索 HTTP 接口", style = MaterialTheme.typography.titleMedium)
                    MethodPicker(c.method) { store.edit(settings.copy(customSearch = c.copy(method = it))) }
                    WebField("接口 URL", c.url) { store.edit(settings.copy(customSearch = c.copy(url = it))) }
                    WebField("搜索 API Key", credentials.customSearchApiKey, secret = true) {
                        store.editCredentials(credentials.copy(customSearchApiKey = it))
                    }
                    WebField("请求头（每行 Header: Value）", c.headersText, multiline = true) {
                        store.edit(settings.copy(customSearch = c.copy(headersText = it)))
                    }
                    if (c.method == WebHttpMethod.POST) WebField("JSON 请求体模板", c.bodyTemplate, multiline = true) {
                        store.edit(settings.copy(customSearch = c.copy(bodyTemplate = it)))
                    }
                    Text("URL 占位符：{{query}} 为编码查询，{{raw_query}} 为原始查询，{{count}} 为结果数。JSON 请求体用 {{query_json}} 和 {{count}}。GET 未设占位符时自动添加 q 参数。")
                    WebField("结果数组路径", c.resultsPath) { store.edit(settings.copy(customSearch = c.copy(resultsPath = it))) }
                    WebField("标题字段路径", c.titleField) { store.edit(settings.copy(customSearch = c.copy(titleField = it))) }
                    WebField("URL 字段路径", c.urlField) { store.edit(settings.copy(customSearch = c.copy(urlField = it))) }
                    WebField("摘要字段路径", c.snippetField) { store.edit(settings.copy(customSearch = c.copy(snippetField = it))) }
                }
                if (settings.fetchBackend == WebToolBackend.CUSTOM) {
                    val c = settings.customFetch
                    Text("自定义正文 HTTP 接口", style = MaterialTheme.typography.titleMedium)
                    MethodPicker(c.method) { store.edit(settings.copy(customFetch = c.copy(method = it))) }
                    WebField("接口 URL", c.url) { store.edit(settings.copy(customFetch = c.copy(url = it))) }
                    WebField("读取 API Key", credentials.customFetchApiKey, secret = true) {
                        store.editCredentials(credentials.copy(customFetchApiKey = it))
                    }
                    WebField("请求头（每行 Header: Value）", c.headersText, multiline = true) {
                        store.edit(settings.copy(customFetch = c.copy(headersText = it)))
                    }
                    if (c.method == WebHttpMethod.POST) WebField("JSON 请求体模板", c.bodyTemplate, multiline = true) {
                        store.edit(settings.copy(customFetch = c.copy(bodyTemplate = it)))
                    }
                    Text("URL 占位符：{{url}} 为编码目标，{{raw_url}} 为原始目标。JSON 请求体用 {{url_json}}。GET 未设占位符时自动添加 url 参数。")
                    WebField("正文 JSON 路径（空值读取原始文本）", c.contentField) {
                        store.edit(settings.copy(customFetch = c.copy(contentField = it)))
                    }
                    WebField("标题 JSON 路径", c.titleField) { store.edit(settings.copy(customFetch = c.copy(titleField = it))) }
                }
                Text("JSON 路径用点分隔，数组下标写作 data.items.0.content；$ 表示根节点。远程正文服务失败后会尝试本机静态读取。API Key 使用 Android Keystore 加密保存。请求头模板是普通设置，请将密钥放在专用 API Key 输入框；跨域重定向会剥离认证头。")
                store.message?.let { Text(it, color = if (store.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                Button(onClick = store::save, enabled = !store.saving) { Text(if (store.saving) "正在保存" else "保存") }
            }
        }
    }
}

@Composable
private fun BackendPicker(title: String, selected: WebToolBackend, onChange: (WebToolBackend) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        WebToolBackend.entries.forEach { backend ->
            val label = when (backend) {
                WebToolBackend.BUILTIN -> "默认公开搜索 / 本机静态读取"
                WebToolBackend.DEEPSEEK -> "DeepSeek"
                WebToolBackend.CUSTOM -> "自定义 HTTP"
            }
            TextButton(onClick = { onChange(backend) }) { Text((if (selected == backend) "● " else "○ ") + label) }
        }
    }
}

@Composable
private fun MethodPicker(selected: WebHttpMethod, onChange: (WebHttpMethod) -> Unit) {
    TextButton(onClick = { onChange(if (selected == WebHttpMethod.GET) WebHttpMethod.POST else WebHttpMethod.GET) }) {
        Text("HTTP 方法：${selected.name}（点击切换）")
    }
}

@Composable
private fun WebField(label: String, value: String, secret: Boolean = false, multiline: Boolean = false, onChange: (String) -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) Int.MAX_VALUE else 1,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        )
        return
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
