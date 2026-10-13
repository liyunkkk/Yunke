package io.github.mangi.eta.agent.terminal

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProcessSupervisorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun delayedOwnershipBeyondOldBudgetIsAcceptedAndOutputIsPreserved() {
        val launcher = setsidLauncher("time.sleep(0.8)")
        val supervisor = ShellProcessSupervisor(allowTreeFallback = false, setsidCommand = launcher.absolutePath)
        try {
            val result = runOneShotShell(supervisor, "user", "printf delayed-ok; printf delayed-err >&2", 5)
            assertEquals(0, result.exitCode)
            assertEquals("delayed-ok", result.output.toString(Charsets.UTF_8))
            assertEquals("delayed-err", result.stderr.toString(Charsets.UTF_8))
        } finally {
            close(supervisor)
        }
    }

    @Test
    fun ownershipTimeoutIsBoundedAndReturnsSafeStage() {
        val launcher = setsidLauncher("time.sleep(30)")
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = launcher.absolutePath,
            startupOwnershipWaitMs = 100,
        )
        try {
            val started = System.nanoTime()
            val result = runOneShotShell(supervisor, "user", "printf secret-command", 5)
            assertEquals(-1, result.exitCode)
            assertTrue(result.stderr.toString(Charsets.UTF_8).contains("stage=ownership_timeout"))
            assertFalse(result.stderr.toString(Charsets.UTF_8).contains("secret-command"))
            assertTrue("timeout must not incur another admission wait", elapsedMs(started) < 3_000)
            assertTrue(supervisor.takeRemainingProcesses().isEmpty())
        } finally {
            close(supervisor)
        }
    }

    @Test
    fun earlyLauncherExitIsReportedWithoutWaitingForOwnershipTimeout() {
        val launcher = setsidLauncher("sys.stderr.write('permission denied secret-stderr-sentinel\\n')\nsys.exit(126)")
        val supervisor = ShellProcessSupervisor(allowTreeFallback = false, setsidCommand = launcher.absolutePath)
        try {
            val started = System.nanoTime()
            val result = runOneShotShell(supervisor, "user", "printf secret-command", 5)
            assertEquals(-1, result.exitCode)
            val stderr = result.stderr.toString(Charsets.UTF_8)
            assertTrue(stderr.contains("stage=launcher_exited exitCode=126"))
            assertTrue(stderr.contains("reason=permission_denied"))
            assertFalse(stderr.contains("secret-stderr-sentinel"))
            assertFalse(stderr.contains("secret-command"))
            assertTrue(elapsedMs(started) < 3_000)
            var mergedFailure = ""
            assertNull(supervisor.startShellProcess("user", "printf secret-command", true,
                onStartFailure = { mergedFailure = it }))
            assertTrue(mergedFailure.contains("stage=launcher_exited exitCode=126"))
            assertTrue(mergedFailure.contains("reason=permission_denied"))
            assertFalse(mergedFailure.contains("secret-stderr-sentinel"))
            assertFalse(mergedFailure.contains("secret-command"))
        } finally {
            close(supervisor)
        }
    }

    @Test
    fun throwingFailureCallbackCannotPreventCleanup() {
        val launcher = setsidLauncher("time.sleep(30)")
        val supervisor = ShellProcessSupervisor(setsidCommand = launcher.absolutePath, startupOwnershipWaitMs = 100)
        try {
            assertNull(supervisor.startShellProcess("user", "printf should-not-run", false,
                onStartFailure = { throw IllegalStateException("diagnostic callback") }))
            assertTrue(supervisor.takeRemainingProcesses().isEmpty())
        } finally {
            close(supervisor)
        }
    }

    @Test
    fun launcherBuildExceptionReportsOnlyStageAndType() {
        val supervisor = ShellProcessSupervisor(rootAvailable = { true })
        var failure = ""
        val process = supervisor.startShellProcess(
            identity = "root",
            command = "secret-command",
            mergeStderr = false,
            environment = TerminalEnvironment.ALPINE,
            onStartFailure = { failure = it },
        )
        assertNull(process)
        assertTrue(failure.startsWith("stage=build_launcher type=IllegalArgumentException elapsedMs="))
        assertFalse(failure.contains("secret-command"))
    }

    @Test
    fun closingCancelsPendingAdmissionWithoutWaitingTenSeconds() {
        pendingAdmission { supervisor, worker, failure ->
            val started = System.nanoTime()
            supervisor.beginClosing()
            worker.join(3_000)
            assertFalse("closing must wake admission", worker.isAlive)
            assertTrue(failure.get().startsWith("stage=closing elapsedMs="))
            assertTrue(elapsedMs(started) < 3_000)
            assertTrue(supervisor.takeRemainingProcesses().isEmpty())
        }
    }

    @Test
    fun cleanupDoesNotReuseStartupBudgetWhileOwnershipIsMissing() {
        pendingAdmission { supervisor, worker, failure ->
            val registrationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var processes = supervisor.takeRemainingProcesses()
            while (processes.isEmpty() && worker.isAlive && System.nanoTime() < registrationDeadline) {
                Thread.sleep(10)
                processes = supervisor.takeRemainingProcesses()
            }
            assertEquals(1, processes.size)
            val started = System.nanoTime()
            processes.forEach { supervisor.terminateAndReap(it) }
            assertTrue("cleanup must not wait for the handshake", elapsedMs(started) < 3_000)
            worker.join(3_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get().startsWith("stage=launcher_exited exitCode="))
            assertFalse(processes.single().isAlive)
        }
    }

    @Test
    fun interruptCancelsPendingAdmissionAndRestoresInterruptFlag() {
        val interrupted = AtomicBoolean()
        pendingAdmission(interrupted) { supervisor, worker, failure ->
            worker.interrupt()
            worker.join(3_000)
            assertFalse("interrupt must wake admission", worker.isAlive)
            assertTrue(failure.get().startsWith("stage=ownership_interrupted type=InterruptedException elapsedMs="))
            assertTrue("interrupt flag must be restored", interrupted.get())
            assertTrue(supervisor.takeRemainingProcesses().isEmpty())
        }
    }

    private fun pendingAdmission(
        interrupted: AtomicBoolean = AtomicBoolean(),
        test: (ShellProcessSupervisor, Thread, AtomicReference<String>) -> Unit,
    ) {
        val marker = File(temporary.root, "entered")
        val launcher = setsidLauncher("open(${pythonQuote(marker.absolutePath)}, 'w').close()\ntime.sleep(30)")
        val supervisor = ShellProcessSupervisor(allowTreeFallback = false, setsidCommand = launcher.absolutePath)
        val failure = AtomicReference("")
        val thrown = AtomicReference<Throwable?>()
        val accepted = AtomicReference<Process?>()
        val worker = thread(name = "test-pending-shell-admission") {
            try {
                accepted.set(supervisor.startShellProcess("user", "printf should-not-run", false,
                    onStartFailure = { failure.set(it) }))
                interrupted.set(Thread.currentThread().isInterrupted)
            } catch (error: Throwable) {
                thrown.set(error)
            }
        }
        try {
            val started = System.nanoTime()
            while (!marker.exists() && worker.isAlive && elapsedMs(started) < 5_000) Thread.sleep(10)
            assertTrue("controlled launcher must be running", marker.exists())
            test(supervisor, worker, failure)
            assertNull(thrown.get())
            assertNull(accepted.get())
        } finally {
            close(supervisor)
            worker.join(3_000)
            accepted.get()?.let { supervisor.terminateAndReap(it); supervisor.unregisterProcess(it) }
        }
    }

    // Real local sessions, no Android/root/device. Same Python dependency as cancellation tests.
    private fun setsidLauncher(beforeExec: String): File = temporary.newFile("setsid.py").apply {
        writeText("#!/usr/bin/python3\nimport os, sys, time\n" +
            "args = sys.argv[1:]\nif args and args[0] == '-w': args = args[1:]\n" +
            "os.setsid()\n$beforeExec\nos.execvp(args[0], args)\n")
        check(setExecutable(true))
    }

    private fun pythonQuote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun elapsedMs(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun close(supervisor: ShellProcessSupervisor) {
        supervisor.beginClosing()
        supervisor.takeRemainingProcesses().forEach {
            supervisor.terminateAndReap(it)
            supervisor.unregisterProcess(it)
        }
    }

    @Test
    fun missingSetsidFailsClosedWhenTreeFallbackIsDisabled() {
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = "eta-test-missing-setsid",
        )

        val process = supervisor.startShellProcess(
            identity = "user",
            command = "echo should-not-run",
            mergeStderr = false,
        )

        assertNull(process)
    }

    @Test
    fun rootAndroidPayloadUsesDiscoveredBusyBoxWithoutChangingUserShell() {
        val supervisor = ShellProcessSupervisor()

        val rootPayload = supervisor.buildAndroidPayload("root", "command -v xz")
        val userPayload = supervisor.buildAndroidPayload("user", "id")

        assertTrue(rootPayload.contains("/data/adb/magisk/busybox"))
        assertTrue(rootPayload.contains("ASH_STANDALONE=1"))
        assertEquals("sh -c 'id'", userPayload)
    }

    @Test
    fun linuxPayloadKeepsShellQuotesAndMountsPrivateExchangeDirectory() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.mangi.eta/files/terminal/alpine/rootfs",
            command = "printf '%s' \"hello\"",
        )

        assertFalse(payload.contains("\\\""))
        assertTrue(payload.contains("unshare -m --propagation private"))
        assertTrue(payload.contains("mount -t proc"))
        assertTrue(payload.contains("eta_mount_required /data/local/tmp"))
        assertTrue(payload.contains("eta_mount_required /data/local/tmp/eta"))
        assertTrue(payload.contains("eta_rootfs/workspace"))
        assertTrue(payload.contains(TerminalRuntime.temporaryDirectory.absolutePath))
        assertTrue(payload.contains("eta_rootfs${LongShellCommand.CHROOT_SCRIPTS_DIR}"))
        assertTrue(payload.contains("eta_rootfs/var/minis/workspace"))
        assertTrue(payload.contains("eta_rootfs/var/minis/offloads"))
        assertTrue(payload.contains("eta_rootfs/var/minis/browser"))
        assertTrue(payload.contains("chroot"))
        assertTrue(payload.contains(AlpineEnvironmentPaths.READY_MARKER))
        assertTrue(payload.contains("/bin/busybox env -i"))
        // Alpine 的 /bin/sh 是绝对符号链接，Android 侧就绪检查必须放行符号链接。
        assertTrue(payload.contains("[ -h \"\$eta_rootfs/bin/sh\" ]"))

        val debianPayload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.mangi.eta/files/terminal/debian/rootfs",
            command = "python3 --version",
        )
        assertTrue(debianPayload.contains("/usr/bin/env -i"))
    }

    @Test
    fun ptyLauncherWrapsPayloadWithScriptAndSetsSize() {
        val supervisor = ShellProcessSupervisor()
        val launcher = supervisor.buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-pty-test.owner"),
            ownershipToken = "token123",
            command = null,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
            pty = true,
            ptyCols = 120,
            ptyRows = 40,
        )

        assertTrue(launcher.contains("script -qfc"))
        assertTrue(launcher.contains("stty rows 40 cols 120"))
        assertTrue(launcher.contains("TERM=xterm-256color"))
        assertTrue(launcher.contains("/dev/null"))
    }
}
