package io.github.mangi.eta.agent.skill

import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SkillRunIsolationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = temporary.root
    }
    private fun createEntry(context: android.content.Context, body: String = "version one"): SkillIndexEntry {
        val root = File(SkillRuntime.skillsRoot(context), "demo").apply { mkdirs() }
        File(root, "SKILL.md").writeText("---\nname: demo\ndescription: test\n---\n$body")
        File(root, "references").mkdirs()
        File(root, "references/guide.md").writeText(body)
        return SkillIndexEntry(id = "demo", name = "demo", description = "test", rootPath = root.path,
            skillFilePath = File(root, "SKILL.md").path, hasScripts = false, hasReferences = true,
            hasAssets = false, hasEvals = false)
    }
    @After fun releaseViews() {
        val context = context()
        listOf("assistant-a", "assistant-b").forEach { id ->
            File(SkillRuntime.assistantSkillsDirectory(context, id), ".runs").listFiles().orEmpty().forEach { view ->
                SkillRuntime.releaseRunSkills(context, File(view, "skills"))
            }
        }
    }
    private fun data(root: File) = File(root.parentFile, "skill-data/demo").canonicalFile

    @Test fun sameSkillHasSeparateAssistantDataAndSeparateRunRoots() {
        val context = context(); val entry = createEntry(context)
        val (a, _) = SkillRuntime.createRunSkills(context, "assistant-a", listOf(entry))
        val (b, _) = SkillRuntime.createRunSkills(context, "assistant-b", listOf(entry))
        File(data(a), "note.txt").writeText("A only")
        assertNotEquals(a.canonicalPath, b.canonicalPath)
        assertNotEquals(data(a), data(b))
        assertFalse(File(data(b), "note.txt").exists())
    }

    @Test fun updateDoesNotChangeAnExistingRunSnapshot() {
        val context = context(); val entry = createEntry(context)
        val (oldRoot, oldEntries) = SkillRuntime.createRunSkills(context, "assistant-a", listOf(entry))
        createEntry(context, "version two")
        val (newRoot, _) = SkillRuntime.createRunSkills(context, "assistant-a", listOf(entry))
        assertTrue(File(oldEntries.single().skillFilePath).readText().contains("version one"))
        assertTrue(File(newRoot, "demo/SKILL.md").readText().contains("version two"))
        assertNotEquals(oldRoot, newRoot)
        assertEquals(data(oldRoot), data(newRoot))
    }

    @Test fun disablingHidesRunCodeButKeepsDataForReenable() {
        val context = context(); val entry = createEntry(context)
        val (oldRoot, _) = SkillRuntime.createRunSkills(context, "assistant-a", listOf(entry))
        File(data(oldRoot), "note.txt").writeText("keep me")
        SkillRuntime.publishVisibleSkills(context, "assistant-a", emptyList())
        assertFalse(File(oldRoot, "demo/SKILL.md").exists())
        val (newRoot, _) = SkillRuntime.createRunSkills(context, "assistant-a", listOf(entry))
        assertEquals("keep me", File(data(newRoot), "note.txt").readText())
        assertFalse(File(oldRoot, "demo/SKILL.md").exists())
    }

    @Test fun enablingAfterAnEmptySnapshotDoesNotExpandThatRun() {
        val context = context(); val entry = createEntry(context)
        val (emptyRoot, entries) = SkillRuntime.createRunSkills(context, "assistant-a", emptyList())
        SkillRuntime.publishVisibleSkills(context, "assistant-a", listOf(entry))
        assertTrue(entries.isEmpty())
        assertTrue(emptyRoot.listFiles().orEmpty().isEmpty())
    }

    @Test fun abandonedSnapshotWithoutLeaseIsCollected() {
        val context = context()
        val (_, _) = SkillRuntime.createRunSkills(context, "assistant-a", listOf(createEntry(context)))
        val runs = File(SkillRuntime.assistantSkillsDirectory(context, "assistant-a"), ".runs")
        // 模拟崩溃：锁文件没写成，目录已经是老目录。
        val orphan = File(runs, "orphan-without-lease").apply { mkdirs() }
        File(orphan, "skills").mkdirs()
        File(orphan, "skills/ghost").apply { mkdirs() }
        File(orphan, "skills/ghost/SKILL.md").writeText("---\nname: ghost\n---\nbody")
        val aged = System.currentTimeMillis() - 60L * 60 * 1000
        assertTrue(orphan.setLastModified(aged))
        assertTrue(File(orphan, "skills").setLastModified(aged))

        SkillRuntime.createRunSkills(context, "assistant-a", listOf(createEntry(context)))

        assertFalse("无锁文件的孤儿快照应被回收", orphan.exists())
    }

    @Test fun freshSnapshotWithoutLeaseSurvivesTheGraceWindow() {
        val context = context()
        val runs = File(SkillRuntime.assistantSkillsDirectory(context, "assistant-a"), ".runs").apply { mkdirs() }
        // 刚创建、还没写 .lease 的快照不能被当成垃圾。
        val pending = File(runs, "pending-create").apply { mkdirs() }
        File(pending, "skills").mkdirs()

        SkillRuntime.createRunSkills(context, "assistant-a", listOf(createEntry(context)))

        assertTrue("创建窗口内的快照不应被回收", pending.exists())
    }

    @Test fun releaseDeletesSnapshotWithoutFollowingTheDataPointer() {
        val context = context(); val entry = createEntry(context)
        val (root, _) = SkillRuntime.createRunSkills(context, "assistant-a", listOf(entry))
        val note = File(data(root), "note.txt").apply { writeText("keep") }
        SkillRuntime.releaseRunSkills(context, root)
        assertFalse(root.exists())
        assertEquals("keep", note.readText())
    }

    @Test fun failedPublishDoesNotLeaveAPartiallyUsableRun() {
        val context = context(); val entry = createEntry(context)
        File(entry.skillFilePath).delete()
        assertThrows(IllegalStateException::class.java) { SkillRuntime.createRunSkills(context, "assistant-a", listOf(entry)) }
        val runs = File(SkillRuntime.assistantSkillsDirectory(context, "assistant-a"), ".runs")
        assertTrue(runs.listFiles().orEmpty().isEmpty())
    }

    @Test fun revokingADoesNotHideBWithTheSameSkillId() {
        val context = context(); val entry = createEntry(context)
        SkillRuntime.createRunSkills(context, "assistant-a", listOf(entry))
        val (b, _) = SkillRuntime.createRunSkills(context, "assistant-b", listOf(entry))
        SkillRuntime.publishVisibleSkills(context, "assistant-a", emptyList())
        assertTrue(File(b, "demo/SKILL.md").isFile)
    }
}
