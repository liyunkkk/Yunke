package io.github.mangi.eta.agent.model

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Enforce the cap while streaming, including chunked responses without Content-Length. */
internal fun InputStream.readGenerationBytes(limit: Int): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (read < 0) return output.toByteArray()
        if (read == 0) continue
        check(read <= limit - total) { "MEDIA_RESPONSE_TOO_LARGE" }
        output.write(buffer, 0, read)
        total += read
    }
}
