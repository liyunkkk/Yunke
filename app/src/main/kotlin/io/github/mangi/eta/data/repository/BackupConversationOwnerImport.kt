package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.config.Prefs
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Separate durable undo for SharedPreferences (which cannot participate in the Room transaction).
 * The marker and owner are written in ONE SharedPreferences commit. A pre-existing owner is never
 * removed, even if its contents happen to match the imported archive.
 */
internal class BackupConversationOwnerImport private constructor(
    private val operation: File,
    private val id: String,
    private val archive: String,
    private val marker: String,
) {
    companion object {
        private const val INTENT = "conversation-owner-intent"
        private const val PREFIX = "backup_conversation_owner_import_"
        private const val OWNER_PREFIX = "agent_conversation_child_owner_v1_c_"
        // A failed commit may have removed the marker only in the current process's memory.
        // Across process restarts, the durable intent and the on-disk marker provide recovery.
        private val uncertainDurability = mutableSetOf<String>()

        fun plan(operation: File, id: String, archive: String): BackupConversationOwnerImport =
            BackupConversationOwnerImport(operation, id, archive, PREFIX + UUID.randomUUID())

        fun recover(context: Context, operation: File, committed: Boolean) {
            val file = File(operation, INTENT)
            if (!file.exists() && !File(file.path + ".bak").exists()) return
            val raw = AtomicFile(file).openRead().use { BackupArchiveSafety.readText(it) }
            val json = JSONObject(raw)
            val id = json.getString("id")
            require(Regex("conv-[0-9a-f-]{36}").matches(id)) { "会话 owner 日志 ID 无效" }
            val idFile = File(operation, "new-conversation-id")
            require(AtomicFile(idFile).openRead().use { BackupArchiveSafety.readText(it, 128) } == id) {
                "会话 owner 日志与导入 ID 不一致"
            }
            val marker = json.getString("marker")
            require(marker.startsWith(PREFIX) && marker.length <= 128) { "会话 owner 日志标记无效" }
            BackupConversationOwnerImport(operation, id, json.getString("archive"), marker).finish(context, committed)
        }
    }

    private fun ownerKey(): String = OWNER_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(id.toByteArray(Charsets.UTF_8))

    fun begin(context: Context) {
        val prefs = requireNotNull(Prefs.localAgentPreferences()) { "Agent preferences 未初始化" }
        require(!prefs.contains(ownerKey())) { "新会话子代理 owner 已存在" }
        durableText(File(operation, INTENT), JSONObject().put("id", id)
            .put("marker", marker).put("archive", archive).toString())
        // No owner is overwritten. The marker distinguishes this import from a competing owner.
        require(!prefs.contains(ownerKey()) && !prefs.contains(marker)) { "新会话子代理 owner 冲突" }
        check(prefs.edit().putString(ownerKey(), archive).putString(marker, archive).commit()) {
            "会话子代理 owner 写入失败"
        }
    }

    fun finish(context: Context, committed: Boolean) {
        val prefs = requireNotNull(Prefs.localAgentPreferences()) { "Agent preferences 未初始化" }
        val token = "${operation.absolutePath}:$marker"
        synchronized(uncertainDurability) {
            val previouslyUncertain = token in uncertainDurability
            if (!prefs.contains(marker) && !previouslyUncertain) return // intent before owner write
            if (prefs.contains(marker)) {
                require(prefs.getString(marker, null) == archive) { "会话 owner 恢复标记发生变化" }
            }
            if (!committed && !previouslyUncertain) {
                require(prefs.contains(ownerKey()) && prefs.getString(ownerKey(), null) == archive) {
                    "会话 owner 在导入后被更改，保留恢复日志"
                }
            }
            // A failed commit can remove the marker in memory without persisting the change.
            // Remember this intent through subsequent recovery calls in this same process.
            uncertainDurability.add(token)
            repeat(2) {
                if (prefs.contains(marker)) {
                    require(prefs.getString(marker, null) == archive) { "会话 owner 恢复标记发生变化" }
                }
                if (!committed) {
                    // Only the marker proved this import owned the key; never delete a changed owner.
                    require(!prefs.contains(ownerKey()) || prefs.getString(ownerKey(), null) == archive) {
                        "会话 owner 在导入后被更改，保留恢复日志"
                    }
                }
                val edit = prefs.edit()
                if (!committed) edit.remove(ownerKey())
                if (edit.remove(marker).commit()) {
                    uncertainDurability.remove(token)
                    return
                }
            }
            error("会话 owner 日志清理未落盘，保留恢复日志")
        }
    }
}
