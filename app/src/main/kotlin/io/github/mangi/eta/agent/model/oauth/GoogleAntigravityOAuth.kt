package io.github.mangi.eta.agent.model.oauth

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.usesOAuth
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.ui.OAuthLoginActivity
import java.net.ProxySelector
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal object GoogleAntigravityOAuth {
    const val DEFAULT_NAME = "反重力"
    const val BASE_URL = "https://daily-cloudcode-pa.googleapis.com"
    const val CLIENT_VERSION = "2.33.0"
    private const val CHANGE_LIST = "1023160026"
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val CLIENT_ID = "1071006060591-tmhssin2h21lcre235vt" + "olojh4g403ep.apps.googleuserco" + "ntent.com"
    private const val CLIENT_SECRET = "GO" + "CSPX-K58FWR" + "486LdLJ1mLB8sX" + "C4z6qDAf"
    private const val CALLBACK_PORT = 51121
    private const val REDIRECT_PATH = "/oauth-callback"
    private const val SCOPES = "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile https://www.googleapis.com/auth/cclog https://www.googleapis.com/auth/experimentsandconfigs https://www.googleapis.com/auth/aicode"
    private const val DEFAULT_PROJECT_ID = "rising-fact-p41fc"
    private val JSON = "application/json".toMediaType()
    private val endpoints = listOf(
        "https://daily-cloudcode-pa.googleapis.com",
        "https://cloudcode-pa.googleapis.com",
        "https://daily-cloudcode-pa.sandbox.googleapis.com",
    )
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).proxySelector(ProxySelector.getDefault()).build()
    }
    private fun redirectUri(): String = "http://localhost:" + CALLBACK_PORT + REDIRECT_PATH
    fun isAntigravityEndpoint(baseUrl: String): Boolean = OAuthCallback.hostOf(baseUrl).orEmpty().contains("cloudcode-pa", true)
    fun usesBackend(provider: ProviderSetting): Boolean = provider.usesOAuth && (ProviderAuthMode.isAntigravity(provider.authMode) || isAntigravityEndpoint(provider.baseUrl))
    suspend fun login(context: Context, providerId: String): String = withContext(Dispatchers.IO) {
        val store = ProviderOAuthStore(context)
        val (verifier, challenge) = OAuthPkce.generate()
        val state = OAuthPkce.generateState()
        store.saveString(providerId, "verifier", verifier)
        store.saveString(providerId, "state", state)
        val (code, returnedState) = try {
            withTimeout(300_000) { waitForCallback(context, buildAuthorizationUrl(challenge, state)) }
        } catch (_: TimeoutCancellationException) { error("登录超时，请重试") }
        if (!returnedState.isNullOrBlank() && returnedState != state) throw IllegalStateException("OAuth state 不匹配")
        exchangeCode(store, providerId, code)
    }
    suspend fun validAccessToken(context: Context, providerId: String, forceRefresh: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val store = ProviderOAuthStore(context)
            val stored = store.loadTokens(providerId) ?: return@withContext null
            val token = stored.optString("access_token").ifBlank { return@withContext null }
            val expireAt = stored.optLong("expire_at", 0L)
            val now = System.currentTimeMillis()
            val stale = forceRefresh || expireAt <= 0L || expireAt - now < 5 * 60_000L
            if (stale) {
                val refreshed = refresh(store, providerId, stored)
                if (refreshed != null) return@withContext refreshed
                if (forceRefresh || now >= expireAt) return@withContext null
            }
            token
        }
    fun projectId(context: Context, providerId: String): String? =
        ProviderOAuthStore(context).loadString(providerId, "project_id")
            ?.takeIf { it.isNotBlank() && it != DEFAULT_PROJECT_ID }

    fun ensureProjectId(context: Context, providerId: String, accessToken: String, refresh: Boolean = false): String? {
        if (!refresh) projectId(context, providerId)?.let { return it }
        if (accessToken.isBlank()) return null
        discoverProjectId(ProviderOAuthStore(context), providerId, accessToken)
        return projectId(context, providerId)
    }
    fun requestUserAgent(): String = "antigravity/hub/$CLIENT_VERSION (aidev_client; os_type=darwin; arch=arm64; cl=$CHANGE_LIST)"

    fun onboardUserAgent(): String = requestUserAgent() + " google-api-nodejs-client/10.3.0"

    fun extraHeaders(): List<CustomHeader> = listOf(
        CustomHeader("User-Agent", requestUserAgent()),
    )
    suspend fun withResolvedAuth(context: Context, provider: ProviderSetting): ProviderSetting {
        if (!usesBackend(provider)) return provider
        val token = validAccessToken(context, provider.id) ?: return provider
        val extra = extraHeaders()
        val merged = extra + provider.customHeaders.filterNot { h -> extra.any { it.name.equals(h.name, true) } }
        val updated = provider.withApiKey(token)
        return when (updated) {
            is io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting -> updated.copy(customHeaders = merged)
            is io.github.mangi.eta.data.model.CustomProviderSetting -> updated.copy(customHeaders = merged)
            is io.github.mangi.eta.data.model.AnthropicProviderSetting -> updated.copy(customHeaders = merged)
            is io.github.mangi.eta.data.model.GeminiProviderSetting -> updated.copy(customHeaders = merged)
        }
    }
    fun defaultEndpointMode(): String = OpenAiEndpointMode.ANTIGRAVITY
    fun defaultModels(): List<Model> = listOf(
        Triple("gemini-4-neon-xhigh", "Gemini 4 Neon", 1_000_000),
        Triple("gemini-4-hydrogen-xhigh", "Gemini 4 Hydrogen (Ultra)", 1_000_000),
        Triple("gemini-4-carbon-high", "Gemini 4 Carbon", 1_000_000),
        Triple("gemini-4-argon-high", "Gemini 4 Argon", 1_000_000),
        Triple("gemini-3.8-flash-high", "Gemini 3.8 Flash", 1_000_000),
        Triple("claude-opus-5-5-high", "Claude Opus 5.5", 1_000_000),
        Triple("claude-sonnet-5-5-high", "Claude Sonnet 5.5", 1_000_000),
        Triple("gemini-3.1-flash", "Gemini 3.1 Flash", 1_000_000),
        Triple("gemini-3-flash", "Gemini 3 Flash", 1_000_000),
        Triple("gemini-3.1-pro", "Gemini 3.1 Pro", 1_000_000),
        Triple("claude-sonnet-4-6", "Claude Sonnet 4.6", 200_000),
        Triple("claude-opus-4-6", "Claude Opus 4.6", 200_000),
        Triple("claude-opus-4-6-thinking", "Claude Opus 4.6 Thinking", 200_000),
        Triple("gpt-oss-120b-medium", "GPT-OSS 120B", 128_000),
    ).mapIndexed { index, (id, name, window) ->
        Model(id = UUID.randomUUID().toString(), modelId = id, displayName = name,
            ownedBy = if (id.startsWith("claude")) "anthropic" else "google", sortOrder = index,
            contextWindow = window, inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
            toolCall = true, reasoning = true, reasoningCapabilities = antigravityReasoningCapabilities(id),
            structuredOutput = true, source = ModelSource.CATALOG)
    }
    fun fetchModels(context: Context, provider: ProviderSetting): List<Model> {
        val token = kotlinx.coroutines.runBlocking {
            validAccessToken(context, provider.id)
        } ?: provider.apiKey
        if (token.isBlank()) error("请先完成 Google 登录，或为该连接填写 API Key")
        val project = ensureProjectId(context, provider.id, token, refresh = true).orEmpty()
        val body = JSONObject().also { if (project.isNotBlank()) it.put("project", project) }
        var lastFailure: Throwable? = null
        endpoints.forEach { endpoint ->
            try {
                val models = listModels(endpoint, token, body)
                if (models.isNotEmpty()) return models
                lastFailure = IllegalStateException("Cloud Code 返回空模型目录（$endpoint）")
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw IllegalStateException(
            "拉取 Google 模型目录失败：${lastFailure?.message ?: "所有 Cloud Code 端点均返回空结果"}",
            lastFailure,
        )
    }
    fun clear(context: Context, providerId: String) { ProviderOAuthStore(context).clear(providerId) }
    internal fun parseModels(body: String): List<Model> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val collected = mutableListOf<Model>()
        val modelsObject = root.optJSONObject("models")
        if (modelsObject != null) {
            // CCA declarations are incomplete for tier-specific Ultra models. Preserve all usable
            // server entries after excluding internal UI/chat placeholders and image-only models.
            val declared = linkedSetOf<String>()
            root.optJSONArray("agentModelSorts")?.let { sorts ->
                for (sort in 0 until sorts.length()) {
                    val groups = sorts.optJSONObject(sort)?.optJSONArray("groups") ?: continue
                    for (group in 0 until groups.length()) {
                        val ids = groups.optJSONObject(group)?.optJSONArray("modelIds") ?: continue
                        for (index in 0 until ids.length()) ids.optString(index).trim()
                            .takeIf(String::isNotBlank)?.let(declared::add)
                    }
                }
            }
            root.optJSONObject("tieredModelIds")?.let { tiered ->
                tiered.keys().forEach { tier ->
                    val ids = tiered.optJSONArray(tier) ?: return@forEach
                    for (index in 0 until ids.length()) ids.optString(index).trim()
                        .takeIf(String::isNotBlank)?.let(declared::add)
                }
            }
            root.optJSONArray("imageGenerationModelIds")?.let { images ->
                for (index in 0 until images.length()) images.optString(index).trim()
                    .takeIf(String::isNotBlank)?.let(declared::add)
            }
            modelsObject.keys().forEach { modelId ->
                val displayName = modelsObject.optJSONObject(modelId)?.optString("displayName").orEmpty()
                if (isUsableModel(modelId, displayName)) declared.add(modelId)
            }
            var order = 0
            declared.forEach { modelId ->
                if (!isUsableModel(modelId, modelsObject.optJSONObject(modelId)?.optString("displayName").orEmpty())) return@forEach
                val display = modelsObject.optJSONObject(modelId)?.optString("displayName")?.ifBlank { null } ?: modelId
                addCatalogModel(collected, modelId, display, order++)
            }
        }
        val modelsArray = root.optJSONArray("models") ?: root.optJSONArray("availableModels")
        if (modelsArray != null) {
            for (index in 0 until modelsArray.length()) {
                val item = modelsArray.optJSONObject(index) ?: continue
                val modelId = item.optString("model").ifBlank { item.optString("name") }.ifBlank { item.optString("id") }
                if (modelId.isBlank()) continue
                addCatalogModel(collected, modelId, item.optString("displayName").ifBlank { modelId }, collected.size)
            }
        }
        return collected.distinctBy { it.modelId.lowercase() }
    }
    private fun addCatalogModel(collected: MutableList<Model>, modelId: String, displayName: String, order: Int) {
        if (!isUsableModel(modelId, displayName)) return
        collected += catalogModel(modelId, displayName, order)
    }
    internal fun prettyModelName(modelId: String, displayName: String): String {
        val name = displayName.trim()
        if (name.isNotBlank() && !name.equals(modelId, true) && !name.startsWith("MODEL_", true)) {
            return name
        }
        return when (modelId.lowercase()) {
            "gemini-4-neon", "gemini-4-neon-high", "gemini-4-neon-xhigh" -> "Gemini 4 Neon"
            "gemini-4-hydrogen", "gemini-4-hydrogen-xhigh" -> "Gemini 4 Hydrogen (Ultra)"
            "gemini-4-carbon", "gemini-4-carbon-high" -> "Gemini 4 Carbon"
            "gemini-4-argon", "gemini-4-argon-high" -> "Gemini 4 Argon"
            "gemini-3.8-flash", "gemini-3.8-flash-high" -> "Gemini 3.8 Flash"
            "claude-sonnet-5-5", "claude-sonnet-5-5-high" -> "Claude Sonnet 5.5"
            "claude-opus-5-5", "claude-opus-5-5-high" -> "Claude Opus 5.5"
            "gemini-3-flash" -> "Gemini 3 Flash"
            "gemini-3.1-flash" -> "Gemini 3.1 Flash"
            "gemini-3.1-pro" -> "Gemini 3.1 Pro"
            "gemini-3.1-pro-high" -> "Gemini 3.1 Pro (High)"
            "gemini-3.1-pro-low" -> "Gemini 3.1 Pro (Low)"
            "gemini-3-pro" -> "Gemini 3 Pro"
            "gemini-3-pro-high" -> "Gemini 3 Pro (High)"
            "gemini-3-pro-low" -> "Gemini 3 Pro Low"
            "claude-sonnet-4-6" -> "Claude Sonnet 4.6"
            "claude-opus-4-6" -> "Claude Opus 4.6"
            "claude-opus-4-6-thinking" -> "Claude Opus 4.6 Thinking"
            "gpt-oss-120b-medium", "gpt-oss-120b" -> "GPT-OSS 120B"
            else -> name.ifBlank { modelId }
        }
    }
    internal fun isUsableModel(modelId: String, displayName: String = modelId): Boolean {
        val id = modelId.trim()
        val name = displayName.trim()
        if (id.isBlank()) return false
        if (id.startsWith("chat_", ignoreCase = true) || id.startsWith("tab_", ignoreCase = true)) return false
        val haystack = (id + " " + name).lowercase()
        if ("placeholder" in haystack) return false
        if (id.startsWith("MODEL_", true)) return false
        if ("image" in haystack && "flash-image" in haystack.replace(" ", "-")) return false
        if (haystack.contains("flash image")) return false
        return true
    }
    private fun catalogModel(modelId: String, displayName: String, order: Int) = Model(
        id = UUID.randomUUID().toString(), modelId = modelId,
        displayName = prettyModelName(modelId, displayName),
        ownedBy = if (modelId.startsWith("claude", true)) "anthropic" else "google",
        sortOrder = order, contextWindow = 1_000_000,
        inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
        toolCall = true,
        reasoning = true,
        reasoningCapabilities = antigravityReasoningCapabilities(modelId),
        source = ModelSource.REMOTE,
    )

    private fun antigravityReasoningCapabilities(modelId: String): ModelReasoningCapabilities? {
        val routed = io.github.mangi.eta.agent.model.AntigravityModelRouting.runtimeModelId(modelId).lowercase()
        val supported = when {
            routed.startsWith("gemini-4-") && routed.endsWith("xhigh") -> listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH,
            )
            routed.startsWith("gemini-4-") -> listOf(
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH,
            )
            else -> return null
        }
        return ModelReasoningCapabilities(
            supportedEfforts = supported,
            defaultEffort = if (routed.endsWith("xhigh")) ReasoningEffort.XHIGH else ReasoningEffort.HIGH,
            defaultEnabled = true,
            mandatory = true,
            canDisable = false,
        )
    }

    private fun listModels(endpoint: String, token: String, body: JSONObject): List<Model> {
        val request = Request.Builder().url(endpoint + "/v1internal:fetchAvailableModels")
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .apply { extraHeaders().forEach { addHeader(it.name, it.value) } }
            .post(body.toString().toRequestBody(JSON)).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code !in 200..299) error("拉取模型失败 HTTP " + response.code)
            return parseModels(text)
        }
    }
    private fun buildAuthorizationUrl(challenge: String, state: String): String =
        AUTH_URL + "?client_id=" + CLIENT_ID + "&redirect_uri=" + Uri.encode(redirectUri()) +
            "&response_type=code&scope=" + Uri.encode(SCOPES) + "&state=" + state +
            "&code_challenge=" + challenge + "&code_challenge_method=S256&access_type=offline&prompt=consent"
    private suspend fun waitForCallback(context: Context, authUrl: String): Pair<String, String?> {
        val result = CompletableDeferred<Pair<String, String?>>()
        val server = OAuthCallbackServer(CALLBACK_PORT) { code, state -> result.complete(code to state) }
        server.onFailure = { error -> if (result.isActive) result.completeExceptionally(error) }
        runCatching { server.start() }
        withContext(Dispatchers.Main.immediate) {
            OAuthLoginActivity.start(
                context,
                authUrl,
                result,
                title = context.getString(io.github.mangi.eta.R.string.provider_oauth_antigravity_login_title),
            )
        }
        try { return result.await() } finally { server.stop(); OAuthLoginActivity.finishIfOpen() }
    }
    private fun exchangeCode(store: ProviderOAuthStore, providerId: String, code: String): String {
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET).add("code", code).add("redirect_uri", redirectUri())
            .add("code_verifier", store.loadString(providerId, "verifier").orEmpty()).build()
        val json = postToken(body)
        persistTokens(store, providerId, json)
        discoverProjectId(store, providerId, json.optString("access_token"))
        val access = json.optString("access_token")
        if (access.isBlank()) error("登录成功但没有 access_token")
        return access
    }
    private fun refresh(store: ProviderOAuthStore, providerId: String, stored: JSONObject): String? {
        val refreshToken = stored.optString("refresh_token").ifBlank { return null }
        val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID).add("client_secret", CLIENT_SECRET).build()
        val json = runCatching { postToken(body) }.getOrNull() ?: return null
        if (!json.has("refresh_token")) json.put("refresh_token", refreshToken)
        persistTokens(store, providerId, json)
        return json.optString("access_token").takeIf { it.isNotBlank() }
    }
    private fun persistTokens(store: ProviderOAuthStore, providerId: String, json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 0L)
        if (expiresIn > 0) json.put("expire_at", System.currentTimeMillis() + expiresIn * 1000)
        store.saveTokens(providerId, json)
    }
    private fun discoverProjectId(store: ProviderOAuthStore, providerId: String, accessToken: String) {
        if (accessToken.isBlank()) return
        val metadata = JSONObject().put("ideType", "ANTIGRAVITY")
        endpoints.forEach { endpoint ->
            val project = runCatching { provisionProject(endpoint, accessToken, metadata) }.getOrNull()
            if (!project.isNullOrBlank() && project != DEFAULT_PROJECT_ID) {
                store.saveString(providerId, "project_id", project)
                store.saveString(providerId, "api_host", endpoint)
                return
            }
        }
    }

    private fun provisionProject(endpoint: String, token: String, metadata: JSONObject): String? {
        var loaded = loadCodeAssist(endpoint, token, JSONObject().put("metadata", metadata)) ?: return null
        val currentTier = tierId(loaded.opt("currentTier"))
        if (currentTier.isNullOrBlank()) {
            onboardUser(endpoint, token, metadata, preferredTierId(loaded))
            loaded = loadCodeAssist(endpoint, token, JSONObject().put("metadata", metadata)) ?: loaded
        }
        extractProject(loaded)?.let { return it }
        val known = extractProject(loaded)
        if (!known.isNullOrBlank()) {
            val refreshed = loadCodeAssist(
                endpoint,
                token,
                JSONObject().put("cloudaicompanionProject", known).put("metadata", metadata),
            )
            extractProject(refreshed)?.let { return it }
        }
        return extractProject(loaded)
    }

    private fun tierId(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf(String::isNotEmpty)
        is JSONObject -> sequenceOf("id", "tierId", "tier_id").map { value.optString(it).trim() }
            .firstOrNull(String::isNotEmpty)
        else -> null
    }

    private fun preferredTierId(loadCodeAssist: JSONObject): String? {
        val tiers = loadCodeAssist.optJSONArray("allowedTiers") ?: return null
        var fallback: String? = null
        for (index in 0 until tiers.length()) {
            val tier = tiers.optJSONObject(index) ?: continue
            val id = tierId(tier) ?: continue
            if (tier.optBoolean("isDefault", false)) return id
            if (fallback == null) fallback = id
        }
        return fallback
    }

    private fun onboardUser(endpoint: String, token: String, metadata: JSONObject, tierId: String?) {
        val body = JSONObject().put("metadata", metadata).also { request ->
            tierId?.takeIf(String::isNotBlank)?.let { request.put("tierId", it) }
        }
        val request = cloudRequest(endpoint + "/v1internal:onboardUser", token, body, onboardUserAgent())
        val operation = httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code !in 200..299) return
            runCatching { JSONObject(text) }.getOrNull() ?: return
        }
        var current = operation
        repeat(15) {
            if (current.optBoolean("done")) return
            val name = current.optString("name")
            if (name.isBlank()) return
            Thread.sleep(1000)
            val poll = Request.Builder().url(endpoint + "/v1internal/" + name)
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", onboardUserAgent())
                .get().build()
            current = httpClient.newCall(poll).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code !in 200..299) return
                runCatching { JSONObject(text) }.getOrNull() ?: return
            }
        }
    }

    private fun loadCodeAssist(endpoint: String, token: String, payload: JSONObject): JSONObject? {
        val request = cloudRequest(endpoint + "/v1internal:loadCodeAssist", token, payload)
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code !in 200..299) return null
            return runCatching { JSONObject(text) }.getOrNull()
        }
    }

    private fun extractProject(json: JSONObject?): String? {
        if (json == null) return null
        json.optString("cloudaicompanionProject").takeIf { it.isNotBlank() }?.let { return it }
        return json.optJSONObject("cloudaicompanionProject")?.optString("id")?.takeIf { it.isNotBlank() }
            ?: json.optJSONObject("response")?.optString("cloudaicompanionProject")?.takeIf { it.isNotBlank() }
    }

    private fun cloudRequest(url: String, token: String, payload: JSONObject, userAgent: String = requestUserAgent()): Request =
        Request.Builder().url(url)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .post(payload.toString().toRequestBody(JSON)).build()
    private fun postToken(body: FormBody): JSONObject {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url(TOKEN_URL).header("Content-Type", "application/x-www-form-urlencoded").post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.code !in 200..299) error("Token 交换失败 (" + response.code + "): " + text.take(300))
                    return JSONObject(text)
                }
            } catch (error: Exception) {
                lastError = error
                if (attempt < 2) Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Token 交换失败")
    }
}
