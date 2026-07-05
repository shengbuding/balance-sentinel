package com.example.deepseekbalance

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 全局崩溃捕获 — 在 App 启动时安装。
 * 崩溃堆栈写入 filesDir/crash.log，应用重启后可读取展示。
 */
object CrashLogger {

    private const val LOG_FILE = "crash.log"
    private const val MAX_ENTRIES = 5
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    fun install(app: Application) {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Write to file with immediate sync to survive crash
            writeCrash(app, throwable)
            // Also log to logcat
            Log.e("CrashLogger", "FATAL", throwable)
            // Re-throw to system handler
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(app: Application, throwable: Throwable) {
        try {
            val file = File(app.filesDir, LOG_FILE)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val sw = java.io.StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val entry = "[$now] ${throwable.javaClass.name}: ${throwable.message ?: "(no msg)"}\n${sw}"

            // Read existing, prepend new
            val existing = if (file.exists()) file.readText() else ""
            val parts = listOf(entry) + existing.split("\n---\n").filter { it.isNotBlank() }
            val kept = parts.take(MAX_ENTRIES).joinToString("\n---\n")

            // Write + fsync
            FileOutputStream(file).use { fos ->
                fos.write(kept.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
        } catch (_: Throwable) {
            // Don't crash in the crash handler
        }
    }

    fun getCrashes(app: Application): List<CrashEntry> {
        try {
            val file = File(app.filesDir, LOG_FILE)
            if (!file.exists()) return emptyList()
            return file.readText()
                .split("\n---\n")
                .filter { it.isNotBlank() }
                .map { entry ->
                    val firstLineEnd = entry.indexOf('\n')
                    val header = if (firstLineEnd > 0) entry.substring(0, firstLineEnd) else entry.take(50)
                    CrashEntry(header, entry)
                }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    fun clear(app: Application) {
        try { File(app.filesDir, LOG_FILE).delete() } catch (_: Exception) {}
    }

    data class CrashEntry(val header: String, val fullStack: String)
}
