package io.github.mangi.eta.agent.tool

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Exercises the real executor/ASK gate, not merely the route policy or source text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalSelfAppSurfaceTest {
    private lateinit var app: Application
    private val external = "example.external.surface"

    @Before fun registerLaunchers() {
        app = RuntimeEnvironment.getApplication()
        launcher(app.packageName, "Self surface fixture")
        launcher(external, "External fixture")
    }

    @Test fun selfLaunchAndGuiDoNotAskThenExternalStillAsksExactlyOnce() {
        var asked = 0
        var observed = 0
        val tools = tools(
            choose = { asked++; AgentTaskSurfaceMode.FOREGROUND },
            observe = { observed++; observation() },
        )
        try {
            val self = call(tools, "launch_app", JSONObject().put("package_name", app.packageName))
            assertTrue(self.getBoolean("ok"))
            assertEquals("self_app", self.getString("task_surface_scope"))
            assertEquals("ask", self.getString("base_task_surface"))
            assertEquals(0, asked)
            assertEquals(AgentTaskSurfaceMode.ASK, tools.runSurface)
            assertTrue(call(tools, "observe_screen").getBoolean("ok"))
            assertEquals(1, observed)
            assertEquals(0, asked)
            val other = call(tools, "launch_app", JSONObject().put("package_name", external))
            assertTrue(other.getBoolean("ok"))
            assertEquals("ordinary_run", other.getString("task_surface_scope"))
            assertEquals(1, asked)
            assertEquals(AgentTaskSurfaceMode.FOREGROUND, tools.runSurface)
            val ordinaryObservation = call(tools, "observe_screen")
            assertTrue(ordinaryObservation.getBoolean("ok"))
            assertFalse(ordinaryObservation.has("task_surface_scope"))
            assertEquals(2, observed)
            assertEquals(1, asked)
        } finally { tools.close() }
    }

    @Test fun selfSegmentDoesNotResetAnAlreadyCancelledOrdinaryAsk() {
        var asked = 0
        val tools = tools(choose = { asked++; null })
        try {
            assertEquals("TASK_SURFACE_CANCELLED", call(tools, "launch_app", JSONObject().put("package_name", external)).getString("code"))
            assertTrue(call(tools, "launch_app", JSONObject().put("package_name", app.packageName)).getBoolean("ok"))
            assertTrue(call(tools, "observe_screen").getBoolean("ok"))
            assertEquals("TASK_SURFACE_CANCELLED", call(tools, "launch_app", JSONObject().put("package_name", external)).getString("code"))
            assertEquals(1, asked)
            assertEquals(AgentTaskSurfaceMode.ASK, tools.runSurface)
        } finally { tools.close() }
    }

    @Test fun lostMainDisplayFocusRejectsWithoutObservingOrReplaying() {
        var foreground: String? = app.packageName
        var observed = 0
        var asked = 0
        val tools = tools(
            choose = { asked++; null },
            foreground = { foreground },
            observe = { observed++; observation() },
        )
        try {
            assertTrue(call(tools, "launch_app", JSONObject().put("package_name", app.packageName)).getBoolean("ok"))
            foreground = external
            assertEquals("SELF_APP_TARGET_LOST", call(tools, "observe_screen").getString("code"))
            assertEquals(0, observed)
            assertEquals(0, asked)
            assertEquals("SELF_APP_TARGET_LOST", call(tools, "observe_screen").getString("code"))
            assertEquals(0, asked)
            assertEquals(0, observed)
            foreground = app.packageName
            assertEquals("SELF_APP_TARGET_LOST", call(tools, "observe_screen").getString("code"))
            assertEquals(0, asked)
            assertEquals(0, observed)
            assertEquals("TASK_SURFACE_CANCELLED", call(tools, "launch_app", JSONObject().put("package_name", external)).getString("code"))
            assertEquals(1, asked)
            // Only an explicit successful self launch authorizes observations again.
            assertTrue(call(tools, "launch_app", JSONObject().put("package_name", app.packageName)).getBoolean("ok"))
            assertTrue(call(tools, "observe_screen").getBoolean("ok"))
            assertEquals(1, observed)
            assertEquals(1, asked)
            assertEquals(AgentTaskSurfaceMode.ASK, tools.runSurface)
        } finally { tools.close() }
    }

    @Test fun backgroundBaseSurvivesSelfAndExternalNeverLaunchesOnForeground() {
        var asked = 0
        var observed = 0
        val tools = tools(
            mode = AgentTaskSurfaceMode.BACKGROUND,
            choose = { asked++; error("Background must not ask") },
            observe = { observed++; observation() },
        )
        try {
            shadowOf(app).clearNextStartedActivities()
            assertTrue(call(tools, "launch_app", JSONObject().put("package_name", app.packageName)).getBoolean("ok"))
            assertEquals(app.packageName, shadowOf(app).nextStartedActivity.component?.packageName)
            assertTrue(call(tools, "observe_screen").getBoolean("ok"))
            assertEquals(1, observed)
            assertEquals(AgentTaskSurfaceMode.BACKGROUND, tools.runSurface)
            // Without root the real virtual route rejects before any foreground start.
            assertEquals("ROOT_REQUIRED", call(tools, "launch_app", JSONObject().put("package_name", external)).getString("code"))
            assertNull(shadowOf(app).nextStartedActivity)
            assertEquals(0, asked)
            assertEquals(AgentTaskSurfaceMode.BACKGROUND, tools.runSurface)
        } finally { tools.close() }
    }

    @Test(timeout = 10000) fun anotherRunRejectsGuiWithoutSideEffectsAndDoesNotStealOwner() {
        var observed = 0
        var asked = 0
        val tools = tools(mode = AgentTaskSurfaceMode.FOREGROUND,
            choose = { asked++; error("Already foreground") },
            observe = { observed++; observation() })
        assertEquals(ForegroundExclusiveGate.Admission.ACQUIRED, ForegroundExclusiveGate.acquire("other-owner"))
        try {
            shadowOf(app).clearNextStartedActivities()
            for (name in listOf("launch_app", "observe_screen", "tap", "wait_for_text", "wait_for_package", "set_alarm", "set_timer")) {
                val args = if (name == "launch_app") JSONObject().put("package_name", external) else JSONObject()
                val rejected = call(tools, name, args)
                assertEquals(name, "FOREGROUND_BUSY", rejected.getString("code"))
                assertFalse(rejected.getBoolean("ok"))
                assertFalse(rejected.getBoolean("executed"))
                assertFalse(rejected.getBoolean("queued"))
                assertFalse(rejected.getBoolean("retryable"))
                assertTrue(rejected.getString("message").contains("另一个会话"))
            }
            assertEquals("FOREGROUND_BUSY", call(tools, "launch_app", JSONObject().put("package_name", app.packageName)).getString("code"))
            assertEquals(0, observed)
            assertEquals(0, asked)
            assertNull(shadowOf(app).nextStartedActivity)
            assertTrue(call(tools, "search_apps", JSONObject().put("query", external)).getBoolean("ok"))
            tools.close()
            assertEquals("other-owner", ForegroundExclusiveGate.ownerForTests())
        } finally {
            tools.close()
            ForegroundExclusiveGate.release("other-owner")
        }
    }

    @Test fun occupiedMainScreenDoesNotBlockBackgroundRoute() {
        val tools = tools(mode = AgentTaskSurfaceMode.BACKGROUND, choose = { error("Must not ask") })
        assertEquals(ForegroundExclusiveGate.Admission.ACQUIRED, ForegroundExclusiveGate.acquire("other-owner"))
        try {
            // Rootless fixture reaches virtual admission, not the foreground busy error.
            assertEquals("ROOT_REQUIRED", call(tools, "launch_app", JSONObject().put("package_name", external)).getString("code"))
            assertEquals("other-owner", ForegroundExclusiveGate.ownerForTests())
        } finally {
            tools.close()
            ForegroundExclusiveGate.release("other-owner")
        }
    }

    @Test fun occupiedAskRejectsBeforePromptAndCanAskAfterOwnerRelease() {
        var asked = 0
        val tools = tools(choose = { asked++; AgentTaskSurfaceMode.BACKGROUND })
        assertEquals(ForegroundExclusiveGate.Admission.ACQUIRED, ForegroundExclusiveGate.acquire("other-owner"))
        try {
            shadowOf(app).clearNextStartedActivities()
            repeat(2) {
                assertEquals("FOREGROUND_BUSY", call(tools, "launch_app", JSONObject().put("package_name", external)).getString("code"))
            }
            assertEquals(0, asked)
            assertEquals(AgentTaskSurfaceMode.ASK, tools.runSurface)
            assertNull(shadowOf(app).nextStartedActivity)
            ForegroundExclusiveGate.release("other-owner")
            assertEquals("ROOT_REQUIRED", call(tools, "launch_app", JSONObject().put("package_name", external)).getString("code"))
            assertEquals(1, asked)
            assertEquals(AgentTaskSurfaceMode.BACKGROUND, tools.runSurface)
        } finally { tools.close(); ForegroundExclusiveGate.release("other-owner") }
    }

    @Test fun ownerAppearingDuringAskIsRecheckedBeforeActualLaunch() {
        val tools = tools(choose = {
            assertEquals(ForegroundExclusiveGate.Admission.ACQUIRED, ForegroundExclusiveGate.acquire("other-owner"))
            AgentTaskSurfaceMode.FOREGROUND
        })
        try {
            shadowOf(app).clearNextStartedActivities()
            assertEquals("FOREGROUND_BUSY", call(tools, "launch_app", JSONObject().put("package_name", external)).getString("code"))
            assertNull(shadowOf(app).nextStartedActivity)
            assertEquals("other-owner", ForegroundExclusiveGate.ownerForTests())
        } finally { tools.close(); ForegroundExclusiveGate.release("other-owner") }
    }

    private fun launcher(pkg: String, label: String) {
        val pm = shadowOf(app.packageManager)
        val component = ComponentName(pkg, "$pkg.SelfSurfaceFixtureActivity")
        val info = pm.addActivityIfNotPresent(component)
        info.enabled = true
        info.exported = true
        info.nonLocalizedLabel = label
        info.applicationInfo.enabled = true
        info.applicationInfo.nonLocalizedLabel = label
        pm.addOrUpdateActivity(info)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
        assertNotNull(app.packageManager.getLaunchIntentForPackage(pkg))
    }

    private fun tools(
        mode: AgentTaskSurfaceMode = AgentTaskSurfaceMode.ASK,
        choose: () -> AgentTaskSurfaceMode?,
        foreground: () -> String? = { app.packageName },
        observe: () -> RootShellDeviceController.Observation = { observation() },
    ) = AgentLocalTools(
        context = app,
        logger = NoOpLogger,
        browserRunId = "self-surface-test",
        frozenSurface = mode,
        chooseSurface = { _, _ -> choose() },
        selfForegroundPackage = foreground,
        rootAvailable = { false },
        beforeToolExecution = { ToolExecutionDecision.Allow },
        screenObservationProvider = { observe() },
    )

    private fun call(tools: AgentLocalTools, name: String, args: JSONObject = JSONObject()) =
        JSONObject(tools.execute(AgentModelClient.ToolCall("test-$name", name, args.toString())).content)

    private fun observation() = RootShellDeviceController.Observation(
        content = JSONObject().put("ok", true).put("tool", "observe_screen").toString(),
        image = null,
        elementObservation = null,
        coordinateSpace = null,
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
