package io.github.mangi.eta.agent.terminal

import org.junit.Assert.*
import org.junit.Test

class LongShellCommandTest {
    @Test fun scriptBeyondKernelArgumentLimitPreservesUnicodeHeredocAndStdin() {
        val source = "cat <<'END' >/dev/null\n" + "中文 ' \" literal \u0024()\n".repeat(14000) + "END\ncat\nprintf '\\nfinished'"
        val prepared = LongShellCommand.prepare(source, TerminalEnvironment.ANDROID, null)
        assertNotNull(prepared.file)
        assertTrue(prepared.command!!.length < 1024)
        val supervisor = ShellProcessSupervisor()
        try {
            val result = runOneShotShell(supervisor, "user", prepared.command, 10, stdin = "input-preserved".toByteArray())
            assertEquals(result.stderr.decodeToString(), 0, result.exitCode)
            assertEquals("input-preserved\nfinished", result.output.decodeToString())
            assertFalse(prepared.file!!.exists())
        } finally { prepared.file?.delete(); supervisor.beginClosing(); supervisor.takeRemainingProcesses().forEach { supervisor.terminateAndReap(it); supervisor.unregisterProcess(it) } }
    }
    @Test fun linuxStagedScriptsUseExplicitGuestMounts() {
        for ((rootfs, expected) in listOf("/rootfs" to LongShellCommand.CHROOT_SCRIPTS_DIR,
            "/app/terminal-user/proot/debian/rootfs" to "/dev/shm")) {
            val prepared = LongShellCommand.prepare("# padding".repeat(2000), TerminalEnvironment.DEBIAN, rootfs)
            try {
                val file = requireNotNull(prepared.file)
                assertEquals(". " + shellQuote("$expected/${file.name}"), prepared.command)
                assertTrue(file.readText().startsWith("rm -f " + shellQuote("$expected/${file.name}")))
                assertFalse(prepared.command!!.contains("/proc/"))
            } finally { prepared.file?.delete() }
        }
    }

    @Test fun ordinaryAndInteractiveCommandsAreNotStaged() {
        assertNull(LongShellCommand.prepare(null, TerminalEnvironment.ANDROID, null).file)
        val command = "printf hello"
        assertEquals(command, LongShellCommand.prepare(command, TerminalEnvironment.ANDROID, null).command)
    }
}
