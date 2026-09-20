package io.github.mangi.eta.agent.kimi

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue

/**
 * 单元测试用的极简 loopback HTTP 桩服务器。
 *
 * 之所以不使用 MockWebServer：本项目只依赖 `okhttp` 本体（5.x 的 mockwebserver
 * 已拆成独立 artifact 且包名变更），为一个测试引入新的测试依赖会牵动版本目录与
 * 云端构建解析。这里用 `ServerSocket` 直接回放响应，零新增依赖，且足以覆盖
 * "请求形状" 与 "响应信封解析" 两类断言。
 *
 * 只实现被 [KimiWebApiClient] 用到的最小子集：HTTP/1.1、`Content-Length` 定长
 * 正文、`Connection: close` 一请求一连接。不支持的形态会直接表现为测试失败，
 * 而不是静默降级。
 */
internal class KimiTestHttpServer : AutoCloseable {

    data class Recorded(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    data class Response(
        val status: Int = 200,
        val body: String = """{"code":0,"msg":"ok","data":{}}""",
    )

    private val serverSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pending = LinkedBlockingQueue<Response>()
    private val recordedRequests = Collections.synchronizedList(mutableListOf<Recorded>())

    init {
        Thread(::acceptLoop, "kimi-test-http").apply {
            isDaemon = true
            start()
        }
    }

    val origin: String = "http://127.0.0.1:${serverSocket.localPort}"

    /** 按先进先出顺序回放响应；队列为空时回放 `code=0` 的空成功信封。 */
    fun enqueue(status: Int, body: String) {
        pending += Response(status, body)
    }

    fun enqueueEnvelope(data: String, code: Int = 0, msg: String = "ok") {
        enqueue(200, """{"code":$code,"msg":"$msg","data":$data,"request_id":"req-1"}""")
    }

    fun requests(): List<Recorded> = synchronized(recordedRequests) { recordedRequests.toList() }

    fun request(index: Int): Recorded = requests()[index]

    fun requestCount(): Int = requests().size

    override fun close() {
        serverSocket.close()
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                return
            }
            socket.use { connection ->
                try {
                    serve(connection.getInputStream(), connection.getOutputStream())
                } catch (_: IOException) {
                    // 客户端提前断开：测试断言会从"请求未被记录"处暴露出来。
                }
            }
        }
    }

    private fun serve(input: InputStream, output: OutputStream) {
        val buffered = BufferedInputStream(input)
        val requestLine = readLine(buffered) ?: return
        val segments = requestLine.split(' ')
        if (segments.size < 2) return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(buffered) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) readExactly(buffered, length) else ""
        recordedRequests += Recorded(segments[0], segments[1], headers, body)

        val response = pending.poll() ?: Response()
        val payload = response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reasonOf(response.status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.flush()
    }

    private fun readExactly(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var filled = 0
        while (filled < length) {
            val read = input.read(bytes, filled, length - filled)
            if (read < 0) break
            filled += read
        }
        return String(bytes, 0, filled, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString()
            if (byte != '\r'.code) line.append(byte.toChar())
        }
    }

    private fun reasonOf(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Status"
    }
}