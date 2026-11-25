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
    /** GUI 悬浮胶囊或静默定时任务接管视觉位时抑制上岛提升，避免顶部双胶囊冲突（对齐 Eta 639eeb2）。 */
    val suppressLiveUpdate: Boolean = false,
) {
    /** 是否有前台任务在跑。IDLE 表示通知只剩常驻的空闲文案。 */
    val isRunning: Boolean get() = phase != AgentExecutionPhase.IDLE

    /**
     * 状态栏胶囊（Live Update 的短文本）用的文案：
     * 统一锁定为 6 个全角汉字视觉宽度（对齐「执行终端命令」的胶囊长度），
     * 避免思考/生成/不同工具切换时灵动岛胶囊忽长忽短；IDLE 返回空串。
     */
    fun shortText(): String = capsuleText(phase, title)

    /**
     * HyperOS 灵动岛右侧窄槽位文案：与 [shortText] 同口径，固定 6 全角字宽度。
     */
    fun islandShortText(): String = shortText()

    companion object {
        /** 胶囊最大码点上限（全角 6 字 / 半角 12 字均在此范围内）。 */
        const val SHORT_TEXT_MAX_CHARS = 12
        /** 灵动岛右侧固定宽度：6 个全角汉字 = 12 个半角视觉单位（与「执行终端命令」完全一致）。 */
        const val ISLAND_RIGHT_MAX_CHARS = 6
        private const val TARGET_ISLAND_UNITS = ISLAND_RIGHT_MAX_CHARS * 2
        private const val FULL_WIDTH_SPACE = '\u3000'
        private const val HALF_WIDTH_SPACE = '\u2002'

        /** Live Update（提升式常驻通知）从 Android 16 / API 36 开始可用。 */
        const val LIVE_UPDATE_MIN_SDK = 36

        /**
         * 是否把当前通知提升为 Live Update。
         *
         * 只有运行态（非 IDLE）且未被悬浮胶囊/静默定时任务抑制时才提升；
         * 终端会话、PRoot/Linux 启动等长驻任务不发布 [AgentExecutionState]，因此永远不会进入提升分支。
         */
        internal fun shouldPromoteToLiveUpdate(state: AgentExecutionState, sdkInt: Int): Boolean =
            sdkInt >= LIVE_UPDATE_MIN_SDK && state.isRunning && !state.suppressLiveUpdate

        internal fun capsuleText(phase: AgentExecutionPhase, title: String?): String {
            if (phase == AgentExecutionPhase.IDLE) return ""
            // 工具阶段展示具体工具名（≤4 字中文工具名自动补「正在」凑齐自然 6 字）；
            // 非工具阶段统一使用自然 6 字动作词，保证胶囊长度恒定不跳动。
            val base = if (phase == AgentExecutionPhase.TOOL_EXECUTING) {
                val clean = title?.let { stripCapsulePrefix(it) }?.takeIf { it.isNotEmpty() }
                if (clean != null) {
                    val units = visualUnits(clean)
                    if (units in 1..8 && !clean.startsWith("正在")) {
                        "正在$clean"
                    } else {
                        clean
                    }
                } else {
                    defaultCapsuleText(phase)
                }
            } else {
                defaultCapsuleText(phase)
            }
            return fixedIslandWidth(base)
        }

        /** 非工具阶段（以及工具标题缺失时）的固定 6 字胶囊文案，保证与「执行终端命令」视觉等宽。 */
        internal fun defaultCapsuleText(phase: AgentExecutionPhase): String = when (phase) {
            AgentExecutionPhase.IDLE -> ""
            AgentExecutionPhase.PREPARING -> "准备执行任务"
            AgentExecutionPhase.THINKING -> "正在深度思考"
            AgentExecutionPhase.TOOL_EXECUTING -> "正在执行操作"
            AgentExecutionPhase.GENERATING -> "正在生成回复"
            AgentExecutionPhase.TRANSLATING -> "正在翻译屏幕"
            AgentExecutionPhase.GENERIC_RUNNING -> "任务正在运行"
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

        /**
         * 把文案归一化到固定 [TARGET_ISLAND_UNITS]（6 个全角汉字）视觉宽度：
         * - 超宽时按码点截断并在末尾补省略号（绝不切断代理对）；
         * - 不足时在左侧补不可被 trim 吃掉的全角/半角空格，使右侧文字紧贴岛右缘且胶囊宽度恒定。
         */
        internal fun fixedIslandWidth(text: String, targetUnits: Int = TARGET_ISLAND_UNITS): String {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || targetUnits <= 0) return ""
            val units = visualUnits(trimmed)
            if (units == targetUnits) return trimmed
            if (units > targetUnits) {
                val limit = (targetUnits - 2).coerceAtLeast(0)
                val out = StringBuilder()
                var used = 0
                var i = 0
                while (i < trimmed.length) {
                    val cp = trimmed.codePointAt(i)
                    val w = codePointUnits(cp)
                    if (used + w > limit) break
                    out.appendCodePoint(cp)
                    used += w
                    i += Character.charCount(cp)
                }
                val clipped = out.toString().trimEnd() + "…"
                return padLeftToWidth(clipped, targetUnits)
            }
            return padLeftToWidth(trimmed, targetUnits)
        }

        private fun padLeftToWidth(text: String, targetUnits: Int): String {
            val deficit = targetUnits - visualUnits(text)
            if (deficit <= 0) return text
            val fullSpaces = deficit / 2
            val halfSpaces = deficit % 2
            val prefix = buildString(fullSpaces + halfSpaces) {
                repeat(fullSpaces) { append(FULL_WIDTH_SPACE) }
                repeat(halfSpaces) { append(HALF_WIDTH_SPACE) }
            }
            return prefix + text
        }

        internal fun visualUnits(text: String): Int {
            var total = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                total += codePointUnits(cp)
                i += Character.charCount(cp)
            }
            return total
        }

        private fun codePointUnits(cp: Int): Int = when {
            cp == HALF_WIDTH_SPACE.code -> 1
            cp == FULL_WIDTH_SPACE.code -> 2
            cp <= 0x7F -> 1
            else -> 2
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
