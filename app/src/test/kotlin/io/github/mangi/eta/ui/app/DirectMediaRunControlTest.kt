package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.executeGenerationRequest
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test

class DirectMediaRunControlTest {
    @Test fun stopBeforeStartingPreventsRequest() = runBlocking {
        val runs=DirectMediaRunControl();val controller=runs.start("before")
        runs.cancel("before");var calls=0
        try { runs.execute(controller) { calls++ };fail("must cancel") }
        catch (_: AgentRunCancelledException) { }
        assertEquals(0,calls)
    }
    @Test fun stoppingRunDoesNotCancelAnotherConversation() {
        val runs=DirectMediaRunControl();val a=runs.start("a");val b=runs.start("b")
        runs.cancel("a");runs.cancel("a")
        assertTrue(a.isCancelled);assertFalse(b.isCancelled)
        runs.cancel("b");assertTrue(b.isCancelled)
    }
    @Test fun normalCompletionReleasesControlWithoutCancellingParent() = runBlocking {
        val runs=DirectMediaRunControl();val controller=runs.start("success")
        assertEquals("done",runs.execute(controller) { "done" })
        assertTrue(controller.isCancelled);assertTrue(isActive)
        runs.cancel("success")
    }
    @Test fun buttonStopCancelsBlockedHttpCall() = runBlocking { blockedCall(stopParent=false) }
    @Test fun parentScopeCancellationCancelsBlockedHttpCall() = runBlocking { blockedCall(stopParent=true) }

    private suspend fun blockedCall(stopParent: Boolean) = coroutineScope {
        val runs=DirectMediaRunControl();val controller=runs.start("blocked")
        val entered=CountDownLatch(1);val call=AtomicReference<Call>();val attempts=AtomicInteger()
        val http=OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
            attempts.incrementAndGet();call.set(chain.call());entered.countDown()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
            while(!chain.call().isCanceled() && System.nanoTime()<deadline) Thread.sleep(5)
            throw java.io.IOException("interceptor stopped")
        }.build()
        var published=false
        val job=launch(Dispatchers.IO) {
            try {
                runs.execute(controller) {
                    executeGenerationRequest(http,Request.Builder().url("https://example.invalid/image").build(),controller) { it.body.string() }
                    published=true
                }
            } catch (_: Exception) { }
        }
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        if(stopParent) job.cancel() else runs.cancel("blocked")
        withTimeout(2000) { job.join() }
        assertTrue(call.get().isCanceled());assertTrue(controller.isCancelled)
        assertFalse(published);assertEquals(1,attempts.get())
    }
}
