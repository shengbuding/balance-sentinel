package com.balancesentinel.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.balancesentinel.app.data.model.RefreshLogEntry
import com.balancesentinel.app.data.util.Logger
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object RefreshLogStore {
    private const val TAG = "RefreshLogStore"
    private const val PREFS_NAME = "refresh_log_store"
    private const val KEY_ENTRIES = "entries"
    const val DEFAULT_MAX_ENTRIES = 100

    private val json = Json { ignoreUnknownKeys = true }
    private val LOG_LOCK = Any()

    /**
     * 批量追加日志条目。
     * 公共方法：写入失败时不抛出异常，兼容旧调用方。
     */
    fun addEntries(context: Context, entries: List<RefreshLogEntry>) {
        try {
            addEntriesStrict(context, entries)
        } catch (e: Exception) {
            Logger.w(TAG, "addEntries failed: ${e.message}")
        }
    }

    /**
     * 批量追加日志条目（严格模式）。
     * 内部方法：写入失败时抛出异常，用于 RefreshResultCommitter 的持久化失败检测。
     */
    internal fun addEntriesStrict(context: Context, entries: List<RefreshLogEntry>) {
        if (entries.isEmpty()) return
        DataMutationCoordinator.withMutation {
            synchronized(LOG_LOCK) {
                val maxEntries = getMaxEntries(context)
                val existing = readEntries(context).toMutableList()
                val existingIds = existing.mapTo(mutableSetOf()) { it.id }
                val toAdd = entries.filter { existingIds.add(it.id) }
                if (toAdd.isEmpty()) return@synchronized
                existing.addAll(0, toAdd)
                if (existing.size > maxEntries) {
                    existing.subList(maxEntries, existing.size).clear()
                }
                writeEntries(context, existing)
            }
        }
    }

    fun addEntry(context: Context, entry: RefreshLogEntry) {
        addEntries(context, listOf(entry))
    }

    fun getEntries(context: Context): List<RefreshLogEntry> = synchronized(LOG_LOCK) {
        readEntries(context)
    }

    /**
     * 清空所有日志条目。
     * 公共方法：写入失败时不抛出异常，兼容旧调用方。
     */
    fun clear(context: Context) {
        try {
            clearStrict(context)
        } catch (e: Exception) {
            Logger.w(TAG, "clear failed: ${e.message}")
        }
    }

    /**
     * 清空所有日志条目（严格模式）。
     * 内部方法：写入失败时抛出异常，用于 RefreshResultCommitter 的持久化失败检测。
     */
    internal fun clearStrict(context: Context) {
        DataMutationCoordinator.withMutation {
            synchronized(LOG_LOCK) {
                check(getPrefs(context).edit().remove(KEY_ENTRIES).commit())
            }
        }
    }

    internal fun snapshotEntries(context: Context): List<RefreshLogEntry> =
        DataMutationCoordinator.withMutation {
            synchronized(LOG_LOCK) { readEntries(context).toList() }
        }

    internal fun restoreEntries(context: Context, snapshot: List<RefreshLogEntry>) {
        DataMutationCoordinator.withMutation {
            synchronized(LOG_LOCK) {
                writeEntries(context, snapshot)
            }
        }
    }

    private fun readEntries(context: Context): List<RefreshLogEntry> {
        return try {
            val raw = getPrefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
            json.decodeFromString(ListSerializer(RefreshLogEntry.serializer()), raw)
        } catch (error: Exception) {
            Logger.w(TAG, "Failed to parse refresh log entries: ${error.message}")
            emptyList()
        }
    }

    private fun writeEntries(context: Context, entries: List<RefreshLogEntry>) {
        val serialized = json.encodeToString(ListSerializer(RefreshLogEntry.serializer()), entries)
        check(getPrefs(context).edit().putString(KEY_ENTRIES, serialized).commit())
    }

    private fun getMaxEntries(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            prefs.getInt(WidgetPrefs.KEY_LOG_MAX, DEFAULT_MAX_ENTRIES).coerceIn(10, 1000)
        } catch (_: Exception) {
            DEFAULT_MAX_ENTRIES
        }
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
