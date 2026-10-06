package io.github.mangi.eta.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 可选的诊断文件日志。关闭时不写盘；开启后把应用日志和本进程 logcat 轮转写入 filesDir/logs。
 */
internal object AppFileLogger {
    const val DIRECTORY_NAME = "logs"
    const val APP_LOG_FILE = "eta-app.log"
    const val LOGCAT_FILE = "eta-logcat.log"

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())
    private val enabled = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)
    // Lifecycle operations may wait for logcat or touch multiple files.
    private val lock = ReentrantLock()
    // Writers never acquire the lifecycle lock. This gate only fences accepted writes
    // against disable/clear; process shutdown and file cleanup happen outside it.
    private val writeLock = ReentrantLock()
    private val logcatProcess = AtomicReference<java.lang.Process?>()
    private val logcatThread = AtomicReference<Thread?>()

    @Volatile private var logsDir: File? = null
    @Volatile private var appSink: FileLogSink? = null
    @Volatile private var logcatSink: FileLogSink? = null
    @Volatile private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile private var debugHeader: DiagnosticHeader? = null

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val directory = File(context.applicationContext.filesDir, DIRECTORY_NAME)
        logsDir = directory
        installCrashHandler()
        debugHeader = DiagnosticHeader.from(context.applicationContext)
    }

    fun setEnabled(value: Boolean) {
        if (!installed.get()) return
        val directory = logsDir ?: return
        lock.withLock {
            if (enabled.get() == value) return
            if (value) {
                if (!directory.exists() && !directory.mkdirs()) {
                    Log.w(ModuleConfig.TAG, "无法创建诊断日志目录")
                    return
                }
                appSink = FileLogSink(directory, APP_LOG_FILE)
                logcatSink = FileLogSink(directory, LOGCAT_FILE)
                writeSessionHeader()
                writeLock.withLock { enabled.set(true) }
                startLogcatLocked()
            } else {
                writeLock.withLock { enabled.set(false) }
                stopLogcatLocked()
                appSink?.close()
                logcatSink?.close()
                appSink = null
                logcatSink = null
            }
        }
    }

    fun isEnabled(): Boolean = enabled.get()

    fun debug(message: String) = write("D", message, null)

    fun info(message: String) = write("I", message, null)

    /**
     * Optional redacted diagnostics share the global file-logging switch, including logcat output.
     * Check at delivery time (not when a run starts), under the write gate shared with
     * disable/clear, so retained child tasks cannot emit after logging has been disabled.
     */
    fun diagnosticInfo(message: String) = write("I", message, null, echoLogcat = true)

    fun warn(message: String) = write("W", message, null)

    fun error(message: String, throwable: Throwable? = null) = write("E", message, throwable)

    fun flush() {
        lock.withLock {
            appSink?.flush()
            logcatSink?.flush()
        }
    }

    fun clear() {
        lock.withLock {
            val wasEnabled = writeLock.withLock { enabled.getAndSet(false) }
            if (wasEnabled) {
                stopLogcatLocked()
            }
            appSink?.clear()
            logcatSink?.clear()
            val directory = logsDir
            if (directory != null) {
                FileLogSink(directory, APP_LOG_FILE).clear()
                FileLogSink(directory, LOGCAT_FILE).clear()
            }
            appSink = null
            logcatSink = null
            if (wasEnabled) {
                val dir = directory ?: return
                appSink = FileLogSink(dir, APP_LOG_FILE)
                logcatSink = FileLogSink(dir, LOGCAT_FILE)
                writeSessionHeader()
                writeLock.withLock { enabled.set(true) }
                startLogcatLocked()
            }
        }
    }

    fun hasLogs(): Boolean {
        val directory = logsDir ?: return false
        return FileLogSink(directory, APP_LOG_FILE).files().isNotEmpty() ||
            FileLogSink(directory, LOGCAT_FILE).files().isNotEmpty()
    }

    fun export(output: OutputStream): Int {
        flush()
        val directory = logsDir ?: throw IllegalStateException("诊断日志尚未初始化")
        val files = FileLogSink(directory, APP_LOG_FILE).files() +
            FileLogSink(directory, LOGCAT_FILE).files()
        if (files.isEmpty()) {
            throw IllegalStateException("没有可导出的日志")
        }
        return DiagnosticLogArchive.writeZip(files, output)
    }

    private fun write(
        level: String,
        message: String,
        throwable: Throwable?,
        echoLogcat: Boolean = false,
    ) {
        if (!enabled.get()) return
        val sink = appSink ?: return
        writeLock.withLock {
            // A caller delayed across clear/re-enable must not write to the new session.
            if (!enabled.get() || appSink !== sink) return
            if (echoLogcat) runCatching { Log.i(ModuleConfig.TAG, message) }
            val builder = StringBuilder(message.length + 80)
            builder.append(timeFormatter.format(Instant.now()))
                .append(' ')
                .append(level)
                .append('/')
                .append(ModuleConfig.TAG)
                .append(": ")
                .append(message)
            if (throwable != null) {
                builder.append('\n').append(Log.getStackTraceString(throwable).trimEnd())
            }
            runCatching { sink.append(builder.toString()) }.onFailure { failure ->
                // Report only the type: paths and message contents may contain private data.
                runCatching { Log.w(ModuleConfig.TAG, "diagnostic append failed: ${failure.safeLogType()}") }
            }
        }
    }

    private fun writeSessionHeader() {
        val header = debugHeader
        val lines = buildString {
            appendLine("==== Eta file logging started ====")
            appendLine("time=${timeFormatter.format(Instant.now())}")
            if (header != null) {
                appendLine("version=${header.versionName} (${header.versionCode})")
                appendLine("sdk=${header.sdk}")
                appendLine("device=${header.manufacturer} ${header.model}")
                appendLine("process=${header.processName}")
            }
        }
        runCatching { appSink?.append(lines.trimEnd()) }
    }

    private fun installCrashHandler() {
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                error("Uncaught exception in ${thread.name}", throwable)
                flush()
            }
            previousCrashHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun startLogcatLocked() {
        stopLogcatLocked()
        val sink = logcatSink ?: return
        val process = runCatching {
            ProcessBuilder(
                "logcat",
                "--pid",
                android.os.Process.myPid().toString(),
                "-v",
                "threadtime",
                "-T",
                "200",
            )
                .redirectErrorStream(true)
                .start()
        }.getOrElse { throwable ->
            runCatching { appSink?.append("logcat start failed: ${throwable.safeLogType()}") }
            return
        }
        logcatProcess.set(process)
        val thread = Thread(
            {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                        while (enabled.get() && !Thread.currentThread().isInterrupted) {
                            val line = reader.readLine() ?: break
                            sink.append(line)
                        }
                    }
                }
            },
            "eta-logcat",
        )
        thread.isDaemon = true
        logcatThread.set(thread)
        thread.start()
    }

    private fun stopLogcatLocked() {
        logcatThread.getAndSet(null)?.interrupt()
        logcatProcess.getAndSet(null)?.let { process ->
            runCatching { process.destroy() }
            runCatching {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            }
        }
    }

    private data class DiagnosticHeader(
        val versionName: String,
        val versionCode: Long,
        val sdk: Int,
        val manufacturer: String,
        val model: String,
        val processName: String,
    ) {
        companion object {
            fun from(context: Context): DiagnosticHeader {
                val info = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }.getOrNull()
                return DiagnosticHeader(
                    versionName = info?.versionName.orEmpty().ifBlank { "unknown" },
                    versionCode = info?.longVersionCode ?: 0L,
                    sdk = Build.VERSION.SDK_INT,
                    manufacturer = Build.MANUFACTURER.orEmpty(),
                    model = Build.MODEL.orEmpty(),
                    processName = context.applicationInfo.processName.orEmpty()
                        .ifBlank { context.packageName },
                )
            }
        }
    }
}
