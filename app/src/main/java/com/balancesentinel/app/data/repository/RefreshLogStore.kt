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

    fun addEntries(context: Context, entries: List<RefreshLogEntry>) {
        if (entries.isEmpty()) return
        synchronized(LOG_LOCK) {
            val maxEntries = getMaxEntries(context)
            val existing = readEntries(context).toMutableList()
            val existingIds = existing.mapTo(mutableSetOf()) { it.id }
            val toAdd = entries.filter { existingIds.add(it.id) }
            if (toAdd.isEmpty()) return
            existing.addAll(0, toAdd)
            if (existing.size > maxEntries) {
                existing.subList(maxEntries, existing.size).clear()
            }
            writeEntries(context, existing)
        }
    }

    fun addEntry(context: Context, entry: RefreshLogEntry) {
        addEntries(context, listOf(entry))
    }

    fun getEntries(context: Context): List<RefreshLogEntry> = synchronized(LOG_LOCK) {
        readEntries(context)
    }

    fun clear(context: Context) {
        synchronized(LOG_LOCK) {
            check(getPrefs(context).edit().remove(KEY_ENTRIES).commit())
        }
    }

    internal fun snapshotEntries(context: Context): List<RefreshLogEntry> =
        synchronized(LOG_LOCK) { readEntries(context).toList() }

    internal fun restoreEntries(context: Context, snapshot: List<RefreshLogEntry>) {
        synchronized(LOG_LOCK) {
            writeEntries(context, snapshot)
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
