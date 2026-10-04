package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.skill.SkillIndexEntry

internal data class SkillSlashQuery(
    val start: Int,
    val query: String,
)

internal object SkillSlashMention {
    private val idPattern = Regex("/skill:([A-Za-z0-9._-]+)")

    fun queryAtCursor(text: String, cursor: Int): SkillSlashQuery? {
        val end = cursor.coerceIn(0, text.length)
        val start = text.lastIndexOf('/', end - 1)
        if (start < 0 || (start > 0 && !text[start - 1].isWhitespace())) return null
        val query = text.substring(start + 1, end)
        if (query.any { it.isWhitespace() }) return null
        return SkillSlashQuery(start, query.removePrefix("skill:"))
    }

    fun selectedIds(text: String): Set<String> =
        idPattern.findAll(text).map { it.groupValues[1] }.toSet()

    fun candidates(skills: List<SkillIndexEntry>, query: String, selectedIds: Set<String>): List<SkillIndexEntry> {
        val normalized = query.trim().lowercase()
        return skills
            .asSequence()
            .filter { it.id !in selectedIds }
            .filter { normalized.isBlank() || listOf(it.id, it.name, it.description).any { value -> value.lowercase().contains(normalized) } }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .take(20)
            .toList()
    }

    fun token(skillId: String): String = "/skill:$skillId "
}
