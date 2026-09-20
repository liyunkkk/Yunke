package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.CustomHeader
import java.net.URI
import org.json.JSONObject

/**
 * 助手运行时配置全链路安全脱敏引擎。
 * 针对自建中转站、GCP/反向代理 IP、API Key 与鉴权 Headers 执行不可逆脱敏遮蔽，
 * 从根源防止私密凭据通过大模型输出或会话日志外泄。
 */
internal object AssistantConfigSanitizer {
    private val IPV4_REGEX = Regex("""\b(\d{1,3}\.\d{1,3})\.\d{1,3}\.\d{1,3}\b""")

    /**
     * 对 Base URL 执行安全清洗：
     * 1. 彻底剔除 URL 中的 Query 参数（防止暴露 ?token= 等）与 Fragment；
     * 2. 对自建公网 IP / 内网 IP 执行后两段掩码（例如 35.212.***.***:8000/v1）；
     * 3. 规范化协议与端口，保留连通性识别能力。
     */
    fun maskBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: ""
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.path ?: ""
            val maskedHost = IPV4_REGEX.replace(host) { match ->
                "${match.groupValues[1]}.***.***"
            }
            "$scheme://$maskedHost$port$path"
        }.getOrElse {
            IPV4_REGEX.replace(trimmed.split("?").first()) { match ->
                "${match.groupValues[1]}.***.***"
            }
        }
    }

    /**
     * 对 API Key 执行局部星号化：
     * 仅保留前缀协议头（如 sk- 等最多前 4 字符）与末尾 4 字符，中间统一以 **** 遮盖。
     */
    fun maskApiKey(rawKey: String): String {
        val trimmed = rawKey.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.length <= 8) return "****"
        val prefix = trimmed.take(4)
        val suffix = trimmed.takeLast(4)
        return "$prefix****$suffix"
    }

    /**
     * 对自定义 Headers 执行脱敏：
     * 仅保留 Header 键名以供排查问题，所有 Header 值统一替换为 [PROTECTED]。
     */
    fun maskHeaders(headers: List<CustomHeader>): JSONObject {
        val obj = JSONObject()
        headers.forEach { header ->
            if (header.name.isNotBlank()) {
                obj.put(header.name, "[PROTECTED]")
            }
        }
        return obj
    }

    /**
     * 组装完整的只读脱敏配置快照。
     */
    fun sanitize(
        config: AgentModelClient.ModelConfig?,
        appVersion: String,
    ): JSONObject {
        val root = JSONObject()
        root.put("app_name", "Yunke")
        root.put("app_version", appVersion)

        val activeModelObj = JSONObject()
        val providerObj = JSONObject()
        if (config != null) {
            activeModelObj.put("model_id", config.model)
            activeModelObj.put("display_name", config.modelDisplayName)
            activeModelObj.put("thinking_enabled", config.thinkingEnabled)
            activeModelObj.put("reasoning_effort", config.effectiveReasoningEffort.name)
            activeModelObj.put("endpoint_mode", config.openAiEndpointMode)
            providerObj.put("provider_id", config.providerId)
            providerObj.put("provider_name", config.providerName)
            providerObj.put("provider_type", config.providerType)
            providerObj.put("base_url_masked", maskBaseUrl(config.baseUrl))
            providerObj.put("api_key_masked", maskApiKey(config.apiKey))
            providerObj.put("custom_headers", maskHeaders(config.customHeaders))
        } else {
            activeModelObj.put("status", "UNCONFIGURED")
            providerObj.put("status", "UNCONFIGURED")
        }
        root.put("active_model", activeModelObj)
        root.put("active_provider", providerObj)

        val switchesObj = JSONObject()
        switchesObj.put("thinking_mode", Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED))
        switchesObj.put("terminal_tools", Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS))
        switchesObj.put("browser_tools", Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS))
        switchesObj.put("device_direct_tools", Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS))
        switchesObj.put("device_sensitive_read_tools", Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS))
        switchesObj.put("device_sensitive_action_tools", Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS))
        switchesObj.put("kimi_web_builtin_browser", Prefs.isEnabled(Prefs.Keys.KIMI_WEB_USE_BUILTIN_BROWSER))
        root.put("switches", switchesObj)
        return root
    }
}
