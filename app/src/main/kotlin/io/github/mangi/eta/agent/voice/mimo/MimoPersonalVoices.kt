package io.github.mangi.eta.agent.voice.mimo

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.agent.voice.tts.SpeechEngine
import io.github.mangi.eta.agent.voice.tts.SpeechEngineResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID

internal object MimoPersonalVoices {
    const val PREFIX = "mimo-local-"
    const val MODEL = "mimo-v2.5-tts-voiceclone"
    const val MAX_BASE64 = 10_000_000L
    const val MAX_BYTES = 7_500_000
    data class Voice(val id: String, val name: String, val providerId: String, val mime: String, val durationMs: Long)
    private lateinit var root: File
    private var loaded = false
    private val mutable = MutableStateFlow<List<Voice>>(emptyList())
    val state = mutable.asStateFlow()
    fun supports(provider: ProviderSetting): Boolean = SpeechSynthesisModels.allowsSpeechEndpoint(provider) &&
        provider.apiKey.isNotBlank() && (SpeechEngineResolver.resolve(provider, "") == SpeechEngine.MIMO || provider.models.any { it.modelId.startsWith("mimo-v2.5-tts") })

    @Synchronized fun load(context: Context) {
        if (loaded) return
        root = File(context.filesDir, "mimo-personal-voices").apply { mkdirs() }
        val index = AtomicFile(File(root, "index.json"))
        try {
        mutable.value = if (!index.baseFile.exists() && !File(root, "index.json.bak").exists()) emptyList() else {
            val items = JSONArray(index.openRead().bufferedReader().use { it.readText() })
            (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                val id = o.getString("id")
                require(id.matches(Regex("mimo-local-[a-f0-9-]{36}")))
                Voice(id, o.getString("name"), o.getString("provider"), o.getString("mime"), o.getLong("duration"))
            }
        }
        } catch (e: Exception) {
            // Keep a failed load retryable without allowing imports to overwrite a broken index.
            loaded = false
            throw e
        }
        loaded = true
    }
    fun mime(bytes: ByteArray): String {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES && ((bytes.size.toLong() + 2) / 3) * 4 <= MAX_BASE64) { "录音过大，Base64 编码后不能超过 10 MB" }
        fun tag(start: Int, value: String) = bytes.size >= start + value.length && bytes.copyOfRange(start, start + value.length).toString(Charsets.US_ASCII) == value
        return when {
            tag(0, "RIFF") && tag(8, "WAVE") -> "audio/wav"
            tag(0, "ID3") || (bytes.size > 2 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 0xe0 == 0xe0 && bytes[1].toInt() and 6 != 0) -> "audio/mpeg"
            else -> error("仅支持有效的 MP3 或 WAV 录音")
        }
    }
    suspend fun import(context: Context, uri: Uri, name: String, providerId: String): Voice = withContext(Dispatchers.IO) {
        require(name.trim().isNotEmpty() && providerId.isNotBlank()) { "请选择提供商并填写声音名称" }
        load(context)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_BYTES + 1) } ?: error("无法读取录音")
        val type = mime(bytes)
        val id = PREFIX + UUID.randomUUID()
        val file = File(root, "$id.audio")
        try {
            file.writeBytes(bytes)
            val retriever = MediaMetadataRetriever()
            val duration = try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { retriever.release() }
            require(duration > 0) { "录音无有效音轨或时长" }
            val voice = Voice(id, name.trim().take(80), providerId, type, duration)
            synchronized(this@MimoPersonalVoices) { persist(mutable.value + voice) }
            voice
        } catch (e: Exception) { file.delete(); throw e }
    }
    @Synchronized private fun persist(voices: List<Voice>) {
        val json = JSONArray()
        voices.forEach { json.put(JSONObject().put("id", it.id).put("name", it.name).put("provider", it.providerId).put("mime", it.mime).put("duration", it.durationMs)) }
        val atomic = AtomicFile(File(root, "index.json")); val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
        mutable.value = voices
    }
    @Synchronized fun remove(id: String) {
        val voice = mutable.value.firstOrNull { it.id == id } ?: return
        val file = File(root, "${voice.id}.audio")
        check(!file.exists() || file.delete()) { "录音删除失败" }
        persist(mutable.value.filterNot { it.id == id })
    }
    @Synchronized fun reference(id: String, providerId: String): String {
        val voice = mutable.value.firstOrNull { it.id == id && it.providerId == providerId } ?: error("个人声音已删除或不属于此提供商")
        val bytes = File(root, "${voice.id}.audio").inputStream().use { it.readNBytes(MAX_BYTES + 1) }
        val type = mime(bytes)
        return "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
    }
}
