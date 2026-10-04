package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.skill.SkillIndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillSlashMentionTest {
    private fun skill(id: String, name: String = id, description: String = "description") = SkillIndexEntry(
        id = id,
        name = name,
        description = description,
        rootPath = "/var/minis/skills/$id",
        skillFilePath = "/var/minis/skills/$id/SKILL.md",
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
    )

    @Test fun queryOnlyStartsAtWhitespaceBoundary() {
        assertEquals(SkillSlashQuery(0, ""), SkillSlashMention.queryAtCursor("/", 1))
        assertEquals(SkillSlashQuery(4, "plan"), SkillSlashMention.queryAtCursor("run /plan", 9))
        assertNull(SkillSlashMention.queryAtCursor("https://example.com", 19))
        assertNull(SkillSlashMention.queryAtCursor("/two words", 10))
    }

    @Test fun candidatesFilterAndSelectedIdsAreStable() {
        val skills = listOf(skill("z", "Zed"), skill("a", "Alpha", "planning"), skill("b", "Beta"))
        assertEquals(listOf("a", "z"), SkillSlashMention.candidates(skills, "", setOf("b")).map { it.id })
        assertEquals(listOf("a"), SkillSlashMention.candidates(skills, "plan", emptySet()).map { it.id })
        assertEquals(setOf("a", "b"), SkillSlashMention.selectedIds("/skill:a do /skill:b"))
    }

    @Test fun tokenIsExplicitAndCanBeAppended() {
        assertEquals("/skill:browser ", SkillSlashMention.token("browser"))
        assertTrue(SkillSlashMention.queryAtCursor("use /skill:browser ", 20) == null)
    }
}
