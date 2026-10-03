package io.github.mangi.eta.agent.model

import okio.Buffer
import okio.ForwardingSource
import okio.Source

/**
 * Normalize SSE's CR, LF and CRLF line endings before handing bytes to EventSource.
 * CR is emitted immediately as LF: an open stream need not supply a following byte.
 * Only the optional LF after CR is suppressed, even across reads; UTF-8 bytes are untouched.
 * No line/body buffering, and timeout/close still belong to the original source.
 */
internal class SseLineEndingSource(source: Source) : ForwardingSource(source) {
    private val input = Buffer()
    private var suppressLf = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L

        while (true) {
            if (input.size == 0L) {
                val read = super.read(input, minOf(byteCount, 8192L))
                if (read == -1L) return -1L
                check(read > 0L) { "source returned no bytes for a positive read" }
            }
            var written = 0L
            while (input.size > 0L && written < byteCount) {
                val byte = input.readByte().toInt() and 0xff
                if (suppressLf && byte == 0x0a) {
                    suppressLf = false
                    continue
                }
                suppressLf = byte == 0x0d
                sink.writeByte(if (suppressLf) 0x0a else byte)
                written++
            }
            if (written > 0L) return written
            // A chunk containing only the LF after CR produces no output. Read on
            // instead of returning zero for a positive request (or inventing a byte).
        }
    }
}
