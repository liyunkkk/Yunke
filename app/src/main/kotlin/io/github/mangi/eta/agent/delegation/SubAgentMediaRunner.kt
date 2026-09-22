package io.github.mangi.eta.agent.delegation

import android.content.Context
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.model.MediaReasoningSettings
import io.github.mangi.eta.agent.model.AgentImageGenerationOptions
import io.github.mangi.eta.agent.model.AgentImageGenerationClient
import io.github.mangi.eta.agent.model.AgentImageGenerationParser
import io.github.mangi.eta.agent.model.AgentVideoGenerationClient
import io.github.mangi.eta.agent.model.AgentVideoGenerationParser
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.runBlocking

/** Media workers return real cached files, never a pretend text answer or a shell-capable model loop. */
internal object SubAgentMediaRunner {
    fun run(context: Context, conversationId: String, config: AgentModelClient.ModelConfig,
        prompt: String, controller: AgentRunController, video: Boolean,
        imageOptions: AgentImageGenerationOptions = AgentImageGenerationOptions()): String {
        controller.throwIfCancelled()
        val mediaConfig = MediaReasoningSettings.apply(config,
            if (video) "video_generation" else "image_generation", config.reasoningEffort)
        val cache = AgentChatImageCache(context)
        val markdown = if (video) {
            val generated = runBlocking { AgentVideoGenerationClient(runController = controller).generate(mediaConfig, prompt,
                transport = MediaReasoningSettings.resolve(config, "video_generation").transport) }
            controller.throwIfCancelled()
            val paths = generated.videos.mapIndexedNotNull { index, item ->
                controller.throwIfCancelled()
                cache.stage(conversationId, item.bytes,
                    "subagent-generated-${index + 1}.${AgentVideoGenerationParser.extensionForMime(item.mimeType)}",
                    maxBytes = AgentVideoGenerationClient.MAX_GENERATED_VIDEO_BYTES)?.absolutePath
            }
            check(paths.isNotEmpty()) { "VIDEO_GENERATION_EMPTY" }
            // Put artifacts before optional provider prose so bounded task results cannot truncate the files away.
            AgentVideoGenerationParser.markdown(paths) + generated.text.takeIf { it.isNotBlank() }?.let { "\n\n${it.take(4000)}" }.orEmpty()
        } else {
            val generated = AgentImageGenerationClient(runController = controller).generate(mediaConfig, prompt, options = imageOptions)
            controller.throwIfCancelled()
            val paths = generated.images.mapIndexedNotNull { index, item ->
                controller.throwIfCancelled()
                cache.stage(conversationId, item.bytes,
                    "subagent-generated-${index + 1}.${AgentImageGenerationParser.extensionForMime(item.mimeType)}")?.absolutePath
            }
            check(paths.isNotEmpty()) { "IMAGE_GENERATION_EMPTY" }
            AgentImageGenerationParser.markdown(paths) + generated.text.takeIf { it.isNotBlank() }?.let { "\n\n${it.take(4000)}" }.orEmpty()
        }
        controller.throwIfCancelled()
        return markdown
    }
}
