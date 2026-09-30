package io.github.mangi.eta.agent.voice.doubao

import io.github.mangi.eta.agent.voice.VoiceDiagnostics
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PersonalVoicePreviewTest {
    private class Player : VoicePreviewPlayer {
        lateinit var ready: () -> Unit
        lateinit var completed: () -> Unit
        lateinit var error: (Int, Int) -> Unit
        var starts = 0
        var releases = 0
        var position = 0
        override fun prepare(filePath: String, ready: () -> Unit, completed: () -> Unit,
            error: (Int, Int) -> Unit, buffering: (Int) -> Unit) {
            this.ready = ready; this.completed = completed; this.error = error
        }
        override fun start() { starts++ }
        override fun position() = position
        override fun duration() = 8000
        override fun release() { releases++ }
    }
    private fun controller(players: MutableList<Player>, logs: MutableList<String> = mutableListOf()) =
        PersonalVoicePreview(factory = { Player().also { players.add(it) } },
            traceFactory = { VoiceDiagnostics("personal-preview", { logs.add(it) }, { 0L }) },
            after = { _, action -> action(); {} },
            background = { it() },
            postToMain = { it() },
            fetch = { File.createTempFile("voice-preview", ".wav") })

    @Test fun loadingAndPlayingAreBothStoppableWithoutRestart() {
        val players = mutableListOf<Player>(); val owner = controller(players)
        owner.toggle("account", "voice", "https://example.test/demo")
        assertTrue(owner.state.value.loading)
        assertTrue(owner.state.value.matches("account", "voice"))
        players[0].ready()
        assertFalse(owner.state.value.loading)
        assertTrue(owner.state.value.active)
        owner.toggle("account", "voice", "https://example.test/demo")
        assertFalse(owner.state.value.active)
        assertEquals(1, players.size)
        assertEquals(1, players[0].starts)
        assertEquals(1, players[0].releases)
    }

    @Test fun stopDuringLoadingInvalidatesLateReady() {
        val players = mutableListOf<Player>(); val owner = controller(players)
        owner.toggle("a", "v", "https://example.test/demo")
        owner.toggle("a", "v", "https://example.test/demo")
        players[0].ready()
        assertEquals(0, players[0].starts)
        assertFalse(owner.state.value.active)
    }

    @Test fun staleCallbacksCannotStopNewVoiceOrShowOldError() {
        val players = mutableListOf<Player>(); val owner = controller(players)
        owner.toggle("a", "v1", "https://example.test/one")
        owner.toggle("a", "v2", "https://example.test/two")
        players[0].ready(); players[0].completed(); players[0].error(1, -1)
        assertEquals(0, players[0].starts)
        assertEquals(1, players[0].releases)
        assertTrue(owner.state.value.matches("a", "v2"))
        assertNull(owner.state.value.error)
        players[1].ready()
        assertEquals(1, players[1].starts)
    }

    @Test fun completionRestoresPlayButtonAndLogsPositionNotPrivateData() {
        val players = mutableListOf<Player>(); val logs = mutableListOf<String>()
        val owner = controller(players, logs)
        owner.toggle("private-account", "private-voice", "https://private.test/secret")
        players[0].ready(); players[0].position = 8000; players[0].completed()
        assertFalse(owner.state.value.active)
        assertEquals(1, players[0].releases)
        assertTrue(logs.any { "preview.complete" in it && "durationMs=8000 positionMs=8000 early=0" in it })
        assertTrue(logs.none { "private" in it || "secret" in it || "https://" in it })
    }

    @Test fun earlyCompletionAndErrorsAreDiagnosable() {
        val players = mutableListOf<Player>(); val logs = mutableListOf<String>()
        val owner = controller(players, logs)
        owner.toggle("a", "v", "https://example.test/demo")
        players[0].ready(); players[0].position = 2000; players[0].completed()
        assertTrue(logs.any { "preview.complete" in it && "early=1" in it })
        owner.toggle("a", "v", "https://example.test/demo")
        players[1].error(1, -1004)
        assertFalse(owner.state.value.active)
        assertNotNull(owner.state.value.error)
        assertTrue(logs.any { "what=1 extra=-1004" in it })
    }

    @Test fun prepareExceptionClearsLoadingAndReleasesPlayer() {
        val player = object : VoicePreviewPlayer {
            var released = false
            override fun prepare(filePath: String, ready: () -> Unit, completed: () -> Unit,
                error: (Int, Int) -> Unit, buffering: (Int) -> Unit) { throw IllegalStateException("prepare failed") }
            override fun start() = Unit
            override fun position() = 0
            override fun duration() = 0
            override fun release() { released = true }
        }
        val owner = PersonalVoicePreview(
            factory = { player },
            traceFactory = { VoiceDiagnostics("test", {}) },
            background = { it() },
            postToMain = { it() },
            fetch = { File.createTempFile("voice-preview", ".wav") },
        )
        owner.toggle("a", "v", "https://example.test/demo")
        assertTrue(player.released)
        assertFalse(owner.state.value.active)
        assertNotNull(owner.state.value.error)
    }

    @Test fun downloadFailureReportsDownloadErrorNotPlaybackFailure() {
        val players = mutableListOf<Player>()
        val owner = PersonalVoicePreview(
            factory = { Player().also { players.add(it) } },
            traceFactory = { VoiceDiagnostics("test", {}) },
            after = { _, action -> action(); {} },
            background = { it() },
            postToMain = { it() },
            fetch = { null },
        )
        owner.toggle("a", "v", "https://example.test/demo")
        assertEquals(0, players.size)
        assertFalse(owner.state.value.active)
        assertEquals("试听音频下载失败，请检查网络后重试。", owner.state.value.error)
    }

    @Test fun earlyCompletionKeepsPlayerAndStopButtonUntilTailGraceExpires() {
        val players = mutableListOf<Player>()
        var deferred: (() -> Unit)? = null
        var delay = 0L
        val owner = PersonalVoicePreview(
            factory = { Player().also { players.add(it) } },
            traceFactory = { VoiceDiagnostics("test", {}) },
            after = { ms, action -> delay = ms; deferred = action; {} },
            background = { it() },
            postToMain = { it() },
            fetch = { File.createTempFile("voice-preview", ".wav") },
        )
        owner.toggle("a", "v", "https://example.test/demo")
        players[0].ready(); players[0].position = 7324; players[0].completed()
        assertEquals(826L, delay)
        assertTrue(owner.state.value.active)
        assertEquals(0, players[0].releases)
        deferred!!()
        assertFalse(owner.state.value.active)
        assertEquals(1, players[0].releases)
    }

    @Test fun switchingOrStoppingDuringDrainInvalidatesDelayedRelease() {
        val players = mutableListOf<Player>()
        val callbacks = mutableListOf<() -> Unit>()
        var cancelled = 0
        val owner = PersonalVoicePreview(
            factory = { Player().also { players.add(it) } },
            traceFactory = { VoiceDiagnostics("test", {}) },
            after = { _, action -> callbacks.add(action); { cancelled++ } },
            background = { it() },
            postToMain = { it() },
            fetch = { File.createTempFile("voice-preview", ".wav") },
        )
        owner.toggle("a", "one", "https://example.test/one")
        players[0].ready(); players[0].completed(); players[0].completed()
        assertEquals(1, callbacks.size)
        owner.toggle("a", "two", "https://example.test/two")
        assertEquals(1, cancelled)
        callbacks[0]()
        assertTrue(owner.state.value.matches("a", "two"))
        assertEquals(0, players[1].releases)
        players[1].ready(); players[1].completed()
        owner.toggle("a", "two", "https://example.test/two")
        assertFalse(owner.state.value.active)
        assertEquals(1, players[1].releases)
        callbacks[1]()
        assertEquals(1, players[1].releases)
    }

    @Test fun drainGraceUsesObservedGapButIsBoundedForInvalidDurations() {
        assertEquals(826L, PersonalVoicePreview.drainDelayMs(3407, 2731))
        assertEquals(150L, PersonalVoicePreview.drainDelayMs(3407, 3407))
        assertEquals(150L, PersonalVoicePreview.drainDelayMs(-1, -1))
        assertEquals(150L, PersonalVoicePreview.drainDelayMs(1000, 2000))
        assertEquals(2150L, PersonalVoicePreview.drainDelayMs(Int.MAX_VALUE, 0))
    }

    @Test fun unknownRiffAndDataLengthsAreRewritten() {
        // 豆包 demo 的真实形态：RIFF 与 data 的 size 都是 0xFFFFFFFF。
        val payload = ByteArray(512) { (it and 0xFF).toByte() }
        val input = riffChunks(0xFFFFFFFFL,
            Triple("fmt ", 16L, ByteArray(16)),
            Triple("data", 0xFFFFFFFFL, payload))
        assertEquals(0xFFFFFFFFL, readUInt32(input, 4))
        assertEquals(0xFFFFFFFFL, readUInt32(input, DATA_SIZE_OFFSET))

        val output = normalizeWavLengths(input)

        assertEquals(input.size, output.size)
        assertEquals((output.size - 8).toLong(), readUInt32(output, 4))
        assertEquals(payload.size.toLong(), readUInt32(output, DATA_SIZE_OFFSET))
    }

    @Test fun oversizedDataLengthIsClampedToRemainingBytes() {
        val payload = ByteArray(300) { 7 }
        val input = riffChunks(0xFFFFFFFFL,
            Triple("fmt ", 16L, ByteArray(16)),
            Triple("data", 999_999L, payload))

        val output = normalizeWavLengths(input)

        assertEquals(input.size, output.size)
        assertEquals((output.size - 8).toLong(), readUInt32(output, 4))
        assertEquals(payload.size.toLong(), readUInt32(output, DATA_SIZE_OFFSET))
    }

    @Test fun standardWavIsUnchanged() {
        val payload = ByteArray(256) { 1 }
        val input = riffChunks((DATA_START + payload.size - 8).toLong(),
            Triple("fmt ", 16L, ByteArray(16)),
            Triple("data", payload.size.toLong(), payload))
        assertArrayEquals(input, normalizeWavLengths(input))
    }

    @Test fun trailingChunkAfterDataIsTruncated() {
        val payload = ByteArray(100) { 2 }
        val input = riffChunks(0xFFFFFFFFL,
            Triple("fmt ", 16L, ByteArray(16)),
            Triple("data", payload.size.toLong(), payload),
            Triple("LIST", 4L, ByteArray(4)))

        val output = normalizeWavLengths(input)

        assertEquals(DATA_START + payload.size, output.size)
        assertEquals((output.size - 8).toLong(), readUInt32(output, 4))
        assertEquals(payload.size.toLong(), readUInt32(output, DATA_SIZE_OFFSET))
    }

    @Test fun nonWavAndShortInputsAreReturnedAsIs() {
        val mp3 = "ID3\u0004\u0000\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(64) { 0x55 }
        assertSame(mp3, normalizeWavLengths(mp3))
        val short = byteArrayOf(0x52, 0x49, 0x46) // "RIF"
        assertSame(short, normalizeWavLengths(short))
        val riffWithoutWave = "RIFF\u0000\u0000\u0000\u0000NOPE".toByteArray(Charsets.US_ASCII)
        assertSame(riffWithoutWave, normalizeWavLengths(riffWithoutWave))
    }

    private fun riffChunks(riffSize: Long, vararg chunks: Triple<String, Long, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(uint32(riffSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        chunks.forEach { (id, declared, payload) ->
            out.write(id.toByteArray(Charsets.US_ASCII))
            out.write(uint32(declared))
            out.write(payload)
            if (payload.size % 2 == 1) out.write(0)
        }
        return out.toByteArray()
    }

    private fun uint32(value: Long) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private companion object {
        // RIFF(12) + fmt chunk(8 + 16) → data 声明长度在 40，payload 从 44 开始。
        const val DATA_SIZE_OFFSET = 40
        const val DATA_START = 44
    }
}
