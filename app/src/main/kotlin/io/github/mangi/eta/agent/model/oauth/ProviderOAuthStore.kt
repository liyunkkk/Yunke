package io.github.mangi.eta.agent.model.oauth

import android.content.Context
import org.json.JSONObject

internal class ProviderOAuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveTokens(providerId: String, json: JSONObject) {
        prefs.edit().putString(tokensKey(providerId), json.toString()).apply()
    }

    fun loadTokens(providerId: String): JSONObject? {
        val raw = prefs.getString(tokensKey(providerId), null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    fun saveString(providerId: String, key: String, value: String) {
        prefs.edit().putString(stringKey(providerId, key), value).apply()
    }

    fun loadString(providerId: String, key: String): String? =
        prefs.getString(stringKey(providerId, key), null)

    fun clear(providerId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.endsWith("_$providerId") }.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        private const val PREFS = "eta_oauth_prefs"

        private fun tokensKey(providerId: String) = "oauth_tokens_$providerId"

        private fun stringKey(providerId: String, key: String) = "oauth_${key}_$providerId"
    }
}
