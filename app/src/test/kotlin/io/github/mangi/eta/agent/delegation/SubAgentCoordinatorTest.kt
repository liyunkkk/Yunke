package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubAgentCoordinatorTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test", model = "child", systemPrompt = "")
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("call", name, args.toString())
    private fun start(c: SubAgentCoordinator) = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "check evidence"))).content)
    private fun get(c: SubAgentCoordinator, id: String, wait: Int = 1000) = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id).put("wait_ms", wait))).content)

    @Test fun dynamicWorkersRunInParallelAndBusyWorkerQueues() {
        val started = CountDownLatch(6)
        val release = CountDownLatch(1)
        SubAgentCoordinator(List(6) { model }) { _, _, _ -> started.countDown(); release.await(); "done" }.use { c ->
            val ids = List(6) { start(c).getString("task_id") }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val queued = start(c)
            assertEquals("queued", queued.getString("status"))
            SubAgentCoordinator(listOf(model)) { _, _, _ -> "other" }.use { other ->
                assertFalse(get(other, ids.first(), 0).getBoolean("ok"))
            }
            release.countDown()
            (ids + queued.getString("task_id")).forEach { assertEquals("completed", get(c, it).getString("status")) }
        }
    }

    @Test fun queuedCancellationAndCancelledRunningCleanupKeepWorkerExclusive() {
        val started = CountDownLatch(1)
        val cleaning = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val called = java.util.Collections.synchronizedList(mutableListOf<String>())
        SubAgentCoordinator(listOf(model)) { _, prompt, _ ->
            called += prompt
            if (prompt.contains("first")) {
                started.countDown()
                try { CountDownLatch(1).await() } catch (_: InterruptedException) { cleaning.countDown() }
                finishCleanup.await()
            }
            "done"
        }.use { c ->
            fun submit(text: String) = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", text))).content).getString("task_id")
            val first = submit("first")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val skip = submit("skip")
            val next = submit("next")
            c.execute(call("cancel_task", JSONObject().put("task_id", skip)))
            c.execute(call("cancel_task", JSONObject().put("task_id", first)))
            assertTrue(cleaning.await(2, TimeUnit.SECONDS))
            assertEquals("queued", get(c, next, 0).getString("status"))
            assertEquals(1, called.size)
            finishCleanup.countDown()
            assertEquals("completed", get(c, next).getString("status"))
            assertEquals("cancelled", get(c, skip, 0).getString("status"))
            assertEquals(2, called.size)
            assertTrue(called.none { it.contains("skip") })
        }
    }

    @Test fun queuedTimeDoesNotConsumeExecutionBudgetAndIdentityIsStable() {
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), timeoutMs = 150, workerIds = listOf("stable-id"), workerNames = listOf("执行A"),
            executeChild = { _, prompt, _ ->
                if (prompt.contains("first")) {
                    firstStarted.countDown()
                    try { release.await() } catch (_: InterruptedException) { release.await() }
                }
                "done"
            }).use { c ->
            val first = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "first").put("agent_id", "stable-id"))).content)
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
            val queued = start(c).getString("task_id")
            Thread.sleep(250)
            assertEquals("timed_out", get(c, first.getString("task_id"), 0).getString("status"))
            assertEquals("queued", get(c, queued, 0).getString("status"))
            release.countDown()
            val done = get(c, queued)
            assertEquals("completed", done.getString("status"))
            assertEquals("stable-id", done.getJSONObject("context_usage").getString("agent_id"))
            assertEquals("AGENT_NOT_CONFIGURED", JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "x").put("agent_id", "deleted"))).content).getString("code"))
        }
    }

    @Test fun telemetryCanQueryTaskWithoutCoordinatorTaskLockInversion() {
        lateinit var c: SubAgentCoordinator
        val observed = CountDownLatch(2)
        c = SubAgentCoordinator(listOf(model), onContext = { stats ->
            val result = get(c, stats.taskId, 0)
            if (result.getBoolean("ok")) observed.countDown()
        }, executeChild = { _, _, _ -> "done" })
        c.use {
            val id = start(c).getString("task_id")
            assertEquals("completed", get(c, id).getString("status"))
            assertTrue(observed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun imageAndVideoRolesUseDedicatedGeneratorsAndNeverReceiveResearchOrWorkspaceTasks() {
        val invoked = mutableListOf<String>()
        SubAgentCoordinator(listOf(model, model, model), roles = listOf("image_generation", "video_generation", "implementation"),
            executeImageChild = { _, prompt, _, _ -> invoked += "image:$prompt"; "![generated](/cache/image.png)" },
            executeVideoChild = { _, prompt, _ -> invoked += "video:$prompt"; "![generated-video](/cache/video.mp4)" },
            executeChild = { _, _, _ -> invoked += "research"; "analysis" }).use { c ->
            fun submit(role: String, extras: JSONObject = JSONObject()): JSONObject = JSONObject(c.execute(call("delegate_task",
                extras.put("task", "draw a lake").put("role", role))).content)
            val image = submit("image_generation")
            assertTrue(get(c, image.getString("task_id")).getString("result").contains("image.png"))
            val video = submit("video_generation")
            assertTrue(get(c, video.getString("task_id")).getString("result").contains("video.mp4"))
            val research = submit("research")
            assertEquals(3, research.getInt("worker"))
            assertEquals("completed", get(c, research.getString("task_id")).getString("status"))
            assertEquals(listOf("image:draw a lake", "video:draw a lake", "research"), invoked)
            assertEquals("WORKER_ROLE_MISMATCH", submit("research", JSONObject().put("worker", 1)).getString("code"))
            assertEquals("INVALID_TASK_ARGUMENTS", submit("image_generation", JSONObject().put("project", "/workspace/p")).getString("code"))
        }
    }

    @Test fun unconfiguredMediaRunnerDoesNotFallBackToTextOrResearch() {
        SubAgentCoordinator(listOf(model), roles = listOf("video_generation")) { _, _, _ -> error("must not run") }.use { c ->
            val response = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "video").put("role", "video_generation"))).content)
            assertEquals("VIDEO_GENERATION_UNAVAILABLE", response.getString("code"))
            assertEquals("ROLE_NOT_CONFIGURED", start(c).getString("code"))
        }
    }

    @Test fun manualParentCompactionDoesNotCompressPauseOrCancelChildren() {
        val parent = io.github.mangi.eta.agent.runtime.AgentRunController()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val children = java.util.Collections.synchronizedList(mutableListOf<io.github.mangi.eta.agent.runtime.AgentRunController>())
        SubAgentCoordinator(listOf(model, model)) { _, _, child ->
            children += child
            started.countDown()
            release.await()
            "done"
        }.use { c ->
            val parentBinding = parent.register { c.close() }
            try {
                val first = start(c).getString("task_id")
                val second = start(c).getString("task_id")
                assertTrue(started.await(2, TimeUnit.SECONDS))
                assertTrue(parent.requestCompact())
                assertTrue(parent.hasPendingCompact)
                children.forEach { child ->
                    assertFalse(child.hasPendingCompact)
                    assertFalse(child.isPaused)
                    assertFalse(child.isCancelled)
                }
                assertFalse(get(c, first, 0).getJSONObject("context_usage").getBoolean("is_compacting"))
                assertFalse(get(c, second, 0).getJSONObject("context_usage").getBoolean("is_compacting"))
                release.countDown()
                assertEquals("completed", get(c, first).getString("status"))
                assertEquals("completed", get(c, second).getString("status"))
            } finally {
                release.countDown()
                parentBinding.close()
            }
        }
    }

    @Test fun selectedChildCompactionNeverTargetsPeersAndCompletionClearsPendingWithoutLosingResult() {
        val parent = io.github.mangi.eta.agent.runtime.AgentRunController()
        val firstReady = CountDownLatch(1)
        val secondReady = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controls = java.util.Collections.synchronizedList(mutableListOf<io.github.mangi.eta.agent.runtime.AgentRunController>())
        SubAgentCoordinator(listOf(model, model)) { _, _, control ->
            controls += control
            if (controls.size == 1) firstReady.countDown() else secondReady.countDown()
            release.await()
            "preserved output"
        }.use { c ->
            try {
                val first = start(c).getString("task_id")
                assertTrue(firstReady.await(2, TimeUnit.SECONDS))
                val second = start(c).getString("task_id")
                assertTrue(secondReady.await(2, TimeUnit.SECONDS))
                assertTrue(c.requestCompact(first, 2, null))
                assertTrue(controls[0].hasPendingCompact)
                assertFalse(controls[1].hasPendingCompact)
                assertFalse(parent.hasPendingCompact)
                assertEquals("pending", get(c, first, 0).getJSONObject("context_usage").getString("manual_compaction_state"))
                release.countDown()
                val finished = get(c, first)
                assertEquals("completed", finished.getString("status"))
                assertEquals("preserved output", finished.getString("result"))
                assertEquals("ended", finished.getJSONObject("context_usage").getString("manual_compaction_state"))
                assertFalse(c.requestCompact(first, 2, null))
                assertEquals("preserved output", get(c, first).getString("result"))
                assertEquals("completed", get(c, second).getString("status"))
                assertFalse(parent.hasPendingCompact)
            } finally { release.countDown() }
        }
    }

    @Test fun taskIdsCanBeRecoveredAfterCompactionWithoutExposingResultBodies() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "private evidence" }.use { c ->
            val id = start(c).getString("task_id")
            assertEquals("completed", get(c, id).getString("status"))
            val list = JSONObject(c.execute(call("get_task_result", JSONObject())).content)
            assertEquals(id, list.getJSONArray("tasks").getJSONObject(0).getString("task_id"))
            assertEquals("completed", list.getJSONArray("tasks").getJSONObject(0).getString("status"))
            assertFalse(list.toString().contains("private evidence"))
            assertTrue(list.isNull("next_offset"))
            assertEquals("private evidence", get(c, id).getString("result"))
        }
    }

    @Test fun cancelCannotBeOverwrittenByLateCompletion() {
        val started = CountDownLatch(1)
        val returned = CountDownLatch(1)
        SubAgentCoordinator(listOf(model)) { _, _, controller ->
            started.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { }
            assertTrue(controller.isCancelled)
            returned.countDown()
            "late answer"
        }.use { c ->
            val id = start(c).getString("task_id")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            c.execute(call("cancel_task", JSONObject().put("task_id", id)))
            assertTrue(returned.await(2, TimeUnit.SECONDS))
            val result = get(c, id, 0)
            assertEquals("cancelled", result.getString("status"))
            assertEquals("", result.getString("result"))
        }
    }

    @Test fun closeCancelsControllerAndRejectsNewTasks() {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val c = SubAgentCoordinator(listOf(model)) { _, _, controller ->
            controller.register { stopped.countDown() }
            started.countDown()
            CountDownLatch(1).await()
            "unused"
        }
        start(c)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        c.close()
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertEquals("RUN_CLOSED", start(c).getString("code"))
    }

    @Test fun timeoutStopsWorkerAndResultsAreBoundedSensitive() {
        val stopped = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), timeoutMs = 100) { _, _, controller ->
            controller.register { stopped.countDown() }
            CountDownLatch(1).await()
            "unused"
        }.use { c ->
            val id = start(c).getString("task_id")
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertEquals("timed_out", get(c, id, 0).getString("status"))
        }
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "a".repeat(20000) }.use { c ->
            val id = start(c).getString("task_id")
            val result = get(c, id)
            assertTrue(result.getString("result").length < 16100)
            assertTrue(result.getBoolean("review_required"))
            assertTrue(c.execute(call("get_task_result", JSONObject().put("task_id", id))).sensitive)
        }
    }

    @Test fun failuresDoNotExposeProviderSecrets() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> error("secret apiKey=do-not-leak") }.use { c ->
            val result = get(c, start(c).getString("task_id"))
            assertEquals("failed", result.getString("status"))
            assertFalse(result.toString().contains("do-not-leak"))
        }
    }
    @Test fun roleSelectionUsesConfiguredModelAndRejectsWrongWorker() {
        val other = model.copy(model = "review-model")
        var used = ""
        SubAgentCoordinator(listOf(model, other), roles = listOf("implementation", "review")) { cfg, _, _ ->
            used = cfg.model
            "summary"
        }.use { c ->
            val started = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "summarize").put("role", "summary"))).content)
            assertEquals("completed", get(c, started.getString("task_id")).getString("status"))
            assertEquals("review-model", used)
            val denied = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "summarize").put("role", "summary").put("worker", 1))).content)
            assertEquals("WORKER_ROLE_MISMATCH", denied.getString("code"))
        }
    }

    @Test fun missingImplementationRoleNeverFallsBackToDifferentModel() {
        SubAgentCoordinator(listOf(model), roles = listOf("review")) { _, _, _ -> "unused" }.use { c ->
            val result = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "implement").put("role", "implementation").put("project", "/workspace/Test"))).content)
            assertEquals("ROLE_NOT_CONFIGURED", result.getString("code"))
        }
    }
    @Test fun contextLimitIsReportedToParentAsActionableFailure() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> throw SubAgentContextLimitException() }.use { c ->
            val id = start(c).getString("task_id")
            val result = get(c, id)
            assertEquals("failed", result.getString("status"))
            assertEquals("SUB_AGENT_CONTEXT_LIMIT", result.getString("error_code"))
            assertTrue(result.getString("result").contains("拆分任务"))
        }
    }
    @Test fun fourSlotsRouteAdditionalImplementationWorkers() {
        val models = (1..4).map { model.copy(model = "model-$it") }
        var chosen = ""
        SubAgentCoordinator(models, roles = listOf("implementation", "review", "implementation", "implementation")) { config, _, _ ->
            chosen = config.model; "done"
        }.use { c ->
            val result = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "check").put("worker", 4))).content)
            val done = get(c, result.getString("task_id"))
            assertEquals("completed", done.getString("status"))
            assertEquals("model-4", chosen)
            assertEquals(4, done.getJSONObject("context_usage").getInt("worker"))
            assertEquals("completed", done.getJSONObject("context_usage").getString("status"))
        }
    }

    @Test fun compressingChildDoesNotBlockOtherWorkerAndLateTelemetryCannotReviveIt() {
        val compressing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<SubAgentContextStats>())
        SubAgentCoordinator(listOf(model, model), onContext = { events += it },
            executeObservedChild = { _, prompt, _, _, _, _, emit ->
                if (prompt.contains("compress me")) {
                    emit(io.github.mangi.eta.agent.runtime.AgentEvent.ContextCompactionStarted(1))
                    compressing.countDown()
                    try { release.await() } catch (_: InterruptedException) { }
                    emit(io.github.mangi.eta.agent.runtime.AgentEvent.ContextCompactionStarted(2))
                }
                "done"
            }, executeChild = { _, _, _ -> error("Observed runner expected") }).use { c ->
            val first = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "compress me"))).content).getString("task_id")
            assertTrue(compressing.await(2, TimeUnit.SECONDS))
            assertTrue(get(c, first, 0).getJSONObject("context_usage").getBoolean("is_compacting"))
            val second = start(c).getString("task_id")
            assertEquals("completed", get(c, second).getString("status"))
            c.execute(call("cancel_task", JSONObject().put("task_id", first)))
            release.countDown()
            assertEquals("cancelled", get(c, first, 0).getString("status"))
            assertFalse(get(c, first, 0).getJSONObject("context_usage").getBoolean("is_compacting"))
            assertFalse(events.last { it.taskId == first }.isCompacting)
        }
    }

    @Test fun telemetryFailureDoesNotPreventCompletionOrCancellation() {
        SubAgentCoordinator(listOf(model), onContext = { error("UI gone") }) { _, _, _ -> "done" }.use { c ->
            assertEquals("completed", get(c, start(c).getString("task_id")).getString("status"))
        }
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), onContext = { error("UI gone") }) { _, _, controller ->
            controller.register { cancelled.countDown() }
            started.countDown()
            CountDownLatch(1).await()
            "unused"
        }.use { c ->
            val id = start(c).getString("task_id")
            assertTrue(started.await(2, TimeUnit.SECONDS))
            c.execute(call("cancel_task", JSONObject().put("task_id", id)))
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun explicitImageOptionsReachOnlySelectedImageRunnerAndNeverMutateModel() {
        val grok = model.copy(model = "grok-imagine-image-2.0")
        var received: io.github.mangi.eta.agent.model.AgentImageGenerationOptions? = null
        var called = 0
        SubAgentCoordinator(listOf(grok), roles = listOf("image_generation"),
            executeImageChild = { config, prompt, _, options ->
                assertEquals(grok, config)
                assertTrue(prompt.contains("portrait"))
                received = options; called++; "file"
            }, executeChild = { _, _, _ -> error("text runner must not be used") }).use { c ->
            val args = JSONObject().put("task", "portrait").put("role", "image_generation")
                .put("image_options", JSONObject().put("aspect_ratio", "9:16").put("resolution", "2k"))
            val id = JSONObject(c.execute(call("delegate_task", args)).content).getString("task_id")
            assertEquals("completed", get(c, id).getString("status"))
            assertEquals("9:16", received!!.aspectRatio)
            assertEquals("2k", received!!.resolution)
            args.put("image_options", JSONObject().put("size", "1080x1920"))
            val rejected = JSONObject(c.execute(call("delegate_task", args)).content)
            assertEquals("IMAGE_GENERATION_INVALID_OPTIONS", rejected.getString("code"))
            assertFalse(rejected.has("task_id"))
            assertEquals(1, called)
        }
    }

    @Test fun textDelegationRejectsImageOptionsInsteadOfIgnoringThem() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> error("must not execute") }.use { c ->
            val result = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "test")
                .put("image_options", JSONObject().put("aspect_ratio", "9:16")))).content)
            assertEquals("IMAGE_GENERATION_INVALID_OPTIONS", result.getString("code"))
        }
    }

}
