package io.github.mangi.eta.agent.media

import android.app.Application
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AgentChatImageCacheTest {
    @Test
    fun stagesBytesAndRemovesConversationAndOrphans() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val kept = cache.stage("conv-keep", byteArrayOf(1, 2, 3, 4), "photo.jpg")!!
        val orphan = cache.stage("conv-orphan", byteArrayOf(5, 6, 7, 8), "other.png")!!

        assertTrue(File(kept.absolutePath).isFile)
        assertTrue(File(orphan.absolutePath).isFile)
        assertTrue(kept.absolutePath.contains(AgentChatImageCache.CACHE_DIRECTORY))
        assertFalse(kept.absolutePath.contains("workspace"))

        cache.deleteOrphans(setOf("conv-keep"))
        assertTrue(File(kept.absolutePath).isFile)
        assertFalse(File(orphan.absolutePath).exists())

        cache.deleteConversation("conv-keep")
        assertFalse(File(kept.absolutePath).exists())
    }

    @Test
    fun readBytesLoadsStagedFileAndDataUrl() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val bytes = byteArrayOf(9, 8, 7, 6)
        val staged = cache.stage("conv-read", bytes, "photo.jpg")!!
        assertEquals(bytes.toList(), AgentChatImageCache.readBytes(staged.absolutePath)!!.toList())
        val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        assertEquals(bytes.toList(), AgentChatImageCache.readBytes(dataUrl)!!.toList())
    }

    @Test
    fun stageFromFileCopiesVideoBytes() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val source = File(context.cacheDir, "clip.mp4")
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        source.writeBytes(payload)
        val staged = cache.stageFromFile("conv-video", source, "chat-video-1.mp4")!!
        assertTrue(File(staged.absolutePath).isFile)
        assertEquals(payload.toList(), File(staged.absolutePath).readBytes().toList())
        assertTrue(staged.displayName.contains("chat-video-1.mp4") || staged.displayName.endsWith("mp4"))
    }
    @Test
    fun workdirAttachmentAliasOnlyResolvesCurrentConversation() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val mine = cache.stage("conv-a", byteArrayOf(1, 2, 3, 4), "chat-image-1.jpg")!!
        File(mine.absolutePath).setLastModified(1_000L)
        // 别的会话里有一张更新的同名图，过去会被当成当前会话的附件。
        val others = cache.stage("conv-b", byteArrayOf(5, 6, 7, 8), "chat-image-1.jpg")!!
        File(others.absolutePath).setLastModified(2_000L)

        assertEquals(
            mine.absolutePath,
            cache.resolveReadableFile("/home/workdir/attachments/image.jpg", "conv-a")!!.absolutePath,
        )
        assertEquals(
            mine.absolutePath,
            cache.resolveReadableFile("/home/workdir/attachments/chat-image-1.jpg", "conv-a")!!.absolutePath,
        )
        assertEquals(
            others.absolutePath,
            cache.resolveReadableFile("/home/workdir/attachments/image.jpg", "conv-b")!!.absolutePath,
        )
        assertEquals(mine.absolutePath, cache.resolveReadableFile(mine.absolutePath, "conv-a")!!.absolutePath)
        assertEquals(null, cache.resolveReadableFile("/home/workdir/attachments/missing.png", "conv-a"))
        assertEquals(null, cache.resolveReadableFile("/tmp/eta-missing-image-does-not-exist.jpg", "conv-a"))
    }

    @Test
    fun aliasNeverFallsBackToAnotherConversation() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        cache.stage("conv-other", byteArrayOf(1, 2, 3, 4), "chat-image-1.jpg")!!

        assertEquals(null, cache.resolveReadableFile("/home/workdir/attachments/image.jpg", "conv-empty"))
        assertEquals(null, cache.resolveReadableFile("/home/workdir/attachments/image.jpg", ""))
    }

    @Test
    fun genericAliasAlsoMatchesLaterImagesInTheSameMessage() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val second = cache.stage("conv-two", byteArrayOf(1, 2, 3, 4), "chat-image-2.png")!!

        assertEquals(
            second.absolutePath,
            cache.resolveReadableFile("/home/workdir/attachments/image.png", "conv-two")!!.absolutePath,
        )
    }

    @Test
    fun absolutePathIntoAnotherConversationIsRejected() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AgentChatImageCache(context)
        val others = cache.stage("conv-b", byteArrayOf(5, 6, 7, 8), "chat-image-1.jpg")!!
        val mine = cache.stage("conv-a", byteArrayOf(1, 2, 3, 4), "chat-image-1.jpg")!!

        assertTrue(cache.isOtherConversationAttachment(others.absolutePath, "conv-a"))
        assertFalse(cache.isOtherConversationAttachment(mine.absolutePath, "conv-a"))
        assertEquals(null, cache.resolveReadableFile(others.absolutePath, "conv-a"))

        // 同一文件的另一种应用数据根写法也要识别出来。
        val alternate = "/data/data/${context.packageName}/cache/${AgentChatImageCache.CACHE_DIRECTORY}/conv-b/x.jpg"
        assertTrue(cache.isOtherConversationAttachment(alternate, "conv-a"))
        assertFalse(cache.isOtherConversationAttachment(alternate.replace("conv-b", "conv-a"), "conv-a"))

        // 缓存目录外的普通文件、工作区里恰好同名的目录，以及没有会话归属的 run 都不受限制。
        assertFalse(cache.isOtherConversationAttachment("/sdcard/Download/photo.jpg", "conv-a"))
        assertFalse(cache.isOtherConversationAttachment("/workspace/eta-chat-images/conv-b/x.jpg", "conv-a"))
        assertFalse(cache.isOtherConversationAttachment(others.absolutePath, ""))
    }
}
