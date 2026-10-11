package io.github.mangi.eta.agent.overlay

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.ComposeView
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.runtime.AgentChildRunControl
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.agent.runtime.AgentRuntimeSession
import io.github.mangi.eta.agent.runtime.AgentRuntimeSessionRegistry
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.EntrySurfaceGuard
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSettings
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 胶囊可见性模型的服务级测试。
 *
 * 与旧光球不同，胶囊在前台与后台副屏都常驻（只有「前台操作高亮」仍按前台门控）。
 * 因此后台副屏运行时胶囊窗口应出现，但不记录前台执行、不消隐入口界面、不弹结果卡。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AgentVirtualRunOverlayServiceTest {
    @Test
    fun `background events show the capsule but never record or dismiss the entry surface`() = withService { service ->
        val session = AgentRuntimeSession("virtual", taskSurfaceMode = AgentTaskSurfaceMode.BACKGROUND)
        registry(service).put(session)
        var dismissals = 0
        val guard = entryGuard { dismissals++; true }
        val windows = RecordingWindows()
        setField(service, "windowManager", windows.manager)

        // 后台副屏会话也能占用胶囊（没有前台会话抢占时）。
        assertTrue(call(service, "claimOverlay", session, true) as Boolean)
        for (event in events()) call(service, "handleAcceptedRunEvent", session, event, guard)
        idle()

        // 前台专属行为仍被抑制：不消隐入口界面、不记录前台执行。
        assertEquals(0, dismissals)
        assertFalse(guard.wasTriggered)
        assertFalse(field(service, "hasExecutedForegroundTool") as Boolean)
        // 但胶囊窗口应出现。
        assertNotNull(field(service, "capsuleView"))
        assertSame(session, field(service, "overlaySession"))
    }

    @Test
    fun `background terminal result does not show the foreground result card`() = withService { service ->
        val session = AgentRuntimeSession("virtual", taskSurfaceMode = AgentTaskSurfaceMode.BACKGROUND)
        registry(service).put(session)
        assertTrue(call(service, "claimOverlay", session, true) as Boolean)
        val windows = RecordingWindows()
        setField(service, "windowManager", windows.manager)
        call(service, "showOverlay")
        idle()
        assertNotNull(field(service, "capsuleView"))

        val result = AgentRuntimeWire.RunResult(session.runId, ok = true, content = "done", error = null)
        assertTrue(session.complete(result))
        call(service, "postTerminalOverlay", session, result, null, null)
        idle()

        // 后台完成：没有前台执行，不弹结果卡；没有其它会话也没有未读时胶囊收起。
        assertNull(field(service, "resultCardView"))
    }

    @Test
    fun `foreground terminal shows the result card and retires the capsule`() = withService { service ->
        Prefs.initLocal(service)
        val previous = AgentTaskSurface.stored()
        try {
            Prefs.putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)
            val session = AgentRuntimeSession("foreground")
            registry(service).put(session)
            assertTrue(call(service, "claimOverlay", session, true) as Boolean)
            val windows = RecordingWindows()
            setField(service, "windowManager", windows.manager)
            call(service, "showOverlay")
            idle()
            var dismissals = 0
            val guard = entryGuard { dismissals++; true }
            call(service, "handleAcceptedRunEvent", session, AgentEvent.ToolStarted(1, "call", "tap", "{}"), guard)
            idle()
            assertEquals(1, dismissals)
            assertTrue(guard.wasTriggered)
            assertTrue(field(service, "hasExecutedForegroundTool") as Boolean)

            val result = AgentRuntimeWire.RunResult(session.runId, true, "foreground result")
            assertTrue(session.complete(result))
            call(service, "postTerminalOverlay", session, result, guard, null)
            idle()
            assertFalse(registry(service).contains(session))
            assertEquals(AgentOverlayPhase.FINISHED, overlayState(service).phase)
            assertEquals("foreground result", overlayState(service).detailText)
            assertNull(field(service, "capsuleView"))
            assertNotNull(field(service, "resultCardView"))
        } finally {
            Prefs.putString(AgentTaskSurface.PREF_KEY, previous.wire)
        }
    }

    @Test
    fun `stale same ID completion cannot consume replacement session`() = withService { service ->
        val old = AgentRuntimeSession("same", taskSurfaceMode = AgentTaskSurfaceMode.BACKGROUND)
        val replacement = AgentRuntimeSession("same", taskSurfaceMode = AgentTaskSurfaceMode.FOREGROUND)
        registry(service).put(old)
        val oldResult = AgentRuntimeWire.RunResult(old.runId, false, "", "已停止")
        assertTrue(old.complete(oldResult))
        registry(service).put(replacement)
        assertTrue(call(service, "claimOverlay", replacement, true) as Boolean)
        val windows = RecordingWindows()
        setField(service, "windowManager", windows.manager)
        setField(service, "hasExecutedForegroundTool", true)
        call(service, "recordSupplementEvent", replacement.runId, "keep", "request", "[]")
        val supplements = field(service, "supplementsByRunId") as Map<*, *>
        val replacementSupplements = supplements[replacement.runId]
        assertNotNull(replacementSupplements)

        call(service, "postTerminalOverlay", old, oldResult, null, null)
        idle()
        assertSame(replacement, registry(service).get("same"))
        assertSame(replacement, field(service, "overlaySession"))
        assertSame(replacementSupplements, supplements["same"])
    }

    @Test
    fun `unreadable stored mode falls back to a frozen ASK value`() = withService { service ->
        Prefs.initLocal(service)
        val prefs = Prefs.localAgentPreferences()!!
        val previous = prefs.all[AgentTaskSurface.PREF_KEY]
        try {
            prefs.edit().putInt(AgentTaskSurface.PREF_KEY, 1).commit()
            val session = AgentRuntimeSession("invalid-preference")
            assertEquals(AgentTaskSurfaceMode.ASK, session.taskSurfaceMode)
            Prefs.putString(AgentTaskSurface.PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)
            assertEquals(AgentTaskSurfaceMode.ASK, session.taskSurfaceMode)
        } finally {
            val editor = prefs.edit()
            when (previous) {
                null -> editor.remove(AgentTaskSurface.PREF_KEY)
                is String -> editor.putString(AgentTaskSurface.PREF_KEY, previous)
                is Int -> editor.putInt(AgentTaskSurface.PREF_KEY, previous)
                else -> error("Unexpected surface preference type")
            }
            editor.commit()
        }
    }

    @Test
    fun `queued retry progress reads paused control when the main looper handles it`() = withService { service ->
        val session = AgentRuntimeSession("paused-retry", taskSurfaceMode = AgentTaskSurfaceMode.FOREGROUND)
        AgentChildRunControl.begin(session)
        registry(service).put(session)
        assertTrue(call(service, "claimOverlay", session, true) as Boolean)
        for (event in listOf(
            AgentEvent.ErrorReconnectChanged(1, "old", "running", 0),
            AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "network"),
            AgentEvent.ErrorReconnectChanged(1, "old", "stopped", 1000),
        )) call(service, "handleAcceptedRunEvent", session, event, null)
        call(service, "requestPause", session.runId)
        idle()
        assertEquals(AgentOverlayPhase.PAUSED, overlayState(service).phase)
        assertEquals(AgentOverlayStatus.Paused, overlayState(service).status)
        call(service, "requestResume", session.runId)
        assertEquals(AgentOverlayPhase.RUNNING, overlayState(service).phase)
        assertEquals(AgentOverlayStatus.Continuing, overlayState(service).status)
        call(service, "handleAcceptedRunEvent", session,
            AgentEvent.ErrorReconnectChanged(1, "new", "running", 1000), null)
        idle()
        assertEquals(AgentOverlayPhase.RUNNING, overlayState(service).phase)
        AgentChildRunControl.finish(session)
    }

    private fun events(): List<AgentEvent> = listOf(
        AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "launch_app"),
        AgentEvent.AssistantBlockEnd(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "tap", contentChars = 2),
        AgentEvent.AssistantReceived(1, 0, "", listOf("launch_app", "observe_screen")),
        AgentEvent.ToolStarted(1, "launch", "launch_app", "{}"),
        AgentEvent.ToolStarted(1, "observe", "observe_screen", "{}"),
        AgentEvent.ToolStarted(1, "tap", "tap", "{}"),
        AgentEvent.ToolStarted(1, "alarm", "set_alarm", "{}"),
        AgentEvent.ToolFinished(1, "observe", "observe_screen", "ok", 1, 1024),
        AgentEvent.ToolImagesAttached(1, "observe_screen", 1, 1024),
    )

    private fun entryGuard(dismiss: () -> Boolean): EntrySurfaceGuard = EntrySurfaceGuard.from(
        AgentRuntimeWire.EntryHandoff(
            "entry", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "{}",
            dismissEntrySurfaceOnForegroundOperation = true,
        ),
        object : AgentLogger {
            override fun debug(message: () -> String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        },
        etaVoiceSurfaceDismissal = dismiss,
    )!!

    private fun withService(block: (AgentRuntimeService) -> Unit) {
        // 胶囊挂载需要悬浮窗权限（无障碍未注册时的回退路径），测试环境显式授予。
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            block(controller.get())
        } finally {
            controller.destroy()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun registry(service: AgentRuntimeService) = field(service, "sessions") as AgentRuntimeSessionRegistry

    @Suppress("UNCHECKED_CAST")
    private fun overlayState(service: AgentRuntimeService): AgentOverlayState =
        (field(service, "state") as MutableState<AgentOverlayState>).value

    private fun field(service: AgentRuntimeService, name: String): Any? =
        AgentRuntimeService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun setField(service: AgentRuntimeService, name: String, value: Any?) {
        AgentRuntimeService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun call(service: AgentRuntimeService, name: String, vararg args: Any?): Any? =
        AgentRuntimeService::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }.apply { isAccessible = true }.invoke(service, *args)

    private class RecordingWindows {
        val added = mutableListOf<View>()
        val removed = mutableListOf<View>()
        val manager: WindowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "addView" -> { added += args!![0] as View; null }
                "removeView", "removeViewImmediate" -> { removed += args!![0] as View; null }
                "updateViewLayout" -> null
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecordingWindows"
                else -> null
            }
        } as WindowManager
    }
}
