package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.content.SharedPreferences

/**
 * DeepSeek Harness (dsh) 编程子代理的配置模型。
 *
 * 从 SharedPreferences 中读取端点、key 文件路径、模型与推理强度；
 * 未配置或读取失败时回退至官方推荐默认值。
 */
internal data class DshSettings(
    val endpoint: String = DEFAULT_ENDPOINT,
    val keyFilePath: String = DEFAULT_KEY_FILE_PATH,
    val model: String = DEFAULT_MODEL,
    val effort: String = DEFAULT_EFFORT,
) {
    companion object {
        const val PREFS_NAME = "dsh_settings"
        const val PREF_ENDPOINT = "dsh_endpoint"
        const val PREF_KEY_FILE_PATH = "dsh_key_file_path"
        const val PREF_MODEL = "dsh_model"
        const val PREF_EFFORT = "dsh_reasoning_effort"
        const val PREF_API_KEY = "dsh_api_key"

        const val DEFAULT_ENDPOINT = "https://wb2.c44lyk.dpdns.org/v1"
        const val DEFAULT_KEY_FILE_PATH = "/root/.dsh/key"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val DEFAULT_EFFORT = "high"

        fun save(
            context: Context,
            endpoint: String = DEFAULT_ENDPOINT,
            model: String = DEFAULT_MODEL,
            effort: String = DEFAULT_EFFORT,
            keyFilePath: String = DEFAULT_KEY_FILE_PATH,
            apiKey: String? = null,
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString(PREF_ENDPOINT, endpoint.trim())
                putString(PREF_MODEL, model.trim())
                putString(PREF_EFFORT, effort.trim())
                putString(PREF_KEY_FILE_PATH, keyFilePath.trim())
                if (apiKey != null) {
                    putString(PREF_API_KEY, apiKey.trim())
                }
                apply()
            }
            if (apiKey != null) {
                DshKeyFileManager.writeKeyFile(context, endpoint, apiKey)
            }
        }

        fun load(context: Context): DshSettings {
            val dshPrefs = runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }.getOrNull()
            val agentPrefs = runCatching {
                context.getSharedPreferences("eta_agent_preferences", Context.MODE_PRIVATE)
            }.getOrNull()
            return load(dshPrefs, agentPrefs)
        }

        fun load(vararg prefsList: SharedPreferences?): DshSettings {
            fun resolve(key: String, default: String): String {
                for (prefs in prefsList) {
                    if (prefs == null) continue
                    val value = prefs.getString(key, null)?.trim()
                    if (!value.isNullOrBlank()) return value
                }
                return default
            }

            return DshSettings(
                endpoint = resolve(PREF_ENDPOINT, DEFAULT_ENDPOINT),
                keyFilePath = resolve(PREF_KEY_FILE_PATH, DEFAULT_KEY_FILE_PATH),
                model = resolve(PREF_MODEL, DEFAULT_MODEL),
                effort = resolve(PREF_EFFORT, DEFAULT_EFFORT),
            )
        }
    }
}
