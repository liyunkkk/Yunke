package io.github.mangi.eta.agent.pet

internal const val WHALE_MAID_MAX_SATIETY = 30_000

internal enum class WhaleMaidSatietyBand {
    STARVING,
    HUNGRY,
    PECKISH,
    FULL,
    STUFFED,
}

internal data class WhaleMaidReaction(
    val mood: String,
    val speech: String,
)

internal val WHALE_MAID_MOODS = setOf(
    "idle", "hungry", "happy", "angry", "eating", "scared", "sad", "thinking",
)

internal fun whaleMaidSatietyBand(satiety: Int): WhaleMaidSatietyBand = when {
    satiety > 20_000 -> WhaleMaidSatietyBand.STUFFED
    satiety >= 10_000 -> WhaleMaidSatietyBand.FULL
    satiety >= 5_000 -> WhaleMaidSatietyBand.PECKISH
    satiety >= 2_000 -> WhaleMaidSatietyBand.HUNGRY
    else -> WhaleMaidSatietyBand.STARVING
}

internal fun whaleMaidSatietyPercent(satiety: Int, maxSatiety: Int = WHALE_MAID_MAX_SATIETY): Int {
    val cap = maxSatiety.coerceAtLeast(1)
    return (satiety.coerceAtLeast(0) * 100 / cap).coerceIn(0, 100)
}

internal fun whaleMaidPrompt(
    eventType: String,
    satiety: Int,
    foodName: String,
    tokensGained: Int,
    sessionTitle: String,
    recentTasks: List<String>,
    memories: List<String>,
): String {
    val safeSatiety = satiety.coerceAtLeast(0)
    val percent = whaleMaidSatietyPercent(safeSatiety)
    val guidance = when (whaleMaidSatietyBand(safeSatiety)) {
        WhaleMaidSatietyBand.STUFFED -> "当前状态是“吃得有点撑了！”：表现肚肚圆滚滚吃太撑、傲娇抱怨主人别喂太多了。"
        WhaleMaidSatietyBand.FULL -> "当前状态饱腹满足，不需要特别描写饥饿感，重点关注工作与日常。"
        WhaleMaidSatietyBand.PECKISH -> "当前状态是“不饿，但是不介意吃多点！”：表示还可以勉强再吃点零食米饭。"
        WhaleMaidSatietyBand.HUNGRY -> "当前状态是“很饿！”：肚子咕咕叫，急切暗示催促主人喂米饭。"
        WhaleMaidSatietyBand.STARVING -> "当前状态是“快要饿死了！”：虚弱极了，害怕慌张地向主人求救要米饭保命。"
    }
    val tasks = recentTasks.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
    val tasksDesc = if (tasks.isEmpty()) {
        "用户最近干的活有“${sessionTitle.ifBlank { "当前任务" }}”。"
    } else {
        "用户最近干的活有" + tasks.joinToString("、") { "“$it”" } + "。"
    }
    val eventDesc = if (eventType == "feed") {
        "主人投喂了${foodName.ifBlank { "米饭" }}，获得了${tokensGained.coerceAtLeast(1)}token的饱食度"
    } else {
        "当前工作会话《${sessionTitle.ifBlank { "无标题" }}》的任务已全部完成"
    }
    val memoryContext = memories.map { it.trim() }.filter { it.isNotEmpty() }.take(5)
        .joinToString("\n") { "- $it" }
        .ifBlank { "无" }
    return """
        你是一个深海鲸鱼女仆桌宠，头顶有小呆毛，身穿深蓝水手女仆裙。
        你的性格带有一点傲娇，但心里特别关心和崇拜主人。
        主人正在使用代鱼处理对话、代码和其他任务。

        【当前饱腹度状态】：
        当前剩余粮食：$safeSatiety Token，饱腹度百分比：$percent%。
        【饱腹感知指引】：
        $guidance

        【主人最近干的活（近5小时内）】：
        $tasksDesc

        【当前事件】：
        $eventDesc

        【你此前说过的话（严禁复读或使用类似句式）】：
        $memoryContext

        【8种心情动作选项】：
        1. idle: 日常自然待机；
        2. hungry: 摸肚子向主人讨饭；
        3. happy: 满心欢喜心满意足；
        4. angry: 傲娇叉腰气鼓鼓；
        5. eating: 捧着大米饭狂炫、大快朵颐（投喂时特别合适）；
        6. scared: 受到惊吓、冷汗发抖（快要饿死或任务出错时）；
        7. sad: 委屈失落揉眼叹气（很饿或委屈时）；
        8. thinking: 托下巴若有所思、灵光一闪。

        【严格输出约束】：
        1. 必须输出纯单行JSON，格式严格为：
        {"mood": "idle"|"hungry"|"happy"|"angry"|"eating"|"scared"|"sad"|"thinking", "speech": "你说的话"}
        2. speech 必须在 25 字以内，口吻生动可爱带轻微傲娇，符合你所选的 mood。
        3. 若是投喂事件，请必须对主人投喂的具体食物进行针对性评价，表达品尝感受、感谢或傲娇吐槽，mood 优先选 eating 或 happy。
        4. 全程绝对禁止出现任何 emoji 表情符号，绝对禁止任何 markdown 代码块！
        5. mood 只能从 idle, hungry, happy, angry, eating, scared, sad, thinking 八个词中选取一个。
    """.trimIndent()
}

internal fun whaleMaidEstimateTokens(prompt: String, speech: String): Int =
    maxOf(40, (prompt.length + speech.length) / 3)

internal fun parseWhaleMaidReaction(
    raw: String,
    eventType: String,
    satiety: Int,
    foodName: String,
    sessionTitle: String,
): WhaleMaidReaction {
    val parsed = extractReactionJson(raw)
    val fallbackMood = when {
        satiety < 2_000 && eventType != "feed" -> "scared"
        eventType == "feed" -> "eating"
        else -> "happy"
    }
    val fallbackSpeech = if (eventType == "feed") {
        "品尝了${foodName.ifBlank { "米饭" }}，谢谢主人。"
    } else {
        "《${sessionTitle.ifBlank { "任务" }}》顺利搞定了。"
    }
    val mood = parsed?.first?.takeIf { it in WHALE_MAID_MOODS } ?: fallbackMood
    val speech = stripWhaleMaidEmoji(parsed?.second.orEmpty())
        .replace('\n', ' ')
        .trim()
        .take(25)
        .ifBlank { fallbackSpeech.take(25) }
    return WhaleMaidReaction(mood, speech)
}

private fun extractReactionJson(raw: String): Pair<String, String>? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val body = raw.substring(start + 1, end)
    val mood = jsonStringField(body, "mood") ?: return null
    val speech = jsonStringField(body, "speech") ?: return null
    return mood to speech
}

private fun jsonStringField(body: String, name: String): String? {
    val pattern = Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""")
    val match = pattern.find(body) ?: return null
    return match.groupValues[1]
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", " ")
        .replace("\\t", " ")
}

internal fun stripWhaleMaidEmoji(text: String): String = buildString(text.length) {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (!isWhaleMaidEmoji(codePoint)) appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
}

private fun isWhaleMaidEmoji(codePoint: Int): Boolean = codePoint in 0x1F000..0x1FAFF ||
    codePoint in 0x2600..0x27BF ||
    codePoint in 0xFE00..0xFE0F ||
    codePoint == 0x200D
