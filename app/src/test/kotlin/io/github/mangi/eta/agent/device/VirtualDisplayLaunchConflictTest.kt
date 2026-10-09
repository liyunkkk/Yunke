package io.github.mangi.eta.agent.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 副屏 launch_app 撞上主屏占用时的答复：三选项齐全、默认不自动停止、且不谎报执行。 */
class VirtualDisplayLaunchConflictTest {
    private val payload = VirtualDisplayLaunchConflict.payload(
        packageName = "com.example.shop",
        code = VirtualDisplayLaunchConflict.CODE_ACTIVE,
        detail = "target task 42 is active on the default display",
    )

    @Test
    fun reportsFailureWithoutClaimingExecution() {
        assertFalse(payload.getBoolean("ok"))
        assertEquals(VirtualDisplayLaunchConflict.CODE_ACTIVE, payload.getString("error"))
        assertEquals("com.example.shop", payload.getString("package_name"))
        assertFalse(payload.getBoolean("executed"))
    }

    @Test
    fun offersFourUserDecisionsWithTakeoverFirstAndAutoStopOffByDefault() {
        val conflict = payload.getJSONObject("conflict")
        assertFalse(conflict.getBoolean("auto_stop_default"))
        assertFalse(conflict.getBoolean("auto_takeover_default"))
        assertEquals("com.example.shop", conflict.getString("package_name"))
        val options = conflict.getJSONArray("options")
        assertEquals(4, options.length())
        val ids = (0 until options.length()).map { options.getJSONObject(it).getString("id") }
        // 接管排第一：不杀进程、不重置界面，收尾自动还回主屏。
        assertEquals(
            listOf(
                VirtualDisplayLaunchConflict.OPTION_TAKEOVER,
                VirtualDisplayLaunchConflict.OPTION_STOP_AND_RETRY,
                VirtualDisplayLaunchConflict.OPTION_CONTINUE_ON_MAIN,
                VirtualDisplayLaunchConflict.OPTION_CANCEL,
            ),
            ids,
        )
        (0 until options.length()).forEach { index ->
            val option = options.getJSONObject(index)
            assertTrue(option.getString("label").isNotBlank())
            assertTrue(option.getString("note").isNotBlank())
        }
    }

    @Test
    fun takeoverDefaultIsReportedWithoutDecidingForTheUser() {
        val auto = VirtualDisplayLaunchConflict.payload(
            packageName = "com.example.shop",
            code = VirtualDisplayLaunchConflict.CODE_RECENT,
            detail = "recent task 42",
            autoTakeoverDefault = true,
        )
        val conflict = auto.getJSONObject("conflict")
        assertTrue(conflict.getBoolean("auto_takeover_default"))
        // 自动接管不等于自动停止：停止主屏实例仍然默认关闭、仍然要用户同意。
        assertFalse(conflict.getBoolean("auto_stop_default"))
        assertFalse(auto.getBoolean("executed"))
    }

    @Test
    fun nextStepRequiresConsentBeforeStoppingAnything() {
        val nextStep = payload.getString("next_step")
        assertTrue(nextStep.contains("ask_user"))
        assertTrue(nextStep.contains(VirtualDisplayLaunchConflict.OPTION_STOP_AND_RETRY))
        assertTrue(nextStep.contains("app_state_control"))
        assertTrue(nextStep.contains("禁止"))
    }

    @Test
    fun detailIsClippedAndCodesCoverActiveAndRecent() {
        val long = VirtualDisplayLaunchConflict.payload("com.a", VirtualDisplayLaunchConflict.CODE_RECENT, "x".repeat(500))
        assertTrue(long.getJSONObject("conflict").getString("detail").length <= 200)
        assertEquals(
            setOf("TARGET_TASK_ACTIVE", "TARGET_TASK_RECENT"),
            VirtualDisplayLaunchConflict.codes,
        )
    }

    @Test
    fun payloadStaysValidJsonWhenDetailIsEmpty() {
        val empty = VirtualDisplayLaunchConflict.payload("com.a", VirtualDisplayLaunchConflict.CODE_ACTIVE, "")
        assertEquals("", empty.getJSONObject("conflict").getString("detail"))
        assertTrue(JSONObject(empty.toString()).getJSONObject("conflict").has("options"))
    }
}
