package io.github.mangi.eta.agent.kimi

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 「Eta 对话 → Kimi 会话」绑定的持久化抽象。
 *
 * 抽成接口后 [KimiWebService] 的绑定逻辑可以在单元测试里用内存实现驱动，
 * 不必真的落盘。
 */
internal interface KimiSessionBindingStore {

    /** 读取全部绑定；不存在或解析失败时返回空表。 */
    fun load(): Map<String, String>

    /** 覆盖写入全部绑定。 */
    fun save(bindings: Map<String, String>)
}

/**
 * 把绑定关系落盘到应用私有目录（`filesDir/kimi_session_bindings.json`）。
 *
 * 内存缓存只能维持单次进程生命周期；进程被系统回收后，同一对话再次委派
 * 必须复用原来的 Kimi 会话，否则上下文会散乱重建。
 */
internal class FileKimiSessionBindingStore(context: Context) : KimiSessionBindingStore {

    private val file = File(context.filesDir, FILE_NAME)

    override fun load(): Map<String, String> = runCatching {
        if (!file.exists()) return emptyMap()
        val json = JSONObject(file.readText(Charsets.UTF_8))
        buildMap {
            json.keys().forEach { key ->
                json.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) }
            }
        }
    }.getOrDefault(emptyMap())

    override fun save(bindings: Map<String, String>) {
        runCatching {
            val json = JSONObject()
            bindings.forEach { (key, value) -> json.put(key, value) }
            file.writeText(json.toString(2), Charsets.UTF_8)
        }
    }

    internal companion object {
        const val FILE_NAME = "kimi_session_bindings.json"
    }
}

/**
 * 内存实现：单进程内有效，进程重启即丢。
 *
 * 供单元测试与无法访问 `filesDir` 的场景使用，生产链路一律用
 * [FileKimiSessionBindingStore]。
 */
internal class InMemoryKimiSessionBindingStore(
    initial: Map<String, String> = emptyMap(),
) : KimiSessionBindingStore {

    private val bindings = LinkedHashMap<String, String>(initial)

    override fun load(): Map<String, String> = synchronized(bindings) { bindings.toMap() }

    override fun save(bindings: Map<String, String>) {
        synchronized(this.bindings) {
            this.bindings.clear()
            this.bindings.putAll(bindings)
        }
    }
}
