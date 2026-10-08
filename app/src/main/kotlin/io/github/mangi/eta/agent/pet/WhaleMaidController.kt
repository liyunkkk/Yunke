package io.github.mangi.eta.agent.pet

import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.Executors

internal object WhaleMaidController {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-whale-maid").apply { isDaemon = true }
    }
    @Volatile private var pendingWorkTitle: String? = null
    @Volatile private var hostInForeground = false

    fun restore(context: Context) {
        val app = context.applicationContext
        if (WhaleMaidStore.isEnabled(app)) show(app)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        WhaleMaidStore.setEnabled(app, enabled)
        if (enabled) syncVisibility(app) else hide(app)
    }

    fun setWorkSpeechEnabled(context: Context, enabled: Boolean) {
        WhaleMaidStore.setWorkSpeechEnabled(context.applicationContext, enabled)
    }

    fun setGlobalVisible(context: Context, enabled: Boolean) {
        WhaleMaidStore.setGlobalVisible(context.applicationContext, enabled)
        syncVisibility(context.applicationContext)
    }

    fun onHostVisibility(context: Context, visible: Boolean) {
        hostInForeground = visible
        syncVisibility(context.applicationContext)
    }

    private fun syncVisibility(app: Context) {
        if (!WhaleMaidStore.isEnabled(app)) return
        if (WhaleMaidStore.globalVisible(app) || hostInForeground) show(app) else hide(app)
    }

    fun feed(context: Context, tokens: Int, foodName: String) {
        val app = context.applicationContext
        if (!WhaleMaidStore.isEnabled(app) || WhaleMaidStore.snapshot(app).thinking) return
        WhaleMaidStore.addSatiety(app, tokens)
        WhaleMaidStore.beginThinking(app)
        show(app)
        executor.execute {
            val snapshot = WhaleMaidStore.snapshot(app)
            val (reaction, used) = WhaleMaidSpeaker.speak(
                context = app,
                eventType = "feed",
                satiety = snapshot.satiety,
                foodName = foodName,
                tokensGained = tokens,
                sessionTitle = "",
                recentTasks = snapshot.recentTasks.map { it.title },
                memories = snapshot.memories.map { it.speech },
            )
            WhaleMaidStore.consume(app, used)
            WhaleMaidStore.recordMemory(app, WhaleMaidMemory(
                mood = reaction.mood,
                speech = reaction.speech,
                tokensUsed = used,
                atMillis = System.currentTimeMillis(),
                eventType = "feed",
            ))
            WhaleMaidStore.present(app, reaction)
            if (pendingWorkTitle != null) deliverWork(app)
        }
    }

    fun onWorkDone(context: Context, title: String) {
        val app = context.applicationContext
        if (!WhaleMaidStore.isEnabled(app) || !WhaleMaidStore.workSpeechEnabled(app)) return
        pendingWorkTitle = title
        show(app)
        executor.execute { deliverWork(app) }
    }

    fun setModelSelection(context: Context, custom: Boolean, providerId: String, modelId: String) {
        WhaleMaidStore.setModelSelection(
            context.applicationContext,
            io.github.mangi.eta.agent.model.ModelFeatureSelection(custom, providerId, modelId),
        )
    }

    fun dismissSpeech(context: Context) {
        WhaleMaidStore.dismissSpeech(context.applicationContext)
    }

    fun finishPose(context: Context, mood: String) {
        WhaleMaidStore.finishPose(context.applicationContext, mood)
    }

    fun setScale(context: Context, scale: Float) {
        WhaleMaidStore.setScale(context.applicationContext, scale)
    }

    fun setPosition(context: Context, x: Int, y: Int) {
        WhaleMaidStore.setPosition(context.applicationContext, x, y)
    }

    fun clearMemories(context: Context) {
        WhaleMaidStore.clearMemories(context.applicationContext)
    }

    private fun deliverWork(app: Context) {
        val title = pendingWorkTitle ?: return
        pendingWorkTitle = null
        if (!WhaleMaidStore.isEnabled(app) || !WhaleMaidStore.workSpeechEnabled(app)) return
        val before = WhaleMaidStore.snapshot(app)
        if (before.satiety <= 0) return
        WhaleMaidStore.rememberTask(app, title)
        WhaleMaidStore.beginThinking(app)
        val snapshot = WhaleMaidStore.snapshot(app)
        val (reaction, used) = WhaleMaidSpeaker.speak(
            context = app,
            eventType = "work_done",
            satiety = snapshot.satiety,
            foodName = "",
            tokensGained = 0,
            sessionTitle = title,
            recentTasks = snapshot.recentTasks.map { it.title },
            memories = snapshot.memories.map { it.speech },
        )
        WhaleMaidStore.consume(app, used)
        WhaleMaidStore.recordMemory(app, WhaleMaidMemory(
            mood = reaction.mood,
            speech = reaction.speech,
            tokensUsed = used,
            atMillis = System.currentTimeMillis(),
            eventType = "work_done",
        ))
        WhaleMaidStore.present(app, reaction)
        if (pendingWorkTitle != null) deliverWork(app)
    }

    private fun show(context: Context) {
        if (!Settings.canDrawOverlays(context)) return
        if (WhaleMaidOverlayService.isRunning()) return
        val intent = Intent(context, WhaleMaidOverlayService::class.java)
            .setAction(WhaleMaidOverlayService.ACTION_SHOW)
        runCatching { context.startService(intent) }
            .onFailure { throwable ->
                AndroidAgentLogger.warn("Whale maid overlay start failed: type=${throwable.javaClass.simpleName}")
            }
    }

    private fun hide(context: Context) {
        context.stopService(Intent(context, WhaleMaidOverlayService::class.java))
    }
}
