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
    fun offersExactlyThreeUserDecisionsWithAutoStopOffByDefault() {
        val conflict = payload.getJSONObject("conflict")
        assertFalse(conflict.getBoolean("auto_stop_default"))
        assertEquals("com.example.shop", conflict.getString("package_name"))
        val options = conflict.getJSONArray("options")
        assertEquals(3, options.length())
        val ids = (0 until options.length()).map { options.getJSONObject(it).getString("id") }
        assertEquals(
            listOf(
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
