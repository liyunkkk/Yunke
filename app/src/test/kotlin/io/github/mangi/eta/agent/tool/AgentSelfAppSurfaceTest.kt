package io.github.mangi.eta.agent.tool

import org.junit.Assert.*
import org.junit.Test

class AgentSelfAppSurfaceTest {
    private val own = "io.github.mangi.eta"
    private fun active() = AgentSelfAppSurface(own).apply { launchedSelf(true) }

    @Test fun onlyExactResolvedSelfPackageBypassesBase() {
        val s = AgentSelfAppSurface(own)
        assertEquals(AgentSelfAppSurface.Route.SELF_LAUNCH, s.route("launch_app", own))
        assertFalse(s.active)
        listOf(null, "eta", "$own.clone", "io.github.mangi", "other.app").forEach {
            assertEquals(AgentSelfAppSurface.Route.BASE, s.route("launch_app", it))
        }
    }
    @Test fun failedLaunchDoesNotAuthorizeSubsequentGui() {
        val s = AgentSelfAppSurface(own)
        s.launchedSelf(false)
        assertFalse(s.active)
        assertTrue(s.lost)
        assertEquals(AgentSelfAppSurface.Route.SELF_GUI, s.route("tap"))
        assertFalse(s.validateForeground(own))
    }
    @Test fun failedRelaunchRevokesAnAlreadyActiveSegment() {
        val s = active()
        assertEquals(AgentSelfAppSurface.Route.SELF_LAUNCH, s.route("launch_app", own))
        assertFalse(s.active)
        s.launchedSelf(false)
        assertFalse(s.active)
        assertTrue(s.lost)
        assertEquals(AgentSelfAppSurface.Route.SELF_GUI, s.route("tap"))
        assertFalse(s.validateForeground(own))
    }
    @Test fun successfulLaunchAuthorizesOnlyOwnGuiSegment() {
        val s = active()
        listOf("observe_screen", "tap", "tap_element", "scroll", "paste_text", "wait_for_text").forEach {
            assertEquals(AgentSelfAppSurface.Route.SELF_GUI, s.route(it))
        }
        assertTrue(s.active)
    }
    @Test fun externalLaunchLeavesSegmentAndRestoresBase() {
        val s = active()
        assertEquals(AgentSelfAppSurface.Route.BASE, s.route("launch_app", "other.app"))
        assertFalse(s.active)
        assertEquals(AgentSelfAppSurface.Route.BASE, s.route("observe_screen"))
    }
    @Test fun unknownOrExternalFocusRevokesBeforeAction() {
        listOf(null, "", "other.app", "$own.clone").forEach {
            val s = active()
            assertFalse(s.validateForeground(it))
            assertFalse(s.active)
            assertTrue(s.lost)
            assertEquals(AgentSelfAppSurface.Route.SELF_GUI, s.route("tap"))
            assertFalse(s.validateForeground(own))
        }
    }
    @Test fun consecutiveQueuedActionsStayRejectedUntilExplicitRetarget() {
        val s = active()
        assertFalse(s.validateForeground("other.app"))
        repeat(3) {
            assertEquals(AgentSelfAppSurface.Route.SELF_GUI, s.route("tap"))
            assertFalse(s.validateForeground(own))
        }
        assertEquals(AgentSelfAppSurface.Route.BASE, s.route("launch_app", "other.app"))
        assertFalse(s.lost)
        assertEquals(AgentSelfAppSurface.Route.BASE, s.route("tap"))
        assertEquals(AgentSelfAppSurface.Route.SELF_LAUNCH, s.route("launch_app", own))
        s.launchedSelf(true)
        assertTrue(s.validateForeground(own))
    }
    @Test fun exactOwnFocusPreservesSegment() {
        val s = active()
        assertTrue(s.validateForeground(own))
        assertTrue(s.active)
    }
    @Test fun lifecycleUsesBaseWithoutDestroyingSelfSegment() {
        val s = active()
        listOf("start_virtual_session", "keep_virtual_result", "finish_virtual_session").forEach {
            assertEquals(AgentSelfAppSurface.Route.BASE, s.route(it))
            assertTrue(s.active)
        }
    }
    @Test fun systemAndExternalBoundaryToolsDoNotInheritOverride() {
        listOf("open_uri", "open_system_panel", "set_alarm", "set_timer").forEach {
            val s = active()
            assertEquals(AgentSelfAppSurface.Route.BASE, s.route(it))
            assertFalse(s.active)
        }
    }
    @Test fun homeRecentsAndPanelsAreBaseWhileInternalKeysRemainScoped() {
        listOf("HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS").forEach {
            val s = active()
            assertEquals(AgentSelfAppSurface.Route.BASE, s.route("press_key", button = it))
            assertFalse(s.active)
        }
        listOf("BACK", "ENTER", "PASTE").forEach {
            assertEquals(AgentSelfAppSurface.Route.SELF_GUI, active().route("press_key", button = it))
        }
    }
    @Test fun waitForOtherPackageCannotBorrowSelfException() {
        val s = active()
        assertEquals(AgentSelfAppSurface.Route.SELF_GUI, s.route("wait_for_package", waitPackage = own))
        assertEquals(AgentSelfAppSurface.Route.BASE, s.route("wait_for_package", waitPackage = "other.app"))
        assertFalse(s.active)
    }
}
