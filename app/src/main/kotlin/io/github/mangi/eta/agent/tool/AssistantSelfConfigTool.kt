package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 助手第一方静默自省与受控自改工具实现。
 * 支持秒级读取自身配置（脱敏保护），并支持安全白名单受控修改（防提示词注入）。
 */
internal class AssistantSelfConfigTool(
    private val context: Context,
) {
    private val appVersion: String by lazy {
        runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "5.3.1"
        }.getOrDefault("5.3.1")
    }

    /**
     * 静默读取当前助手的脱敏配置快照。
     */
    fun getAssistantConfig(): String {
        val runtimeConfig = runBlocking {
            RuntimeConfigRepository.currentRuntimeConfig()
        }
        val sanitized = AssistantConfigSanitizer.sanitize(
            config = runtimeConfig,
            appVersion = appVersion,
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "get_assistant_config")
            .put("config", sanitized)
            .toString()
    }

    /**
     * 安全受控修改自身设置。
     */
    fun updateAssistantConfig(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        val target = args.optString("target").trim()
        val valueStr = args.optString("value").trim()
        if (action.isBlank() || target.isBlank()) {
            return errorJson("INVALID_ARGUMENTS", "必须提供 action 与 target 参数")
        }

        // 强防注入护栏：严禁任何涉及凭据明文的修改
        val targetLower = target.lowercase()
        if (targetLower.contains("key") || targetLower.contains("secret") ||
            targetLower.contains("token") || targetLower.contains("password")
        ) {
            return errorJson(
                "PERMISSION_DENIED",
                "出于最高安全防护原则，系统严禁通过自然语言对话修改或传入 API Key/Token 凭据。请在设置界面中手动配置。"
            )
        }

        return when (action) {
            "switch_model" -> handleSwitchModel(target)
            "switch_provider" -> handleSwitchProvider(target)
            "toggle_setting" -> handleToggleSetting(target, valueStr)
            else -> errorJson(
                "UNSUPPORTED_ACTION",
                "不支持的 action: $action。仅支持 switch_model、switch_provider、toggle_setting"
            )
        }
    }

    private fun handleSwitchModel(target: String): String {
        val allProviders = runBlocking { ProviderRepository.allProviders() }
        val allModels = allProviders.flatMap { it.models }
        val matchedModel = allModels.firstOrNull { model ->
            model.id.equals(target, ignoreCase = true) ||
                model.displayName.equals(target, ignoreCase = true) ||
                model.modelId.equals(target, ignoreCase = true)
        } ?: allModels.firstOrNull { model ->
            model.displayName.contains(target, ignoreCase = true) ||
                model.modelId.contains(target, ignoreCase = true)
        }
        if (matchedModel == null) {
            val available = JSONArray()
            allModels.filter { it.isEnabled }.take(15).forEach {
                available.put("${it.displayName} (${it.id})")
            }
            return JSONObject()
                .put("ok", false)
                .put("code", "MODEL_NOT_FOUND")
                .put("message", "未找到匹配的模型：$target")
                .put("available_models", available)
                .toString()
        }
        runBlocking {
            RuntimeConfigRepository.setSelectedModelId(matchedModel.id)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "update_assistant_config")
            .put("action", "switch_model")
            .put("selected_model_id", matchedModel.id)
            .put("selected_model_name", matchedModel.displayName)
            .put("message", "已成功将模型切换为：${matchedModel.displayName}")
            .toString()
    }

    private fun handleSwitchProvider(target: String): String {
        val allProviders = runBlocking { ProviderRepository.allProviders() }
        val matchedProvider = allProviders.firstOrNull { provider ->
            provider.id.equals(target, ignoreCase = true) ||
                provider.name.equals(target, ignoreCase = true)
        } ?: allProviders.firstOrNull { provider ->
            provider.name.contains(target, ignoreCase = true)
        }
        if (matchedProvider == null) {
            val available = JSONArray()
            allProviders.filter { it.isEnabled }.forEach {
                available.put("${it.name} (${it.id})")
            }
            return JSONObject()
                .put("ok", false)
                .put("code", "PROVIDER_NOT_FOUND")
                .put("message", "未找到匹配的服务商：$target")
                .put("available_providers", available)
                .toString()
        }

        runBlocking {
            RuntimeConfigRepository.setSelectedProviderId(matchedProvider.id)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "update_assistant_config")
            .put("action", "switch_provider")
            .put("selected_provider_id", matchedProvider.id)
            .put("selected_provider_name", matchedProvider.name)
            .put("message", "已成功将服务商切换为：${matchedProvider.name}")
            .toString()
    }

    private fun handleToggleSetting(target: String, valueStr: String): String {
        val key = when (target.lowercase()) {
            "thinking_mode", "thinking_enabled", "agent_thinking_enabled" -> Prefs.Keys.AGENT_THINKING_ENABLED
            "terminal_tools", "agent_terminal_tools" -> Prefs.Keys.AGENT_TERMINAL_TOOLS
            "browser_tools", "agent_browser_tools" -> Prefs.Keys.AGENT_BROWSER_TOOLS
            "device_direct_tools", "agent_device_direct_tools" -> Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS
            "device_sensitive_read_tools", "agent_device_sensitive_read_tools" ->
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS
            "device_sensitive_action_tools", "agent_device_sensitive_action_tools" ->
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS
            "kimi_web_builtin_browser", "kimi_builtin_browser" -> Prefs.Keys.KIMI_WEB_USE_BUILTIN_BROWSER
            else -> null
        }
        if (key == null) {
            return errorJson(
                "UNSUPPORTED_SETTING",
                "不支持修改该配置项：$target。允许的受控开关：thinking_mode, terminal_tools, browser_tools, " +
                    "device_direct_tools, device_sensitive_read_tools, device_sensitive_action_tools, kimi_builtin_browser"
            )
        }
        val newValue = if (valueStr.isNotBlank()) {
            valueStr.toBooleanStrictOrNull() ?: (valueStr == "1" || valueStr.equals("on", ignoreCase = true))
        } else {
            // 未传 value 则自动取反
            !Prefs.isEnabled(key)
        }
        val local = Prefs.localAgentPreferences()
        if (local != null) {
            local.edit().putBoolean(key, newValue).commit()
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "update_assistant_config")
            .put("action", "toggle_setting")
            .put("setting_key", key)
            .put("new_value", newValue)
            .put("message", "已成功将设置 [$target] 更新为: $newValue")
            .toString()
    }

    private fun errorJson(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()
}
