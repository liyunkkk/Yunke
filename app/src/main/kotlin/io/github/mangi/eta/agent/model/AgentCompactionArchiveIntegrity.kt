package io.github.mangi.eta.agent.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** One bounded byte snapshot and one tool-marker contract for restoration and branch copying. */
internal object AgentCompactionArchiveIntegrity {
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private val marker = Regex(
        "\\[Eta tool output pruned; original: context-checkpoint:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}); read_compacted_history\\]",
    )

    data class ToolReference(val id: String, val head: String, val tail: String)

    fun toolReference(message: AgentModelClient.ConversationMessage): ToolReference? {
        if (!message.role.equals("tool", ignoreCase = true) || message.contentJson.isNotBlank()) return null
        val matches = marker.findAll(message.content).take(2).toList()
        if (matches.isEmpty()) return null
        if (matches.size != 1) throw CompactionArchiveRestoreException("工具原文标记不唯一")
        val match = matches.single()
        val start = match.range.first
        val after = match.range.last + 1
        if (start == 0 || after >= message.content.length ||
            message.content[start - 1] != '\n' || message.content[after] != '\n') {
            throw CompactionArchiveRestoreException("工具原文标记位置无效")
        }
        return ToolReference(match.groupValues[1],
            message.content.substring(0, start - 1), message.content.substring(after + 1))
    }

    /** Hash and decode/copy MUST consume these same bytes, never reopen the file afterward. */
    fun verifiedBytes(file: File, checksum: File, remainingBytes: Long): ByteArray {
        if (remainingBytes <= 0L) throw CompactionArchiveRestoreException("原文总量超过读取上限")
        if (!file.isFile || !checksum.isFile || Files.isSymbolicLink(file.toPath()) ||
            Files.isSymbolicLink(checksum.toPath())) {
            throw CompactionArchiveRestoreException("原文归档缺失或包含链接")
        }
        val expected = Files.newInputStream(checksum.toPath(), LinkOption.NOFOLLOW_LINKS).use {
            boundedBytes(it, 64L)
        }.toString(Charsets.US_ASCII)
        if (!Regex("[0-9a-f]{64}").matches(expected)) throw CompactionArchiveRestoreException("原文校验值无效")
        val bytes = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use {
            boundedBytes(it, minOf(remainingBytes, MAX_FILE_BYTES.toLong()))
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (bytes.isEmpty() || actual != expected) throw CompactionArchiveRestoreException("历史原文校验失败")
        return bytes
    }

    /** Read at most limit+1 bytes even if a file grows after its metadata was checked. */
    internal fun boundedBytes(input: InputStream, limit: Long): ByteArray {
        require(limit in 1..MAX_FILE_BYTES.toLong())
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("原文读取已取消")
            val request = minOf(buffer.size.toLong(), limit - output.size() + 1L).toInt()
            val count = input.read(buffer, 0, request)
            if (count < 0) break
            if (output.size().toLong() + count > limit) throw CompactionArchiveRestoreException("原文超过读取上限")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
