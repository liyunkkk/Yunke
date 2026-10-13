package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File

/**
 * 管理容器内 DeepSeek Harness 密钥文件（<rootfs>/root/.dsh/key）的写入与读取。
 *
 * 安全规范：
 * 不得将 API Key 通过命令行参数或环境变量传给容器进程（同机 root 进程可读 /proc/星/environ）。
 * 直接将密钥文件写入容器 rootfs，文件权限设置为 0600（仅属主可读写）。
 */
internal object DshKeyFileManager {

    /**
     * 容器内密钥文件的宿主机路径：<rootfs>/root/.dsh/key
     */
    fun resolveHostKeyFile(context: Context): File {
        val distribution = LinuxEnvironmentSettingsRepository.current(context)
        val rootfsDir = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        val relative = DshSettings.DEFAULT_KEY_FILE_PATH.removePrefix("/")
        return File(rootfsDir, relative)
    }

    /**
     * 格式化密钥文件内容，严格保证仅包含 DEEPSEEK_BASE_URL 与 DEEPSEEK_API_KEY 两行。
     */
    fun formatKeyFileContent(endpoint: String, apiKey: String): String {
        val cleanEndpoint = endpoint.trim().ifBlank { DshSettings.DEFAULT_ENDPOINT }
        val cleanApiKey = apiKey.trim()
        return "DEEPSEEK_BASE_URL=$cleanEndpoint\nDEEPSEEK_API_KEY=$cleanApiKey\n"
    }

    /**
     * 将 endpoint 与 apiKey 写入指定文件。
     *
     * 权限尽力设置为 0600（属主可读写，其他用户无任何权限）：
     * Android 限制说明：
     * Android 底层 Linux 内核与文件系统（ext4/f2fs）支持 POSIX 权限位，但由于应用运行在独立的
     * Linux UID 沙盒内，文件访问受到 SELinux 策略及应用目录 mount 选项约束。
     * java.io.File 的 setReadable/setWritable API 在非 root 进程下会尽力清除 group/others 权限位
     * 并保留 owner 权限位。后续容器进程（以 root 或映射 UID 运行）可依此属主权限安全读取。
     */
    fun writeKeyFile(
        file: File,
        endpoint: String,
        apiKey: String,
    ): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val content = formatKeyFileContent(endpoint, apiKey)
            file.writeText(content, Charsets.UTF_8)

            // 尽力设置 0600：先抹去所有人权限，再单独给予属主读写权限
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            true
        }.getOrDefault(false)
    }

    fun writeKeyFile(
        context: Context,
        endpoint: String,
        apiKey: String,
    ): Boolean = writeKeyFile(resolveHostKeyFile(context), endpoint, apiKey)

    /**
     * 解析密钥文件，返回 Pair(endpoint, apiKey)。若文件不存在或读取失败返回 null。
     */
    fun parseKeyFile(file: File): Pair<String?, String?>? {
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            var endpoint: String? = null
            var apiKey: String? = null
            file.forEachLine(Charsets.UTF_8) { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                    val key = trimmed.substringBefore("=").trim().uppercase()
                    val value = trimmed.substringAfter("=").trim().removeSurrounding("\"").removeSurrounding("'")
                    if (key == "DEEPSEEK_BASE_URL" || key == "BASE_URL") {
                        if (value.isNotBlank()) endpoint = value
                    } else if (key == "DEEPSEEK_API_KEY" || key == "API_KEY") {
                        if (value.isNotBlank()) apiKey = value
                    }
                }
            }
            Pair(endpoint, apiKey)
        }.getOrNull()
    }

    /**
     * 读取 API Key。优先从密钥文件读取，若无则从 SharedPreferences 回退读取。
     */
    fun readApiKey(context: Context, targetFile: File? = null): String {
        val file = targetFile ?: resolveHostKeyFile(context)
        val parsed = parseKeyFile(file)
        if (!parsed?.second.isNullOrBlank()) {
            return parsed!!.second!!
        }
        val prefs = context.getSharedPreferences(DshSettings.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(DshSettings.PREF_API_KEY, "") ?: ""
    }

    /**
     * 密钥文件是否存在。
     */
    fun exists(context: Context, targetFile: File? = null): Boolean {
        val file = targetFile ?: resolveHostKeyFile(context)
        return file.isFile
    }
}
