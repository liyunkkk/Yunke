package io.github.mangi.eta.agent.dsh

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshProvisionerTest {

    @Test
    fun buildShellCommandInjectsPythonFallback() {
        val script = "print('provision')"
        val argsJson = "{\"action\":\"status\"}"
        val command = DshProvisioner.buildShellCommand(script, argsJson)

        assertTrue("必须包含 python3 探测兜底", command.contains("command -v python3 >/dev/null 2>&1 ||"))
        assertTrue("兜底输出 RUNTIME_MISSING", command.contains("RUNTIME_MISSING"))
        assertTrue("必须以 python3 -I -c 执行", command.contains("python3 -I -c"))
        assertTrue("包含转义后的脚本", command.contains(DshProvisioner.quote(script)))
        assertTrue("包含转义后的参数", command.contains(DshProvisioner.quote(argsJson)))
    }

    @Test
    fun parseStatusOutputHandlesInstalledState() {
        val jsonOutput = """
            {"ok":true,"installed":true,"version":"0.1.5-rc.1","key_exists":true,"runtime_path":"/usr/local/bin/dsh","key_file":"/root/.dsh/key"}
        """.trimIndent()

        val status = DshProvisioner.parseStatusOutput(jsonOutput)

        assertTrue(status.ok)
        assertTrue(status.installed)
        assertEquals("0.1.5-rc.1", status.version)
        assertTrue(status.keyExists)
        assertEquals("/usr/local/bin/dsh", status.runtimePath)
        assertEquals("/root/.dsh/key", status.keyFile)
        assertNull(status.errorCode)
    }

    @Test
    fun parseStatusOutputHandlesNotInstalledState() {
        val jsonOutput = """
            {"ok":true,"installed":false,"version":null,"key_exists":false,"runtime_path":"/usr/local/bin/dsh","key_file":"/root/.dsh/key"}
        """.trimIndent()

        val status = DshProvisioner.parseStatusOutput(jsonOutput)

        assertTrue(status.ok)
        assertFalse(status.installed)
        assertNull(status.version)
        assertFalse(status.keyExists)
    }

    @Test
    fun parseStatusOutputHandlesTerminalEnvelopeAndErrors() {
        val envelope = JSONObject()
            .put("ok", true)
            .put("exit_code", 0)
            .put("stdout", "log line\n{\"ok\":false,\"code\":\"STATUS_FAILED\",\"hint\":\"cannot access filesystem\"}\n")
            .toString()

        val status = DshProvisioner.parseStatusOutput(envelope)

        assertFalse(status.ok)
        assertEquals("STATUS_FAILED", status.errorCode)
        assertEquals("cannot access filesystem", status.errorMessage)
    }

    @Test
    fun parseInstallOutputHandlesSuccessfulInstall() {
        val jsonOutput = """
            {"ok":true,"installed":true,"already_installed":false,"version":"0.1.5-rc.1","runtime_path":"/usr/local/bin/dsh","message":"Successfully installed dsh runtime (0.1.5-rc.1)"}
        """.trimIndent()

        val result = DshProvisioner.parseInstallOutput(jsonOutput)

        assertTrue(result.ok)
        assertTrue(result.installed)
        assertFalse(result.alreadyInstalled)
        assertEquals("0.1.5-rc.1", result.version)
        assertEquals("/usr/local/bin/dsh", result.runtimePath)
        assertTrue(result.message?.contains("Successfully installed") == true)
        assertNull(result.errorCode)
    }

    @Test
    fun parseInstallOutputHandlesAlreadyInstalledIdempotency() {
        val jsonOutput = """
            {"ok":true,"installed":true,"already_installed":true,"version":"0.1.5-rc.1","runtime_path":"/usr/local/bin/dsh","message":"Runtime already installed"}
        """.trimIndent()

        val result = DshProvisioner.parseInstallOutput(jsonOutput)

        assertTrue(result.ok)
        assertTrue(result.installed)
        assertTrue(result.alreadyInstalled)
        assertEquals("0.1.5-rc.1", result.version)
    }

    @Test
    fun parseInstallOutputHandlesKnownErrors() {
        val errorPayloads = listOf(
            "PYPI_METADATA_FETCH_FAILED" to "Network error 404",
            "WHEEL_NOT_FOUND" to "No aarch64 wheel found",
            "DOWNLOAD_FAILED" to "Connection reset",
            "WHEEL_STRUCTURE_INVALID" to "Missing runtime binary in wheel",
            "SYMLINK_FAILED" to "Permission denied",
            "VERIFICATION_FAILED" to "dsh exited with 127",
        )

        for ((code, hint) in errorPayloads) {
            val json = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("hint", hint)
                .toString()
            val parsed = DshProvisioner.parseInstallOutput(json)
            assertFalse(parsed.ok)
            assertEquals(code, parsed.errorCode)
            assertEquals(hint, parsed.errorMessage)
        }
    }

    @Test
    fun parseInstallOutputHandlesTimeoutEnvelope() {
        val timeoutEnvelope = JSONObject()
            .put("ok", false)
            .put("timed_out", true)
            .put("exit_code", -2)
            .put("stdout", "")
            .put("stderr", "")
            .toString()

        val result = DshProvisioner.parseInstallOutput(timeoutEnvelope)
        assertFalse(result.ok)
        assertEquals("TIMEOUT", result.errorCode)
    }

    @Test
    fun dshProvisionerInvokesExecutorWithExpectedAction() {
        var recordedCommand: String? = null
        var recordedTimeout: Int? = null

        val mockExecutor = DshCommandExecutor { _, command, timeoutMs ->
            recordedCommand = command
            recordedTimeout = timeoutMs
            """{"ok":true,"installed":true,"version":"0.1.5-rc.1","key_exists":true}"""
        }

        val provisioner = DshProvisioner(
            script = "test_script",
            executor = mockExecutor,
        )

        val status = provisioner.status()
        assertTrue(status.ok)
        assertTrue(status.installed)
        assertEquals(30_000, recordedTimeout)
        assertTrue(recordedCommand?.contains("\"action\":\"status\"") == true)

        val installResult = provisioner.install(force = true, timeoutMs = 900_000)
        assertTrue(installResult.ok)
        assertEquals(900_000, recordedTimeout)
        assertTrue(recordedCommand?.contains("\"action\":\"install\"") == true)
        assertTrue(recordedCommand?.contains("\"force\":true") == true)
    }
}
