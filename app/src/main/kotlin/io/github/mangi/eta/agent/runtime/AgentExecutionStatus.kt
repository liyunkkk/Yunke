package io.github.mangi.eta.agent.runtime

internal enum class AgentExecutionPhase {
    IDLE,
    PREPARING,
    THINKING,
    TOOL_EXECUTING,
    GENERATING,
    TRANSLATING,
    GENERIC_RUNNING,
}

internal data class AgentExecutionState(
    val phase: AgentExecutionPhase = AgentExecutionPhase.IDLE,
    val title: String? = null,
    val subtitle: String? = null,
    val detail: String? = null,
    val startedAtElapsedRealtime: Long = 0L,
    val showChronometer: Boolean = false,
    val expandedSnippet: String? = null,
) {
    /** 是否有前台任务在跑。IDLE 表示通知只剩常驻的空闲文案。 */
    val isRunning: Boolean get() = phase != AgentExecutionPhase.IDLE

    /**
     * 状态栏胶囊（Live Update 的短文本）用的文案：优先当前动作（工具显示名 / 思考中 / 生成中），
     * 按 [SHORT_TEXT_MAX_CHARS] 个码点截断，IDLE 返回空串。
     */
    fun shortText(): String = capsuleText(phase, title)

    companion object {
        /** 胶囊口径：Eta 侧约 7 个字符可完整显示，这里按 12 个码点留余量。 */
        const val SHORT_TEXT_MAX_CHARS = 12

        /** Live Update（提升式常驻通知）从 Android 16 / API 36 开始可用。 */
        const val LIVE_UPDATE_MIN_SDK = 36

        /**
         * 是否把当前通知提升为 Live Update。
         *
         * 只有运行态（非 IDLE）才提升；终端会话、PRoot/Linux 启动等长驻任务不发布
         * [AgentExecutionState]，因此永远不会进入提升分支。SDK 判断抽成纯函数，便于单测覆盖
         * 低版本路径（低版本恒为 false，通知构建走原有分支）。
         */
        internal fun shouldPromoteToLiveUpdate(state: AgentExecutionState, sdkInt: Int): Boolean =
            sdkInt >= LIVE_UPDATE_MIN_SDK && state.isRunning

        internal fun capsuleText(phase: AgentExecutionPhase, title: String?): String {
            if (phase == AgentExecutionPhase.IDLE) return ""
            // 工具阶段胶囊显示具体工具（当前动作最有信息量）；其余阶段用固定短词，
            // 不受标题措辞（emoji、省略号、模板串）影响，长度天然可控。
            val base = if (phase == AgentExecutionPhase.TOOL_EXECUTING) {
                title?.let { stripCapsulePrefix(it) }?.takeIf { it.isNotEmpty() }
                    ?: defaultCapsuleText(phase)
            } else {
                defaultCapsuleText(phase)
            }
            return clampCapsuleText(base, SHORT_TEXT_MAX_CHARS)
        }

        /** 非工具阶段（以及工具标题缺失时）的胶囊文案，口径对齐 Eta 的 execution_chip_* / overlay_*。 */
        internal fun defaultCapsuleText(phase: AgentExecutionPhase): String = when (phase) {
            AgentExecutionPhase.IDLE -> ""
            AgentExecutionPhase.PREPARING -> "准备中"
            AgentExecutionPhase.THINKING -> "思考中"
            AgentExecutionPhase.TOOL_EXECUTING -> "操作中"
            AgentExecutionPhase.GENERATING -> "生成中"
            AgentExecutionPhase.TRANSLATING -> "翻译中"
            AgentExecutionPhase.GENERIC_RUNNING -> "运行中"
        }

        /**
         * 去掉标题前缀的图标与结尾省略号：通知标题形如「⚡ 点击屏幕」「🧠 深度思考…」，
         * 胶囊里只留文字，避免 emoji 在状态栏被裁成半截。
         */
        internal fun stripCapsulePrefix(raw: String): String {
            val text = raw.trim()
            var index = 0
            // 代理对两半都不是字母数字，逐字符跳过不会停在半个 emoji 上。
            while (index < text.length && !text[index].isLetterOrDigit()) index++
            return text.substring(index).trim().trimEnd('…', '.', '。', '，', ',', ' ')
        }

        /** 按码点截断（不切断代理对），超长时补省略号，结果长度恒为 [maxChars] 个码点。 */
        internal fun clampCapsuleText(text: String, maxChars: Int = SHORT_TEXT_MAX_CHARS): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || maxChars <= 0) return ""
            val codePointCount = trimmed.codePointCount(0, trimmed.length)
            if (codePointCount <= maxChars) return trimmed
            val end = trimmed.offsetByCodePoints(0, maxChars - 1)
            return trimmed.substring(0, end).trimEnd() + "…"
        }
    }
}
