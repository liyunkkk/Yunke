package io.github.mangi.eta.agent.overlay

internal data class AgentOrbPosition(val x: Float, val y: Float)
internal data class AgentOrbBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** One physical pointer owns a gesture. Once slop is crossed it cannot become a click. */
internal class AgentOrbDragGesture(touchSlop: Float) {
    private val slopSquared = touchSlop.coerceAtLeast(0f).let { it * it }
    private var pointerId: Int? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    fun begin(id: Int, rawX: Float, rawY: Float) {
        cancel()
        if (!rawX.isFinite() || !rawY.isFinite()) return
        pointerId = id
        downX = rawX
        downY = rawY
    }

    fun move(id: Int, rawX: Float, rawY: Float): AgentOrbPosition? {
        if (pointerId == null) return null
        if (pointerId != id || !rawX.isFinite() || !rawY.isFinite()) {
            cancel()
            return null
        }
        val dx = rawX - downX
        val dy = rawY - downY
        if (dx * dx + dy * dy > slopSquared) dragging = true
        return if (dragging) AgentOrbPosition(dx, dy) else null
    }

    fun finish(id: Int): Boolean {
        val click = pointerId == id && !dragging
        cancel()
        return click
    }

    fun cancel() {
        pointerId = null
        dragging = false
    }
}

/** Only a matched press/release at this focused node activates keyboard click. */
internal class AgentOrbActivationKey {
    private var pressed: Int? = null
    fun down(key: Int) { if (pressed == null) pressed = key }
    fun up(key: Int): Boolean {
        val click = pressed == key
        cancel()
        return click
    }
    fun cancel() { pressed = null }
}

/** Physical pixel geometry; no END gravity sign or RTL dependence. */
internal object AgentOrbPlacement {
    fun clamp(position: AgentOrbPosition, bounds: AgentOrbBounds, width: Int, height: Int): AgentOrbPosition =
        AgentOrbPosition(
            position.x.coerceIn(bounds.left.toFloat(), (bounds.right - width).coerceAtLeast(bounds.left).toFloat()),
            position.y.coerceIn(bounds.top.toFloat(), (bounds.bottom - height).coerceAtLeast(bounds.top).toFloat()),
        )

    fun initial(bounds: AgentOrbBounds, width: Int, height: Int, margin: Int): AgentOrbPosition = clamp(
        AgentOrbPosition(
            (bounds.right - width - margin).toFloat(),
            bounds.top + (bounds.bottom - bounds.top) * 0.6f,
        ), bounds, width, height,
    )

    fun bubble(
        orb: AgentOrbPosition,
        orbWidth: Int,
        orbHeight: Int,
        width: Int,
        height: Int,
        bounds: AgentOrbBounds,
        gap: Int,
    ): AgentOrbPosition {
        val left = orb.x - gap - width
        val right = orb.x + orbWidth + gap
        if (left >= bounds.left) return clamp(AgentOrbPosition(left, orb.y), bounds, width, height)
        if (right + width <= bounds.right) return clamp(AgentOrbPosition(right, orb.y), bounds, width, height)
        // On a narrow display neither side fits: keep the controls above/below the
        // orb rather than clamping them over its only drag/collapse touch target.
        val below = orb.y + orbHeight + gap
        val above = orb.y - gap - height
        val y = when {
            below + height <= bounds.bottom -> below
            above >= bounds.top -> above
            bounds.bottom - below >= orb.y - gap - bounds.top -> below
            else -> above
        }
        return clamp(AgentOrbPosition(orb.x, y), bounds, width, height)
    }
}
