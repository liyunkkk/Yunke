package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import org.json.JSONArray
import org.json.JSONObject

/** Main-agent choices only, shared across conversations by provider ID + model selection ID.
 * API model names/aliases decide eligibility, never identity. The single local scalar participates
 * in the existing agent-preferences backup/restore without touching child defaults or presets.
 * Reads are deliberately uncached: an external restore must not be overwritten by stale UI memory.
 * The old conversation speed was transient, so there is no legacy/global value to inherit.
 */
internal class MainAgentSpeedDefaultsRepository(private val preferences: SharedPreferences) {
    fun modeFor(providerId: String, modelId: String): GptSpeedMode {
        if (!validIdentity(providerId, modelId)) return GptSpeedMode.NORMAL
        return synchronized(preferences) {
            // Invalid/future records are displayed as normal, but retained until an explicit repair.
            runCatching { read()[Identity(providerId, modelId)] }.getOrNull() ?: GptSpeedMode.NORMAL
        }
    }

    /** Only explicit, eligible GPT changes write memory; unavailable/non-GPT projections never do. */
    fun remember(provider: ProviderSetting?, model: Model?, mode: GptSpeedMode): Boolean {
        if (!supportsGptSpeedBinding(provider, model)) return false
        val boundProvider = requireNotNull(provider)
        val boundModel = requireNotNull(model)
        if (boundProvider.models.singleOrNull { it.id == boundModel.id } != boundModel) return false
        val identity = Identity(boundProvider.id, boundModel.id)
        if (!validIdentity(identity.providerId, identity.modelId)) return false
        synchronized(preferences) {
            // Strict read before editing: never erase other model choices if the store is corrupt.
            val entries = read().toMutableMap()
            if (entries[identity] == mode) return true
            entries[identity] = mode
            val oldScalar = preferences.getString(KEY, null)
            val nextScalar = encode(entries)
            val failure = runCatching {
                check(preferences.edit().putString(KEY, nextScalar).commit()) {
                    "主代理模型速度设置未落盘"
                }
            }.exceptionOrNull()
            if (failure != null) {
                // Android commit(false) has ALREADY changed the in-process map. Restore the exact
                // old scalar (including absence), not just the projected UI, before reporting failure.
                // A second failed disk write still restores SharedPreferences' in-memory value.
                val rollback = preferences.edit()
                if (oldScalar == null) rollback.remove(KEY) else rollback.putString(KEY, oldScalar)
                runCatching { rollback.commit() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
        return true
    }

    private data class Identity(val providerId: String, val modelId: String)

    private fun read(): Map<Identity, GptSpeedMode> {
        val raw = preferences.getString(KEY, null) ?: return emptyMap()
        val json = JSONObject(raw)
        require(json.opt("version") is Int && json.getInt("version") == 1) {
            "Unsupported main-agent speed defaults version"
        }
        val array = json.getJSONArray("models")
        val entries = linkedMapOf<Identity, GptSpeedMode>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val provider = item.opt("provider") as? String ?: error("Invalid speed provider identity")
            val model = item.opt("model") as? String ?: error("Invalid speed model identity")
            require(validIdentity(provider, model)) { "Invalid main-agent speed identity" }
            val mode = GptSpeedMode.entries.singleOrNull { it.name == item.opt("speed") }
                ?: error("Invalid main-agent speed mode")
            val identity = Identity(provider, model)
            require(entries.put(identity, mode) == null) { "Duplicate main-agent speed identity" }
        }
        return entries
    }

    private fun encode(entries: Map<Identity, GptSpeedMode>): String {
        val array = JSONArray()
        entries.entries.sortedWith(compareBy({ it.key.providerId }, { it.key.modelId })).forEach { (id, mode) ->
            array.put(JSONObject().put("provider", id.providerId).put("model", id.modelId).put("speed", mode.name))
        }
        return JSONObject().put("version", 1).put("models", array).toString()
    }

    private fun validIdentity(providerId: String, modelId: String): Boolean =
        providerId.isNotBlank() && modelId.isNotBlank() && '\u0000' !in providerId && '\u0000' !in modelId

    companion object {
        const val KEY = "agent_main_gpt_speed_defaults_v1"
    }
}
