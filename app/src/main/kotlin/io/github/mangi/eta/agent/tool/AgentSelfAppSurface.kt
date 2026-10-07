package io.github.mangi.eta.agent.tool

/** A target-scoped exception, never a replacement for the run's ordinary surface choice.
 * The caller serializes GUI calls and commits a segment only after a successful self launch.
 */
internal class AgentSelfAppSurface(private val ownPackage: String) {
    enum class Route { BASE, SELF_LAUNCH, SELF_GUI }

    var active: Boolean = false
        private set
    // Keep stale queued GUI calls scoped after focus loss. Only an explicit target/boundary
    // can restore BASE; otherwise a second tap could silently hit an unrelated display/app.
    var lost: Boolean = false
        private set

    fun route(
        tool: String,
        launchPackage: String? = null,
        button: String? = null,
        waitPackage: String? = null,
    ): Route {
        if (tool == "launch_app") {
            leave()
            return if (launchPackage == ownPackage) Route.SELF_LAUNCH else Route.BASE
        }
        if (!active && !lost) return Route.BASE
        if (tool in LIFECYCLE) return Route.BASE
        val eligible = tool in SELF_GUI ||
            (tool == "press_key" && button in setOf("BACK", "ENTER", "PASTE")) ||
            (tool == "wait_for_package" && waitPackage == ownPackage)
        if (eligible) return Route.SELF_GUI
        // System panels, URI handlers, clocks and app switches never inherit the exception.
        leave()
        return Route.BASE
    }

    fun launchedSelf(success: Boolean) {
        active = success
        lost = !success
    }
    fun leave() {
        active = false
        lost = false
    }

    fun validateForeground(packageName: String?): Boolean {
        if (active && !lost && packageName == ownPackage) return true
        active = false
        lost = true
        return false
    }

    companion object {
        private val LIFECYCLE = setOf("start_virtual_session", "keep_virtual_result", "finish_virtual_session")
        private val SELF_GUI = setOf(
            "observe_screen", "wait", "wait_for_text", "tap", "tap_area", "tap_element",
            "long_press", "long_press_element", "swipe", "scroll", "scroll_element",
            "input_text", "replace_text", "clear_text", "paste_text",
        )
    }
}
