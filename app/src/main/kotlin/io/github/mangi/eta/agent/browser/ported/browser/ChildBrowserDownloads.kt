package io.github.mangi.eta.agent.browser.ported.browser

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Bounded task-owned IO. No URL, headers or exception body are returned as diagnostics. */
internal class ChildBrowserDownloads(
    private val directory: () -> File,
    private val cookies: (String) -> String?,
) {
    data class Saved(val file: File, val size: Long)
    class Refused(val code: String) : IllegalStateException(code)
    private val slots = Semaphore(3)
    private val generation = AtomicLong()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    @Volatile private var closed = false

    fun cancelAll() {
        generation.incrementAndGet()
        connections.values.forEach { runCatching { it.disconnect() } }
    }
    fun close() { closed = true; cancelAll() }

    private fun checked(raw: String): URI {
        val value = runCatching { URI(raw) }.getOrNull()
        if (value == null || value.scheme?.lowercase() !in setOf("http", "https") ||
            value.host.isNullOrBlank() || value.rawUserInfo != null || raw.length > 8192 ||
            raw.any { it.code < 32 }) throw Refused("CHILD_DOWNLOAD_URL_NOT_ALLOWED")
        return value
    }
    private suspend fun checkActive(epoch: Long) {
        currentCoroutineContext().ensureActive()
        if (closed || generation.get() != epoch) throw kotlinx.coroutines.CancellationException()
    }

    suspend fun fetch(raw: String, userAgent: String? = null, declaredLength: Long = -1): Saved {
        if (declaredLength > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
        val initial = checked(raw)
        return save { file, epoch ->
            var target = initial
            for (redirect in 0..5) {
                checkActive(epoch)
                val key = UUID.randomUUID().toString()
                val connection = target.toURL().openConnection() as HttpURLConnection
                connections[key] = connection
                try {
                    checkActive(epoch)
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = false
                    userAgent?.takeIf { it.length < 2048 && it.none { c -> c == '\r' || c == '\n' } }
                        ?.let { connection.setRequestProperty("User-Agent", it) }
                    // Re-resolve for each redirect target; never forward the old host header.
                    cookies(target.toString())?.let { connection.setRequestProperty("Cookie", it) }
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        if (redirect == 5) throw Refused("CHILD_DOWNLOAD_REDIRECT_LIMIT")
                        val location = connection.getHeaderField("Location")
                            ?: throw Refused("CHILD_DOWNLOAD_REDIRECT_INVALID")
                        val next = checked(target.resolve(location).toString())
                        if (target.scheme.equals("https", true) && !next.scheme.equals("https", true))
                            throw Refused("CHILD_DOWNLOAD_DOWNGRADE_REFUSED")
                        target = next
                        continue
                    }
                    if (status !in 200..299) throw Refused("CHILD_DOWNLOAD_HTTP_FAILURE")
                    if (connection.contentLengthLong > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var copied = 0L
                            while (true) {
                                checkActive(epoch)
                                val count = input.read(buffer)
                                if (count < 0) break
                                checkActive(epoch)
                                if (copied + count > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
                                output.write(buffer, 0, count)
                                copied += count
                            }
                        }
                    }
                    return@save
                } finally {
                    connections.remove(key)
                    connection.disconnect()
                }
            }
            throw Refused("CHILD_DOWNLOAD_REDIRECT_LIMIT")
        }
    }

    suspend fun blob(bytes: ByteArray): Saved {
        if (bytes.size.toLong() > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
        return save { file, epoch ->
            file.outputStream().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkActive(epoch)
                    val count = minOf(8192, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
            }
        }
    }

    private suspend fun save(write: suspend (File, Long) -> Unit): Saved {
        if (!slots.tryAcquire()) throw Refused("CHILD_DOWNLOAD_BUSY")
        val epoch = generation.get()
        try {
            return withContext(Dispatchers.IO) {
                checkActive(epoch)
                val folder = directory()
                if (!folder.isDirectory && !folder.mkdirs()) throw Refused("CHILD_DOWNLOAD_DIRECTORY_UNAVAILABLE")
                val temporary = File.createTempFile("eta-child-", ".part", folder)
                var destination: File? = null
                var published = false
                try {
                    write(temporary, epoch)
                    checkActive(epoch)
                    val reserved = File(folder, "download_${UUID.randomUUID()}.bin")
                    if (!reserved.createNewFile()) throw Refused("CHILD_DOWNLOAD_FILE_CONFLICT")
                    destination = reserved
                    if (!temporary.renameTo(reserved)) throw Refused("CHILD_DOWNLOAD_PUBLISH_FAILED")
                    checkActive(epoch)
                    val result = Saved(reserved, reserved.length())
                    published = true
                    result
                } finally {
                    temporary.delete()
                    if (!published) destination?.delete()
                }
            }
        } finally { slots.release() }
    }

    companion object { const val MAX_BYTES = 32L * 1024 * 1024 }
}
