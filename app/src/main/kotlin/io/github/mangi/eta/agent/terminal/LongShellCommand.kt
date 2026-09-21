package io.github.mangi.eta.agent.terminal

import java.io.File

/** Script transport avoids execve's per-argument limit; this is not an input cap. */
internal object LongShellCommand {
    const val INLINE_BYTES = 8_192
    const val CHROOT_SCRIPTS_DIR = "/tmp/eta-command-scripts"
    data class Prepared(val command: String?, val file: File? = null)
    fun prepare(command: String?, environment: TerminalEnvironment, rootfs: String?): Prepared {
        if (command == null || command.toByteArray(Charsets.UTF_8).size <= INLINE_BYTES) return Prepared(command)
        val directory = TerminalRuntime.temporaryDirectory.apply { mkdirs() }
        val file = File.createTempFile("command-", ".sh", directory)
        try {
            check(file.setReadable(false, false) && file.setWritable(false, false))
            check(file.setReadable(true, true) && file.setWritable(true, true))
            val path = when {
                !environment.isLinux -> file.absolutePath
                LinuxEnvironmentPaths.backendOf(rootfs) == LinuxExecutionBackend.PROOT -> "/dev/shm/${file.name}"
                else -> "$CHROOT_SCRIPTS_DIR/${file.name}"
            }
            // The interpreter opens the file before unlinking; no credential-bearing script remains after start.
            file.writeText("rm -f ${shellQuote(path)}\n" + command + "\n")
            return Prepared(". ${shellQuote(path)}", file)
        } catch (e: Exception) { file.delete(); throw e }
    }
}
