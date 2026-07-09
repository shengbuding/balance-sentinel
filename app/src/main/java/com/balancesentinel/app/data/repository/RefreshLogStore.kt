package com.balancesentinel.app.data.repository

import com.balancesentinel.app.data.util.Logger
import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.model.RefreshLogEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 刷新日志持久化存储。
 * 使用普通 SharedPreferences + JSON，Widget 进程也能写入。
 * 上限由 [WidgetPrefs.logMaxEntries] 控制（默认 100 条），新记录插在头部。
 */
object RefreshLogStore {

    private const val TAG = "RefreshLogStore"
    private const val PREFS_NAME = "refresh_log_store"
    private const val KEY_ENTRIES = "entries"
    const val DEFAULT_MAX_ENTRIES = 100

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 批量写入刷新日志。一次读、一次写，避免逐条 O(n²) 序列化。
     * 用于数据导入等大量写入场景。
     */
    fun addEntries(context: Context, entries: List<RefreshLogEntry>) {
        if (entries.isEmpty()) return
        try {
            val maxEntries = getMaxEntries(context)
            val existing = getEntries(context).toMutableList()
            val existingIds = existing.map { it.id }.toSet()
            val toAdd = entries.filter { it.id !in existingIds }
            if (toAdd.isEmpty()) return
            existing.addAll(0, toAdd)
            if (existing.size > maxEntries) {
                existing.subList(maxEntries, existing.size).clear()
            }
            val serialized = json.encodeToString(ListSerializer(RefreshLogEntry.serializer()), existing)
            getPrefs(context).edit().putString(KEY_ENTRIES, serialized).apply()
        } catch (_: Exception) { }
    }

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
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse refresh log entries: ${e.message}")
            emptyList()
        }
    }

    /**
     * 清除全部日志。
     */
    fun clear(context: Context) {
        try {
            getPrefs(context).edit().remove(KEY_ENTRIES).apply()
        } catch (e: Exception) { Logger.w(TAG, "clear failed", e) }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
