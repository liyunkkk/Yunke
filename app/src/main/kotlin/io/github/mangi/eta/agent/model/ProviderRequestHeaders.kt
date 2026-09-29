package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.oauth.GoogleAntigravityOAuth
import io.github.mangi.eta.agent.model.oauth.OpenAiCodexOAuth
import io.github.mangi.eta.data.model.CustomHeader
import java.util.UUID
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ProviderRequestHeaders {
    fun mergeInto(
        builder: Headers.Builder,
        baseUrl: String,
        customHeaders: List<CustomHeader>,
        sessionId: String = UUID.randomUUID().toString(),
    ) {
        val host = baseUrl.toHttpUrlOrNull()?.host
        if (host == "chatgpt.com") {
            builder.set("User-Agent", "codex_cli_rs/${OpenAiCodexOAuth.CLIENT_VERSION} (Android; arm64)")
            builder.set("Originator", "codex_cli_rs")
            builder.set("Version", OpenAiCodexOAuth.CLIENT_VERSION)
        } else if (host?.contains("cloudcode-pa") == true) {
            // Cloud Code 认 Antigravity Hub UA；写成 Eta / Electron IDE 会 403。
            builder.set("User-Agent", GoogleAntigravityOAuth.requestUserAgent())
        } else {
            builder.set("User-Agent", "Yunke")
        }
        CustomHeaderFilter.mergeInto(builder, customHeaders)
        if (sessionId.isNotBlank()) {
            builder.set("session-id", sessionId)
            builder.set("thread-id", sessionId)
        }
        if (baseUrl.toHttpUrlOrNull()?.host == "opencode.ai") {
            // 会话头由 Runtime 持有，避免固定自定义值把所有对话合并到同一路由。
            builder.set(
                "x-opencode-session",
                UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString(),
            )
        }
    }
}
