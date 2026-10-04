package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentStopInteractionTest {
    @Test fun stopOwnsOnlyItsTranscriptAndDoesNotBlockMetadata() = fixture { app ->
        select(app, "c")
        call(app, "bindUsageRun", "run-c", "c")
        stopping(app)["run-c"] = false
        assertTrue(call(app, "rejectConversationArchiveMutation", true) as Boolean)
        assertFalse(call(app, "rejectConversationArchiveMutation", false) as Boolean)
        app.renameConversation("c", "Renamed while stopping")
        val titles = field(app, "conversationTitles") as Map<*, *>
        assertEquals("Renamed while stopping", titles["c"])
    }

    @Test fun stoppedConversationDoesNotLockAnotherConversation() = fixture { app ->
        select(app, "c")
        call(app, "bindUsageRun", "run-c", "c")
        stopping(app)["run-c"] = false
        select(app, "other")
        assertFalse(call(app, "rejectConversationArchiveMutation", true) as Boolean)
        assertFalse(call(app, "rejectConversationArchiveMutation", false) as Boolean)
    }

    @Test fun safeActionsStillRespectArchiveMaintenanceLock() = fixture { app ->
        select(app, "c")
        app.javaClass.getDeclaredField("conversationArchiveBusy").apply { isAccessible = true }.setBoolean(app, true)
        assertTrue(call(app, "rejectConversationArchiveMutation", false) as Boolean)
        assertTrue(call(app, "rejectConversationArchiveMutation", true) as Boolean)
    }

    @Test fun duplicateTerminalEventKeepsTheSameGraceWatchdog() = fixture { app ->
        call(app, "finishStopSeal", "run-c")
        @Suppress("UNCHECKED_CAST")
        val jobs = field(app, "stopSealWatchdogJobs") as Map<String, Job>
        val first = requireNotNull(jobs["run-c"])
        call(app, "finishStopSeal", "run-c")
        assertSame(first, jobs["run-c"])
        assertTrue((field(app, "stopSealTerminalTimeout") as RunStopSealTimeout).isPending("run-c"))
        call(app, "cancelStopSealWatchdog", "run-c")
    }

    private fun select(app: AgentAppState, id: String) {
        app.javaClass.getDeclaredField("selectedConversationId").apply { isAccessible = true }.set(app, id)
        call(app, "updateConversation", id, AgentChatHomeUiState(
            messages = emptyList(), input = "", isStreaming = false, thinkingEnabled = false,
        ), false, true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stopping(app: AgentAppState) = field(app, "stoppingRuns") as MutableMap<String, Boolean>

    private fun fixture(block: (AgentAppState) -> Unit) {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        // Test only synchronous state changes; never contact runtime or start persistence jobs.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { it.cancel() }
        try { block(AgentAppState(context, scope)) }
        finally { scope.cancel(); EtaDatabase.closeForTests() }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
}
