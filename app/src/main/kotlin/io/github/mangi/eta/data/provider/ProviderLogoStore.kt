package io.github.mangi.eta.data.provider

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 供应商头像。
 *
 * 取值约定：
 *  - 空字符串：按 sourceType 自动（沿用原有 ProviderBranding 行为）
 *  - "builtin:<slug>"：内置头像，slug 见 [builtins]
 *  - "file:<绝对路径>"：自定义图片（从相册选入后拷进应用私有目录）
 */
internal object ProviderLogoStore {
    private const val KEY_PREFIX = "provider_logo_v1_"

    /** 内置头像：slug -> drawable。列表顺序即选择器展示顺序。 */
    val builtins: List<Pair<String, Int>> = listOf(
        "openai" to R.drawable.provider_logo_openai,
        "anthropic" to R.drawable.provider_logo_anthropic,
        "google" to R.drawable.provider_icon_google,
        "gemini" to R.drawable.provider_icon_googlegemini,
        "x" to R.drawable.provider_icon_x,
        "meta" to R.drawable.provider_icon_meta,
        "mistral" to R.drawable.provider_icon_mistralai,
        "deepseek" to R.drawable.provider_icon_deepseek,
        "qwen" to R.drawable.provider_icon_qwen,
        "moonshot" to R.drawable.provider_icon_moonshotai,
        "minimax" to R.drawable.provider_icon_minimax,
        "alibaba" to R.drawable.provider_icon_alibabacloud,
        "baidu" to R.drawable.provider_icon_baidu,
        "bytedance" to R.drawable.provider_icon_bytedance,
        "perplexity" to R.drawable.provider_icon_perplexity,
        "nvidia" to R.drawable.provider_icon_nvidia,
        "huggingface" to R.drawable.provider_icon_huggingface,
        "ollama" to R.drawable.provider_icon_ollama,
        "openrouter" to R.drawable.provider_icon_openrouter,
        "antigravity" to R.drawable.provider_logo_antigravity,
        "bailian" to R.drawable.provider_logo_bailian,
        "doubao" to R.drawable.model_logo_doubao,
    )

    private val state = MutableStateFlow<Map<String, String>>(emptyMap())

    fun logos(): StateFlow<Map<String, String>> = state.asStateFlow()

    fun builtinRes(slug: String): Int? = builtins.firstOrNull { it.first == slug }?.second

    /** 读取一次持久化的头像表。界面进入时调用即可。 */
    fun refresh(context: Context) {
        val prefs = Prefs.localAgentPreferences() ?: return
        val map = prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(KEY_PREFIX) to (it.value as String) }
        state.value = map
    }

    fun valueOf(providerId: String): String = state.value[providerId].orEmpty()

    fun set(context: Context, providerId: String, value: String) {
        val prefs = Prefs.localAgentPreferences() ?: return
        prefs.edit().putString(KEY_PREFIX + providerId, value).apply()
        state.value = state.value.toMutableMap().apply { put(providerId, value) }
    }
}
