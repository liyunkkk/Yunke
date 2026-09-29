package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHistoryImageHydratorTest {
    @Test
    fun listsMissingVideoPathForNonVideoModels() {
        val path = "/cache/missing-clip.mp4"
        val message = AgentModelClient.ConversationMessage(
            role = "user",
            content = "hello",
            contentJson = JSONArray().put(
                JSONObject()
                    .put("type", AgentHistoryImageHydrator.TYPE_VIDEO_FILE)
                    .put("path", path)
                    .put("mime", "video/mp4"),
            ).toString(),
        )
        val hydrated = AgentHistoryImageHydrator.hydrate(
            message = message,
            supportsVision = true,
            supportsVideo = false,
        )
        assertTrue(hydrated.content.contains("[用户视频] $path"))
        assertFalse(hydrated.contentJson.contains("video_url"))
    }

    @Test
    fun visionModelsAlsoGetTheAttachmentPath() {
        val file = java.io.File.createTempFile("eta-hydrate-", "-chat-image-1.jpg")
        try {
            val message = AgentModelClient.ConversationMessage(
                role = "user",
                content = "把图标改成这个",
                contentJson = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "把图标改成这个"))
                    .put(
                        JSONObject()
                            .put("type", AgentHistoryImageHydrator.TYPE_IMAGE_FILE)
                            .put("path", file.absolutePath)
                            .put("mime", "image/jpeg"),
                    ).toString(),
            )
            for (vision in listOf(true, false)) {
                val hydrated = AgentHistoryImageHydrator.hydrate(message, supportsVision = vision)
                // 看得到画面也要拿到真实路径，否则文件操作只能去猜路径。
                assertTrue(hydrated.content.contains("[用户图片] ${file.absolutePath}"))
                assertTrue(hydrated.contentJson.contains("[用户图片]"))
            }
        } finally {
            file.delete()
        }
    }
}
