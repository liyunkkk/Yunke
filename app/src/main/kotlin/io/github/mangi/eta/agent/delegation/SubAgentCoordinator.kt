package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentImageGenerationOptions
import io.github.mangi.eta.agent.model.ImageGenerationParameterException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Owned by a single parent run. Terminal state cannot be overwritten by a late worker. */
internal class SubAgentCoordinator(
    private val workers: List<AgentModelClient.ModelConfig>,
    private val timeoutMs: Long = 180_000,
    private val compressionTimeoutMs: Long = 180_000,
    private val videoTimeoutMs: Long = 600_000,
    private val roles: List<String> = List(workers.size) { "research" },
    private val workspace: SubAgentWorkspace? = null,
    private val executeWorkspaceChild: ((AgentModelClient.ModelConfig, String, AgentRunController, String, String, Boolean) -> String)? = null,
    private val onContext: (SubAgentContextStats) -> Unit = {},
    private val executeObservedChild: ((AgentModelClient.ModelConfig, String, AgentRunController, String, String?, Boolean, (io.github.mangi.eta.agent.runtime.AgentEvent) -> Unit) -> String)? = null,
    private val workerIds: List<String> = workers.indices.map { "worker-${it + 1}" },
    private val workerNames: List<String> = workerIds,
    private val workerModelIds: List<String> = List(workers.size) { "" },
    private val prepareManualCompactor: (AgentModelClient.ModelConfig) -> AgentModelClient.ModelConfig = { it },
    private val executeVideoChild: ((AgentModelClient.ModelConfig, String, AgentRunController) -> String)? = null,
    private val executeImageChild: ((AgentModelClient.ModelConfig, String, AgentRunController, AgentImageGenerationOptions) -> String)? = null,
    private val executeChild: (AgentModelClient.ModelConfig, String, AgentRunController) -> String,
) : AutoCloseable {
    init { require(workers.isNotEmpty() && roles.size == workers.size && workerIds.size == workers.size && workerIds.distinct().size == workers.size && workerNames.size == workers.size && workerModelIds.size == workers.size) }

    private class Task(val id: String, val worker: Int, val role: String, val project: String, @Volatile var workspaceId: String? = null) {
        lateinit var context: SubAgentContextTracker
        lateinit var clock: SubAgentExecutionClock
        @Volatile var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
        @Volatile var workspacePath = ""
        @Volatile var executing = false

        val dispatchGate = java.util.concurrent.CountDownLatch(1)
        val controller = AgentRunController()
        @Volatile var state = "queued"
        @Volatile var result = ""
        @Volatile var errorCode = ""
        @Volatile var future: Future<*>? = null
    }
    // Lazy single-thread executors enforce one live task per configured identity, including cleanup.
    private val pools = workers.map { Executors.newSingleThreadExecutor() }
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val tasks = linkedMapOf<String, Task>()
    private var closed = false

    fun requestCompact(taskId: String, keepRecent: Int?, model: AgentModelClient.ModelConfig?): Boolean {
        val task = synchronized(this) { if (closed) null else tasks[taskId] } ?: return false
        return synchronized(task) {
            if (task.state != "running" || task.role in setOf("image_generation", "video_generation")) {
                publishContext(task.context.manualRequest("ended"))
                false
            } else if (task.context.value.isCompacting || task.context.value.manualCompactionState == "pending") {
                true
            } else {
                val accepted = task.controller.requestCompact(keepRecent, model ?: prepareManualCompactor(workers[task.worker]))
                publishContext(task.context.manualRequest(if (accepted) "pending" else "ended"))
                accepted
            }
        }
    }

    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val json = try {
            val args = JSONObject(call.argumentsJson)
            when (call.name) {
                "delegate_task" -> start(args)
                "get_task_result" -> get(args)
                "manage_agent_workspace" -> manage(args)
                "cancel_task" -> {
                    val task = find(args.getString("task_id"))
                    stop(task, "cancelled")
                    snapshot(task)
                }
                else -> error("Unknown delegation tool")
            }
        } catch (error: ImageGenerationParameterException) {
            JSONObject().put("ok", false).put("code", "IMAGE_GENERATION_INVALID_OPTIONS").put("message", error.message)
        } catch (_: IllegalArgumentException) {
            JSONObject().put("ok", false).put("code", "INVALID_TASK_ARGUMENTS")
        } catch (_: org.json.JSONException) {
            JSONObject().put("ok", false).put("code", "INVALID_TASK_ARGUMENTS")
        }
        // Child evidence may contain sensitive tool output. Do not persist raw returned text.
        return AgentModelClient.ToolResult(json.toString(), sensitive = true)
    }

    private fun start(args: JSONObject): JSONObject {
        val task = synchronized(this) {
            if (closed) return errorResult("RUN_CLOSED")
            val instruction = args.getString("task")
            val context = args.optString("context")
            require(instruction.isNotBlank() && instruction.length <= 12000 && context.length <= 20000)
            val role = args.optString("role", "research")
            require(role in setOf("research", "implementation", "review", "summary", "image_generation", "video_generation"))
            val workerById = args.optString("agent_id").takeIf { it.isNotBlank() }?.let { workerIds.indexOf(it) }
            if (workerById != null && workerById < 0) return errorResult("AGENT_NOT_CONFIGURED")
            if (workerById != null && args.has("worker") && workerById != args.getInt("worker") - 1) return errorResult("WORKER_ID_MISMATCH")
            val worker = workerById ?: if (args.has("worker")) args.getInt("worker") - 1 else {
                val desired = if (role == "summary") "review" else role
                val candidates = if (role == "research") roles.indices.filter { roles[it] !in setOf("image_generation", "video_generation") } else roles.indices.filter { roles[it] == desired }
                candidates.minByOrNull { candidate -> tasks.values.count { it.worker == candidate && (it.state in setOf("queued", "running") || it.executing) } }
                    ?: return errorResult("ROLE_NOT_CONFIGURED")
            }
            require(worker in workers.indices)
            if (role == "research" && roles[worker] in setOf("image_generation", "video_generation")) return errorResult("WORKER_ROLE_MISMATCH")
            if (role != "research" && roles[worker] != (if (role == "summary") "review" else role)) return errorResult("WORKER_ROLE_MISMATCH")
            val imageOptions = if (args.has("image_options")) {
                if (role != "image_generation") AgentImageGenerationOptions.invalid("image_options 仅适用于 image_generation。")
                val options = args.optJSONObject("image_options") ?: AgentImageGenerationOptions.invalid("image_options 必须是对象。")
                AgentImageGenerationOptions.fromJson(options).also { it.applyTo(JSONObject(), workers[worker].model) }
            } else AgentImageGenerationOptions()
            val project = args.optString("project")
            val workspaceId = args.optString("workspace_id").ifBlank { null }
            if (role in setOf("image_generation", "video_generation")) {
                require(project.isBlank() && workspaceId == null)
                if (role == "image_generation" && executeImageChild == null) return errorResult("IMAGE_GENERATION_UNAVAILABLE")
                if (role == "video_generation" && executeVideoChild == null) return errorResult("VIDEO_GENERATION_UNAVAILABLE")
            }
            if (role == "implementation" || workspaceId != null) {
                require(workspace != null && executeWorkspaceChild != null && Regex("/workspace/[^/]+").matches(project))
            }
            if (workspaceId != null && tasks.values.any { it.project == project && it.workspaceId == workspaceId && (it.state in setOf("queued", "running") || it.executing) }) return errorResult("WORKSPACE_IN_USE")
            require(role != "implementation" || workspaceId == null)
            val task = Task(UUID.randomUUID().toString(), worker, role, project, workspaceId)
            val model = workers[worker]
            task.context = SubAgentContextTracker(SubAgentContextStats(task.id, worker + 1, role, model.model,
                model.modelDisplayName.ifBlank { model.model }, model.providerName, model.contextWindow, status = "queued", agentId = workerIds[worker], agentName = workerNames[worker], providerId = model.providerId, modelId = workerModelIds[worker]))
            tasks[task.id] = task
            task.future = pools[worker].submit {
                try { task.dispatchGate.await() } catch (_: InterruptedException) { return@submit }
                synchronized(task) {
                    if (task.state != "queued") return@submit
                    task.executing = true
                    task.state = "running"
                    task.clock = SubAgentExecutionClock(if (role == "video_generation") videoTimeoutMs else timeoutMs, compressionTimeoutMs)
                    publishContext(task.context.start())
                }
                var ownsWorkspaceLease = false
                try {
                    task.watchdog = timer.scheduleAtFixedRate({
                        val expired = synchronized(task) { if (task.state == "running") task.clock.expired() else null }
                        if (expired != null) stop(task, "timed_out", expired)
                        if (task.state != "running") task.watchdog?.cancel(false)
                    }, minOf(timeoutMs, 100L).coerceAtLeast(1), 50, TimeUnit.MILLISECONDS)
                    task.controller.throwIfCancelled()
                    if (role == "implementation") {
                        val prepared = workspace!!.requireOperation(project, "prepare")
                        task.workspaceId = prepared.getString("id")
                        ownsWorkspaceLease = true
                        task.workspacePath = prepared.getString("path")
                    } else if (workspaceId != null) {
                        val existing = workspace!!.requireOperation(project, "begin_review", workspaceId)
                        check(existing.getString("state") == "reviewing")
                        ownsWorkspaceLease = true
                        task.workspacePath = existing.getString("path")
                    }
                    task.controller.throwIfCancelled()
                    val prompt = "Role: $role\nTask:\n$instruction\n\nContext supplied by main agent:\n$context"
                    val answer = if (role in setOf("image_generation", "video_generation")) {
                        val mediaPrompt = instruction + if (context.isBlank()) "" else "\n\n补充要求：\n$context"
                        if (role == "video_generation") executeVideoChild!!.invoke(workers[worker], mediaPrompt, task.controller)
                        else executeImageChild!!.invoke(workers[worker], mediaPrompt, task.controller, imageOptions)
                    } else if (executeObservedChild != null) {
                        executeObservedChild.invoke(workers[worker], prompt, task.controller, project, task.workspaceId, role == "implementation") { event ->
                            synchronized(task) {
                                if (task.state == "running") task.context.accept(event)?.let { stats ->
                                    task.clock.setCompacting(stats.isCompacting)
                                    publishContext(stats)
                                }
                            }
                        }
                    } else task.workspaceId?.let { id ->
                        executeWorkspaceChild!!.invoke(workers[worker], prompt, task.controller, project, id, role == "implementation")
                    } ?: executeChild(workers[worker], prompt, task.controller)
                    task.controller.throwIfCancelled()
                    if (role == "implementation") workspace!!.requireOperation(project, "seal", task.workspaceId)
                    else if (task.workspaceId != null) workspace!!.requireOperation(project, if (role == "review") "review" else "end_review", task.workspaceId)
                    task.controller.throwIfCancelled()
                    synchronized(task) {
                        check(task.state == "running") { "Task stopped during workspace finalization" }
                        if (task.state == "running") {
                            task.result = answer.take(16000) + if (answer.length > 16000) "\n[结果已截断]" else ""
                            task.state = "completed"
                            task.context.finish(task.state)
                        }
                    }
                } catch (error: Exception) {
                    // Future.cancel interrupts the worker. Clear only for bounded cleanup, then restore.
                    val interrupted = Thread.interrupted()
                    if (ownsWorkspaceLease && task.workspaceId != null) runCatching { workspace?.operation(project, if (role == "implementation") "fail" else "end_review", task.workspaceId) }
                    if (interrupted) Thread.currentThread().interrupt()
                    synchronized(task) {
                        if (task.state == "running") {
                            task.errorCode = when (error) {
                                is ImageGenerationParameterException -> "IMAGE_GENERATION_INVALID_OPTIONS"
                                is SubAgentContextLimitException -> "SUB_AGENT_CONTEXT_LIMIT"
                                is WorkspaceOperationException -> error.code
                                else -> when (role) {
                                    "image_generation" -> "IMAGE_GENERATION_FAILED"
                                    "video_generation" -> "VIDEO_GENERATION_FAILED"
                                    else -> ""
                                }
                            }
                            task.result = if (error is ImageGenerationParameterException) error.message.orEmpty()
                            else if (error is SubAgentContextLimitException)
                                "子代理上下文不足，自动压缩不可用或未能释放足够空间。请拆分任务或调整模型窗口后重新委派；已有工作树改动保留。"
                            else "子代理未完成，请主代理接手或重新委派。"
                            task.state = "failed"
                            task.context.finish(task.state)
                        }
                    }
                } finally {
                    task.watchdog?.cancel(false)
                    synchronized(task) { publishContext(task.context.finish(task.state)) }
                    task.executing = false
                }
            }
            task
        }
        // Never call an external telemetry sink while holding the coordinator lock.
        // The gate preserves queued -> running event order without charging queue time.
        synchronized(task) { publishContext(task.context.value) }
        task.dispatchGate.countDown()
        return snapshot(task)
    }
    // Telemetry failure must never prevent cancellation or change a task outcome.
    private fun publishContext(stats: SubAgentContextStats) { runCatching { onContext(stats) } }
    @Synchronized private fun find(id: String): Task = requireNotNull(tasks[id]) { "Task does not belong to this run" }
    private fun get(args: JSONObject): JSONObject {
        // Compaction may redact old sensitive tool replies. Rediscover task IDs without replaying result bodies.
        if (!args.has("task_id")) {
            val all = synchronized(this) { tasks.values.toList().asReversed() }
            val offset = args.optInt("offset", 0).coerceAtLeast(0)
            val page = all.drop(offset).take(20).map { task -> synchronized(task) {
                JSONObject().put("task_id", task.id).put("status", task.state).put("role", task.role)
                    .put("worker", task.worker + 1).put("agent_id", workerIds[task.worker])
            } }
            return JSONObject().put("ok", true).put("tasks", org.json.JSONArray(page)).put("total", all.size)
                .put("next_offset", if (offset + page.size < all.size) offset + page.size else JSONObject.NULL)
        }
        val task = find(args.getString("task_id"))
        val wait = args.optLong("wait_ms", 0).coerceIn(0, 10000)
        if (wait > 0 && task.state in setOf("queued", "running")) {
            try { task.future?.get(wait, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
            catch (_: java.util.concurrent.CancellationException) { }
            catch (_: java.util.concurrent.ExecutionException) { }
        }
        return snapshot(task)
    }
    private fun stop(task: Task, state: String, errorCode: String = "") {
        synchronized(task) {
            if (task.state !in setOf("queued", "running")) return
            task.state = state
            task.errorCode = errorCode
            task.watchdog?.cancel(false)
            publishContext(task.context.finish(state))
        }
        task.controller.cancel()
        task.future?.cancel(true)
    }
    private fun snapshot(task: Task): JSONObject = synchronized(task) {
        JSONObject().put("ok", true).put("task_id", task.id).put("worker", task.worker + 1)
            .put("agent_id", workerIds[task.worker]).put("agent_name", workerNames[task.worker])
            .put("status", task.state).put("result", task.result)
            .put("context_usage", task.context.value.copy(status = task.state,
                isCompacting = task.state == "running" && task.context.value.isCompacting).toJson())
            .put("role", task.role).put("project", task.project).put("error_code", task.errorCode)
            .put("workspace_id", task.workspaceId ?: JSONObject.NULL).put("workspace_path", task.workspacePath)
            .put("review_required", true)
    }
    @Synchronized private fun manage(args: JSONObject): JSONObject {
        if (closed) return errorResult("RUN_CLOSED")
        val backend = workspace ?: return errorResult("WORKSPACE_UNAVAILABLE")
        val action = args.getString("action")
        require(action in setOf("list", "inspect", "merge", "discard"))
        val project = args.getString("project")
        val id = args.optString("workspace_id").ifBlank { null }
        if (tasks.values.any { it.project == project && (id == null || it.workspaceId == id) && (it.state in setOf("queued", "running") || it.executing) }) return errorResult("WORKSPACE_IN_USE")
        return backend.operation(project, action, id)
    }
    private fun errorResult(code: String) = JSONObject().put("ok", false).put("code", code)
    override fun close() {
        val owned = synchronized(this) {
            closed = true
            tasks.values.toList()
        }
        owned.forEach { stop(it, "cancelled") }
        pools.forEach { it.shutdownNow() }
        timer.shutdownNow()
    }
}
