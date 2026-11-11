package io.github.mangi.eta.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.ModelRetrySettings
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.ArrowPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.WindowSpinnerPreference
import io.github.mangi.eta.ui.icons.SubAgents
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 设置二级页：模型与对话。
 *
 * 内容全部取自 Yunke 旧一级页的 section_agent + section_model_features（原样搬运，
 * 顺序、图标、条件渲染不变），不是照抄 Number444 的 SettingsModelScreen.kt：
 * Number444 那页的自动展开思考 / 默认思考深度 / 自动压缩 / 压缩服务商 / 压缩模型
 * 五项依赖已搁置的思考 lane key（AGENT_AUTO_EXPAND_THINKING /
 * AGENT_DEFAULT_REASONING_EFFORT / AGENT_COMPACT_PROVIDER_ID / AGENT_COMPACT_MODEL_ID /
 * AGENT_AUTO_COMPACTION_ENABLED，Yunke Prefs.kt 里没有），一律不搬；压缩配置走
 * Yunke 自己的 ContextCompression 页（下面第三行入口）。
 * currentProviderId / currentModelId 由 AgentAppRoot 的 SettingsModel 入口从
 * shellHomeState 传入（旧一级页的 SettingsScreen 参数已随瘦身删掉，见 README）。
 */
@Composable
internal fun SettingsModelScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
    currentModelId: String? = null,
) {
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    var modelRetryCount by remember(agentPrefs) {
        mutableStateOf(
            ModelRetrySettings.parseCount(
                agentPrefs?.getString(Prefs.Keys.MODEL_RETRY_COUNT, ModelRetrySettings.DEFAULT_COUNT.toString()),
            ),
        )
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.settings_category_model_conversation),
        onBack = onBack,
    ) {
        // ── LLM 提供商 ──────────────────────────────────────────────
        item(key = "model_providers") {
            SmallTitle(stringResource(R.string.settings_llm_providers))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SettingsProviderEntry(currentProviderId, currentModelId) {
                    onNavigate(AppRoute.ModelProviders)
                }

                SwitchPref(
                    context = context,
                    prefs = agentPrefs,
                    title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                    key = Prefs.Keys.AGENT_THINKING_ENABLED,
                    icon = Icons.Rounded.Psychology,
                )

                ArrowPreference(
                    title = stringResource(R.string.route_context_compression),
                    startAction = {
                        PreferenceIcon(
                            icon = Icons.Rounded.Compress,
                        )
                    },
                    onClick = { onNavigate(AppRoute.ContextCompression) },
                )
            }
        }

        // ── 模型功能 ────────────────────────────────────────────────
        item(key = "model_features") {
            SmallTitle(stringResource(R.string.settings_model_features))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.auxiliary_vision_title),
                    startAction = { PreferenceIcon(icon = Icons.Rounded.RemoveRedEye) },
                    onClick = { onNavigate(AppRoute.AuxiliaryVision) },
                )
                ArrowPreference(title = "子代理",
                    startAction = { PreferenceIcon(icon = SubAgents) },
                    onClick = { onNavigate(AppRoute.SubAgents) })
                ArrowPreference(
                    title = stringResource(R.string.title_model_title),
                    startAction = { PreferenceIcon(icon = Icons.Rounded.AutoAwesome) },
                    onClick = { onNavigate(AppRoute.TitleModel) },
                )
                ArrowPreference(
                    title = stringResource(R.string.error_reconnect_title),
                    startAction = { PreferenceIcon(icon = Icons.Rounded.Refresh) },
                    onClick = { onNavigate(AppRoute.ErrorReconnectSettings) },
                )

                WindowSpinnerPreference(
                    title = "模型请求重试次数",
                    summary = "网络或中转站不稳时可调高；0 表示不自动重试。",
                    items = ModelRetrySettings.allowedCounts.map { DropdownItem(text = it.toString()) },
                    selectedIndex = ModelRetrySettings.allowedCounts.indexOf(modelRetryCount).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val count = ModelRetrySettings.allowedCounts.getOrNull(index)
                            ?: return@WindowSpinnerPreference
                        val targetPrefs = agentPrefs ?: return@WindowSpinnerPreference
                        if (putStringSync(targetPrefs, Prefs.Keys.MODEL_RETRY_COUNT, count.toString())) {
                            modelRetryCount = count
                        } else {
                            Toast.makeText(
                                context.applicationContext,
                                context.getString(R.string.settings_write_failed),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    startAction = {
                        PreferenceIcon(icon = Icons.Rounded.Refresh, enabled = agentPrefs != null)
                    },
                    enabled = agentPrefs != null,
                )
            }
        }
    }
}
