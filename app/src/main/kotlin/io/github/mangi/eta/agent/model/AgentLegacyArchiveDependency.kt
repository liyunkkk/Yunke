package io.github.mangi.eta.agent.model

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Read-only compatibility for old branches that copied summaries but not their dependencies.
 * Never called by restoreHistory/read_compacted_history; no source archive is modified.
 * The caller must first validate a local parent archive and its generated reference.
 */
internal class AgentLegacyArchiveDependency(private val parent: File, private val source: File) {
    private val usedScopes = linkedSetOf<File>()
    private var inspectedEntries = 0
    private var verifiedBytes = 0L
    private var candidates = 0

    fun read(id: String, remainingBytes: Long): ByteArray {
        check(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id))
        checkScope(source)
        var selected: ByteArray? = null
        Files.newDirectoryStream(parent.toPath()).use { paths ->
            for (path in paths) {
                interrupted()
                check(++inspectedEntries <= MAX_ENTRIES) { "旧分支归档扫描数量超限" }
                val scope = path.toFile()
                if (scope == source || !Regex("[0-9a-f]{64}").matches(scope.name)) continue
                check(!Files.isSymbolicLink(path)) { "旧分支归档目录包含链接" }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                val json = File(scope, "$id.json")
                val sha = File(scope, "$id.sha256")
                if (Files.notExists(json.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    Files.notExists(sha.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                checkScope(scope)
                check(++candidates <= 128) { "旧分支归档副本数量超限" }
                val bytes = AgentCompactionArchiveIntegrity.verifiedBytes(
                    json, sha, minOf(remainingBytes, MAX_VERIFY_BYTES - verifiedBytes),
                )
                verifiedBytes += bytes.size
                // Do not silently choose the first UUID match or substitute a corrupt copy.
                check(selected == null || selected.contentEquals(bytes)) { "旧分支归档副本不一致" }
                selected = bytes
                usedScopes += scope
            }
        }
        recheckScopes()
        return checkNotNull(selected) { "缺失的旧分支归档没有可验证副本" }
    }

    fun recheckScopes() {
        checkScope(source)
        usedScopes.forEach(::checkScope)
    }

    private fun checkScope(scope: File) {
        interrupted()
        check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) { "原文目录不可用" }
        check(scope.isDirectory && !Files.isSymbolicLink(scope.toPath())) { "原文来源不可用" }
        check(Files.notExists(File(parent, "${scope.name}.deleted").toPath(), LinkOption.NOFOLLOW_LINKS)) { "原文来源已删除" }
    }

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("旧分支归档恢复已取消")
    }

    private companion object {
        const val MAX_ENTRIES = 4096
        const val MAX_VERIFY_BYTES = 64L * 1024 * 1024
    }
}
