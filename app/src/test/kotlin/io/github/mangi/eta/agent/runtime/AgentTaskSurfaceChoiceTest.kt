package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskSurfaceChoiceTest {
    @Test
    fun interruptWhileWaitingForDialogKeepsChoiceAndSessionInSync() {
        val session = AgentRuntimeSession("run-choice", taskSurfaceMode = AgentTaskSurfaceMode.ASK)
        var replayed = 0
        val mode = AgentTaskSurfaceChoice.choose(
            session = session,
            await = { AgentTaskSurfaceMode.FOREGROUND },
            awaitHostHidden = { throw InterruptedException() },
            onForegroundResolved = { replayed++ },
        )
        assertEquals(AgentTaskSurfaceMode.FOREGROUND, mode)
        assertEquals(AgentTaskSurfaceMode.FOREGROUND, session.taskSurfaceMode)
        assertEquals(1, replayed)
        assertTrue(Thread.interrupted())
    }

    @Test
    fun backgroundChoiceDoesNotReplayForegroundStart() {
        val session = AgentRuntimeSession("run-bg", taskSurfaceMode = AgentTaskSurfaceMode.ASK)
        var replayed = 0
        val mode = AgentTaskSurfaceChoice.choose(session, { AgentTaskSurfaceMode.BACKGROUND }, {}, { replayed++ })
        assertEquals(AgentTaskSurfaceMode.BACKGROUND, mode)
        assertEquals(AgentTaskSurfaceMode.BACKGROUND, session.taskSurfaceMode)
        assertEquals(0, replayed)
    }

    @Test
    fun cancelLeavesSessionAsk() {
        val session = AgentRuntimeSession("run-cancel", taskSurfaceMode = AgentTaskSurfaceMode.ASK)
        assertEquals(null, AgentTaskSurfaceChoice.choose(session, { null }, {}, {}))
        assertEquals(AgentTaskSurfaceMode.ASK, session.taskSurfaceMode)
    }

    @Test
    fun foregroundReplayReusesOriginalToolStarted() {
        val replay = AgentForegroundReplay()
        val started = AgentEvent.ToolStarted(round = 3, toolCallId = "call-1", name = "launch_app", argsPreview = "{}")
        replay.accept(started)
        assertEquals(started, replay.startedEvent("launch_app"))
        assertEquals(3, replay.startedEvent("tap").round)
        assertEquals("tap", replay.startedEvent("tap").name)
    }
}
