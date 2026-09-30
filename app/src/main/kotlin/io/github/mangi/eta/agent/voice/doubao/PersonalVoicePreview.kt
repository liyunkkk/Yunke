package io.github.mangi.eta.agent.voice.doubao

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.voice.VoiceDiagnostics
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request

internal interface VoicePreviewPlayer {
    // 播本地文件：远端流式播放会踩到「长度字段非标准」的 WAV 解析失败。
    fun prepare(filePath: String, ready: () -> Unit, completed: () -> Unit,
        error: (Int, Int) -> Unit, buffering: (Int) -> Unit)
    fun start()
    fun position(): Int
    fun duration(): Int
    fun release()
}

internal class AndroidVoicePreviewPlayer : VoicePreviewPlayer {
    private val player = MediaPlayer()
    override fun prepare(filePath: String, ready: () -> Unit, completed: () -> Unit,
        error: (Int, Int) -> Unit, buffering: (Int) -> Unit) {
        player.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        player.setOnPreparedListener { ready() }
        player.setOnCompletionListener { completed() }
        player.setOnErrorListener { _, what, extra -> error(what, extra); true }
        player.setOnBufferingUpdateListener { _, percent -> buffering(percent) }
        player.setDataSource(filePath)
        player.prepareAsync()
    }
    override fun start() = player.start()
    override fun position() = player.currentPosition
    override fun duration() = player.duration
    override fun release() {
        player.setOnPreparedListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        player.setOnBufferingUpdateListener(null)
        player.release()
    }
}

/**
 * 下载 demo 音频并修正 WAV 长度字段，落到本地临时文件。
 *
 * 豆包 demo 音频是 ffmpeg 流式写法（RIFF/data 长度都是 0xFFFFFFFF），Android 的 WAV
 * 解析器必须信任长度字段，直接交给 MediaPlayer 流式播放会在 prepare 阶段异步失败。
 */
