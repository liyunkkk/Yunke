package io.github.mangi.eta.core

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 按大小轮转的日志文件。当前文件超过 [maxBytes] 后依次滚动为 `name.1.ext`、`name.2.ext`。
 */
internal class FileLogSink(
    private val directory: File,
    private val fileName: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxRotatedFiles: Int = DEFAULT_ROTATED_FILES,
) {
    private val lock = ReentrantLock()
    private var output: OutputStream? = null
    private var currentSize = 0L
    private var acceptWrites = true

    init {
        require(fileName.isNotBlank()) { "日志文件名不能为空" }
        require(maxBytes > 0L) { "日志文件大小必须大于 0" }
        require(maxRotatedFiles > 0) { "轮转文件数必须大于 0" }
    }

    fun append(text: String) {
        if (text.isEmpty()) return
        val payload = if (text.endsWith('\n')) text else "$text\n"
        lock.withLock {
            if (!acceptWrites) return
            appendLocked(payload)
        }
    }

    fun flush() {
        lock.withLock { output?.flush() }
    }

    fun close() {
        lock.withLock { shutdownLocked(deleteFiles = false) }
    }

    fun clear() {
        lock.withLock { shutdownLocked(deleteFiles = true) }
    }

    fun files(): List<File> = lock.withLock { filesLocked(includeEmpty = false) }

    fun currentFile(): File = File(directory, fileName)

    private fun appendLocked(payload: String) {
        if (!directory.exists() && !directory.mkdirs()) {
            error("无法创建日志目录：${directory.absolutePath}")
        }
        val incoming = payload.toByteArray(Charsets.UTF_8)
        if (output == null) {
            openCurrentLocked()
        }
        if (currentSize > 0L && currentSize + incoming.size > maxBytes) {
            rotateLocked()
        }
        val stream = output ?: error("日志文件未打开")
        stream.write(incoming)
        stream.flush()
        currentSize += incoming.size
        if (currentSize > maxBytes) {
            rotateLocked()
        }
    }

    private fun openCurrentLocked() {
        closeLocked()
        val file = currentFile()
        output = BufferedOutputStream(FileOutputStream(file, true), BUFFER_SIZE)
        currentSize = file.length()
    }

    private fun rotateLocked() {
        closeLocked()
        val current = currentFile()
        if (current.exists()) {
            val oldest = rotatedFile(maxRotatedFiles)
            if (oldest.exists()) oldest.delete()
            for (index in maxRotatedFiles - 1 downTo 1) {
                val source = rotatedFile(index)
                if (source.exists()) {
                    moveFile(source, rotatedFile(index + 1))
                }
            }
            moveFile(current, rotatedFile(1))
        }
        openCurrentLocked()
    }

    private fun shutdownLocked(deleteFiles: Boolean) {
        acceptWrites = false
        closeLocked()
        if (deleteFiles) {
            filesLocked(includeEmpty = true).forEach { file -> file.delete() }
            currentSize = 0L
        }
    }

    private fun closeLocked() {
        runCatching {
            output?.flush()
            output?.close()
        }
        output = null
    }

    private fun filesLocked(includeEmpty: Boolean): List<File> {
        val files = ArrayList<File>(maxRotatedFiles + 1)
        val current = currentFile()
        if (current.exists() && (includeEmpty || current.length() > 0L)) files.add(current)
        for (index in 1..maxRotatedFiles) {
            val rotated = rotatedFile(index)
            if (rotated.exists() && (includeEmpty || rotated.length() > 0L)) files.add(rotated)
        }
        return files
    }

    private fun moveFile(source: File, target: File) {
        if (!source.exists()) return
        source.copyTo(target, overwrite = true)
        check(source.delete()) { "无法轮转日志文件：" }
    }

    private fun rotatedFile(index: Int): File = File(directory, rotatedName(index))

    private fun rotatedName(index: Int): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) {
            "$fileName.$index"
        } else {
            "${fileName.substring(0, dot)}.$index${fileName.substring(dot)}"
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_ROTATED_FILES = 2
        private const val BUFFER_SIZE = 8 * 1024
    }
}
