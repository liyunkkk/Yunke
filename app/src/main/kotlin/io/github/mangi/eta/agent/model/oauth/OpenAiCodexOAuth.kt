package io.github.mangi.eta.agent.model.oauth

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.ui.OAuthLoginActivity
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.usesOAuth
import io.github.mangi.eta.data.model.withApiKey
import java.net.ProxySelector
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import okhttp3.FormBody

internal object OpenAiCodexOAuth {
    const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val DEFAULT_NAME = "OpenAI Codex"
    const val CLIENT_VERSION = "0.144.1"

    private const val AUTH_URL = "https://auth.openai.com/oauth/authorize"
    private const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val CALLBACK_PORT = 1455
    private const val REDIRECT_PATH = "/auth/callback"
    private const val SCOPES = "openid profile email offline_access api.connectors.read api.connectors.invoke"
    private const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT$REDIRECT_PATH"
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .proxySelector(ProxySelector.getDefault())
            .build()
    }

    fun isCodexEndpoint(baseUrl: String): Boolean {
        val host = OAuthCallback.hostOf(baseUrl).orEmpty()
        return host.equals("chatgpt.com", ignoreCase = true) ||
            host.equals("auth.openai.com", ignoreCase = true)
    }

    fun usesCodexBackend(provider: ProviderSetting): Boolean =
        provider.usesOAuth && isCodexEndpoint(provider.baseUrl)

    suspend fun login(context: Context, providerId: String): String = withContext(Dispatchers.IO) {
        val store = ProviderOAuthStore(context)
        val (verifier, challenge) = OAuthPkce.generate()
        val state = OAuthPkce.generateState()
        store.saveString(providerId, "verifier", verifier)
        store.saveString(providerId, "state", state)
        val authUrl = buildAuthorizationUrl(challenge, state)
        val (code, returnedState) = try {
            withTimeout(300_000) { waitForCallback(context, authUrl) }
        } catch (_: TimeoutCancellationException) {
            error("登录超时，请重试")
        }
        if (!returnedState.isNullOrBlank() && returnedState != state) {
            throw IllegalStateException("OAuth state 不匹配")
        }
        exchangeCode(store, providerId, code)
    }

    suspend fun validAccessToken(context: Context, providerId: String): String? =
        withContext(Dispatchers.IO) {
            val store = ProviderOAuthStore(context)
            val stored = store.loadTokens(providerId) ?: return@withContext null
            val token = stored.optString("access_token").ifBlank { return@withContext null }
            val expireAt = stored.optLong("expire_at", 0L)
            val now = System.currentTimeMillis()
            if (expireAt > 0 && expireAt - now < 4 * 3600_000L) {
                val refreshed = refresh(store, providerId, stored)
                if (refreshed != null) return@withContext refreshed
                if (now >= expireAt) {
                    store.clear(providerId)
                    return@withContext null
                }
            }
            token
        }

    fun accountId(context: Context, providerId: String): String? {
        val store = ProviderOAuthStore(context)
        store.loadString(providerId, "account_id")?.takeIf { it.isNotBlank() }?.let { return it }
        val tokens = store.loadTokens(providerId) ?: return null
        val extracted = extractAccountId(tokens) ?: return null
        store.saveString(providerId, "account_id", extracted)
        return extracted
    }

    suspend fun withResolvedAuth(
        context: Context,
        provider: ProviderSetting,
    ): ProviderSetting {
        if (!usesCodexBackend(provider)) return provider
        val token = validAccessToken(context, provider.id) ?: provider.apiKey
        val extra = extraHeaders(context, provider.id, provider.baseUrl)
        val mergedHeaders = extra + provider.customHeaders.filterNot { header ->
            extra.any { it.name.equals(header.name, ignoreCase = true) }
        }
        if (token == provider.apiKey && extra.isEmpty()) return provider
        val updated = provider.withApiKey(token)
        return when (updated) {
            is io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting ->
                updated.copy(customHeaders = mergedHeaders)
            is io.github.mangi.eta.data.model.CustomProviderSetting ->
                updated.copy(customHeaders = mergedHeaders)
            is io.github.mangi.eta.data.model.AnthropicProviderSetting ->
                updated.copy(customHeaders = mergedHeaders)
        }
    }

    fun extraHeaders(context: Context, providerId: String, baseUrl: String): List<CustomHeader> {
        if (!isCodexEndpoint(baseUrl)) return emptyList()
        val headers = mutableListOf(
            CustomHeader("User-Agent", "codex_cli_rs/$CLIENT_VERSION (Android; arm64)"),
            CustomHeader("Originator", "codex_cli_rs"),
            CustomHeader("Version", CLIENT_VERSION),
        )
        accountId(context, providerId)?.takeIf { it.isNotBlank() }?.let {
            headers += CustomHeader("ChatGPT-Account-ID", it)
        }
        return headers
    }

    fun defaultModels(): List<Model> {
        val specs = listOf(
            Triple("gpt-5.6-sol", "GPT-5.6 Sol", 1_050_000),
            Triple("gpt-5.6-terra", "GPT-5.6 Terra", 1_050_000),
            Triple("gpt-5.6-luna", "GPT-5.6 Luna", 1_050_000),
            Triple("gpt-5.5", "GPT-5.5", 400_000),
            Triple("gpt-5.4", "GPT-5.4", 400_000),
            Triple("gpt-5.4-mini", "GPT-5.4 Mini", 400_000),
        )
        return specs.mapIndexed { index, (modelId, name, window) ->
            Model(
                id = UUID.randomUUID().toString(),
                modelId = modelId,
                displayName = name,
                ownedBy = "openai",
                sortOrder = index,
                contextWindow = window,
                inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
                toolCall = true,
                reasoning = true,
                structuredOutput = true,
                source = ModelSource.CATALOG,
            )
        }
    }

    fun defaultEndpointMode(): String = OpenAiEndpointMode.RESPONSES

    fun clear(context: Context, providerId: String) {
        ProviderOAuthStore(context).clear(providerId)
    }

    private fun buildAuthorizationUrl(challenge: String, state: String): String =
        "$AUTH_URL?" + listOf(
            "client_id=$CLIENT_ID",
            "redirect_uri=${Uri.encode(REDIRECT_URI)}",
            "response_type=code",
            "scope=${Uri.encode(SCOPES)}",
            "state=$state",
            "code_challenge=$challenge",
            "code_challenge_method=S256",
            "originator=codex_cli_rs",
            "id_token_add_organizations=true",
        ).joinToString("&")

    private suspend fun waitForCallback(context: Context, authUrl: String): Pair<String, String?> {
        val result = CompletableDeferred<Pair<String, String?>>()
        val server = OAuthCallbackServer(CALLBACK_PORT) { code, state ->
            result.complete(code to state)
        }
        server.onFailure = { error ->
            if (result.isActive) result.completeExceptionally(error)
        }
        runCatching { server.start() }
        withContext(Dispatchers.Main.immediate) {
            OAuthLoginActivity.start(context, authUrl, result)
        }
        try {
            return result.await()
        } finally {
            server.stop()
            OAuthLoginActivity.finishIfOpen()
        }
    }

    private fun exchangeCode(store: ProviderOAuthStore, providerId: String, code: String): String {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", store.loadString(providerId, "verifier").orEmpty())
            .build()
        val json = postToken(body)
        persistTokens(store, providerId, json)
        val access = json.optString("access_token")
        if (access.isBlank()) error("登录成功但没有 access_token")
        return access
    }

    private fun refresh(store: ProviderOAuthStore, providerId: String, stored: JSONObject): String? {
        val refreshToken = stored.optString("refresh_token").ifBlank { return null }
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        val json = runCatching { postToken(body) }.getOrNull() ?: return null
        if (!json.has("refresh_token")) json.put("refresh_token", refreshToken)
        persistTokens(store, providerId, json)
        return json.optString("access_token").takeIf { it.isNotBlank() }
    }

    private fun persistTokens(store: ProviderOAuthStore, providerId: String, json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 0L)
        if (expiresIn > 0) {
            json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        }
        store.saveTokens(providerId, json)
        extractAccountId(json)?.let { store.saveString(providerId, "account_id", it) }
    }

    private fun postToken(body: FormBody): JSONObject {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .post(body)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code !in 200..299) {
                        error("Token 交换失败 (${response.code}): ${text.take(300)}")
                    }
                    return JSONObject(text)
                }
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Token 交换失败")
    }

    internal fun extractAccountId(json: JSONObject): String? {
        json.optString("account_id").takeIf { it.isNotBlank() }?.let { return it }
        val idToken = json.optString("id_token")
        if (idToken.isBlank()) return null
        return parseIdToken(idToken)
    }

    internal fun parseIdToken(token: String): String? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        val payload = runCatching {
            val padded = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
            String(java.util.Base64.getUrlDecoder().decode(padded))
        }.getOrNull() ?: return null
        val claims = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        claims.optString("chatgpt_account_id").takeIf { it.isNotBlank() }?.let { return it }
        val auth = claims.optJSONObject("https://api.openai.com/auth") ?: return null
        return auth.optString("chatgpt_account_id").takeIf { it.isNotBlank() }
    }
}
