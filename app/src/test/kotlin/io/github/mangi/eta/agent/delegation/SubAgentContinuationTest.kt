package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubAgentContinuationTest {
    private val model = AgentModelClient.ModelConfig(baseUrl="https://example.invalid",apiKey="test",model="model",systemPrompt="")
    private fun call(c:SubAgentCoordinator,name:String,args:JSONObject) = JSONObject(c.execute(AgentModelClient.ToolCall("c",name,args.toString())).content)
    private fun start(c:SubAgentCoordinator,worker:Int=1) = call(c,"delegate_task",JSONObject().put("task","work").put("worker",worker)).getString("task_id")
    private fun get(c:SubAgentCoordinator,id:String) = call(c,"get_task_result",JSONObject().put("task_id",id))
    private fun awaitStatus(c:SubAgentCoordinator,id:String,status:String):JSONObject {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        while(System.nanoTime()<end) { val j=get(c,id);if(j.getString("status")==status)return j;Thread.sleep(5) }
        error("status expected $status; got ${get(c,id)}")
    }
    @Test fun continuationPreservesTaskExecutionAndDoesNotReplayWork() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        SubAgentCoordinator(listOf(model),timeoutMs=80,allowTimeoutContinuation=true) { _,_,controller ->
            calls.incrementAndGet();entered.countDown();release.await();controller.throwIfCancelled();"same work"
        }.use { c ->
            val id=start(c);assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertTrue(awaitStatus(c,id,"awaiting_decision").getBoolean("can_continue"))
            release.countDown()
            val continued=call(c,"continue_task",JSONObject().put("task_id",id))
            assertEquals(1,continued.getInt("continuation_count"))
            assertEquals("same work",awaitStatus(c,id,"completed").getString("result"))
            assertEquals(1,calls.get())
            assertEquals("TASK_NOT_AWAITING_DECISION",call(c,"continue_task",JSONObject().put("task_id",id)).getString("code"))
        }
    }
    @Test fun parentCanCancelWhileAwaitingDecision() {
        SubAgentCoordinator(listOf(model),timeoutMs=60,allowTimeoutContinuation=true) { _,_,controller ->
            while(true) { Thread.sleep(5);controller.throwIfCancelled() }
            @Suppress("UNREACHABLE_CODE") "never"
        }.use { c ->
            val id=start(c);awaitStatus(c,id,"awaiting_decision")
            assertEquals("cancelled",call(c,"cancel_task",JSONObject().put("task_id",id)).getString("status"))
        }
    }
    @Test fun duplicateProfilesAndParentsShareLimitButDifferentProvidersDoNot() {
        val entered=CountDownLatch(2);val release=CountDownLatch(1);val calls=AtomicInteger()
        val config=model.copy(providerId="shared-${System.nanoTime()}")
        val work:(AgentModelClient.ModelConfig,String,io.github.mangi.eta.agent.runtime.AgentRunController)->String = {_,_,_->calls.incrementAndGet();entered.countDown();release.await();"done"}
        SubAgentCoordinator(listOf(config,config),modelParallelLimits=listOf(2,2),executeChild=work).use { a ->
            SubAgentCoordinator(listOf(config),modelParallelLimits=listOf(2),executeChild=work).use { b ->
                val first=start(a);val second=start(a,2);assertTrue(entered.await(2,TimeUnit.SECONDS))
                val queued=start(b);assertEquals("queued",get(b,queued).getString("status"));assertEquals(2,calls.get())
                SubAgentCoordinator(listOf(config.copy(providerId="other")),modelParallelLimits=listOf(1)) {_,_,_->"independent"}.use { independent ->
                    assertEquals("independent",awaitStatus(independent,start(independent),"completed").getString("result"))
                }
                release.countDown();awaitStatus(a,first,"completed");awaitStatus(a,second,"completed");awaitStatus(b,queued,"completed")
                a.close() // use closes twice: must not release b's lease twice.
            }
        }
    }
    @Test fun sameIdentityCanRunParallelAndZeroHasNoFixedCap() {
        for (limit in listOf(3,0)) {
            val entered=CountDownLatch(3);val release=CountDownLatch(1)
            SubAgentCoordinator(listOf(model.copy(providerId="test-$limit")),modelParallelLimits=listOf(limit)) {_,_,_->entered.countDown();release.await();"done"}.use { c ->
                val ids=List(3){start(c)}
                assertTrue(entered.await(2,TimeUnit.SECONDS));release.countDown()
                ids.forEach{awaitStatus(c,it,"completed")}
            }
        }
    }
    @Test fun renewKeepsCumulativeCompressionBudget() {
        var now=0L
        val clock=SubAgentExecutionClock(100,50){now}
        clock.setCompacting(true);now=40;assertNull(clock.expired());clock.setCompacting(false)
        now=160;assertEquals("SUB_AGENT_TIMEOUT",clock.expired());clock.renewExecution()
        clock.setCompacting(true);now=175;assertEquals("SUB_AGENT_COMPACTION_TIMEOUT",clock.expired())
    }
    @Test fun inFlightCompressionStillCountsDuringMainDecisionWait() {
        var now=0L;val clock=SubAgentExecutionClock(100,50){now}
        now=110;clock.setCompacting(true)
        assertEquals("SUB_AGENT_TIMEOUT",clock.expired());clock.pauseExecution()
        now=140;clock.setCompacting(false)
        now=1000;assertNull(clock.expired()) // idle wait is not charged
        clock.renewExecution();clock.setCompacting(true)
        now=1025;assertEquals("SUB_AGENT_COMPACTION_TIMEOUT",clock.expired())
    }
    @Test fun liveModelLimitCanIncreaseWithoutCreatingAnotherPool() {
        val key="resize-${System.nanoTime()}"
        val a=SubAgentModelPools.acquire(key,1)
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        try {
            repeat(2){a.executor.submit{entered.countDown();release.await()}}
            val b=SubAgentModelPools.acquire(key,2)
            assertSame(a.executor,b.executor)
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertEquals(2,SubAgentModelPools.currentLimit(a))
            SubAgentModelPools.configure(key,1)
            assertEquals(1,SubAgentModelPools.currentLimit(b))
            release.countDown();SubAgentModelPools.release(b)
        } finally { release.countDown();SubAgentModelPools.release(a) }
    }

}
