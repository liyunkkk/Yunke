package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubAgentContinuationTest {
    private val model = AgentModelClient.ModelConfig(baseUrl="https://example.invalid", apiKey="test", model="model", systemPrompt="")
    private fun call(c: SubAgentCoordinator, name: String, args: JSONObject) = JSONObject(c.execute(AgentModelClient.ToolCall("c", name, args.toString())).content)
    private fun start(c: SubAgentCoordinator, worker: Int = 1) = call(c, "delegate_task", JSONObject().put("task", "work").put("worker", worker)).getString("task_id")
    private fun get(c: SubAgentCoordinator, id: String) = call(c, "get_task_result", JSONObject().put("task_id", id))
    private fun awaitStatus(c: SubAgentCoordinator, id: String, status: String): JSONObject {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (System.nanoTime() < end) { val j = get(c,id); if (j.getString("status") == status) return j; Thread.sleep(5) }
        error("expected $status, got ${get(c,id)}")
    }
    @Test fun manualPauseAndContinuePreserveOneExecution() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        SubAgentCoordinator(listOf(model)) { _, _, controller ->
            calls.incrementAndGet(); entered.countDown(); release.await(); controller.throwIfCancelled(); "same work"
        }.use { c ->
            val id = start(c); assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertEquals("awaiting_decision",call(c,"supervise_task",JSONObject().put("task_id",id).put("action","pause")).getString("status"))
            release.countDown()
            assertTrue(get(c,id).getBoolean("can_continue"))
            assertEquals(1, call(c,"continue_task",JSONObject().put("task_id",id)).getInt("continuation_count"))
            assertEquals("same work",awaitStatus(c,id,"completed").getString("result"))
            assertEquals(1,calls.get())
        }
    }
    @Test fun cancelWhilePausedIsNotCompletion() {
        SubAgentCoordinator(listOf(model)) { _,_,controller ->
            while (true) { Thread.sleep(5); controller.throwIfCancelled() }
            @Suppress("UNREACHABLE_CODE") "never"
        }.use { c ->
            val id = start(c); awaitStatus(c,id,"running")
            call(c,"supervise_task",JSONObject().put("task_id",id).put("action","pause"))
            assertEquals("cancelled",call(c,"cancel_task",JSONObject().put("task_id",id)).getString("status"))
        }
    }
    @Test fun duplicateProfilesShareLimitAndQueueTimeIsNotExecution() {
        val entered=CountDownLatch(2); val release=CountDownLatch(1)
        val config=model.copy(providerId="shared-${System.nanoTime()}")
        val work:(AgentModelClient.ModelConfig,String,io.github.mangi.eta.agent.runtime.AgentRunController)->String = {_,_,_->entered.countDown();release.await();"done"}
        SubAgentCoordinator(listOf(config,config),modelParallelLimits=listOf(2,2),executeChild=work).use { a ->
            SubAgentCoordinator(listOf(config),modelParallelLimits=listOf(2),executeChild=work).use { b ->
                val first=start(a); val second=start(a,2); assertTrue(entered.await(2,TimeUnit.SECONDS))
                val queued=start(b); assertEquals("queued",get(b,queued).getString("status"))
                release.countDown(); awaitStatus(a,first,"completed"); awaitStatus(a,second,"completed"); awaitStatus(b,queued,"completed")
            }
        }
    }
    @Test fun textSoftWarningDoesNotExpireAndCompressionRemainsCumulative() {
        var now=0L
        val clock=SubAgentExecutionClock(100,50,softExecution=true,now={now})
        now=101; assertNull(clock.expired()); assertTrue(clock.softWarningDue()); assertFalse(clock.softWarningDue())
        now=2000; assertNull(clock.expired())
        clock.setCompacting(true); now=2049; assertNull(clock.expired())
        clock.renewExecution(); now=2051; assertEquals("SUB_AGENT_COMPACTION_TIMEOUT",clock.expired())
    }
    @Test fun mediaDeadlineIsStillHard() {
        var now=0L; val clock=SubAgentExecutionClock(180,360,now={now})
        now=181; assertEquals("SUB_AGENT_TIMEOUT",clock.expired())
    }
}
