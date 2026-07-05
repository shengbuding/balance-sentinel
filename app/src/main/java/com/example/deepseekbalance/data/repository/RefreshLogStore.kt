package com.example.deepseekbalance.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.deepseekbalance.data.model.RefreshLogEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 刷新日志持久化存储。
 * 使用普通 SharedPreferences + JSON，Widget 进程也能写入。
 * 上限由 [WidgetPrefs.logMaxEntries] 控制（默认 100 条），新记录插在头部。
 */
object RefreshLogStore {

    private const val PREFS_NAME = "refresh_log_store"
    private const val KEY_ENTRIES = "entries"
    const val DEFAULT_MAX_ENTRIES = 100

    private val json = Json { ignoreUnknownKeys = true }

    /** 写入一条刷新日志。新记录插入头部，超过上限时裁剪尾部。 */
    fun addEntry(context: Context, entry: RefreshLogEntry) {
        try {
            val maxEntries = getMaxEntries(context)
            val entries = getEntries(context).toMutableList()
            entries.add(0, entry)
            if (entries.size > maxEntries) {
                entries.subList(maxEntries, entries.size).clear()
            }
            val serialized = json.encodeToString(ListSerializer(RefreshLogEntry.serializer()), entries)
            getPrefs(context).edit().putString(KEY_ENTRIES, serialized).apply()
        } catch (_: Exception) {
            // 日志写入失败不应影响主流程
        }
    }

    /** 读取当前日志上限（跨进程兼容：直接从 SharedPreferences 读，避免依赖 WidgetPrefs 实例） */
    private fun getMaxEntries(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            prefs.getInt(WidgetPrefs.KEY_LOG_MAX, DEFAULT_MAX_ENTRIES).coerceIn(10, 1000)
        } catch (_: Exception) {
            DEFAULT_MAX_ENTRIES
        }
    }

    /**
     * 读取全部日志（最新在前）。
     */
    fun getEntries(context: Context): List<RefreshLogEntry> {
        return try {
            val raw = getPrefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
            json.decodeFromString(ListSerializer(RefreshLogEntry.serializer()), raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 清除全部日志。
     */
    fun clear(context: Context) {
        try {
            getPrefs(context).edit().remove(KEY_ENTRIES).apply()
        } catch (_: Exception) {}
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
