package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.label
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File
import java.nio.file.Files

/**
 * Per-round, read-only detection of common command-line tools in the Android and selected Linux
 * environments. Only file metadata is inspected: nothing is executed, no environment is switched
 * and no user command is involved. The probe list is fixed, so the prompt never carries user data.
 *
 * Linux paths are resolved inside the rootfs: absolute symlinks such as
 * `/usr/local/bin/python3 -> /opt/eta/python/...` point at guest paths and would look broken if
 * resolved on the host.
 */
internal object AgentShellToolAvailability {
    data class Snapshot(
        /** Display label of the selected distribution, e.g. "Debian". */
        val linuxLabel: String,
        val linuxReady: Boolean,
        val linux: List<String>,
        val android: List<String>,
        val probed: List<String> = PROBED,
    )

    /** Fixed probe set; order is kept stable so the prompt text stays cache friendly. */
    val PROBED = listOf("git", "gh", "python3", "pip3", "uv", "node", "npm", "java", "jadx", "apktool", "curl", "ssh")

    /** Matches the PATH exported for Linux sessions by ShellProcessSupervisor/ProotCommandBuilder. */
    val LINUX_PATH = listOf("/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin")

    private val ANDROID_FALLBACK_PATH = listOf(
        "/product/bin", "/system/bin", "/system/xbin", "/system_ext/bin", "/odm/bin", "/vendor/bin", "/vendor/xbin",
    )
    private const val MAX_LINK_HOPS = 32

    /** Never throws; returns null when detection itself is impossible. */
    fun detect(context: Context): Snapshot? = runCatching {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        detect(
            rootfs = rootfs.takeIf { LinuxEnvironmentPaths.rootfsReady(it.absolutePath) },
            linuxLabel = distribution.terminalEnvironment.label(),
            androidDirs = androidDirs(System.getenv("PATH")),
        )
    }.getOrNull()

    /** Pure core used by tests. A null [rootfs] means the selected Linux environment is not ready. */
    fun detect(rootfs: File?, linuxLabel: String, androidDirs: List<String>, probed: List<String> = PROBED): Snapshot {
        val linux = if (rootfs == null) emptyList() else probed.filter { name ->
            LINUX_PATH.any { dir -> guestExecutable(rootfs, "$dir/$name") }
        }
        val android = probed.filter { name ->
            androidDirs.any { dir -> File(dir, name).let { it.isFile && it.canExecute() } }
        }
        return Snapshot(linuxLabel, rootfs != null, linux, android, probed)
    }

    internal fun androidDirs(pathEnv: String?): List<String> =
        (pathEnv.orEmpty().split(':').filter { it.startsWith("/") } + ANDROID_FALLBACK_PATH).distinct()

    /** True when [guestPath], resolved inside [rootfs], is an executable regular file. */
    internal fun guestExecutable(rootfs: File, guestPath: String): Boolean {
        val host = resolveGuest(rootfs, guestPath) ?: return false
        return host.isFile && host.canExecute()
    }

    /**
     * Resolves every path component as the guest would, clamping `..` at the guest root so a link
     * can never escape [rootfs]. Returns null for link loops or unreadable links.
     */
    internal fun resolveGuest(rootfs: File, guestPath: String): File? {
        val pending = ArrayDeque(components(guestPath))
        val resolved = ArrayList<String>()
        var hops = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            if (part == ".") continue
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.lastIndex)
                continue
            }
            resolved += part
            val host = File(rootfs, resolved.joinToString("/")).toPath()
            if (!Files.isSymbolicLink(host)) continue
            if (++hops > MAX_LINK_HOPS) return null
            val target = runCatching { Files.readSymbolicLink(host).toString() }.getOrNull() ?: return null
            resolved.removeAt(resolved.lastIndex)
            if (target.startsWith("/")) resolved.clear()
            components(target).asReversed().forEach(pending::addFirst)
        }
        return File(rootfs, resolved.joinToString("/"))
    }

    private fun components(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    /** Prompt guidance built only from fixed probe names and detection flags. */
    fun promptNote(snapshot: Snapshot): String {
        fun list(names: List<String>) = if (names.isEmpty()) "无" else names.joinToString("、")
        val linuxPart = if (snapshot.linuxReady) {
            "Linux（${snapshot.linuxLabel}）检测到：${list(snapshot.linux)}"
        } else {
            "Linux（${snapshot.linuxLabel}）工具环境未就绪"
        }
        val missing = snapshot.probed.filter { it !in snapshot.linux && it !in snapshot.android }
        return "本机命令检测（客户端在本轮开始时只读检查可执行文件，未执行任何命令）：" +
            "$linuxPart；Android 检测到：${list(snapshot.android)}" +
            (if (missing.isEmpty()) "" else "；两个环境都未检测到：${list(missing)}") + "。" +
            "选择环境时：Android 系统操作（pm、am、cmd、dumpsys、settings、getprop 等）使用 environment=android 或 run_command；" +
            "其他命令在 Linux 已就绪时优先 terminal(environment=linux)，只在 Android 检测到的命令才用 Android；" +
            "两个环境都未检测到时直接说明需要安装，不要在两个环境之间来回试。" +
            "检测范围仅限上述名称，且可能读不到部分受保护目录，以实际执行结果为准。"
    }
}