internal class VoicePreviewDownloader(
    private val cacheDir: File,
    private val client: OkHttpClient = defaultClient(),
) {
    /** @return 可播放的本地文件；下载或落盘失败返回 null。 */
    fun fetch(url: String): File? {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body
            val out = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > MAX_BYTES) return null
                    out.write(buffer, 0, read)
                }
            }
            val normalized = normalizeWavLengths(out.toByteArray())
            val directory = File(cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
            val extension = if (isRiffWave(normalized)) "wav" else "mp3"
            val file = File(directory, "preview-${UUID.randomUUID()}.$extension")
            file.writeBytes(normalized)
            return file
        }
    }

    internal companion object {
        const val DOWNLOAD_BUFFER_BYTES = 8192
        const val MAX_BYTES = 16 * 1024 * 1024
        const val CACHE_DIRECTORY = "voice-preview"

        fun defaultClient(): OkHttpClient = AgentHttpClient.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** Main-thread owner; stale callbacks can neither start playback nor clear a newer selection. */
internal class PersonalVoicePreview(
    private val factory: () -> VoicePreviewPlayer = { AndroidVoicePreviewPlayer() },
    private val traceFactory: () -> VoiceDiagnostics = { VoiceDiagnostics("personal-preview") },
    private val after: (Long, () -> Unit) -> (() -> Unit) = { delayMs, action ->
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable { action() }
        handler.postDelayed(task, delayMs)
        val cancel: () -> Unit = { handler.removeCallbacks(task) }
        cancel
    },
    /** 后台执行下载；默认新起工作线程，测试可注入同步执行。 */
    private val background: ((() -> Unit) -> Unit) = { work ->
        Thread(work, "voice-preview-download").start()
    },
    /** 把下载完成回调切回主线程；测试可注入同步执行。 */
    private val postToMain: (() -> Unit) -> Unit = { action ->
        Handler(Looper.getMainLooper()).post(action)
    },
    /** 下载并经 [normalizeWavLengths] 规范化后落到本地临时文件；失败返回 null。 */
    private val fetch: (String) -> File? = { null },
) {
    data class State(val account: String = "", val id: String = "", val loading: Boolean = false, val error: String? = null) {
        val active get() = id.isNotEmpty()
        fun matches(account: String, id: String) = active && this.account == account && this.id == id
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var player: VoicePreviewPlayer? = null
    private var trace: VoiceDiagnostics? = null
    private var generation = 0
    private var draining = false
    private var cancelDrain: (() -> Unit)? = null
    private var currentFile: File? = null

    internal companion object {
        // Some Android output paths report completion before their queued tail is audible.
        // This is a bounded release grace period, not a seek/replay or a substitute for missing audio.
        fun drainDelayMs(duration: Int, position: Int): Long {
            val remaining = if (duration > 0 && position >= 0) (duration.toLong() - position).coerceIn(0L, 2000L) else 0L
            return remaining + 150L
        }
    }

    fun toggle(account: String, id: String, url: String) {
        if (mutable.value.matches(account, id)) { stop(1); return }
        stop(2)
        if (!url.startsWith("https://")) return
        val token = ++generation
        val diagnostic = traceFactory()
        trace = diagnostic
        mutable.value = State(account, id, loading = true)
        diagnostic.mark("preview.prepare")
        // 先下载到本地再播：远端 demo 是长度字段非标准的 WAV，MediaPlayer 流式播放会解析失败。
        diagnostic.mark("preview.download")
        background {
            val file = runCatching { fetch(url) }.getOrNull()
            // 下载是异步的：回到主线程后必须再校验代次，避免切音色/离开页面后旧下载抢回播放器。
            postToMain {
                if (token != generation) { discard(file); return@postToMain }
                if (file == null) { failDownload(token); return@postToMain }
                diagnostic.mark("preview.downloaded", "bytes" to runCatching { file.length() }.getOrDefault(0L))
                currentFile = file
                try {
                    val owned = factory()
                    player = owned
                    owned.prepare(file.absolutePath,
                        ready = {
                            if (token == generation && player === owned) {
                                diagnostic.mark("preview.ready", "durationMs" to safeDuration(owned))
                                try {
                                    owned.start()
                                    if (token == generation && player === owned) {
                                        mutable.value = State(account, id)
                                        diagnostic.mark("preview.start")
                                    }
                                } catch (_: Exception) { fail(token, -2, 0) }
                            }
                        },
                        completed = {
                            if (token == generation && player === owned) {
                                val duration = safeDuration(owned)
                                val position = safePosition(owned)
                                diagnostic.mark("preview.complete", "durationMs" to duration, "positionMs" to position,
                                    "early" to if (duration > 0 && position >= 0 && duration - position > 500) 1 else 0)
                                if (!draining) {
                                    draining = true
                                    val delayMs = drainDelayMs(duration, position)
                                    diagnostic.mark("preview.drain", "delayMs" to delayMs)
                                    cancelDrain = after(delayMs) {
                                        if (token == generation && player === owned) {
                                            diagnostic.mark("preview.drained", "positionMs" to safePosition(owned))
                                            stop(3)
                                        }
                                    }
                                }
                            }
                        },
                        error = { what, extra -> fail(token, what, extra) },
                        buffering = { percent ->
                            if (token == generation) diagnostic.mark("preview.buffer", "percent" to percent, sampled = true)
                        },
                    )
                } catch (_: Exception) { fail(token, -1, 0) }
            }
        }
    }

    private fun fail(token: Int, what: Int, extra: Int) {
        if (token != generation) return
        trace?.mark("preview.error", "what" to what, "extra" to extra)
        stop(4)
        mutable.value = State(error = "试听播放失败，请查询音色状态后重试。")
    }

    /** 下载失败与播放失败分开报，避免把一次网络问题说成音频坏。 */
    private fun failDownload(token: Int) {
        if (token != generation) return
        trace?.mark("preview.download_error")
        stop(4)
        mutable.value = State(error = "试听音频下载失败，请检查网络后重试。")
    }

    private fun discard(file: File?) {
        file?.let { runCatching { it.delete() } }
    }

    // 0 leave screen; 1 explicit stop; 2 switch voice; 3 completion; 4 error; 5 remove voice.
    fun stop(reason: Int = 0) {
        generation++
        cancelDrain?.invoke()
        cancelDrain = null
        draining = false
        val owned = player
        player = null
        if (owned != null) {
            trace?.mark("preview.stop", "reason" to reason,
                "positionMs" to safePosition(owned), "durationMs" to safeDuration(owned))
            runCatching { owned.release() }
        }
        discard(currentFile)
        currentFile = null
        trace?.finish()
        trace = null
        mutable.value = State()
    }
    private fun safePosition(player: VoicePreviewPlayer) = runCatching { player.position() }.getOrDefault(-1)
    private fun safeDuration(player: VoicePreviewPlayer) = runCatching { player.duration() }.getOrDefault(-1)
}

/** `RIFF`/`WAVE` 头判定，供扩展名选择与规范化前置校验共用。 */
internal fun isRiffWave(bytes: ByteArray): Boolean =
    bytes.matchesAscii(0, "RIFF") && bytes.matchesAscii(8, "WAVE")

/**
 * 修正 WAV 的长度字段（纯函数，便于单测）。
 *
 * 服务端 demo 是 ffmpeg 流式写法：RIFF(offset 4) 与 data chunk 的 size 都是 `0xFFFFFFFF`，
 * Android 的 WAV 解析器必须信任这两个字段，于是 prepare 直接失败。这里按 chunk 遍历，
 * 把 data 实际长度与 RIFF 总长写成真实值；不是 WAV（或没有 data chunk）时原样返回。
 */
internal fun normalizeWavLengths(bytes: ByteArray): ByteArray {
    if (bytes.size < 12 || !isRiffWave(bytes)) return bytes
    val fileLength = bytes.size
    var offset = 12L
    var dataStart = -1L
    var dataSizeOffset = -1
    var declaredDataSize = 0L
    while (offset + 8 <= fileLength) {
        val id = bytes.asciiAt(offset.toInt())
        val declared = bytes.readUInt32LE(offset.toInt() + 4)
        if (id == "data") {
            dataStart = offset + 8
            dataSizeOffset = offset.toInt() + 4
            declaredDataSize = declared
            break
        }
        // payload 按偶数对齐；声明长度不可信时停止遍历。
        val advance = 8L + declared + (declared and 1L)
        if (advance <= 0L || offset + advance > fileLength) return bytes
        offset += advance
    }
    if (dataStart < 0) return bytes
    val remaining = fileLength - dataStart
    val unknownLength = declaredDataSize == UINT32_MAX || declaredDataSize > remaining
    val actualLength = if (unknownLength) remaining else declaredDataSize
    // data 不是最后一个 chunk 时，把尾部截掉，避免 MediaPlayer 继续读后面的块。
    val finalLength = if (unknownLength) fileLength else (dataStart + actualLength).toInt()
    val output = bytes.copyOf(finalLength)
    output.writeUInt32LE(dataSizeOffset, actualLength)
    output.writeUInt32LE(4, (finalLength - 8).toLong())
    return output
}

private const val UINT32_MAX = 0xFFFFFFFFL

private fun ByteArray.matchesAscii(offset: Int, text: String): Boolean =
    offset >= 0 && offset + text.length <= size && text.indices.all { this[offset + it] == text[it].code.toByte() }

private fun ByteArray.asciiAt(offset: Int): String =
    if (offset + 4 <= size) String(this, offset, 4, Charsets.US_ASCII) else ""

private fun ByteArray.readUInt32LE(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)

private fun ByteArray.writeUInt32LE(offset: Int, value: Long) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}
