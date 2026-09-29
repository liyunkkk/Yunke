package io.github.mangi.eta.agent.model.oauth

import io.github.mangi.eta.data.model.ProviderAuthMode
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCodexOAuthTest {
    @Test
    fun missingAuthModeDefaultsToApiKey() {
        assertEquals(ProviderAuthMode.API_KEY, ProviderAuthMode.parse(null))
        assertEquals(ProviderAuthMode.API_KEY, ProviderAuthMode.parse(""))
        assertEquals(ProviderAuthMode.API_KEY, ProviderAuthMode.parse("api_key"))
        assertEquals(ProviderAuthMode.OAUTH, ProviderAuthMode.parse("oauth"))
        assertEquals(ProviderAuthMode.OAUTH, ProviderAuthMode.parse("OAuth"))
        assertFalse(ProviderAuthMode.isOAuth("api_key"))
        assertTrue(ProviderAuthMode.isOAuth("oauth"))
        assertEquals(ProviderAuthMode.OAUTH_ANTIGRAVITY, ProviderAuthMode.parse("oauth_antigravity"))
        assertTrue(ProviderAuthMode.isOAuth("oauth_antigravity"))
        assertTrue(ProviderAuthMode.isAntigravity("oauth_antigravity"))
        assertFalse(ProviderAuthMode.isAntigravity("oauth"))
    }

    @Test
    fun pkceVerifierAndChallengeAreUrlSafe() {
        val (verifier, challenge) = OAuthPkce.generate()
        assertTrue(verifier.length >= 43)
        assertTrue(challenge.length >= 43)
        assertFalse(verifier.contains("+") || verifier.contains("/") || verifier.contains("="))
        assertFalse(challenge.contains("+") || challenge.contains("/") || challenge.contains("="))
        val again = OAuthPkce.generate()
        assertNotEquals(verifier, again.first)
    }

    @Test
    fun chatgptHostIsCodexEndpoint() {
        assertTrue(OpenAiCodexOAuth.isCodexEndpoint("https://chatgpt.com/backend-api/codex"))
        assertTrue(OpenAiCodexOAuth.isCodexEndpoint("https://chatgpt.com/backend-api/codex/"))
        assertFalse(OpenAiCodexOAuth.isCodexEndpoint("https://api.openai.com/v1"))
        assertFalse(OpenAiCodexOAuth.isCodexEndpoint(""))
    }

    @Test
    fun defaultModelsCoverCurrentCodexLineup() {
        val ids = OpenAiCodexOAuth.defaultModels().map { it.modelId }
        assertTrue(ids.contains("gpt-5.6-sol"))
        assertTrue(ids.contains("gpt-5.4-mini"))
        assertTrue(OpenAiCodexOAuth.defaultModels().all { it.toolCall == true && it.reasoning == true })
    }

    @Test
    fun callbackParsesAuthorizationCode() {
        val url = "http://localhost:1455/auth/callback?code=abc&state=xyz"
        assertTrue(OAuthCallback.isRedirectUrl(url))
        val (code, state) = OAuthCallback.parseUrlOrThrow(url)
        assertEquals("abc", code)
        assertEquals("xyz", state)
    }

    @Test
    fun callbackIgnoresUnrelatedLocalhost() {
        assertFalse(OAuthCallback.isRedirectUrl("http://localhost:1455/"))
        assertFalse(OAuthCallback.isRedirectUrl("https://auth.openai.com/oauth/authorize"))
    }

    @Test(expected = IllegalStateException::class)
    fun callbackSurfacesOauthError() {
        OAuthCallback.parseUrlOrThrow("http://localhost:1455/auth/callback?error=access_denied")
    }


    @Test
    fun parseIdTokenReadsNestedChatgptAccountId() {
        val payload = JSONObject()
            .put(
                "https://api.openai.com/auth",
                JSONObject().put("chatgpt_account_id", "acct-nested"),
            )
            .toString()
        val token = "aaa.${b64(payload)}.sig"
        assertEquals("acct-nested", OpenAiCodexOAuth.parseIdToken(token))
    }

    @Test
    fun extractAccountIdPrefersTokenBodyThenJwt() {
        val payload = JSONObject().put("chatgpt_account_id", "acct-jwt").toString()
        val json = JSONObject()
            .put("account_id", "acct-body")
            .put("id_token", "aaa.${b64(payload)}.sig")
        assertEquals("acct-body", OpenAiCodexOAuth.extractAccountId(json))
    }

    private fun b64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
