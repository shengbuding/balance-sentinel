package com.balancesentinel.app

import android.app.Application
import android.os.Build
import com.balancesentinel.app.data.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局崩溃捕获 + 面包屑日志 — 在 [DeepSeekApp.onCreate] 中安装。
 *
 * 功能：
 * - 未捕获异常写入 filesDir/crash.log（最多 10 条，fsync 确保落盘）
 * - 环形面包屑缓冲区（最近 30 条事件，崩溃时自动附到报告末尾）
 * - 每条崩溃包含设备信息、App 版本、线程名、堆栈
 * - 同时输出到 logcat（Logger.e）确保 adb logcat 可见
 */
object CrashLogger {

    private const val LOG_FILE = "crash.log"
    private const val MAX_ENTRIES = 10
    private const val MAX_BREADCRUMBS = 30
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var appRef: Application? = null

    // ── 面包屑（环形缓冲区） ──
    private val breadcrumbs = ArrayDeque<String>(MAX_BREADCRUMBS)

    /** 记录一条面包屑——崩溃前最后一次记录的事件上下文 */
    @Synchronized
    fun breadcrumb(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        breadcrumbs.addLast("[$ts] $tag: $message")
        if (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
    }

    /** 导出面包屑快照 */
    @Synchronized
    fun getBreadcrumbs(): List<String> = breadcrumbs.toList()

    // ── 安装 ──

    fun install(app: Application) {
        appRef = app
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(thread, throwable)
            Logger.e("CrashLogger", "FATAL", throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }
        breadcrumb("CrashLogger", "Crash handler installed; app version=${appVersion()}")
    }

    // ── 非致命错误报告 ──

    /** 记录非致命异常（不会崩溃，但值得一提） */
    fun logNonFatal(tag: String, throwable: Throwable) {
        try {
            val app = appRef ?: return
            val file = File(app.filesDir, LOG_FILE)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = buildCrashEntry(
                tag = tag,
                throwable = throwable,
                threadName = Thread.currentThread().name,
                timestamp = now,
                isFatal = false
            )
            appendCrash(file, sanitize(entry))
            Logger.w(tag, "Non-fatal recorded", throwable)
        } catch (_: Throwable) {}
    }

    // ── 写入 ──

    /** API Key 脱敏正则（与 Logger 保持一致） */
    private val API_KEY_REGEX = Regex("""sk-[a-zA-Z0-9]{10,}""")
    private const val REDACTED = "sk-***"

    private fun sanitize(text: String): String {
        return API_KEY_REGEX.replace(text, REDACTED)
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        try {
            val app = appRef ?: return
            val file = File(app.filesDir, LOG_FILE)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = buildCrashEntry(
                tag = "FATAL",
                throwable = throwable,
                threadName = thread.name,
                timestamp = now,
                isFatal = true
            )
            appendCrash(file, sanitize(entry))
        } catch (_: Throwable) {
            // Don't crash in the crash handler
        }
    }

    private fun buildCrashEntry(
        tag: String,
        throwable: Throwable,
        threadName: String,
        timestamp: String,
        isFatal: Boolean
    ): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val level = if (isFatal) "FATAL" else "NON-FATAL"

        return buildString {
            appendLine("[$timestamp] [$level] ${throwable.javaClass.name}: ${throwable.message ?: "(no message)"}")
            appendLine()
            appendLine("── 设备信息 ──")
            appendLine("  制造商     : ${Build.MANUFACTURER}")
            appendLine("  型号       : ${Build.MODEL}")
            appendLine("  产品名     : ${Build.PRODUCT}")
            appendLine("  Android    : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("  App 版本   : ${appVersion()}")
            appendLine("  线程       : $threadName")
            appendLine()
            appendLine("── 堆栈 ──")
            appendLine(sw.toString().trimEnd())
            appendLine()
            appendLine("── 面包屑 ──")
            val crumbs = getBreadcrumbs()
            if (crumbs.isEmpty()) {
                appendLine("  (无)")
            } else {
                crumbs.takeLast(10).forEach { appendLine("  $it") }
            }
            if (crumbs.size > 10) {
                appendLine("  ... + ${crumbs.size - 10} more")
            }
        }
    }

    private fun appendCrash(file: File, entry: String) {
        val existing = if (file.exists()) file.readText() else ""
        val parts = listOf(entry) + existing.split("\n══════════════════════════════════\n").filter { it.isNotBlank() }
        val kept = parts.take(MAX_ENTRIES).joinToString("\n══════════════════════════════════\n")

        FileOutputStream(file).use { fos ->
            fos.write(kept.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    // ── 读取 ──

    fun getCrashes(app: Application): List<CrashEntry> {
        try {
            val file = File(app.filesDir, LOG_FILE)
            if (!file.exists()) return emptyList()
            return file.readText()
                .split("\n══════════════════════════════════\n")
                .filter { it.isNotBlank() }
                .map { entry ->
                    val firstLineEnd = entry.indexOf('\n')
                    val headerLine = if (firstLineEnd > 0) entry.substring(0, firstLineEnd) else entry.take(80)
                    CrashEntry(
                        header = headerLine.removePrefix("[").removeSuffix("]"),
                        fullStack = entry
                    )
                }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    fun clear(app: Application) {
        try { File(app.filesDir, LOG_FILE).delete() } catch (_: Exception) {}
    }

    // ── 工具 ──

    private fun appVersion(): String {
        return try {
            val pkgInfo = appRef?.packageManager?.getPackageInfo(appRef!!.packageName, 0)
            "${pkgInfo?.versionName ?: "?"} (build ${pkgInfo?.longVersionCode ?: 0})"
        } catch (_: Exception) {
            "unknown"
        }
    }

    data class CrashEntry(val header: String, val fullStack: String)
}
