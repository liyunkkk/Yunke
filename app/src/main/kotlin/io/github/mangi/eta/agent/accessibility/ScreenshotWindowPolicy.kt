package io.github.mangi.eta.agent.accessibility

/** 截图窗口筛选必须保持“模型看到的”和实际接收触摸的窗口一致。 */
internal object ScreenshotWindowPolicy {
    enum class Decision {
        CAPTURE,
        EXCLUDE,
        BLOCK_UNKNOWN,
    }

    fun decide(
        isAccessibilityOverlay: Boolean,
        isApplicationWindow: Boolean,
        active: Boolean,
        focused: Boolean,
        resolvedPackage: String?,
        ownPackage: String,
        excludedPackages: Set<String>,
        windowTitle: String? = null,
    ): Decision {
        if (isAccessibilityOverlay && resolvedPackage == ownPackage) {
            return Decision.EXCLUDE
        }
        // 我们自己的浮窗（胶囊/氛围光/结果卡）改为 TYPE_APPLICATION_OVERLAY 后，
        // 不再命中上面的无障碍类型判据；按标题排除，避免 Agent 在前台截图里
        // 看到自己的状态胶囊与微缩镜。第三方同包名窗口不受影响（包名即自己）。
        if (resolvedPackage == ownPackage && windowTitle.isOwnOverlayTitle()) {
            return Decision.EXCLUDE
        }
        if (resolvedPackage in excludedPackages) return Decision.EXCLUDE
        if (
            excludedPackages.isNotEmpty() &&
            resolvedPackage.isNullOrBlank() &&
            (isApplicationWindow || active || focused)
        ) {
            return Decision.BLOCK_UNKNOWN
        }
        return Decision.CAPTURE
    }

    /** 浮窗标题统一前缀；新增浮窗时保持该前缀即可被截图过滤。 */
    private const val OWN_OVERLAY_TITLE_PREFIX = "Eta Agent"

    private fun String?.isOwnOverlayTitle(): Boolean =
        this != null && this.startsWith(OWN_OVERLAY_TITLE_PREFIX)
}
