package com.balancesentinel.app.widget

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 跨进程 Widget 错误日志。
 * 关键：widget 运行在系统进程，但它拿到的 context 是我们 App 的 context，
 * 所以 filesDir 应该属于我们自己 App。
 * 同时用 context.getExternalFilesDir 作为备选。
 */
object WidgetErrorLogger {

    private const val MAX_ENTRIES = 5
    private const val LOG_FILE = "widget_errors.log"

    fun log(context: Context, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val msg = "EXCEPTION: ${throwable.javaClass.name}: ${throwable.message ?: "(no msg)"}\n${sw}"
        writeLog(context, msg)
    }

    fun logMessage(context: Context, message: String) {
        writeLog(context, message)
    }

    private fun writeLog(context: Context, message: String) {
        val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $message"
        val truncated = if (entry.length > 8000) entry.take(8000) + "...[truncated]" else entry

        // Try internal filesDir first
        writeToFile(context.filesDir, truncated)
        // Also try cache dir as fallback
        if (context.filesDir.absolutePath != context.cacheDir.absolutePath) {
            writeToFile(context.cacheDir, truncated)
        }
    }

    private fun writeToFile(dir: File, entry: String) {
        try {
            val file = File(dir, LOG_FILE)
            val existing = if (file.exists()) file.readText() else ""
            val parts = (existing + "\n---\n" + entry).split("\n---\n")
            val kept = parts.takeLast(MAX_ENTRIES).joinToString("\n---\n")
            file.writeText(kept)
        } catch (_: Exception) {}
    }

    fun getLogs(context: Context): List<ErrorEntry> {
        val entries = mutableListOf<ErrorEntry>()

        // Read from internal filesDir
        readFromDir(context.filesDir, entries)
        // Also try cache dir
        if (entries.isEmpty()) {
            readFromDir(context.cacheDir, entries)
        }

        return entries
    }

    private fun readFromDir(dir: File, entries: MutableList<ErrorEntry>) {
        try {
            val file = File(dir, LOG_FILE)
            if (file.exists()) {
                val parts = file.readText().split("\n---\n")
                for (part in parts) {
                    val trimmed = part.trim()
                    if (trimmed.isNotEmpty()) {
                        val ts = if (trimmed.startsWith("[")) {
                            trimmed.substring(1, minOf(14, trimmed.length))
                        } else "?"
                        entries.add(ErrorEntry(ts, trimmed))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun clear(context: Context) {
        try { File(context.filesDir, LOG_FILE).delete() } catch (_: Exception) {}
        try { File(context.cacheDir, LOG_FILE).delete() } catch (_: Exception) {}
    }

    data class ErrorEntry(val timestamp: String, val message: String)
}
