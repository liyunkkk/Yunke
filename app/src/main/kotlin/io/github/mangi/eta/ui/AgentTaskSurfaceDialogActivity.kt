package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.device.AgentTaskPrompt
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import kotlinx.coroutines.launch

/**
 * 透明窗口里的“这次在哪里执行”弹窗，盖在当前任何应用之上。
 * 选完或取消后立即结束自己，工具线程在 [AgentTaskPrompt.awaitHostHidden] 等它退场再继续。
 */
class AgentTaskSurfaceDialogActivity : ComponentActivity() {
    private var requestId: String? = null
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_REQUEST_ID)
        if (id == null || AgentTaskPrompt.pending.value?.id != id) {
            finish()
            return
        }
        requestId = id
        AgentTaskPrompt.hostStarted()
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)
                val pending by AgentTaskPrompt.pending.collectAsState()
                LaunchedEffect(pending?.id) {
                    // run 已取消或别处已经回答：不再挂着一个过期弹窗。
                    if (pending?.id != id) finish()
                }
                AgentAppTheme(appearance = appearance, applyInterfaceScale = true) {
                    AgentTaskSurfaceChoiceDialog(
                        onChoose = ::answer,
                        onCancel = { answer(null) },
                    )
                }
            }
        }
    }

    private fun answer(mode: AgentTaskSurfaceMode?) {
        if (answered) return
        answered = true
        requestId?.let { AgentTaskPrompt.answer(it, mode) }
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        val id = requestId
        if (id != null) {
            // 窗口被系统或用户直接关掉也算取消，避免工具线程一直等。
            if (!answered && isFinishing && AgentTaskPrompt.pending.value?.id == id) {
                AgentTaskPrompt.answer(id, null)
            }
            AgentTaskPrompt.hostStopped()
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "io.github.mangi.eta.extra.TASK_SURFACE_REQUEST"

        fun launch(context: Context, requestId: String) {
            val intent = Intent(context, AgentTaskSurfaceDialogActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { context.startActivity(intent) }.onFailure { throwable ->
                // 后台启动受限时退回应用内弹窗，回到代鱼即可看到。
                AndroidAgentLogger.warnThrottled("task_surface_dialog_launch_failed") {
                    "Task surface dialog launch failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }
}
