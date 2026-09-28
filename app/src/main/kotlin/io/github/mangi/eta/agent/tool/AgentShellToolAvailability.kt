package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.label
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-round, read-only detection of common command-line tools in the Android and selected Linux
 * environments. Only file metadata is inspected; a bounded Root readlink fallback can read protected links.
 * No user command is executed and no execution environment is switched. The probe list is fixed, so the prompt never carries user data.
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
    /** Each pass follows one more level of root-only links; tool chains here are at most 3 deep. */
    private const val MAX_PRIVILEGED_PASSES = 4
    private const val ROOT_READLINK_TIMEOUT_MS = 2_000L
    private const val ROOT_READLINK_MAX_OUTPUT = 64 * 1024
    private const val MAX_CACHED_TARGETS = 256

    /** Never throws; returns null when detection itself is impossible. */
    fun detect(context: Context): Snapshot? = runCatching {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        detect(
            rootfs = rootfs.takeIf { LinuxEnvironmentPaths.rootfsReady(it.absolutePath) },
            linuxLabel = distribution.terminalEnvironment.label(),
            androidDirs = androidDirs(System.getenv("PATH")),
            privilegedLinkReader = if (RootAccess.isGranted) ::readLinksAsRoot else null,
        )
    }.getOrNull()

    /**
     * Pure core used by tests. A null [rootfs] means the selected Linux environment is not ready.
     *
     * A rootfs unpacked by root keeps the `app_data_file:s0` label without the app's MCS
     * categories. The app can stat those links and read regular files, but SELinux denies
     * `readlink`, so every symlinked tool would look missing. Links the app cannot read are
     * collected per pass and resolved in one batch by [privilegedLinkReader] (root metadata read,
     * never an execution of the tool), then detection is re-run with the new targets.
     */
    fun detect(
        rootfs: File?,
        linuxLabel: String,
        androidDirs: List<String>,
        probed: List<String> = PROBED,
        privilegedLinkReader: ((List<File>) -> Map<File, String>)? = null,
        appLinkReader: (File) -> String? = ::readLinkAsApp,
    ): Snapshot {
        val android = probed.filter { name ->
            androidDirs.any { dir -> File(dir, name).let { it.isFile && it.canExecute() } }
        }
        if (rootfs == null) return Snapshot(linuxLabel, false, emptyList(), android, probed)

        val known = HashMap<File, String>()
        val attempted = HashSet<File>()
        var linux = emptyList<String>()
        for (pass in 0..MAX_PRIVILEGED_PASSES) {
            val pending = LinkedHashSet<File>()
            val reader: (File) -> String? = { link ->
                val target = known[link] ?: appLinkReader(link)
                    ?: if (privilegedLinkReader != null) cachedTarget(link) else null
                if (target == null && link !in attempted) pending += link
                target
            }
            linux = probed.filter { name ->
                LINUX_PATH.any { dir -> guestExecutable(rootfs, "$dir/$name", reader) }
            }
            if (pending.isEmpty() || privilegedLinkReader == null || pass == MAX_PRIVILEGED_PASSES) break
            attempted += pending
            val resolved = runCatching { privilegedLinkReader(pending.toList()) }.getOrNull().orEmpty()
                .filterKeys { it in pending }
            if (resolved.isEmpty()) break
            known += resolved
            resolved.forEach { (link, target) -> rememberTarget(link, target) }
        }
        return Snapshot(linuxLabel, true, linux, android, probed)
    }

    internal fun androidDirs(pathEnv: String?): List<String> =
        (pathEnv.orEmpty().split(':').filter { it.startsWith("/") } + ANDROID_FALLBACK_PATH).distinct()

    /** True when [guestPath], resolved inside [rootfs], is an executable regular file. */
    internal fun guestExecutable(
        rootfs: File,
        guestPath: String,
        readLink: (File) -> String? = ::readLinkAsApp,
    ): Boolean {
        val host = resolveGuest(rootfs, guestPath, readLink) ?: return false
        return host.isFile && host.canExecute()
    }

    /**
     * Resolves every path component as the guest would, clamping `..` at the guest root so a link
     * can never escape [rootfs]. Returns null for link loops or unreadable links.
     */
    internal fun resolveGuest(
        rootfs: File,
        guestPath: String,
        readLink: (File) -> String? = ::readLinkAsApp,
    ): File? {
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
            val host = File(rootfs, resolved.joinToString("/"))
            if (!Files.isSymbolicLink(host.toPath())) continue
            if (++hops > MAX_LINK_HOPS) return null
            val target = readLink(host) ?: return null
            resolved.removeAt(resolved.lastIndex)
            if (target.startsWith("/")) resolved.clear()
            components(target).asReversed().forEach(pending::addFirst)
        }
        return File(rootfs, resolved.joinToString("/"))
    }

    private fun readLinkAsApp(link: File): String? =
        runCatching { Files.readSymbolicLink(link.toPath()).toString() }.getOrNull()

    /**
     * One fixed root script for a batch of link paths chosen by the resolver inside the rootfs.
     * It only runs `readlink`; paths are single-quoted and targets come back NUL separated in
     * input order, with an empty field for links root could not read either.
     */
    internal fun rootReadlinkScript(links: List<File>): String =
        "for p in ${links.joinToString(" ") { quote(it.path) }}; do " +
            "/system/bin/readlink -n -- \"\$p\" 2>/dev/null || :; printf '\\0'; done"

    internal fun parseRootReadlinkOutput(links: List<File>, stdout: String): Map<File, String> {
        val fields = stdout.split('\u0000')
        // One NUL-terminated field per input, including failures. Reject partial output or banners.
        if (fields.size != links.size + 1 || fields.lastOrNull() != "") return emptyMap()
        return links.indices.mapNotNull { index ->
            fields[index].takeIf { it.isNotEmpty() }?.let { links[index] to it }
        }.toMap()
    }

    private fun readLinksAsRoot(links: List<File>): Map<File, String> {
        if (links.isEmpty()) return emptyMap()
        val result = BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
            executor.execute(
                rootReadlinkScript(links),
                timeoutMillis = ROOT_READLINK_TIMEOUT_MS,
                maxOutputBytes = ROOT_READLINK_MAX_OUTPUT,
            )
        }
        if (!result.ok || result.truncated) return emptyMap()
        return parseRootReadlinkOutput(links, result.stdout)
    }

    /** Link targets read with root, reused across rounds until the link itself changes. */
    private data class LinkStamp(val modified: FileTime, val size: Long, val fileKey: String?)
    private val targetCache = ConcurrentHashMap<String, Pair<LinkStamp, String>>()

    private fun linkStamp(link: File): LinkStamp? = runCatching {
        val attributes = Files.readAttributes(link.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isSymbolicLink) return@runCatching null
        LinkStamp(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey()?.toString())
    }.getOrNull()

    private fun cachedTarget(link: File): String? {
        val entry = targetCache[link.path] ?: return null
        return entry.second.takeIf { linkStamp(link) == entry.first }
    }

    private fun rememberTarget(link: File, target: String) {
        val stamp = linkStamp(link) ?: return
        if (targetCache.size >= MAX_CACHED_TARGETS) targetCache.clear()
        targetCache[link.path] = stamp to target
    }

    internal fun clearTargetCacheForTest() = targetCache.clear()

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
        return "本机命令检测（客户端在本轮开始时只读检查可执行文件，未执行用户命令）：" +
            "$linuxPart；Android 检测到：${list(snapshot.android)}" +
            (if (missing.isEmpty()) "" else "；两个环境都未检测到：${list(missing)}") + "。" +
            "选择环境时：Android 系统操作（pm、am、cmd、dumpsys、settings、getprop 等）使用 environment=android 或 run_command；" +
            "其他命令在 Linux 已就绪时优先 terminal(environment=linux)，只在 Android 检测到的命令才用 Android；" +
            "两个环境都未检测到时先说明检测结果；未检出不等于未安装，权限受限时不得断言需要安装，不要在两个环境之间来回试。" +
            "检测范围仅限上述名称，且可能读不到部分受保护目录，以实际执行结果为准。"
    }
}
