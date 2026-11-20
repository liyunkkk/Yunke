package io.github.mangi.eta.agent.scheduler

import io.github.mangi.eta.data.model.ScheduledTaskCapabilities
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskRunContextTest {
    private fun context(capabilities: ScheduledTaskCapabilities = ScheduledTaskCapabilities()) =
        ScheduledTaskRunContext(
            taskId = "task-1",
            taskName = "test",
            runId = "run-1",
            triggerSource = ScheduledTaskTriggerSource.SCHEDULE,
            deliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
            sessionMode = ScheduledTaskSessionMode.ISOLATED,
            capabilities = capabilities,
            memo = "",
            scheduledForAt = null,
            startedAt = 1L,
        )

    private fun ScheduledTaskRunContext.allDeviceGroups(
        tool: String,
        toolEnabled: (String) -> Boolean = { true },
    ) = allowsTool(
        name = tool,
        browserEnabled = true,
        terminalEnabled = true,
        deviceDirectEnabled = true,
        deviceSensitiveReadEnabled = true,
        deviceSensitiveActionEnabled = true,
        toolEnabled = toolEnabled,
    )

    @Test
    fun directPhoneToolsRespectReadAndActionCapabilities() {
        val readOnly = context(ScheduledTaskCapabilities(deviceReadTools = true, deviceActionTools = false))
        assertTrue(readOnly.allDeviceGroups("get_sound_state"))
        assertFalse(readOnly.allDeviceGroups("set_flashlight"))
        assertFalse(readOnly.allDeviceGroups("set_volume"))
    }

    @Test
    fun personalPhoneToolsUseSensitiveReadAndActionGroups() {
        val policy = context()
        assertFalse(policy.allowsTool(
            "read_calendar_event", true, true, true, false, true,
        ))
        val actionPolicy = context(ScheduledTaskCapabilities(deviceActionTools = true))
        assertFalse(actionPolicy.allowsTool(
            "create_calendar_event", true, true, true, true, false,
        ))
        assertTrue(actionPolicy.allowsTool(
            "create_calendar_event", true, true, true, true, true,
        ))
        assertFalse(policy.allowsTool(
            "read_note", true, true, true, false, true,
        ))
        assertTrue(policy.allDeviceGroups("read_calendar_event"))
    }

    @Test
    fun guiDelegationManagementAndDisabledToolsAreDenied() {
        val policy = context()
        assertFalse(policy.allDeviceGroups("tap"))
        assertFalse(policy.allDeviceGroups("delegate_task"))
        assertFalse(policy.allDeviceGroups("manage_agent_workspace"))
        assertFalse(policy.allDeviceGroups("scheduled_tasks"))
        assertTrue(policy.allDeviceGroups("scheduled_task_report"))
        assertFalse(policy.allDeviceGroups("scheduled_task_report") { false })
    }

    @Test
    fun terminalAndBrowserCapabilitiesAreRespected() {
        val policy = context(ScheduledTaskCapabilities(browserTools = false, terminalTools = false))
        assertFalse(policy.allDeviceGroups("browser_use"))
        assertFalse(policy.allDeviceGroups("run_command"))
        assertTrue(policy.allDeviceGroups("device_status"))
    }
}
