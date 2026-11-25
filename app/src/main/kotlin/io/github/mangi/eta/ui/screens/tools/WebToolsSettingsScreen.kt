package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider as CouiDivider
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.WebHttpMethod
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.BasicComponent
import io.github.mangi.eta.ui.components.Card
import io.github.mangi.eta.ui.components.HorizontalDivider
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.TextButton
import io.github.mangi.eta.ui.components.TextField
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiSectionTitle
import io.github.mangi.eta.ui.components.coui.CouiSegmentedTabs
import io.github.mangi.eta.ui.components.coui.CouiSelectPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Independent page: deliberately has no shared strings or navigation dependencies. */
@Composable
internal fun WebToolsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val store = remember(context, scope) { WebToolsSettingsStore(context.applicationContext, scope) }
    val settings = store.settings
    val credentials = store.credentials
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val cardHorizontal = if (isCoui) CouiPageDefaults.horizontalMargin else 12.dp

    MiuixScaffoldPage(title = "网页搜索与读取", onBack = onBack) {
        if (!store.loaded) {
            item(key = "web-loading") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = cardHorizontal, vertical = 12.dp),
                ) {
                    Text(
                        text = store.message ?: "正在加载设置…",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                    )
                }
            }
            return@MiuixScaffoldPage
        }

        // 顶部行为说明提示条
        item(key = "web-intro") {
            WebToolsInfoBanner(
                text = "公开搜索与静态正文读取不占用浏览器，不继承浏览器登录状态；后续页读取当前运行的快照。",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cardHorizontal)
                    .padding(top = 8.dp, bottom = 4.dp),
            )
        }

        // 分组 1：搜索服务
        item(key = "web-search-section") {
            WebSectionHeader(title = "搜索服务", isCoui = isCoui)
            BackendPickerCard(
                selected = settings.searchBackend,
                isSearch = true,
                isCoui = isCoui,
                horizontalPadding = cardHorizontal,
                onChange = {
                    TouchHaptics.click(view)
                    store.edit(settings.copy(searchBackend = it))
                },
            )
        }

        // 分组 2：读取服务
        item(key = "web-fetch-section") {
            WebSectionHeader(title = "读取服务", isCoui = isCoui)
            BackendPickerCard(
                selected = settings.fetchBackend,
                isSearch = false,
                isCoui = isCoui,
                horizontalPadding = cardHorizontal,
                onChange = {
                    TouchHaptics.click(view)
                    store.edit(settings.copy(fetchBackend = it))
                },
            )
        }

        // 条件分组：DeepSeek Search / Fetch
        if (settings.searchBackend == WebToolBackend.DEEPSEEK || settings.fetchBackend == WebToolBackend.DEEPSEEK) {
            item(key = "web-deepseek-section") {
                WebSectionHeader(title = "DeepSeek Search / Fetch", isCoui = isCoui)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = cardHorizontal),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        WebField(
                            label = "Base URL",
                            value = settings.deepSeek.baseUrl,
                            isCoui = isCoui,
                        ) {
                            store.edit(settings.copy(deepSeek = settings.deepSeek.copy(baseUrl = it)))
                        }
                        WebField(
                            label = "DeepSeek API Key（留空复用模型提供商）",
                            value = credentials.deepSeekApiKey,
                            secret = true,
                            isCoui = isCoui,
                        ) {
                            store.editCredentials(credentials.copy(deepSeekApiKey = it))
                        }
                        CredentialStatusRow(detected = store.hasProviderDeepSeekKey)
                    }
                }
            }
        }

        // 条件分组：自定义搜索 HTTP 接口
        if (settings.searchBackend == WebToolBackend.CUSTOM) {
            item(key = "web-custom-search-section") {
                val c = settings.customSearch
                WebSectionHeader(title = "自定义搜索 HTTP 接口", isCoui = isCoui)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = cardHorizontal),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MethodPickerRow(
                            selected = c.method,
                            isCoui = isCoui,
                            onChange = {
                                TouchHaptics.click(view)
                                store.edit(settings.copy(customSearch = c.copy(method = it)))
                            },
                        )
                        WebField("接口 URL", c.url, isCoui = isCoui) {
                            store.edit(settings.copy(customSearch = c.copy(url = it)))
                        }
                        WebField("搜索 API Key", credentials.customSearchApiKey, secret = true, isCoui = isCoui) {
                            store.editCredentials(credentials.copy(customSearchApiKey = it))
                        }
                        WebField("请求头（每行 Header: Value）", c.headersText, multiline = true, isCoui = isCoui) {
                            store.edit(settings.copy(customSearch = c.copy(headersText = it)))
                        }
                        if (c.method == WebHttpMethod.POST) {
                            WebField("JSON 请求体模板", c.bodyTemplate, multiline = true, isCoui = isCoui) {
                                store.edit(settings.copy(customSearch = c.copy(bodyTemplate = it)))
                            }
                        }
                        Text(
                            text = "URL 占位符：{{query}} 编码查询，{{raw_query}} 原始查询，{{count}} 结果数；JSON 请求体使用 {{query_json}} 与 {{count}}。GET 未设占位符时自动附加 q 参数。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
                        )
                        WebField("结果数组路径", c.resultsPath, isCoui = isCoui) {
                            store.edit(settings.copy(customSearch = c.copy(resultsPath = it)))
                        }
                        WebField("标题字段路径", c.titleField, isCoui = isCoui) {
                            store.edit(settings.copy(customSearch = c.copy(titleField = it)))
                        }
                        WebField("URL 字段路径", c.urlField, isCoui = isCoui) {
                            store.edit(settings.copy(customSearch = c.copy(urlField = it)))
                        }
                        WebField("摘要字段路径", c.snippetField, isCoui = isCoui) {
                            store.edit(settings.copy(customSearch = c.copy(snippetField = it)))
                        }
                    }
                }
            }
        }

        // 条件分组：自定义正文 HTTP 接口
        if (settings.fetchBackend == WebToolBackend.CUSTOM) {
            item(key = "web-custom-fetch-section") {
                val c = settings.customFetch
                WebSectionHeader(title = "自定义正文 HTTP 接口", isCoui = isCoui)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = cardHorizontal),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MethodPickerRow(
                            selected = c.method,
                            isCoui = isCoui,
                            onChange = {
                                TouchHaptics.click(view)
                                store.edit(settings.copy(customFetch = c.copy(method = it)))
                            },
                        )
                        WebField("接口 URL", c.url, isCoui = isCoui) {
                            store.edit(settings.copy(customFetch = c.copy(url = it)))
                        }
                        WebField("读取 API Key", credentials.customFetchApiKey, secret = true, isCoui = isCoui) {
                            store.editCredentials(credentials.copy(customFetchApiKey = it))
                        }
                        WebField("请求头（每行 Header: Value）", c.headersText, multiline = true, isCoui = isCoui) {
                            store.edit(settings.copy(customFetch = c.copy(headersText = it)))
                        }
                        if (c.method == WebHttpMethod.POST) {
                            WebField("JSON 请求体模板", c.bodyTemplate, multiline = true, isCoui = isCoui) {
                                store.edit(settings.copy(customFetch = c.copy(bodyTemplate = it)))
                            }
                        }
                        Text(
                            text = "URL 占位符：{{url}} 编码目标，{{raw_url}} 原始目标；JSON 请求体使用 {{url_json}}。GET 未设占位符时自动附加 url 参数。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
                        )
                        WebField("正文 JSON 路径（留空读取原始文本）", c.contentField, isCoui = isCoui) {
                            store.edit(settings.copy(customFetch = c.copy(contentField = it)))
                        }
                        WebField("标题 JSON 路径", c.titleField, isCoui = isCoui) {
                            store.edit(settings.copy(customFetch = c.copy(titleField = it)))
                        }
                    }
                }
            }
        }

        // 底部安全与路径说明脚注
        item(key = "web-footer-note") {
            Text(
                text = "JSON 路径以点分隔，数组下标写作 data.items.0.content；$ 表示根节点。远程正文服务失败后会自动回退本机静态读取。API Key 经 Android Keystore 加密存储，请勿写入普通请求头模板；跨域重定向会自动剥离认证头。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isCoui) 28.dp else 20.dp)
                    .padding(top = 14.dp, bottom = 12.dp),
            )
        }

        // 保存状态反馈 + 主保存按钮
        item(key = "web-save-section") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cardHorizontal)
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                store.message?.let { statusText ->
                    SaveFeedbackBanner(
                        text = statusText,
                        isError = store.messageIsError,
                    )
                }
                if (isCoui) {
                    CouiButton(
                        text = if (store.saving) "正在保存…" else "保存设置",
                        onClick = {
                            TouchHaptics.click(view)
                            store.save()
                        },
                        enabled = !store.saving,
                        role = CouiButtonRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(
                        text = if (store.saving) "正在保存…" else "保存设置",
                        onClick = {
                            TouchHaptics.click(view)
                            store.save()
                        },
                        enabled = !store.saving,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (isCoui) {
                CouiPageBottomSpacer()
            } else {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun WebSectionHeader(title: String, isCoui: Boolean) {
    if (isCoui) {
        CouiSectionTitle(text = title)
    } else {
        SmallTitle(text = title)
    }
}

@Composable
private fun WebToolsInfoBanner(text: String, modifier: Modifier = Modifier) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val primary = MiuixTheme.colorScheme.primary
    val bg = if (isCoui) {
        primary.copy(alpha = if (CouiTheme.isDark) 0.16f else 0.08f)
    } else {
        MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = primary,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(18.dp),
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BackendPickerCard(
    selected: WebToolBackend,
    isSearch: Boolean,
    isCoui: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onChange: (WebToolBackend) -> Unit,
) {
    val entries = WebToolBackend.entries
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
    ) {
        entries.forEachIndexed { index, backend ->
            val (title, summary) = when (backend) {
                WebToolBackend.BUILTIN -> if (isSearch) {
                    "默认公开搜索" to "内置公开网页检索，无需额外配置 API Key"
                } else {
                    "本机静态读取" to "直接抓取并提取静态网页正文，无需额外配置"
                }
                WebToolBackend.DEEPSEEK -> "DeepSeek" to if (isSearch) {
                    "使用 DeepSeek 官方搜索接口"
                } else {
                    "使用 DeepSeek 远程网页正文提取"
                }
                WebToolBackend.CUSTOM -> "自定义 HTTP" to if (isSearch) {
                    "对接自建或第三方搜索 REST API"
                } else {
                    "对接自建或第三方正文抽取 REST API"
                }
            }
            val isSelected = selected == backend
            if (isCoui) {
                CouiSelectPreference(
                    title = title,
                    summary = summary,
                    selected = isSelected,
                    onSelect = { onChange(backend) },
                )
            } else {
                BasicComponent(
                    title = title,
                    summary = summary,
                    onClick = { onChange(backend) },
                    endActions = {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.outline.copy(alpha = 0.25f),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.onPrimary),
                                )
                            }
                        }
                    },
                )
            }
            if (index < entries.lastIndex) {
                if (isCoui) {
                    CouiDivider(
                        startIndent = CouiPageDefaults.rowPadding,
                        endIndent = CouiPageDefaults.rowPadding,
                    )
                } else {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        thickness = 0.5.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MethodPickerRow(
    selected: WebHttpMethod,
    isCoui: Boolean,
    onChange: (WebHttpMethod) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "HTTP 请求方法",
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (isCoui) {
            CouiSegmentedTabs(
                items = listOf(
                    WebHttpMethod.GET to "GET",
                    WebHttpMethod.POST to "POST",
                ),
                selectedKey = selected,
                onSelectedKeyChange = onChange,
                small = true,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WebHttpMethod.entries.forEach { method ->
                    val isSelected = method == selected
                    TextButton(
                        text = method.name,
                        onClick = { onChange(method) },
                        colors = if (isSelected) {
                            ButtonDefaults.textButtonColorsPrimary()
                        } else {
                            ButtonDefaults.textButtonColors()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialStatusRow(detected: Boolean) {
    val tint = if (detected) StatusSuccess else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (detected) StatusSuccess.copy(alpha = 0.10f)
                else MiuixTheme.colorScheme.surfaceContainer,
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Key,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = if (detected) "已检测到 DeepSeek 提供商凭据（留空将自动复用）" else "尚未检测到 DeepSeek 提供商凭据，请在此填写 API Key",
            style = MiuixTheme.textStyles.footnote1,
            color = tint,
        )
    }
}

@Composable
private fun SaveFeedbackBanner(text: String, isError: Boolean) {
    val accent = if (isError) StatusError else StatusSuccess
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = accent,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WebField(
    label: String,
    value: String,
    secret: Boolean = false,
    multiline: Boolean = false,
    isCoui: Boolean,
    onChange: (String) -> Unit,
) {
    val transformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
    if (isCoui) {
        CouiTextField(
            value = value,
            onValueChange = onChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) Int.MAX_VALUE else 1,
            visualTransformation = transformation,
        )
    } else {
        TextField(
            value = value,
            onValueChange = onChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            visualTransformation = transformation,
        )
    }
}
