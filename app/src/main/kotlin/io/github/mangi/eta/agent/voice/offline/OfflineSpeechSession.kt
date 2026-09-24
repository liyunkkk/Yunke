package io.github.mangi.eta.agent.voice.offline

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.k2fsa.sherpa.ncnn.DecoderConfig
import com.k2fsa.sherpa.ncnn.FeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import io.github.mangi.eta.agent.voice.pcmLevel
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** No network and no audio files. All native calls and microphone ownership stay on one worker. */
internal object OfflineSpeechSession {
    private val microphone = Mutex()
    // OpenMP/ncnn keep process-wide runtime state; a dedicated worker avoids
    // DefaultDispatcher thread churn around model load.
    private val native = Executors.newSingleThreadExecutor { thread ->
        Thread(thread, "eta-speech-ncnn").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    @SuppressLint("MissingPermission") // Permission is requested by the user-initiated UI entry.
    suspend fun recognize(
        context: Context,
        onListening: suspend () -> Unit,
        onText: suspend (String) -> Unit,
        mode: io.github.mangi.eta.agent.voice.VoiceEntryMode = io.github.mangi.eta.agent.voice.VoiceEntryMode.DICTATION,
        onLevel: (Float) -> Unit = {},
        finishRequested: () -> Boolean = { false },
    ): Boolean = withContext(native) {
        check(microphone.tryLock()) { "Speech input is already active" }
        var recognizer: SherpaNcnn? = null
        var recorder: AudioRecord? = null
        try {
            check(io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode) && OfflineSpeechPack.state.value.ready)
            val dir = OfflineSpeechPack.directory(context)
            // Recheck sizes before passing paths to native code; full hashes were verified at startup/download.
            check(SpeechModelManifest.assets.all { File(dir, it.name).length() == it.bytes })
            fun path(name: String) = File(dir, name).absolutePath
            val engine = SherpaNcnn(RecognizerConfig(
                featConfig = FeatureExtractorConfig(16_000f, 80),
                modelConfig = ModelConfig(
                    encoderParam = path("encoder_jit_trace-pnnx.ncnn.param"),
                    encoderBin = path("encoder_jit_trace-pnnx.ncnn.bin"),
                    decoderParam = path("decoder_jit_trace-pnnx.ncnn.param"),
                    decoderBin = path("decoder_jit_trace-pnnx.ncnn.bin"),
                    joinerParam = path("joiner_jit_trace-pnnx.ncnn.param"),
                    joinerBin = path("joiner_jit_trace-pnnx.ncnn.bin"),
                    tokens = path("tokens.txt"),
                    numThreads = 1, useGPU = false,
                ),
                decoderConfig = DecoderConfig(method = "greedy_search"),
                enableEndpoint = true,
                rule1MinTrailingSilence = 8f,
                rule2MinTrailingSilence = 1.2f,
                rule3MinUtteranceLength = 60f,
            ))
            recognizer = engine
            ensureActive() // Closing the screen during model load must never start recording later.
            val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0)
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 64_000))
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            ensureActive()
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            withContext(Dispatchers.Main) { onListening() }
            val samples = ShortArray(1600)
            val start = SystemClock.elapsedRealtime()
            var lastText = ""
            while (true) {
                // 面板点「结束录音并发送」：跳出循环，由下方 natural completion 分支冲刷尾包。
                if (finishRequested()) break
                ensureActive()
                val elapsed = SystemClock.elapsedRealtime() - start
                if (SpeechInputPolicy.timedOut(elapsed, lastText.isNotEmpty())) break
                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed" }
                if (count > 0) onLevel(pcmLevel(samples, count))
                if (count == 0) { delay(10); continue }
                engine.acceptSamples(FloatArray(count) { samples[it] / 32768f })
                while (engine.isReady()) { ensureActive(); engine.decode() }
                val text = engine.text.trim()
                if (text.isNotEmpty() && text != lastText) {
                    lastText = text
                    withContext(Dispatchers.Main) { onText(text) }
                }
                if (engine.isEndpoint()) break
            }
            // Only natural completion flushes decoder tail. Explicit cancellation keeps already shown text.
            ensureActive()
            engine.acceptSamples(FloatArray(4800))
            engine.inputFinished()
            while (engine.isReady()) { ensureActive(); engine.decode() }
            val finalText = engine.text.trim()
            if (finalText.isNotEmpty() && finalText != lastText) withContext(Dispatchers.Main) { onText(finalText) }
            finalText.isNotEmpty() || lastText.isNotEmpty()
        } finally {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { recognizer?.close() }
            microphone.unlock()
        }
    }
}
