package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentPromptBuilder
import io.github.mangi.eta.agent.skill.SkillContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Pure file-metadata tests; nothing is executed. Layout mirrors a real Debian rootfs. */
class AgentShellToolAvailabilityTest {
    @get:Rule val temp = TemporaryFolder()

    private fun executable(file: File) = file.apply {
        parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true)
    }
    private fun link(at: File, target: String) {
        at.parentFile.mkdirs(); Files.createSymbolicLink(at.toPath(), File(target).toPath())
    }

    private fun rootfs(): File = temp.newFolder("rootfs").also { root ->
        executable(File(root, "usr/bin/git"))
        executable(File(root, "usr/bin/gh"))
        // Absolute guest links, as installed by the Python/Node profiles.
        executable(File(root, "opt/eta/python/cpython-3.14.7-linux-aarch64-gnu/bin/python3.14"))
        link(File(root, "opt/eta/python/cpython-3.14-linux-aarch64-gnu"), "/opt/eta/python/cpython-3.14.7-linux-aarch64-gnu")
        link(File(root, "usr/local/bin/python3"), "/opt/eta/python/cpython-3.14-linux-aarch64-gnu/bin/python3.14")
        executable(File(root, "opt/eta/node/26.8.1/bin/node"))
        link(File(root, "usr/local/bin/node"), "/opt/eta/node/26.8.1/bin/node")
        // Relative link chain.
        executable(File(root, "opt/eta/node/26.8.1/lib/node_modules/npm/bin/npm-cli.js"))
        link(File(root, "opt/eta/node/26.8.1/bin/npm"), "../lib/node_modules/npm/bin/npm-cli.js")
        link(File(root, "usr/local/bin/npm"), "/opt/eta/node/26.8.1/bin/npm")
        executable(File(root, "usr/local/bin/uv"))
        // Dangling link must not count.
        link(File(root, "usr/local/bin/jadx"), "/opt/missing/jadx")
        // Non-executable file must not count.
        File(root, "usr/bin/curl").apply { parentFile.mkdirs(); writeText("x"); setExecutable(false) }
    }

    @Test
    fun resolvesAbsoluteAndRelativeGuestLinksInsideRootfs() {
        val root = rootfs()
        val snapshot = AgentShellToolAvailability.detect(root, "Debian", androidDirs = emptyList())
        assertTrue(snapshot.linuxReady)
        assertEquals(listOf("git", "gh", "python3", "uv", "node", "npm"), snapshot.linux)
        assertTrue(snapshot.android.isEmpty())
    }

    @Test
    fun linksCannotEscapeTheRootfs() {
        val outside = executable(File(temp.root, "host-only/bin/java"))
        val root = temp.newFolder("jail")
        link(File(root, "usr/bin/java"), "../../../../host-only/bin/java")
        link(File(root, "usr/bin/ssh"), outside.absolutePath)
        val snapshot = AgentShellToolAvailability.detect(root, "Debian", androidDirs = emptyList())
        assertFalse(snapshot.linux.contains("java"))
        assertFalse(snapshot.linux.contains("ssh"))
    }

    @Test
    fun linkLoopsAreRejected() {
        val root = temp.newFolder("loop")
        link(File(root, "usr/bin/git"), "/usr/bin/gh")
        link(File(root, "usr/bin/gh"), "/usr/bin/git")
        assertNull(AgentShellToolAvailability.resolveGuest(root, "/usr/bin/git"))
        assertTrue(AgentShellToolAvailability.detect(root, "Debian", emptyList()).linux.isEmpty())
    }

    @Test
    fun androidToolsAreDetectedFromTheirOwnPathAndNotAssumedAbsent() {
        val bin = temp.newFolder("android-bin")
        executable(File(bin, "git"))
        executable(File(bin, "curl"))
        val snapshot = AgentShellToolAvailability.detect(null, "Alpine", listOf(bin.absolutePath))
        assertFalse(snapshot.linuxReady)
        assertTrue(snapshot.linux.isEmpty())
        assertEquals(listOf("git", "curl"), snapshot.android)
        val note = AgentShellToolAvailability.promptNote(snapshot)
        assertTrue(note, note.contains("Linux（Alpine）工具环境未就绪"))
        assertTrue(note, note.contains("Android 检测到：git、curl"))
    }

    @Test
    fun androidDirsKeepAbsolutePathEntriesAndFallbacks() {
        val dirs = AgentShellToolAvailability.androidDirs("/data/adb/ksu/bin:relative:/system/bin")
        assertEquals("/data/adb/ksu/bin", dirs.first())
        assertFalse(dirs.contains("relative"))
        assertEquals(1, dirs.count { it == "/system/bin" })
    }

    @Test
    fun promptNotePrefersLinuxButKeepsAndroidSystemCommandsOnAndroid() {
        val snapshot = AgentShellToolAvailability.detect(rootfs(), "Debian", emptyList())
        val note = AgentShellToolAvailability.promptNote(snapshot)
        assertTrue(note, note.contains("Linux（Debian）检测到：git、gh、python3、uv、node、npm"))
        assertTrue(note, note.contains("Android 检测到：无"))
        assertTrue(note, note.contains("两个环境都未检测到：pip3、java、jadx、apktool、curl、ssh"))
        assertTrue(note, note.contains("优先 terminal(environment=linux)"))
        assertTrue(note, note.contains("pm、am、cmd、dumpsys"))
        assertTrue(note, note.contains("未执行任何命令"))
    }

    @Test
    fun promptCarriesDetectionAndDropsHardAndroidFirstRule() {
        val snapshot = AgentShellToolAvailability.detect(rootfs(), "Debian", emptyList())
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1", apiKey = "k", model = "m", systemPrompt = "",
            terminalTools = true, browserTools = false,
        )
        for (root in listOf(true, false)) {
            val text = (0 until AgentPromptBuilder.buildSystemMessages(config, SkillContext.EMPTY,
                AgentMemoryContext.DISABLED, rootAvailable = root, shellTools = snapshot).length()).joinToString("\n") {
                AgentPromptBuilder.buildSystemMessages(config, SkillContext.EMPTY, AgentMemoryContext.DISABLED,
                    rootAvailable = root, shellTools = snapshot).getJSONObject(it).optString("content")
            }
            assertTrue(text.contains("本机命令检测"))
            assertTrue(text.contains("environment 按下方本机命令检测选择"))
            assertFalse(text.contains("首轮调用 terminal，action=open_and_exec，environment=android"))
            assertFalse(text.contains("未指定环境的命令使用 terminal 的 environment=android"))
        }
        // Without a snapshot the prompt stays valid and carries no fabricated detection.
        val plain = AgentPromptBuilder.buildSystemMessages(config, SkillContext.EMPTY, AgentMemoryContext.DISABLED, true)
        assertFalse((0 until plain.length()).any { plain.getJSONObject(it).optString("content").contains("本机命令检测") })
    }
}
